// B1: global switches and global configuration (Manage Jenkins -> System -> Batch Control).
// Usage: node b1.mjs <step> ...   steps: switches approvers self durations incidents mail invalid help savefail manager
import { login, close, shot, api, job, groovy, waitFor, sleep, BASE, MAIL, log, setGlobal, globalCfg, changeRows, requestRun, clickBuildEntry, mails } from './lib.mjs';
import { execSync } from 'node:child_process';
const L = 'section-b.log';
const steps = {};
const sideOf = async (page) => (await page.locator('#side-panel a').allInnerTexts()).map((t) => t.trim()).filter(Boolean);
const toggles = async () => (await changeRows(/,CONFIG_TOGGLE,/));
const configXml = () => execSync("docker exec batch-control-e2e cat /var/jenkins_home/io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration.xml").toString();
const xmlFlag = (x, f) => (x.match(new RegExp(`<${f}>(\\w+)</${f}>`)) || [])[1];

steps.switches = async () => {
  const ad = await login('admin');
  // throwaway job for configurer's delete (admin creates it while switches are on; admin is not vetoed)
  if ((await api('admin', '/job/b1-tmp/api/json')).status === 404) {
    await ad.page.goto(`${BASE}/view/all/newJob`); await ad.page.fill('#name', 'b1-tmp');
    await ad.page.locator('label:has-text("Freestyle project")').first().click();
    await Promise.all([ad.page.waitForNavigation(), ad.page.locator('#ok-button').click()]);
    await Promise.all([ad.page.waitForNavigation(), ad.page.locator('button[name="Submit"]').click()]);
  }
  // ---- B1-01 both off
  const t0 = (await toggles()).length;
  let r = await setGlobal(ad.page, { runControlEnabled: false, changeControlEnabled: false });
  const t1 = await toggles();
  log(L, `B1-01 both off saved (${r.status}); CONFIG_TOGGLE +${t1.length - t0}: ${t1.slice(0, t1.length - t0).join(' || ')}`);
  const c0 = (await changeRows()).length; const runs0 = (await api('admin', '/batch-control/history/runs.csv')).text.split('\n').length;
  const rq = await login('requester');
  await rq.page.goto(`${BASE}/job/batch-daily/`);
  const s1 = await sideOf(rq.page);
  const notice1 = await rq.page.locator('#main-panel .jenkins-alert').count();
  await shot(rq.page, ['#side-panel', '#main-panel .jenkins-app-bar, #main-panel h1'], 'B1-01-1-requester-job-page', { pad: 8 });
  const n0 = (await job('batch-daily')).nextBuildNumber;
  const b = await clickBuildEntry(rq.page, /Build with Parameters|Build Now/);
  const built = await waitFor(async () => (await job('batch-daily')).nextBuildNumber > n0, { timeout: 30000 });
  const cf = await login('configurer');
  const cfg = await cf.page.goto(`${BASE}/job/b1-tmp/configure`);
  await cf.page.fill('textarea[name="description"]', 'edited by configurer with both switches off');
  await Promise.all([cf.page.waitForNavigation(), cf.page.locator('button[name="Submit"]').click()]);
  await cf.page.goto(`${BASE}/job/b1-tmp/`);
  cf.page.once('dialog', (d) => d.accept());
  await cf.page.locator('#side-panel a:has-text("Delete Project")').click();
  await cf.page.waitForTimeout(800);
  const ok = cf.page.locator('dialog[open] button[data-id="ok"], dialog[open] button:has-text("Yes")').first();
  if (await ok.count()) await Promise.all([cf.page.waitForNavigation().catch(() => null), ok.click()]);
  await sleep(3000);
  const del = (await api('admin', '/job/b1-tmp/api/json')).status;
  const c1 = (await changeRows()).length; const runs1 = (await api('admin', '/batch-control/history/runs.csv')).text.split('\n').length;
  log(L, `B1-01 requester sidebar ${JSON.stringify(s1)} notices=${notice1}; Build with Parameters -> built=${!!built} (${n0}->${(await job('batch-daily')).nextBuildNumber}); configurer configure=${cfg.status()} delete -> job ${del}; change records ${c0}->${c1}, runs.csv lines ${runs0}->${runs1}`);
  await cf.context.close();
  // ---- B1-02 run control only
  const t2a = (await toggles()).length;
  r = await setGlobal(ad.page, { runControlEnabled: true });
  const t2 = await toggles();
  await rq.page.goto(`${BASE}/job/batch-daily/`);
  const s2 = await sideOf(rq.page);
  await shot(rq.page, '#side-panel', 'B1-02', { pad: 8 });
  const n1 = (await job('batch-daily')).nextBuildNumber;
  const b2 = await clickBuildEntry(rq.page, 'Direct Build (needs approval)');
  await sleep(3000);
  log(L, `B1-02 run control on: sidebar ${JSON.stringify(s2)}; Direct Build -> HTTP ${b2.status} next ${n1}->${(await job('batch-daily')).nextBuildNumber}; toggle ${t2.slice(0, t2.length - t2a).join(' || ')}`);
  // ---- B1-03 change control only
  const t3a = (await toggles()).length;
  r = await setGlobal(ad.page, { runControlEnabled: false, changeControlEnabled: true });
  const t3 = await toggles();
  await rq.page.goto(`${BASE}/job/batch-daily/`);
  const s3 = await sideOf(rq.page);
  await shot(rq.page, '#side-panel', 'B1-03', { pad: 8 });
  const n2 = (await job('batch-daily')).nextBuildNumber;
  await clickBuildEntry(rq.page, /Build with Parameters|Build Now/);
  const built3 = await waitFor(async () => (await job('batch-daily')).nextBuildNumber > n2, { timeout: 30000 });
  log(L, `B1-03 change control only: sidebar ${JSON.stringify(s3)}; Build -> built=${!!built3}; toggles +${t3.length - t3a}: ${t3.slice(0, t3.length - t3a).join(' || ')}`);
  // ---- B1-04 both on
  r = await setGlobal(ad.page, { runControlEnabled: true });
  await rq.page.goto(`${BASE}/job/batch-daily/`);
  const s4 = await sideOf(rq.page);
  await shot(rq.page, '#side-panel', 'B1-04', { pad: 8 });
  log(L, `B1-04 both on: sidebar ${JSON.stringify(s4)}`);
  // ---- B1-05 toggle only run control while change control stays on
  const t5a = (await toggles()).length;
  await setGlobal(ad.page, { runControlEnabled: false });
  const x1 = configXml();
  await setGlobal(ad.page, { runControlEnabled: true });
  const x2 = configXml();
  const t5 = await toggles();
  log(L, `B1-05 run off/on with change on: toggles +${t5.length - t5a} [${t5.slice(0, t5.length - t5a).map((l) => l.split(',').slice(1, 4).join('/') + ' ' + l.split(',').slice(6).join(',').slice(0, 60)).join(' || ')}]; config.xml after off: run=${xmlFlag(x1, 'runControlEnabled')} change=${xmlFlag(x1, 'changeControlEnabled')}; after on: run=${xmlFlag(x2, 'runControlEnabled')} change=${xmlFlag(x2, 'changeControlEnabled')}`);
  await ad.page.goto(`${BASE}/batch-control/changes/`);
  await shot(ad.page, ad.page.locator('#main-panel tr:has-text("CONFIG_TOGGLE")'), 'B1-05-toggle-records', { pad: 8 });
  await close();
};

