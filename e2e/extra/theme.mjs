// e2e-05 check 1: dark theme. node theme.mjs <setup|set|audit> ...
//   setup                      - requester files one pending run request and one pending grant (ids -> out/theme-ids.json)
//   set <none|dark>            - admin selects the theme on Manage Jenkins -> Appearance (the real form)
//   audit <light|dark> <user>  - every Batch Control screen for <user>: capture, WCAG contrast audit, red-boxed offenders
//
// The contrast audit computes, for every visible element with its own text (and every form control) in the
// page, the text colour against the composited background, and lists pairs under 4.5:1 (3:1 for large text).
import fs from 'node:fs';
import { login, BASE, shot, text, log, close, sleep, OUT, SHOTS } from './lib.mjs';

const flat = (s) => s.replace(/\s+/g, ' ').trim();
const [step, ...a] = process.argv.slice(2);
const idsFile = `${OUT}/theme-ids.json`;

if (step === 'setup') {
  const { page } = await login('requester');
  await page.goto(`${BASE}/job/fresh-daily/batch-control/`);
  await page.fill('textarea[name="reason"]', 'e2e-05 dark theme: pending request for the screens');
  for (const ap of ['approver-1', 'admin']) await page.locator(`input[name="approvers"][value="${ap}"]`).check({ force: true });
  await Promise.all([page.waitForLoadState('load'), page.click('button[name="Submit"]')]);
  const run = (page.url().match(/requests\/([^/]+)/) || [])[1];
  await page.goto(`${BASE}/batch-control/grants/?scopeType=JOB&scopeFullName=fresh-daily`);
  const f = page.locator('form[action$="grants/create"]');
  await f.locator('input[name="actions"][value="CONFIGURE"]').check({ force: true });
  await f.locator('textarea[name="reason"]').fill('e2e-05 dark theme: pending grant for the screens');
  for (const ap of ['approver-1', 'admin']) await f.locator(`input[name="approvers"][value="${ap}"]`).check({ force: true });
  await Promise.all([page.waitForLoadState('load'), f.locator('button[name="Submit"]').click()]);
  const grant = (page.url().match(/grants\/(\d{8}-\d{6}-\w+)/) || [])[1];
  fs.writeFileSync(idsFile, JSON.stringify({ run, grant }));
  log('theme setup', { run, grant });
  await page.context().close();
}

if (step === 'set') {
  const theme = a[0];
  const { page } = await login('admin');
  await page.goto(`${BASE}/manage/appearance/`);
  await page.locator(`input[data-theme="${theme}"]`).check({ force: true });
  await shot(page, page.locator(`input[data-theme="${theme}"]`).locator('xpath=ancestor::*[contains(@class,"jenkins-radio") or contains(@class,"radio-block")][1]'), `T-00-appearance-${theme}`);
  await Promise.all([page.waitForLoadState('load'), page.click('button[name="Submit"]')]);
  const bg = await page.evaluate(() => getComputedStyle(document.body).backgroundColor);
  log('theme set', theme, '-> body background', bg, 'data-theme', await page.evaluate(() => document.documentElement.dataset.theme || ''));
  await page.context().close();
}

