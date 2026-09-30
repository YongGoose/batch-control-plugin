// B7 change-control grants. Usage: node b7.mjs <step> ...
import { login, close, shot, api, job, waitFor, sleep, BASE, log, changeRows, mails } from './lib.mjs';
import { execSync } from 'node:child_process';
const L = 'section-b.log';
const steps = {};
const txt = async (p, sel = '#main-panel, body') => (await p.locator(sel).first().innerText()).replace(/\s+/g, ' ');
const errText = (t) => (t.replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ').match(/(?:Error|Access Denied|Oops!?) (.{0,240}?) (?:REST API|Logging ID)/) || [null, t.replace(/\s+/g, ' ').slice(0, 160)])[1];

/** requester submits a grant request in the form; returns the detail URL (and the page for inspection). */
async function requestGrant(page, { type = 'JOB', scope, actions, minutes = 15, pattern, reason, approver = 'approver-1' }) {
  await page.goto(`${BASE}/batch-control/grants/`);
  await page.selectOption('select[name="scopeType"]', type);
  await page.fill('input[name="scopeFullName"]', scope);
  for (const a of actions) {
    const box = page.locator(`input[name="actions"][value="${a}"]`);
    if (!(await box.isChecked())) await box.locator('xpath=following-sibling::label[1]').click();
  }
  if (pattern !== undefined) await page.fill('input[name="createNamePattern"]', pattern);
  await page.selectOption('select[name="durationMinutes"]', String(minutes));
  await page.fill('textarea[name="reason"]', reason);
  await page.locator(`input[name="approvers"][value="${approver}"] + label`).click();
  const [resp] = await Promise.all([page.waitForNavigation({ waitUntil: 'load' }).catch(() => null), page.locator('button:has-text("Request Grant")').click()]);
  if (!resp || resp.status() >= 400) return { error: errText(await page.content()), status: resp && resp.status() };
  const href = await page.locator('#main-panel table').first().locator('tbody tr', { hasText: reason.slice(0, 30) }).first().locator('a').first().getAttribute('href');
  return { url: new URL(href, page.url()).href };
}
async function decide(url, how = 'approve', comment = 'ok') {
  const { context, page } = await login('approver-1');
  await page.goto(url);
  const f = page.locator(`form[name="${how}"]`);
  await f.locator('textarea[name="comment"]').fill(comment);
  const [r] = await Promise.all([page.waitForNavigation({ waitUntil: 'load' }).catch(() => null), f.locator('button').first().click()]);
  const t = await txt(page);
  await context.close();
  return { status: r && r.status(), text: t };
}
const status = async (u, p) => (await p.goto(BASE + u)).status();

steps.configure = async () => {
  const rq = await login('requester'); const p = rq.page;
  // B7-02/03/04
  const g = await requestGrant(p, { scope: 'batch-daily', actions: ['CONFIGURE'], minutes: 15, reason: 'Change the batch-daily description (B7-02).' });
  await p.goto(`${BASE}/batch-control/grants/`);
  const row = (await p.locator('#main-panel table').first().locator('tbody tr').first().innerText()).replace(/\s+/g, ' ');
  await shot(p, p.locator('#main-panel table').first().locator('tbody tr').first(), 'B7-02', { pad: 8 });
  await sleep(2000);
  const id = g.url.split('/grants/')[1].replace('/', '');
  log(L, `B7-02 grant request row "${row}"; mail to approver-1: ${(await mails(id)).map((m) => m.To[0].Address + ': ' + m.Subject).join(' | ')}`);
  const ap = await login('approver-1');
  await ap.page.goto(g.url);
  await shot(ap.page, '#main-panel', 'B7-03', { pad: 6 });
  log(L, `B7-03 approver detail: ${(await txt(ap.page, '#main-panel')).slice(0, 380)}`);
  await ap.context.close();
  await decide(g.url);
  await p.goto(`${BASE}/batch-control/grants/`);
  const act = p.locator('table:has(th:has-text("Expires")) tbody tr:has-text("batch-daily")').first();
  log(L, `B7-04 Active Grants row "${(await act.innerText()).replace(/\s+/g, ' ')}"`);
  await shot(p, act, 'B7-04', { pad: 8 });
  // B7-05/06
  await p.goto(`${BASE}/job/batch-daily/`);
  const side = (await p.locator('#side-panel a').allInnerTexts()).map((t) => t.trim()).filter(Boolean);
  await shot(p, '#side-panel', 'B7-05-1-sidebar', { pad: 6 });
  const [cr] = await Promise.all([p.waitForNavigation(), p.locator('#side-panel a:has-text("Configure")').click()]);
  await p.waitForTimeout(1500);
  const formOk = await p.locator('form[name="config"] textarea[name="description"]').count();
  await shot(p, ['#main-panel .jenkins-app-bar, #main-panel h1', 'textarea[name="description"]'], 'B7-05-2-configure-form', { pad: 8 });
  log(L, `B7-05 requester sidebar ${JSON.stringify(side)}; Configure -> ${cr.status()} form rendered ${formOk}`);
  await p.fill('textarea[name="description"]', 'Daily batch job for the nightly data load. (edited by requester under a CONFIGURE window, B7-06)');
  await Promise.all([p.waitForNavigation(), p.locator('button[name="Submit"]').click()]);
  const desc = await txt(p, '#description, #main-panel');
  await sleep(1500);
  const rec = (await changeRows(/,CONFIGURE,batch-daily,requester,/))[0];
  log(L, `B7-06 saved -> ${p.url().replace(BASE, '')}; job page shows edit: ${desc.includes('B7-06')}; record ${rec}`);
  const ad = await login('admin');
  await ad.page.goto(`${BASE}/batch-control/changes/`);
  const cRow = ad.page.locator('#main-panel tr:has-text("batch-daily"):has-text("requester")').first();
  const glink = await cRow.locator('a').evaluateAll((as) => as.map((a) => a.innerText.trim() + '->' + a.getAttribute('href')));
  const d = cRow.locator('summary, a:has-text("Diff")').first(); if (await d.count()) { await d.click(); await ad.page.waitForTimeout(400); }
  await shot(ad.page, cRow, 'B7-06-record', { pad: 8 });
  log(L, `B7-06 Change Records row links ${JSON.stringify(glink)}; diff excerpt: ${(await cRow.innerText()).split('\n').filter((l) => /^[+-]\s*</.test(l.trim())).slice(0, 2).join(' | ')}`);
  // B11-11 grant link opens the grant detail
  const gl = glink.find((x) => x.includes('/grants/'));
  if (gl) log(L, `B11-11 grant link ${gl} -> ${(await ad.page.goto(new URL(gl.split('->')[1], BASE).href)).status()} "${(await txt(ad.page, '#main-panel')).slice(0, 80)}"`);
  // B7-07/08
  const x = await p.goto(`${BASE}/job/batch-daily/config.xml`);
  log(L, `B7-07 config.xml -> ${x.status()}; B7-08 batch-pipeline/configure -> ${await status('/job/batch-pipeline/configure', p)}`);
  await close();
};

steps.expiry = async () => {
  const rq = await login('requester'); const p = rq.page;
  const g = await requestGrant(p, { scope: 'batch-pipeline', actions: ['CONFIGURE'], minutes: 1, reason: 'One-minute window, save after expiry (B7-09).' });
  await decide(g.url);
  const cfgBefore = (await api('admin', '/job/batch-pipeline/config.xml', { raw: true })).text;
  const r = await p.goto(`${BASE}/job/batch-pipeline/configure`); await p.waitForTimeout(1500);
  await p.fill('textarea[name="description"]', 'edited after the window closed (B7-09)');
  log(L, `B7-09 configure opened -> ${r.status()}; waiting 70 s for the window to end`);
  await sleep(70000);
  const [s] = await Promise.all([p.waitForNavigation({ waitUntil: 'load' }), p.locator('button[name="Submit"]').click()]);
  const t = errText(await p.content());
  await shot(p, '#main-panel, body', 'B7-09', { pad: 8 });
  const same = (await api('admin', '/job/batch-pipeline/config.xml', { raw: true })).text === cfgBefore;
  log(L, `B7-09 Save after expiry -> ${s.status()} "${t}"; config unchanged ${same}`);
  // B7-10
  await p.goto(`${BASE}/batch-control/grants/`);
  const heads = await p.locator('#main-panel h2').allInnerTexts();
  const hist = p.locator('h2:has-text("Expired"), h2:has-text("History"), h2:has-text("Past")').first();
  const again = p.locator('#main-panel a:has-text("Request again"), #main-panel a:has-text("again"), #main-panel a:has-text("Re-request")');
  log(L, `B7-10 Grants sections ${JSON.stringify(heads)}; re-request links ${await again.count()}`);
  await shot(p, '#main-panel', 'B7-10', { pad: 6 });
  if (await again.count()) {
    await again.first().click(); await p.waitForLoadState('load');
    log(L, `B7-10 re-request -> ${p.url().replace(BASE, '')} scope=${await p.inputValue('input[name="scopeFullName"]')}`);
  }
  await close();
};

steps.folder = async () => {
  const rq = await login('requester'); const p = rq.page;
  // B7-12 FOLDER CONFIGURE
  let g = await requestGrant(p, { type: 'FOLDER', scope: 'team', actions: ['CONFIGURE'], minutes: 15, reason: 'Folder-wide configure (B7-12).' });
  await decide(g.url);
  log(L, `B7-12 FOLDER team CONFIGURE: team/app-1/configure -> ${await status('/job/team/job/app-1/configure', p)}; batch-cron/configure -> ${await status('/job/batch-cron/configure', p)}; team/configure -> ${await status('/job/team/configure', p)}`);
  // B7-13/14 FOLDER CREATE without restriction
  g = await requestGrant(p, { type: 'FOLDER', scope: 'team', actions: ['CREATE'], minutes: 15, reason: 'Create any job in team/ (B7-13).' });
  await decide(g.url);
  const gid = g.url.split('/grants/')[1].replace('/', '');
  await p.goto(`${BASE}/job/team/newJob`);
  await p.fill('#name', 'app-free');
  await p.locator('label:has-text("Freestyle project")').first().click();
  await Promise.all([p.waitForNavigation(), p.locator('#ok-button').click()]);
  await p.waitForTimeout(1500);
  const locked = { approval: await p.locator('[name="_.approvalRequired"]').first().isChecked(), timer: await p.locator('[name="_.blockTimer"]').first().isChecked(), upstream: await p.locator('[name="_.blockUpstream"]').first().isChecked() };
  await shot(p, p.locator('[name="_.approvalRequired"]').first().locator('xpath=ancestor::*[contains(@class,"jenkins-section") or contains(@class,"optionalBlock-container")][1]'), 'B7-13', { pad: 8 });
  await Promise.all([p.waitForNavigation(), p.locator('button[name="Submit"]').click()]);
  const cre = (await changeRows(/,CREATE,team\/app-free,/))[0];
  log(L, `B7-13 New Item team/app-free -> created; lock ${JSON.stringify(locked)}; CREATE record ${cre} (grant ${gid})`);
  await p.goto(`${BASE}/`);
  const rootNew = await p.locator('#side-panel a:has-text("New Item")').count();
  log(L, `B7-14 root New Item offered ${rootNew}; /view/all/newJob -> ${await status('/view/all/newJob', p)}; POST /createItem?name=outside -> ${(await api('requester', '/createItem?name=outside&mode=hudson.model.FreeStyleProject', { method: 'POST', body: '' })).status}; outside exists ${(await api('admin', '/job/outside/api/json')).status}`);
  await close();
};

steps.pattern = async () => {
  const rq = await login('requester'); const p = rq.page;
  // B7-17 invalid regex, B7-18 256-char name
  const bad = await requestGrant(p, { type: 'FOLDER', scope: 'team', actions: ['CREATE'], pattern: '/app-[/', reason: 'Invalid regex (B7-17).' });
  await shot(p, '#main-panel, body', 'B7-17', { pad: 8 });
  log(L, `B7-17 pattern "/app-[/" -> ${bad.status} "${bad.error}"`);
  // B7-16 regex
  const g = await requestGrant(p, { type: 'FOLDER', scope: 'team', actions: ['CREATE'], pattern: '/app-[0-9]+/', minutes: 15, reason: 'Numbered apps only (B7-16).' });
  await decide(g.url);
  const vi0 = (await changeRows(/GRANT_VIOLATION/)).length;
  const create = async (name) => {
    await p.goto(`${BASE}/job/team/newJob`);
    await p.locator('#name').pressSequentially(name, { delay: 30 });
    await p.waitForTimeout(1200);
    const msg = (await p.locator('#itemname-invalid, .input-validation-message, #itemname-required').allInnerTexts()).join(' ').trim();
    await p.locator('label:has-text("Freestyle project")').first().click();
    const [r] = await Promise.all([p.waitForNavigation().catch(() => null), p.locator('#ok-button').click()]);
    const out = `${r && r.status()} ${errText(await p.content()).slice(0, 90)} (inline "${msg}")`;
    if (r && r.status() === 200 && p.url().includes('/configure')) await Promise.all([p.waitForNavigation(), p.locator('button[name="Submit"]').click()]);
    return out;
  };
  const a7 = await create('app-7');
  const ax = await create('app-x');
  const long = await create('app-' + '1'.repeat(252));
  const vi1 = await changeRows(/GRANT_VIOLATION/);
  log(L, `B7-16 /app-[0-9]+/: app-7 -> ${a7} exists ${(await api('admin', '/job/team/job/app-7/api/json')).status}; app-x -> ${ax} exists ${(await api('admin', '/job/team/job/app-x/api/json')).status}`);
  log(L, `B7-18 256-char name -> ${long}`);
  log(L, `B7-15 GRANT_VIOLATION records added by typing and three submissions: ${vi1.length - vi0} (expected one per refused POST: 2) ${vi1.slice(0, vi1.length - vi0).map((l) => l.split(',')[2].slice(0, 30)).join(', ')}`);
  // B7-19 rename app-7 -> evil
  const vr = (await changeRows(/GRANT_VIOLATION/)).length;
  await p.goto(`${BASE}/job/team/job/app-7/confirm-rename`);
  const rs = p.url();
  let rr = 'no rename page';
  if (await p.locator('input[name="newName"]').count()) {
    await p.fill('input[name="newName"]', 'evil'); await p.waitForTimeout(1000);
    const inline = (await p.locator('.error, .validation-error-area--visible').allInnerTexts()).join(' ').replace(/\s+/g, ' ').trim();
    const [r] = await Promise.all([p.waitForNavigation().catch(() => null), p.locator('button[name="Submit"], button:has-text("Rename")').first().click()]);
    rr = `${r && r.status()} "${errText(await p.content()).slice(0, 140)}" inline "${inline}"`;
    await shot(p, '#main-panel, body', 'B7-19', { pad: 8 });
  }
  log(L, `B7-19 rename team/app-7 -> evil: ${rr}; evil exists ${(await api('admin', '/job/team/job/evil/api/json')).status}; app-7 exists ${(await api('admin', '/job/team/job/app-7/api/json')).status}; GRANT_VIOLATION +${(await changeRows(/GRANT_VIOLATION/)).length - vr}`);
  // B7-20 CLI create-job with trailing space
  const g2 = await requestGrant(p, { type: 'FOLDER', scope: 'team', actions: ['CREATE'], pattern: 'app-8', minutes: 15, reason: 'Exact app-8 (B7-20).' });
  await decide(g2.url);
  let cli;
  try { cli = execSync(`echo '<project><builders/></project>' | ../scripts/cli.sh requester create-job "team/app-8 " 2>&1; echo "EXIT=$?"`, { shell: '/bin/bash' }).toString(); } catch (e) { cli = e.stdout.toString(); }
  log(L, `B7-20 CLI create-job "team/app-8 " -> ${cli.replace(/\s+/g, ' ').slice(0, 200)}; exists "app-8 " ${(await api('admin', '/job/team/job/app-8%20/api/json')).status}, app-8 ${(await api('admin', '/job/team/job/app-8/api/json')).status}`);
  await close();
};

steps.delete = async () => {
  const rq = await login('requester'); const p = rq.page;
  // B7-21 DELETE window on team/app-7
  const g = await requestGrant(p, { scope: 'team/app-7', actions: ['DELETE'], minutes: 15, reason: 'Remove app-7 (B7-21).' });
  await decide(g.url);
  await p.goto(`${BASE}/job/team/job/app-7/`);
  const side = (await p.locator('#side-panel a').allInnerTexts()).map((t) => t.trim());
  p.once('dialog', (d) => d.accept());
  await p.locator('#side-panel a:has-text("Delete Project")').click(); await p.waitForTimeout(800);
  const ok = p.locator('dialog[open] button[data-id="ok"]').first(); if (await ok.count()) await Promise.all([p.waitForNavigation().catch(() => null), ok.click()]);
  await sleep(2000);
  log(L, `B7-21 requester sidebar has Delete Project: ${side.includes('Delete Project')}; after delete app-7 -> ${(await api('admin', '/job/team/job/app-7/api/json')).status}; record ${(await changeRows(/,DELETE,team\/app-7,/))[0]}`);
  // B7-22 configurer (standing Configure+Delete) without a window
  const cf = await login('configurer');
  await cf.page.goto(`${BASE}/job/team/job/app-free/`);
  cf.page.once('dialog', (d) => d.accept());
  await cf.page.locator('#side-panel a:has-text("Delete Project")').click(); await cf.page.waitForTimeout(800);
  const ok2 = cf.page.locator('dialog[open] button[data-id="ok"]').first();
  if (await ok2.count()) await Promise.all([cf.page.waitForNavigation().catch(() => null), ok2.click()]);
  await cf.page.waitForTimeout(1500);
  const vt = await txt(cf.page);
  await shot(cf.page, '#main-panel, body, .jenkins-notification', 'B7-22', { pad: 8 });
  log(L, `B7-22 configurer Delete Project on team/app-free -> ${cf.page.url().replace(BASE, '')} "${vt.slice(0, 260)}"; job still there ${(await api('admin', '/job/team/job/app-free/api/json')).status}`);
  // B7-24 reject empty / with comment
  const g3 = await requestGrant(p, { scope: 'batch-pipeline', actions: ['CONFIGURE'], reason: 'To be rejected (B7-24).' });
  const e = await decide(g3.url, 'reject', '');
  const w = await decide(g3.url, 'reject', 'Not in this sprint.');
  await p.goto(g3.url);
  log(L, `B7-24 reject empty -> ${e.status} "${errText(e.text)}"; with comment -> ${(await txt(p, '#main-panel')).match(/Status \w+.{0,200}/)[0]}`);
  await shot(p, '#main-panel table', 'B7-24', { pad: 8 });
  // B7-25 scope the requester cannot see
  const g4 = await requestGrant(p, { scope: 'team/secret-job', actions: ['CONFIGURE'], reason: 'A job I cannot see (B7-25).' });
  log(L, `B7-25 CONFIGURE on team/secret-job -> ${g4.status} "${g4.error}"`);
  await shot(p, '#main-panel, body', 'B7-25', { pad: 8 });
  await close();
};

steps.replay = async () => {
  // B7-27 CONFIGURE window on an uncontrolled Pipeline confers Replay
  const rq = await login('requester'); const p = rq.page;
  const last = (await job('batch-upstream', 'lastBuild[number]')).lastBuild.number;
  await p.goto(`${BASE}/job/batch-upstream/${last}/`);
  const before = (await p.locator('#side-panel a').allInnerTexts()).map((t) => t.trim()).includes('Replay');
  const g = await requestGrant(p, { scope: 'batch-upstream', actions: ['CONFIGURE'], minutes: 15, reason: 'Replay check on an uncontrolled Pipeline (B7-27).' });
  await decide(g.url);
  await p.goto(`${BASE}/job/batch-upstream/${last}/`);
  const after = (await p.locator('#side-panel a').allInnerTexts()).map((t) => t.trim()).includes('Replay');
  await shot(p, '#side-panel', 'B7-27', { pad: 6 });
  const n = (await job('batch-upstream')).nextBuildNumber;
  let res = 'no Replay';
  if (after) {
    await p.locator('#side-panel a:has-text("Replay")').click(); await p.waitForLoadState('load');
    const [r] = await Promise.all([p.waitForNavigation().catch(() => null), p.locator('button:has-text("Run")').first().click()]);
    await sleep(8000);
    res = `${r && r.status()} ${p.url().replace(BASE, '')} next ${n}->${(await job('batch-upstream')).nextBuildNumber}`;
  }
  log(L, `B7-27 Replay on batch-upstream #${last}: before window ${before}, with window ${after}; replay -> ${res}`);
  await close();
};

const wanted = process.argv.slice(2);
try { for (const s of wanted) { console.log(`== ${s}`); await steps[s](); } } finally { await close(); }