steps.approvers = async () => {
  const ad = await login('admin');
  // ---- B1-06 upper-case id
  await setGlobal(ad.page, { approversText: 'approver-1\nAPPROVER-2\napprover-disc\nadmin' });
  const rq = await login('requester');
  await rq.page.goto(`${BASE}/job/batch-pipeline/batch-control/`);
  const offered = await rq.page.locator('form[name="batch-control-request"] input[name="approvers"]').evaluateAll((es) => es.map((e) => e.value));
  await shot(rq.page, rq.page.locator('form[name="batch-control-request"] input[name="approvers"]').first().locator('xpath=ancestor::*[contains(@class,"jenkins-form-item")][1]'), 'B1-06-1-form', { pad: 8 });
  let url = null; let decided = '';
  if (offered.includes('APPROVER-2')) {
    url = await requestRun(rq.page, '/job/batch-pipeline/', { reason: 'Upper-case approver id (B1-06).', approvers: ['APPROVER-2'] });
    const a2 = await login('approver-2');
    await a2.page.goto(url);
    const forms = await a2.page.locator('form[name="approve"]').count();
    if (forms) {
      await a2.page.fill('form[name="approve"] textarea[name="comment"]', 'case-insensitive ok');
      await Promise.all([a2.page.waitForLoadState('load'), a2.page.locator('form[name="approve"] button').first().click()]);
    }
    decided = (await a2.page.locator('#main-panel').innerText()).replace(/\s+/g, ' ').slice(0, 260);
    await shot(a2.page, '#main-panel table', 'B1-06-2-decided', { pad: 8 });
    log(L, `B1-06 approver-2 decision form present=${forms}; page: ${decided}`);
    await a2.context.close();
  }
  log(L, `B1-06 approvers offered: ${JSON.stringify(offered)}; stored ${JSON.stringify((await globalCfg()).approvers)}`);
  // ---- B1-07 empty list
  await setGlobal(ad.page, { approversText: '' });
  await rq.page.goto(`${BASE}/job/batch-daily/batch-control/`);
  const w1 = (await rq.page.locator('#main-panel .jenkins-alert').allInnerTexts()).join(' ').replace(/\s+/g, ' ');
  const f1 = await rq.page.locator('form[name="batch-control-request"] button:has-text("Submit Request")').count();
  await shot(rq.page, '#main-panel', 'B1-07-1-request-form', { pad: 8 });
  await rq.page.goto(`${BASE}/batch-control/grants/`);
  const w2 = (await rq.page.locator('#main-panel .jenkins-alert').allInnerTexts()).join(' ').replace(/\s+/g, ' ');
  const f2 = await rq.page.locator('button:has-text("Request Grant")').count();
  await shot(rq.page, rq.page.locator('#main-panel .jenkins-alert').first(), 'B1-07-2-grants-form', { pad: 8 });
  const rest = await api('requester', '/job/batch-daily/batch-control/submit', { method: 'POST', body: new URLSearchParams({ reason: 'x', approvers: 'approver-1', DATE: '2026-01-01' }), headers: { 'Content-Type': 'application/x-www-form-urlencoded' } });
  log(L, `B1-07 empty approvers: request form alert "${w1}" submit=${f1}; grants alert "${w2}" submit=${f2}; REST submit -> ${rest.status} "${rest.text.replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ').match(/(Error|refused|approver)[^.]*\./i)?.[0] || rest.text.slice(0, 80)}"`);
  await setGlobal(ad.page, { approversText: 'approver-1\napprover-2\napprover-disc\nadmin' });
  log(L, `B1-07 restored approvers ${JSON.stringify((await globalCfg()).approvers)}`);
  await close();
};