// ---- contrast audit (runs in the page) ----
function auditInPage() {
  const cv = document.createElement('canvas'); cv.width = cv.height = 1;
  const cx = cv.getContext('2d', { willReadFrequently: true });
  const rgba = (s) => { cx.clearRect(0, 0, 1, 1); cx.fillStyle = 'rgba(0,0,0,0)'; cx.fillStyle = s; cx.fillRect(0, 0, 1, 1); const d = cx.getImageData(0, 0, 1, 1).data; return [d[0], d[1], d[2], d[3] / 255]; };
  const alphaOf = (s) => (!s || s === 'transparent' ? 0 : rgba(s)[3]);
  const over = (top, a, bot) => top.map((c, i) => c * a + bot[i] * (1 - a));
  const lum = ([r, g, b]) => { const f = (c) => { c /= 255; return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4; }; return 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(b); };
  const ratio = (x, y) => { const [l1, l2] = [lum(x), lum(y)].sort((p, q) => q - p); return (l1 + 0.05) / (l2 + 0.05); };
  function bgOf(el) {
    const layers = [];
    for (let e = el; e; e = e.parentElement) {
      const cs = getComputedStyle(e);
      if (cs.backgroundImage && cs.backgroundImage !== 'none' && !/url\(/.test(cs.backgroundImage)) return null; // gradient: unknown
      const a = alphaOf(cs.backgroundColor);
      if (a > 0) { layers.push([rgba(cs.backgroundColor).slice(0, 3), a]); if (a >= 1) break; }
    }
    let c = [255, 255, 255];
    for (let i = layers.length - 1; i >= 0; i--) c = over(layers[i][0], layers[i][1], c);
    return c;
  }
  const path = (e) => { const p = []; for (let n = e; n && n !== document.body && p.length < 5; n = n.parentElement) p.unshift(n.tagName.toLowerCase() + (n.id ? '#' + n.id : '') + (n.classList.length ? '.' + [...n.classList].slice(0, 2).join('.') : '')); return p.join('>'); };
  const out = []; let n = 0; const lightBoxes = [];
  const pageBg = bgOf(document.body);
  const dark = lum(pageBg) < 0.2;
  let seq = 0;
  for (const el of document.querySelectorAll('body *')) {
    if (el.closest('#page-header, footer, .page-footer, script, style, noscript, svg, template')) continue;
    const r = el.getBoundingClientRect();
    if (r.width < 2 || r.height < 2) continue;
    const cs = getComputedStyle(el);
    if (cs.visibility === 'hidden' || cs.display === 'none' || parseFloat(cs.opacity) === 0) continue;
    const isCtl = /^(INPUT|TEXTAREA|SELECT|BUTTON)$/.test(el.tagName) && el.type !== 'hidden' && el.type !== 'checkbox' && el.type !== 'radio';
    const own = [...el.childNodes].filter((c) => c.nodeType === 3).map((c) => c.textContent).join('').trim();
    // In a dark page, a large light box is a finding by itself (a panel that kept a light background).
    if (dark && r.width > 120 && r.height > 24) {
      const a = alphaOf(cs.backgroundColor);
      if (a > 0.5 && lum(rgba(cs.backgroundColor).slice(0, 3)) > 0.6) { el.dataset.bcLight = String(++seq); lightBoxes.push({ tag: el.dataset.bcLight, path: path(el), bg: cs.backgroundColor, text: (el.innerText || '').replace(/\s+/g, ' ').slice(0, 80) }); }
    }
    if (!own && !isCtl) continue;
    n++;
    const bg = bgOf(el); if (!bg) continue;
    const fg = over(rgba(cs.color).slice(0, 3), alphaOf(cs.color) * Math.min(1, parseFloat(cs.opacity) || 1), bg);
    const cr = ratio(fg, bg);
    const size = parseFloat(cs.fontSize); const bold = parseInt(cs.fontWeight, 10) >= 700;
    const need = (size >= 24 || (bold && size >= 18.66)) ? 3 : 4.5;
    if (cr < need) { el.dataset.bcLow = String(++seq); out.push({ tag: el.dataset.bcLow, ratio: +cr.toFixed(2), need, text: (own || el.value || el.placeholder || '').slice(0, 70), fg: fg.map(Math.round).join(','), bg: bg.map(Math.round).join(','), path: path(el) }); }
  }
  out.sort((p, q) => p.ratio - q.ratio);
  return { checked: n, dark, pageBg: pageBg.map(Math.round).join(','), low: out, lightBoxes };
}

function screens(user, ids) {
  const common = [
    ['landing', '/batch-control/'],
    ['requests', '/batch-control/requests/'],
    ['request-pending', `/batch-control/requests/${ids.run}/`],
    ['request-executed', '/batch-control/requests/20260930-230622-8k8gyy/'],
    ['request-invalidated', '/batch-control/requests/20260930-132848-hthns2/'],
    ['activations', '/batch-control/activations/'],
    ['activation-detail', '/batch-control/activations/20260930-132612-0eqk8h/'],
    ['grants', '/batch-control/grants/'],
    ['grant-pending', `/batch-control/grants/${ids.grant}/`],
    ['grant-violation', '/batch-control/grants/20260930-231101-h8uutp/'],
    ['job-page', '/job/fresh-daily/'],
    ['job-new-locked', '/job/fresh-secret/'],
    ['run-form', '/job/fresh-daily/batch-control/'],
    ['activation-form', '/job/fresh-daily/batch-control-activation'],
  ];
  if (user === 'admin') {
    return [...common,
      ['changes', '/batch-control/changes/'],
      ['dashboard', '/batch-control/dashboard/'],
      ['incidents', '/batch-control/incidents/'],
      ['incident-open', '/batch-control/incidents/20260930-230950-ny616w/'],
      ['incident-resolved', '/batch-control/incidents/20260930-082024-zztwrv/'],
      ['history', '/batch-control/history/'],
      ['history-changes', '/batch-control/history/?kind=changes&from=2026-09-01&to=2026-09-30'],
      ['history-monthly', '/batch-control/history/monthly?month=2026-09'],
      ['history-bad-dates', '/batch-control/history/?from=2026-13-45&to=yesterday'],
      ['configuration', '/batch-control-configuration/'],
      ['manage-monitors', '/manage/'],
      ['job-config-section', '/job/fresh-daily/configure'],
    ];
  }
  // requester: the refusal pages they meet
  return [...common,
    ['refusal-changes', '/batch-control/changes/'],
    ['refusal-configuration', '/batch-control-configuration/'],
    ['refusal-incident', '/batch-control/incidents/20260930-230950-ny616w/'],
    ['refusal-history', '/batch-control/history/'],
  ];
}

if (step === 'audit') {
  const [theme, user] = a;
  const ids = JSON.parse(fs.readFileSync(idsFile, 'utf8'));
  const { page } = await login(user);
  const results = [];
  const run = async (name, go) => {
    const status = await go();
    await sleep(300);
    const res = await page.evaluate(auditInPage);
    const base = `T-${theme}-${user}-${name}`;
    await page.screenshot({ path: `${SHOTS}/${base}-full.png`, fullPage: true });
    // Cropped evidence: the main panel boxed; plus the worst low-contrast element and any light box, boxed.
    await shot(page, '#main-panel', `${base}-main`, { pad: 10 });
    const worst = res.low.filter((x) => !/^(header|footer)/.test(x.path))[0];
    if (worst) await shot(page, `[data-bc-low="${worst.tag}"]`, `${base}-lowest`, { pad: 40 });
    if (res.lightBoxes[0]) await shot(page, `[data-bc-light="${res.lightBoxes[0].tag}"]`, `${base}-lightbox`, { pad: 20 });
    const head = flat(await text(page)).slice(0, 140);
    results.push({ name, status, url: page.url(), checked: res.checked, dark: res.dark, pageBg: res.pageBg, low: res.low.slice(0, 12), lowCount: res.low.length, lightBoxes: res.lightBoxes.slice(0, 6), head });
    log(`${base}: HTTP ${status} dark=${res.dark} checked=${res.checked} low=${res.low.length} lightBoxes=${res.lightBoxes.length} | ${head.slice(0, 90)}`);
    for (const x of res.low.slice(0, 5)) log(`    low ${x.ratio}/${x.need} "${x.text}" fg=${x.fg} bg=${x.bg} ${x.path}`);
    for (const x of res.lightBoxes.slice(0, 3)) log(`    lightbox bg=${x.bg} ${x.path} "${x.text}"`);
  };
  for (const [name, url] of screens(user, ids)) {
    await run(name, async () => (await page.goto(BASE + url)).status());
  }
  // Forms with warnings/errors, as a user meets them.
  if (user === 'admin') {
    await run('config-invalid-approver', async () => {
      const r = await page.goto(`${BASE}/batch-control-configuration/`);
      const f = page.locator('textarea[name="_.approversText"]').first();
      await f.fill('approver-1, approver-2, nosuch-e2e05');
      await f.blur(); await sleep(1500);
      return r.status();
    });
    await run('config-invalid-submit', async () => {
      await Promise.all([page.waitForLoadState('load'), page.locator('button[name="Submit"]').first().click()]);
      return 200;
    });
  } else {
    await run('run-form-invalid', async () => {
      await page.goto(`${BASE}/job/fresh-daily/batch-control/`);
      await Promise.all([page.waitForLoadState('load'), page.click('button[name="Submit"]')]);
      return 200;
    });
    await run('grant-form-invalid', async () => {
      await page.goto(`${BASE}/batch-control/grants/`);
      const f = page.locator('form[action$="grants/create"]');
      await f.locator('input[name="scopeFullName"]').fill('fresh-nope');
      await Promise.all([page.waitForLoadState('load'), f.locator('button[name="Submit"]').click()]);
      return 200;
    });
    await run('refusal-approval-required', async () => {
      await page.goto(`${BASE}/job/fresh-daily/build?delay=0sec`);
      await Promise.all([page.waitForLoadState('load'), page.locator('#main-panel form button[name="Submit"], #main-panel form button.jenkins-button--primary').first().click()]);
      return 200;
    });
  }
  fs.writeFileSync(`${OUT}/theme-${theme}-${user}.json`, JSON.stringify(results, null, 1));
  await page.context().close();
}
await close();
