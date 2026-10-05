// Shared Playwright helpers for the e2e checklist (run-3).
//
// Rules this file enforces (e2e tester brief):
//  - one fresh browser context per account per scenario (login()), never a
//    logout/login in the same context;
//  - screenshots capture only the relevant region, outlined in red first
//    (shot()), saved under e2e/screenshots/run-3/<name>.png;
//  - server state is read separately with api() (basic auth, no session).
import { chromium } from 'playwright';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
export const E2E = path.resolve(here, '..');
export const SHOTS = path.join(E2E, 'screenshots', process.env.BC_SHOTS || 'run-3');
export const OUT = path.join(E2E, 'out');
fs.mkdirSync(SHOTS, { recursive: true });
fs.mkdirSync(OUT, { recursive: true });

const env = Object.fromEntries(
  fs.readFileSync(path.join(E2E, '.env'), 'utf8').split('\n')
    .filter((l) => /^[A-Z_]+=/.test(l)).map((l) => [l.split('=')[0], l.slice(l.indexOf('=') + 1)]));
export const BASE = `http://localhost:${env.BC_PORT || 8080}`;
export const MAIL = `http://localhost:${env.BC_MAIL_PORT || 8025}`;

export function password(user) {
  if (user === 'admin') return env.BC_ADMIN_PASSWORD;
  if (user === 'requester') return env.BC_REQUESTER_PASSWORD;
  if (user === 'approver-1' || user === 'approver-2') return env.BC_APPROVER_PASSWORD;
  return env.BC_OTHER_PASSWORD;
}

let browser;
export async function launch() {
  if (!browser) browser = await chromium.launch({ channel: 'chrome' }); // system Google Chrome (no Playwright download needed)
  return browser;
}
export async function close() {
  if (browser) await browser.close();
  browser = undefined;
}

/** New context + page, logged in through the real login form. */
export async function login(user, opts = {}) {
  const b = await launch();
  const context = await b.newContext({
    viewport: opts.viewport || { width: 1280, height: 900 },
    deviceScaleFactor: opts.scale || 1,
    colorScheme: opts.colorScheme || 'light',
    locale: opts.locale || 'en-US',
  });
  const page = await context.newPage();
  page.setDefaultTimeout(20000);
  if (user) {
    await page.goto(`${BASE}/login`);
    await page.fill('#j_username', user);
    await page.fill('input[name="j_password"]', password(user));
    await Promise.all([page.waitForLoadState('load'), page.click('button[name="Submit"], button[type="submit"]')]);
    if (page.url().includes('loginError')) throw new Error(`login failed for ${user}`);
  }
  return { context, page };
}

/**
 * Outlines the target(s) in red and saves a clip of their union bounding box
 * with padding. `target` is a Locator or a CSS/text selector or an array.
 */