steps.self = async () => {
  const ad = await login('admin');
  await setGlobal(ad.page, { allowAdminSelfApproval: false });
  let url = await requestRun(ad.page, '/job/batch-pipeline/', { reason: 'Self approval switched off (B1-08).', approvers: ['admin'] }).catch((e) => null);
  const t1 = (await ad.page.locator('#main-panel, body').first().innerText()).replace(/\s+/g, ' ').slice(0, 300);
  const forms1 = await ad.page.locator('form[name="approve"]').count();
  await shot(ad.page, '#main-panel', 'B1-08-1-self-off', { pad: 8 });
  log(L, `B1-08 off: admin designates himself -> ${ad.page.url()} decision form=${forms1} "${t1}"`);
  await setGlobal(ad.page, { allowAdminSelfApproval: true });
  url = await requestRun(ad.page, '/job/batch-pipeline/', { reason: 'Self approval on (B1-08).', approvers: ['admin'] });
  await ad.page.fill('form[name="approve"] textarea[name="comment"]', 'self');
  await Promise.all([ad.page.waitForLoadState('load'), ad.page.locator('form[name="approve"] button').first().click()]);
  await shot(ad.page, '#main-panel table', 'B1-08-2-self-on', { pad: 8 });
  const id = url.split('/requests/')[1].replace('/', '');
  const csv = (await api('admin', '/batch-control/history/requests.csv')).text.split('\n');
  log(L, `B1-08 on: requests.csv header ${csv[0]} | row ${csv.find((l) => l.startsWith(id))}`);
  await close();
};

