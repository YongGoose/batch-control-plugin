// Fresh-eyes e2e driver (e2e-04). Independent of the older run-3 drivers.
//
// Rules: one new browser context per account (real login form), a clipped,
// red-boxed screenshot per step, server state read separately with basic auth.
import { chromium } from 'playwright';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
export const SHOTS = path.join(here, 'screenshots', process.env.BC_SHOTS || '');
export const OUT = path.join(here, 'out');
fs.mkdirSync(SHOTS, { recursive: true });
fs.mkdirSync(OUT, { recursive: true });
export const BASE = 'http://localhost:8080';
export const MAIL = 'http://localhost:8025';

// Passwords come from e2e/.env (never committed), as in ../browser/lib.mjs.
const ENV = Object.fromEntries(fs.readFileSync(path.join(here, '..', '..', '.env'), 'utf8').split('\n')
  .filter((l) => /^[A-Z_]+=/.test(l)).map((l) => [l.split('=')[0], l.slice(l.indexOf('=') + 1)]));
const PW = {
  admin: ENV.BC_ADMIN_PASSWORD, requester: ENV.BC_REQUESTER_PASSWORD,
  'approver-1': ENV.BC_APPROVER_PASSWORD, 'approver-2': ENV.BC_APPROVER_PASSWORD,
};
export const pw = (u) => PW[u] || ENV.BC_OTHER_PASSWORD;

let browser;
export async function launch() {
  if (!browser) browser = await chromium.launch({ channel: 'chrome' });
  return browser;
}
export async function close() { if (browser) await browser.close(); browser = undefined; }

export async function login(user, opts = {}) {
  const b = await launch();
  const context = await b.newContext({ viewport: { width: 1280, height: 900 }, locale: 'en-US' });
  const page = await context.newPage();
  page.setDefaultTimeout(20000);
  if (user) {
    await page.goto(`${BASE}/login`);
    await page.fill('#j_username', user);
    await page.fill('input[name="j_password"]', pw(user));
    await Promise.all([page.waitForLoadState('load'), page.click('button[name="Submit"], button[type="submit"]')]);
    if (page.url().includes('loginError')) throw new Error(`login failed for ${user}`);
  }
  return { context, page };
}

/** Red-box the target(s) and save a clip of their union with padding. */
export async function shot(page, target, name, { pad = 20 } = {}) {
  const file = path.join(SHOTS, `${name}.png`);
  const targets = (Array.isArray(target) ? target : [target]).map((t) => (typeof t === 'string' ? page.locator(t).first() : t));
  const vp = page.viewportSize();
  const docH = await page.evaluate(() => document.documentElement.scrollHeight);
  await page.setViewportSize({ width: vp.width, height: Math.min(Math.max(docH, vp.height), 8000) });
  await page.waitForTimeout(200);
  const boxes = [];
  for (const t of targets) {
    try {
      await t.evaluate((el) => { el.style.outline = '3px solid red'; el.style.outlineOffset = '2px'; });
      const b = await t.boundingBox();
      if (b) boxes.push(b);
    } catch { /* missing element: still capture the page region */ }
  }
  let clip;
  if (boxes.length) {
    const x0 = Math.max(0, Math.min(...boxes.map((b) => b.x)) - pad);
    const y0 = Math.max(0, Math.min(...boxes.map((b) => b.y)) - pad);
    const x1 = Math.max(...boxes.map((b) => b.x + b.width)) + pad;
    const y1 = Math.max(...boxes.map((b) => b.y + b.height)) + pad;
    clip = { x: x0, y: y0, width: Math.min(x1, vp.width) - x0, height: Math.min(y1 - y0, 3000) };
  }
  await page.screenshot({ path: file, clip, fullPage: !clip });
  for (const t of targets) { try { await t.evaluate((el) => { el.style.outline = ''; }); } catch { /* ignore */ } }
  await page.setViewportSize(vp);
  return file;
}

/** Visible text of the main panel (or body). */
export async function text(page) {
  const main = page.locator('#main-panel');
  return (await main.count()) ? main.innerText() : page.locator('body').innerText();
}
export async function sidebar(page) {
  const s = page.locator('#side-panel, #tasks');
  return (await s.count()) ? s.first().innerText() : '';
}

const auth = (user) => 'Basic ' + Buffer.from(`${user}:${pw(user)}`).toString('base64');
/** GET, or POST with a crumb obtained in the same session (cookie kept). */
export async function api(user, p, init = {}) {
  const headers = { Authorization: auth(user), ...(init.headers || {}) };
  if (init.method === 'POST' && !init.noCrumb) {
    const c = await fetch(BASE + '/crumbIssuer/api/json', { headers: { Authorization: auth(user) } });
    if (c.status === 200) {
      const j = await c.json();
      headers[j.crumbRequestField] = j.crumb;
      const ck = c.headers.get('set-cookie');
      if (ck) headers.Cookie = ck.split(';')[0];
    }
  }
  const r = await fetch(BASE + p, { ...init, headers, redirect: init.redirect || 'manual' });
  const body = await r.text();
  return { status: r.status, body, json: () => JSON.parse(body) };
}

/** Script console, admin only: used to arrange or read state, never as the step under test. */
export async function groovy(script) {
  const r = await api('admin', '/scriptText', { method: 'POST', body: new URLSearchParams({ script }) });
  return r.body.trim();
}

export async function mails(query = '') {
  const r = await fetch(`${MAIL}/api/v1/search?query=${encodeURIComponent(query)}&limit=50`);
  return (await r.json()).messages || [];
}
export async function mailBody(id) { const r = await fetch(`${MAIL}/api/v1/message/${id}`); return r.json(); }

export function log(...a) { const s = a.map((x) => (typeof x === 'string' ? x : JSON.stringify(x))).join(' '); console.log(s); fs.appendFileSync(path.join(OUT, 'log.txt'), s + '\n'); }
export const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