export async function shot(page, target, name, { pad = 16, full = false } = {}) {
  const file = path.join(SHOTS, `${name}.png`);
  const targets = Array.isArray(target) ? target : [target];
  const boxes = [];
  // Grow the viewport to the whole document first: then nothing is off-screen and
  // sticky header/button bars sit at their natural place instead of covering the
  // region (a fullPage clip paints them where the viewport was).
  const vp = page.viewportSize();
  const docH = await page.evaluate(() => document.documentElement.scrollHeight);
  if (!full) {
    await page.setViewportSize({ width: vp.width, height: Math.min(Math.max(docH, vp.height), 8000) });
    await page.waitForTimeout(250);
  }
  await page.addStyleTag({ content: '.bc-e2e-box { position: absolute; border: 3px solid #d00; border-radius: 4px; pointer-events: none; z-index: 99999; }' }).catch(() => {});
  for (const t of targets) {
    const loc = typeof t === 'string' ? page.locator(t).first() : t.first();
    try {
      await loc.waitFor({ state: 'visible', timeout: 3000 });
      // Element box shrunk to its visible content (a panel is often much taller than
      // its content because of min-height), then a red overlay box on top of it.
      const box = await loc.evaluate((el) => {
        // Box = union of the element's visible content (a panel is often much larger than
        // what it shows: an error page's main panel fills the viewport). Inside a <dialog>
        // (top layer) an overlay cannot be drawn above it, so the element gets an outline.
        const r0 = el.getBoundingClientRect();
        let x0 = Infinity, y0 = Infinity, x1 = -Infinity, y1 = -Infinity;
        for (const d of [el, ...el.querySelectorAll('*')]) {
          if (d !== el && d.children.length) continue;
          const q = d.getBoundingClientRect();
          if (q.width <= 0 || q.height <= 0 || getComputedStyle(d).visibility === 'hidden') continue;
          if (d !== el && !((d.innerText || '').trim() || ['IMG', 'svg', 'INPUT', 'SELECT', 'TEXTAREA', 'BUTTON', 'CANVAS'].includes(d.tagName))) continue;
          x0 = Math.min(x0, q.left); y0 = Math.min(y0, q.top); x1 = Math.max(x1, q.right); y1 = Math.max(y1, q.bottom);
        }
        if (!isFinite(x0) || (x1 - x0) * (y1 - y0) > r0.width * r0.height) { x0 = r0.left; y0 = r0.top; x1 = r0.right; y1 = r0.bottom; }
        const b = { x: x0 + window.scrollX, y: y0 + window.scrollY, width: x1 - x0, height: y1 - y0 };
        if (el.closest('dialog[open]')) {
          el.style.outline = '3px solid #d00'; el.style.outlineOffset = '3px'; el.dataset.bcE2eOutline = '1';
        } else {
          const d = document.createElement('div');
          d.className = 'bc-e2e-box';
          d.style.left = `${b.x - 5}px`; d.style.top = `${b.y - 5}px`;
          d.style.width = `${b.width + 4}px`; d.style.height = `${b.height + 4}px`;
          document.body.appendChild(d);
        }
        return b;
      });
      if (box.width > 0 && box.height > 0) boxes.push(box);
    } catch (e) {
      console.log(`  (shot ${name}: target not found: ${t})`);
    }
  }
  if (full || boxes.length === 0) {
    if (!full) console.log(`  (shot ${name}: NO TARGET FOUND - capture not saved, fix the selector)`);
    if (!full) { if (!full) await page.setViewportSize(vp); return null; }
    await page.screenshot({ path: file });
  } else {
    const dims = await page.evaluate(() => ({ w: document.documentElement.scrollWidth, h: document.documentElement.scrollHeight, sx: window.scrollX, sy: window.scrollY }));
    const x0 = Math.max(0, Math.min(...boxes.map((b) => b.x)) - pad);
    const y0 = Math.max(0, Math.min(...boxes.map((b) => b.y)) - pad);
    const x1 = Math.min(dims.w, Math.max(...boxes.map((b) => b.x + b.width)) + pad);
    const y1 = Math.min(dims.h, Math.max(...boxes.map((b) => b.y + b.height)) + pad);
    await page.screenshot({ path: file, clip: { x: x0, y: y0, width: x1 - x0, height: y1 - y0 }, fullPage: true });
  }
  await page.evaluate(() => { document.querySelectorAll('.bc-e2e-box').forEach((d) => d.remove()); document.querySelectorAll('[data-bc-e2e-outline]').forEach((e) => { e.style.outline = ''; delete e.dataset.bcE2eOutline; }); }).catch(() => {});
  if (!full) await page.setViewportSize(vp);
  console.log(`  screenshot ${path.relative(E2E, file)}`);
  return file;
}

/** Basic-auth REST call (server state, not the behaviour under test). */
export async function api(user, p, { method = 'GET', body, headers = {}, raw = false } = {}) {
  const auth = 'Basic ' + Buffer.from(`${user}:${password(user)}`).toString('base64');
  const h = { Authorization: auth, ...headers };
  if (method !== 'GET') {
    const c = await fetch(`${BASE}/crumbIssuer/api/json`, { headers: { Authorization: auth } });
    if (c.ok) {
      const j = await c.json();
      h[j.crumbRequestField] = j.crumb;
      h.Cookie = (c.headers.get('set-cookie') || '').split(';')[0];
    }
  }
  const r = await fetch(`${BASE}${p}`, { method, body, headers: h, redirect: 'manual' });
  const text = await r.text();
  let json;
  if (!raw) { try { json = JSON.parse(text); } catch { /* not json */ } }
  return { status: r.status, text, json, location: r.headers.get('location') };
}

/** Groovy on the admin script console: ARRANGE or READ state only. */
export async function groovy(script) {
  const r = await api('admin', '/scriptText', {
    method: 'POST', body: new URLSearchParams({ script }),
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
  });
  return r.text.trim();
}