steps.durations = async () => {
  const ad = await login('admin');
  await setGlobal(ad.page, { grantDurationOptionsText: '5, 10' });
  const rq = await login('requester');
  await rq.page.goto(`${BASE}/batch-control/grants/`);
  const opts = await rq.page.locator('select[name="durationMinutes"] option').allInnerTexts();
  await shot(rq.page, rq.page.locator('select[name="durationMinutes"]'), 'B1-11-1-options', { pad: 30 });
  if (!(await rq.page.locator('#main-panel table').first().locator('tbody tr', { hasText: 'Five-minute window' }).count())) {
    await rq.page.fill('input[name="scopeFullName"]', 'batch-pipeline');
    await rq.page.locator('#grant-action-configure + label').click();
    await rq.page.selectOption('select[name="durationMinutes"]', '5');
    await rq.page.fill('textarea[name="reason"]', 'Five-minute window (B1-11).');
    await rq.page.locator('#grant-approver-0 + label').click();
    await Promise.all([rq.page.waitForLoadState('load'), rq.page.locator('button:has-text("Request Grant")').click()]);
  }
  const href = await rq.page.locator('#main-panel table').first().locator('tbody tr', { hasText: 'Five-minute window' }).first().locator('a').first().getAttribute('href');
  const gurl = new URL(href, rq.page.url()).href;
  const ap = await login('approver-1');
  await ap.page.goto(gurl);
  await ap.page.fill('form[name="approve"] textarea[name="comment"]', 'ok');
  await Promise.all([ap.page.waitForLoadState('load'), ap.page.locator('form[name="approve"] button').first().click()]);
  await rq.page.goto(`${BASE}/batch-control/grants/`);
  const row = rq.page.locator('table:has(th:has-text("Expires")) tbody tr').first();
  log(L, `B1-11 durations "5, 10": options ${JSON.stringify(opts)}; active grant row "${(await row.innerText()).replace(/\s+/g, ' ')}"`);
  await shot(rq.page, row, 'B1-11-2-active', { pad: 8 });
  // B1-12 max 20, custom 30
  await setGlobal(ad.page, { maxGrantMinutes: 20 });
  await rq.page.goto(`${BASE}/batch-control/grants/`);
  const desc = (await rq.page.locator('.jenkins-form-item:has(input[name="customDurationMinutes"])').innerText()).replace(/\s+/g, ' ');
  const before = (await rq.page.locator('#main-panel table').first().locator('tbody tr').count());
  await rq.page.fill('input[name="scopeFullName"]', 'batch-pipeline');
  await rq.page.locator('#grant-action-configure + label').click();
  await rq.page.fill('input[name="customDurationMinutes"]', '30');
  await rq.page.fill('textarea[name="reason"]', 'Custom 30 over a max of 20 (B1-12).');
  await rq.page.locator('#grant-approver-0 + label').click();
  const [resp] = await Promise.all([rq.page.waitForNavigation({ waitUntil: 'load' }).catch(() => null), rq.page.locator('button:has-text("Request Grant")').click()]);
  await rq.page.waitForTimeout(800);
  const t = (await rq.page.locator('#main-panel, body').first().innerText()).replace(/\s+/g, ' ').slice(0, 300);
  const html5 = await rq.page.locator('input[name="customDurationMinutes"]').evaluate((e) => e.validationMessage).catch(() => '');
  await shot(rq.page, '#main-panel', 'B1-12', { pad: 8 });
  await rq.page.goto(`${BASE}/batch-control/grants/`);
  const after = (await rq.page.locator('#main-panel table').first().locator('tbody tr').count());
  log(L, `B1-12 max 20: field says "${desc}"; submit custom 30 -> ${resp ? resp.status() : 'no navigation'} browser validation "${html5}" page "${t}"; grant requests ${before}->${after}`);
  await setGlobal(ad.page, { grantDurationOptionsText: '1, 15, 30, 60', maxGrantMinutes: 240 });
  await close();
};

steps.incidents = async () => {
  const ad = await login('admin');
  await setGlobal(ad.page, { incidentResultsText: 'FAILURE, UNSTABLE, ABORTED' });
  // a long-running uncontrolled job the admin starts and aborts
  if ((await api('admin', '/job/b1-sleep/api/json')).status === 404) {
    const xml = `<?xml version='1.1' encoding='UTF-8'?><project><description>B1-13 long runner</description><builders><hudson.tasks.Shell><command>sleep 120</command></hudson.tasks.Shell></builders></project>`;
    await api('admin', '/createItem?name=b1-sleep', { method: 'POST', body: xml, headers: { 'Content-Type': 'application/xml' } });
  }
  // clear the creation lock in the UI (admin: standing configure)
  await ad.page.goto(`${BASE}/job/b1-sleep/configure`); await ad.page.waitForTimeout(1200);
  const req = ad.page.locator('[name="_.approvalRequired"]').first();
  if (await req.isChecked()) await req.locator('xpath=following-sibling::label[1]').click();
  await Promise.all([ad.page.waitForNavigation(), ad.page.locator('button[name="Submit"]').click()]);
  const abortOnce = async () => {
    const n = (await job('b1-sleep')).nextBuildNumber;
    await ad.page.goto(`${BASE}/job/b1-sleep/`);
    await clickBuildEntry(ad.page, /Build Now/);
    await waitFor(async () => (await job('b1-sleep', 'lastBuild[number,building]')).lastBuild?.building, { timeout: 30000 });
    await ad.page.goto(`${BASE}/job/b1-sleep/${n}/`);
    const stop = ad.page.locator('a.stop-button-link, #main-panel a[href$="stop"], .jenkins-button--destructive, [tooltip*="Abort"], [title*="Abort"]').first();
    ad.page.once('dialog', (d) => d.accept());
    await stop.click();
    await ad.page.waitForTimeout(800);
    const ok = ad.page.locator('dialog[open] button[data-id="ok"]').first();
    if (await ok.count()) await ok.click();
    await waitFor(async () => (await job('b1-sleep', 'lastBuild[result]')).lastBuild?.result === 'ABORTED', { timeout: 30000 });
    await sleep(4000);
    return n;
  };
  const inc0 = (await api('admin', '/batch-control/history/incidents.csv')).text.split('\n').filter((l) => l.includes('b1-sleep'));
  const n1 = await abortOnce();
  const inc1 = (await api('admin', '/batch-control/history/incidents.csv')).text.split('\n').filter((l) => l.includes('b1-sleep'));
  await ad.page.goto(`${BASE}/batch-control/incidents/`);
  const irow = ad.page.locator('#main-panel tr:has-text("b1-sleep")').first();
  if (await irow.count()) await shot(ad.page, irow, 'B1-13-1-incident', { pad: 8 });
  await setGlobal(ad.page, { incidentResultsText: 'FAILURE, UNSTABLE' });
  const n2 = await abortOnce();
  const inc2 = (await api('admin', '/batch-control/history/incidents.csv')).text.split('\n').filter((l) => l.includes('b1-sleep'));
  await ad.page.goto(`${BASE}/batch-control/dashboard/`);
  const drow = ad.page.locator(`#main-panel tr:has-text("b1-sleep")`).first();
  await shot(ad.page, ad.page.locator('#main-panel tr:has-text("b1-sleep")'), 'B1-13-2-dashboard-aborted', { pad: 8 });
  log(L, `B1-13 ABORTED added: abort #${n1} -> incidents for b1-sleep ${inc0.length}->${inc1.length}; ABORTED removed: abort #${n2} -> ${inc2.length}; dashboard rows: ${(await ad.page.locator('#main-panel tr:has-text("b1-sleep")').allInnerTexts()).map((t) => t.replace(/\s+/g, ' ')).join(' || ')}`);
  await close();
};