export async function job(name, tree = 'nextBuildNumber,inQueue,builds[number,result]') {
  const p = name.split('/').map((s) => `job/${encodeURIComponent(s)}`).join('/');
  return (await api('admin', `/${p}/api/json?tree=${encodeURIComponent(tree)}`)).json;
}

export async function queue() {
  return (await api('admin', '/queue/api/json?tree=items[task[name],why]')).json.items;
}

export const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

export async function waitFor(fn, { timeout = 90000, every = 2000 } = {}) {
  const end = Date.now() + timeout;
  for (;;) {
    const v = await fn();
    if (v) return v;
    if (Date.now() > end) return v;
    await sleep(every);
  }
}

/** Mail sink messages (mailpit API). */
export async function mails(query) {
  const r = await fetch(`${MAIL}/api/v1/search?query=${encodeURIComponent(query)}&limit=50`);
  return (await r.json()).messages || [];
}

/** Appends a line to out/<file> (evidence log). */
export function log(file, line) {
  fs.appendFileSync(path.join(OUT, file), line + '\n');
  console.log(line);
}

/**
 * Clicks a sidebar entry by its visible label; if it leads to core's
 * "build with parameters" page, clicks its Build button too. Returns the
 * final URL, HTTP status and main-panel text the user is left with.
 */
export async function clickBuildEntry(page, label) {
  const entry = page.locator('#side-panel a, #tasks a').filter({ hasText: label }).first();
  const [nav] = await Promise.all([page.waitForNavigation({ waitUntil: 'load', timeout: 15000 }).catch(() => null), entry.click()]);
  let resp = nav;
  await page.waitForTimeout(800);
  const buildBtn = page.locator('#main-panel button.jenkins-\\!-build-color, #main-panel button[name="Submit"]:has-text("Build")').first();
  if (/\/build(WithParameters)?(\?|$)/.test(page.url()) && await buildBtn.count()) {
    [resp] = await Promise.all([page.waitForNavigation({ waitUntil: 'load', timeout: 15000 }).catch(() => null), buildBtn.click()]);
  }
  await page.waitForTimeout(1200);
  const text = (await page.locator('#main-panel, body').first().innerText()).replace(/\s+/g, ' ').trim();
  const notif = (await page.locator('#notification-bar, .jenkins-notification').allInnerTexts().catch(() => [])).join(' ').trim();
  return { url: page.url(), status: resp ? resp.status() : null, text, notif };
}

/** Fills and submits Request Run for <job> as <page>'s user. Returns the request URL. */
export async function requestRun(page, jobPath, { reason, approvers, params = {} }) {
  await page.goto(`${BASE}${jobPath}`);
  await page.locator('#side-panel a:has-text("Request Run")').click();
  await page.waitForLoadState('load');
  await page.fill('form[name="batch-control-request"] textarea[name="reason"]', reason);
  for (const a of approvers) {
    await page.locator(`form[name="batch-control-request"] input[name="approvers"][value="${a}"] + label`).click();
  }
  for (const [k, v] of Object.entries(params)) {
    const item = page.locator(`form[name="batch-control-request"] .jenkins-form-item:has(.jenkins-form-label:text-is("${k}"))`).last();
    if (await item.locator('select').count()) await item.locator('select').selectOption(v);
    else if (await item.locator('.hidden-password-update-btn').count()) {
      // core's concealed password widget: the user presses "Change Password", then types
      await item.locator('.hidden-password-update-btn').click();
      await item.locator('input[name="value"]:visible').fill(v);
    } else await item.locator('input[name="value"]').fill(v);
  }
  await Promise.all([page.waitForNavigation({ waitUntil: 'load' }), page.locator('button:has-text("Submit Request")').click()]);
  return page.url();
}


/**
 * Batch Control section of Manage Jenkins -> System, as a user edits it.
 * `values`: { fieldName: string|boolean } using the form field names
 * (runControlEnabled, approversText, pendingTimeoutHours, ...). Returns the
 * page state after Save (url, status, any validation/error text).
 */
export async function setGlobal(page, values, { save = true } = {}) {
  await page.goto(`${BASE}/manage/configure`);
  await page.waitForTimeout(1500);
  for (const [k, v] of Object.entries(values)) {
    const el = page.locator(`[name="_.${k}"]`).first();
    await el.scrollIntoViewIfNeeded();
    if (typeof v === 'boolean') {
      if ((await el.isChecked()) !== v) await el.locator('xpath=following-sibling::label[1]').click();
    } else {
      await el.fill(String(v));
      await el.blur();
    }
  }
  await page.waitForTimeout(800);
  const errors = (await page.locator('.error, .validation-error-area--visible .error').allInnerTexts()).map((t) => t.trim()).filter(Boolean);
  if (!save) return { errors };
  const [resp] = await Promise.all([page.waitForNavigation({ waitUntil: 'load' }).catch(() => null), page.locator('button[name="Submit"]').click()]);
  await page.waitForTimeout(800);
  const text = (await page.locator('#main-panel, body').first().innerText()).replace(/\s+/g, ' ').slice(0, 400);
  return { errors, status: resp ? resp.status() : null, url: page.url(), text };
}

/** Current Batch Control global configuration (read, script console). */
export async function globalCfg() {
  return JSON.parse(await groovy(`def c = io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration.get()
println groovy.json.JsonOutput.toJson([run: c.runControlEnabled, change: c.changeControlEnabled, approvers: c.approvers, self: c.allowAdminSelfApproval,
 pending: c.pendingTimeoutHours, approvedRun: c.approvedRunTimeoutMinutes, durations: c.grantDurationOptions, max: c.maxGrantMinutes,
 incidents: c.incidentResults, retention: c.retentionMonths, notify: c.notifyBeforeExpiryMinutes, mail: c.emailNotifications])`));
}

/** changes.csv rows (array of arrays; naive CSV split is enough for the id/type/target/user columns). */
export async function changeRows(re) {
  const t = (await api('admin', '/batch-control/history/changes.csv')).text.split('\n').slice(1).filter(Boolean);
  return re ? t.filter((l) => re.test(l)) : t;
}

/** Error text of a Jenkins error / access-denied page. */
export const errText = (t) => (t.replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ').match(/(?:Error|Access Denied|Oops!?) (.{0,240}?) (?:REST API|Logging ID)/) || [null, t.replace(/\s+/g, ' ').slice(0, 160)])[1];

/** requester (page's user) submits a grant request in the form; returns { url } or { error, status }.
 *  D-71: a window names one item (scopeFullName); there is no scope type selector, so `type` is ignored
 *  (kept in the signature so callers need not change). #107: the "New job name restriction" field lives in an
 *  optionalBlock that is hidden until Create is ticked, so the CREATE action is checked before the pattern is filled. */
export async function requestGrant(page, { type = 'JOB', scope, actions, minutes = 15, pattern, reason, approver = 'approver-1' }) {
  await page.goto(`${BASE}/batch-control/grants/`);
  await page.waitForSelector('input[name="scopeFullName"]');
  await page.fill('input[name="scopeFullName"]', scope);
  for (const a of actions) {
    const box = page.locator(`input[name="actions"][value="${a}"]`);
    if (!(await box.isChecked())) await box.locator('xpath=following-sibling::label[1]').click();
  }
  if (pattern !== undefined) {
    // The field is revealed by ticking Create (#107); it is a plain visible input without it only on pre-#107 builds.
    const field = page.locator('input[name="createNamePattern"]');
    await field.waitFor({ state: 'visible' }).catch(() => {});
    await field.fill(pattern);
  }
  await page.selectOption('select[name="durationMinutes"]', String(minutes));
  await page.fill('textarea[name="reason"]', reason);
  await page.locator(`input[name="approvers"][value="${approver}"] + label`).click();
  const [resp] = await Promise.all([page.waitForNavigation({ waitUntil: 'load' }).catch(() => null), page.locator('button:has-text("Request Grant")').click()]);
  if (!resp || resp.status() >= 400) return { error: errText(await page.content()), status: resp && resp.status() };
  const href = await page.locator('#main-panel table').first().locator('tbody tr', { hasText: reason.slice(0, 30) }).first().locator('a').first().getAttribute('href');
  return { url: new URL(href, page.url()).href };
}

/** approver-1 approves/rejects a request detail URL in the UI. */
export async function decide(url, how = 'approve', comment = 'ok', user = 'approver-1') {
  const { context, page } = await login(user);
  await page.goto(url);
  const f = page.locator(`form[name="${how}"]`);
  await f.locator('textarea[name="comment"]').fill(comment);
  const [r] = await Promise.all([page.waitForNavigation({ waitUntil: 'load' }).catch(() => null), f.locator('button').first().click()]);
  const t = (await page.locator('#main-panel, body').first().innerText()).replace(/\s+/g, ' ');
  await context.close();
  return { status: r && r.status(), text: t };
}