steps.mail = async () => {
  const ad = await login('admin');
  await setGlobal(ad.page, { emailNotifications: false });
  const t0 = Date.now();
  const rq = await login('requester');
  const url = await requestRun(rq.page, '/job/batch-pipeline/', { reason: 'Mail switched off (B1-16).', approvers: ['approver-1'] });
  await sleep(6000);
  const id = url.split('/requests/')[1].replace('/', '');
  const m = await mails(id);
  log(L, `B1-16 mail off: request ${id} -> mails mentioning it: ${m.length}`);
  await rq.page.goto(url);
  rq.page.once('dialog', (d) => d.accept());
  await rq.page.locator('a:has-text("Cancel Request")').first().click(); await rq.page.waitForTimeout(800);
  const ok = rq.page.locator('dialog[open] button[data-id="ok"]').first(); if (await ok.count()) await Promise.all([rq.page.waitForLoadState('load'), ok.click()]);
  await setGlobal(ad.page, { emailNotifications: true });
  log(L, `B1-16 restored mail on: ${(await globalCfg()).mail}`);
  await close();
};

steps.invalid = async () => {
  const ad = await login('admin');
  const before = await globalCfg();
  const cases = [['approvedRunTimeoutMinutes', '0'], ['maxGrantMinutes', '0'], ['retentionMonths', '-1'], ['notifyBeforeExpiryMinutes', '0'], ['grantDurationOptionsText', '15,abc'], ['incidentResultsText', 'FAILURE, BOGUS']];
  for (const [f, v] of cases) {
    const r = await setGlobal(ad.page, { [f]: v }, { save: false });
    const field = ad.page.locator(`[name="_.${f}"]`).first();
    const item = field.locator('xpath=ancestor::*[contains(@class,"jenkins-form-item")][1]');
    const msg = (await item.locator('.error, .warning, .validation-error-area').allInnerTexts()).join(' ').replace(/\s+/g, ' ').trim();
    const html5 = await field.evaluate((e) => e.validationMessage || '');
    await shot(ad.page, item, `B1-17-${f}-${v.replace(/[^a-z0-9-]/gi, '_')}`, { pad: 6 });
    const [resp] = await Promise.all([ad.page.waitForNavigation({ waitUntil: 'load', timeout: 8000 }).catch(() => null), ad.page.locator('button[name="Submit"]').click()]);
    await ad.page.waitForTimeout(800);
    const after = await globalCfg();
    const page = (await ad.page.locator('#main-panel, body').first().innerText()).replace(/\s+/g, ' ').slice(0, 160);
    log(L, `B1-17 ${f}="${v}": inline "${msg}" html5 "${html5}"; Save -> ${resp ? resp.status() : 'blocked in browser'} ${resp ? page : ''}; stored ${JSON.stringify({ pending: after.pending, approvedRun: after.approvedRun, max: after.max, retention: after.retention, notify: after.notify, durations: after.durations, incidents: after.incidents })}`);
    if (JSON.stringify(after) !== JSON.stringify(before)) {
      await setGlobal(ad.page, { pendingTimeoutHours: before.pending, approvedRunTimeoutMinutes: before.approvedRun, maxGrantMinutes: before.max, retentionMonths: before.retention, notifyBeforeExpiryMinutes: before.notify, grantDurationOptionsText: before.durations.join(', '), incidentResultsText: before.incidents.join(', ') });
    }
  }
  await close();
};

steps.help = async () => {
  const ad = await login('admin');
  await ad.page.goto(`${BASE}/manage/configure`); await ad.page.waitForTimeout(1500);
  const sec = ad.page.locator('.jenkins-section:has(.jenkins-section__title:text-is("Batch Control"))').first();
  const helps = sec.locator('a.jenkins-help-button, .jenkins-help-button');
  const n = await helps.count();
  const out = [];
  for (let i = 0; i < n; i++) {
    await helps.nth(i).click(); await ad.page.waitForTimeout(700);
  }
  const areas = await sec.locator('.help-area .help, .help').evaluateAll((es) => es.filter((e) => e.offsetParent !== null).map((e) => e.innerText.replace(/\s+/g, ' ').trim()));
  await shot(ad.page, sec, 'B1-19', { pad: 6 });
  log(L, `B1-19 ${n} help buttons in the section; ${areas.length} opened; empty/"Loading" ones: ${areas.filter((a) => !a || /Loading/.test(a)).length}; first: ${areas.slice(0, 3).map((a) => a.slice(0, 90)).join(' | ')}`);
  const labels = await sec.locator('.jenkins-form-label, label').allInnerTexts();
  const withHelp = await sec.locator('.jenkins-form-item').evaluateAll((items) => items.map((it) => ({ l: (it.querySelector('.jenkins-form-label, label')?.innerText || '').trim(), h: !!it.querySelector('.jenkins-help-button') })).filter((x) => x.l));
  log(L, `B1-19 fields without help: ${JSON.stringify(withHelp.filter((x) => !x.h).map((x) => x.l))}`);
  await close();
};

steps.savefail = async () => {
  const f = '/var/jenkins_home/io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration.xml';
  const t0 = (await toggles()).length;
  execSync(`docker exec batch-control-e2e sh -c 'cp ${f} /tmp/bc-cfg.bak && rm ${f} && mkdir ${f} && echo x > ${f}/keep'`);
  const ad = await login('admin');
  const r = await setGlobal(ad.page, { runControlEnabled: false });
  await shot(ad.page, '#main-panel, body', 'B1-20-1-save-error', { pad: 8 });
  const cfg = await globalCfg();
  await ad.page.goto(`${BASE}/manage/configure`); await ad.page.waitForTimeout(1200);
  const shown = await ad.page.locator('[name="_.runControlEnabled"]').isChecked();
  const t1 = (await toggles()).length;
  // gate still refuses
  const n = (await job('batch-pipeline')).nextBuildNumber;
  const b = await api('requester', '/job/batch-pipeline/build', { method: 'POST' });
  await sleep(3000);
  log(L, `B1-20 save with blocked file: HTTP ${r.status} "${r.text.slice(0, 220)}"; in memory run=${cfg.run}; form after reload shows run=${shown}; CONFIG_TOGGLE ${t0}->${t1}; REST build -> ${b.status}, next ${n}->${(await job('batch-pipeline')).nextBuildNumber}`);
  execSync(`docker exec batch-control-e2e sh -c 'rm -rf ${f} && cp /tmp/bc-cfg.bak ${f}'`);
  await close();
};

steps.manager = async () => {
  const m = await login('manager');
  const r1 = await m.page.goto(`${BASE}/manage/configure`);
  const t1 = (await m.page.locator('#main-panel, body').first().innerText()).replace(/\s+/g, ' ').slice(0, 200);
  await shot(m.page, '#main-panel, body', 'B1-22-1-manager-configure', { pad: 8 });
  const r2 = await m.page.goto(`${BASE}/manage/`);
  const t2 = (await m.page.locator('#main-panel, body').first().innerText()).replace(/\s+/g, ' ').slice(0, 200);
  const hasManage = await m.page.locator('a[href$="/manage"], a[href="/manage/"]').count();
  // can the manager do the other Manage things the README lists (revoke, cancel)?
  const rq = await api('requester', '/manage/configure', { method: 'POST', body: new URLSearchParams({ json: '{}' }), headers: { 'Content-Type': 'application/x-www-form-urlencoded' } });
  log(L, `B1-22 manager GET /manage/configure -> ${r1.status()} "${t1}"; /manage -> ${r2.status()} "${t2}"; Manage link in header=${hasManage}; requester POST /manage/configure -> ${rq.status}`);
  await close();
};

const wanted = process.argv.slice(2);
try { for (const s of wanted) { console.log(`== ${s}`); await steps[s](); } } finally { await close(); }
