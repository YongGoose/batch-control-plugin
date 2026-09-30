// B5 blocked paths (the rows not covered by section 7 / PR-xx).
// Usage: node b5.mjs replay upstream timer running self sidebars
import { login, close, shot, api, job, waitFor, sleep, BASE, log, requestRun, changeRows, setGlobal, clickBuildEntry } from './lib.mjs';
import { execSync } from 'node:child_process';
const L = 'section-b.log';
const steps = {};
const txt = async (p, sel = '#main-panel, body') => (await p.locator(sel).first().innerText()).replace(/\s+/g, ' ');
async function approve(user, url, comment = 'ok') {
  const { context, page } = await login(user);
  await page.goto(url);
  await page.fill('form[name="approve"] textarea[name="comment"]', comment);
  await Promise.all([page.waitForLoadState('load'), page.locator('form[name="approve"] button').first().click()]);
  await context.close();
}
async function activation(job, action, reason) {
  const { context, page } = await login('requester');
  await page.goto(`${BASE}/job/${job}/batch-control/activation`);
  const heading = await txt(page, '#main-panel');
  if (!heading.includes(action === 'ACTIVATE' ? 'Request Activation' : 'Request a Hold')) { await context.close(); return 'not offered'; }
  await page.fill('textarea[name="reason"]', reason);
  await page.locator('input[name="approvers"][value="approver-1"] + label').click();
  await Promise.all([page.waitForNavigation(), page.locator('button:has-text("Submit Request")').click()]);
  const url = page.url();
  await context.close();
  await approve('approver-1', url, `${action} for B5`);
  return url;
}
async function jobProp(page, jobName, { approvalRequired, blockTimer, blockUpstream, allowed } = {}) {
  await page.goto(`${BASE}/job/${jobName}/configure`); await page.waitForTimeout(1500);
  const set = async (f, v) => { if (v === undefined) return; const el = page.locator(`[name="_.${f}"]`).first(); if ((await el.isChecked()) !== v) await el.locator('xpath=following-sibling::label[1]').click(); await page.waitForTimeout(300); };
  await set('approvalRequired', approvalRequired); await set('blockTimer', blockTimer); await set('blockUpstream', blockUpstream);
  if (allowed !== undefined) await page.locator('[name="_.allowedUpstreamJobsText"]').first().fill(allowed);
  await Promise.all([page.waitForNavigation(), page.locator('button[name="Submit"]').click()]);
}
async function runUpstream(page) {
  const n = (await job('batch-upstream')).nextBuildNumber;
  const d0 = (await job('batch-daily')).nextBuildNumber;
  await page.goto(`${BASE}/job/batch-upstream/`);
  await clickBuildEntry(page, 'Build Now');
  await waitFor(async () => { const j = await job('batch-upstream', 'nextBuildNumber,lastBuild[building,result]'); return j.nextBuildNumber > n && j.lastBuild && !j.lastBuild.building; }, { timeout: 90000 });
  await sleep(2000);
  const res = (await job('batch-upstream', 'lastBuild[number,result]')).lastBuild;
  const con = (await api('admin', `/job/batch-upstream/${res.number}/consoleText`, { raw: true })).text.trim().split('\n');
  const dj = await job('batch-daily', 'nextBuildNumber,lastBuild[number,actions[causes[shortDescription]]]');
  return { up: `#${res.number} ${res.result}`, console: con.filter((l) => /batch-daily|Approval|refus|block|ERROR|Scheduling|Starting/i.test(l)).slice(0, 3).join(' | '), daily: `${d0}->${dj.nextBuildNumber}`, cause: dj.nextBuildNumber > d0 ? JSON.stringify((dj.lastBuild.actions.find((a) => a && a.causes) || {}).causes?.map((c) => c.shortDescription)) : '-' };
}

steps.replay = async () => {
  const ad = await login('admin');
  const last = (await job('batch-pipeline', 'lastSuccessfulBuild[number]')).lastSuccessfulBuild.number;
  const tb0 = await changeRows(/TRIGGER_BLOCKED,batch-pipeline/);
  const n0 = (await job('batch-pipeline')).nextBuildNumber;
  await ad.page.goto(`${BASE}/job/batch-pipeline/${last}/`);
  await ad.page.locator('#side-panel a:has-text("Replay")').click(); await ad.page.waitForLoadState('load');
  await shot(ad.page, '#main-panel', 'B5-05-1-replay-page', { pad: 6 });
  const [r] = await Promise.all([ad.page.waitForNavigation({ waitUntil: 'load' }).catch(() => null), ad.page.locator('button:has-text("Run"), button[name="Submit"]').first().click()]);
  await sleep(4000);
  const after = await txt(ad.page);
  await shot(ad.page, '#main-panel', 'B5-05-2-after-run', { pad: 6 });
  const tb1 = await changeRows(/TRIGGER_BLOCKED,batch-pipeline/);
  log(L, `B5-05 admin Replay #${last} -> ${r && r.status()} ${ad.page.url().replace(BASE, '')}; page "${after.slice(0, 160)}"; next ${n0}->${(await job('batch-pipeline')).nextBuildNumber}; TRIGGER_BLOCKED ${tb0.length}->${tb1.length} ${tb1[0] || ''}`);
  // grant holder: a CONFIGURE window on batch-pipeline confers Run/Replay (LIMITATIONS 33)
  const rq = await login('requester');
  await rq.page.goto(`${BASE}/batch-control/grants/?scopeType=JOB&scopeFullName=batch-pipeline`);
  await rq.page.selectOption('select[name="durationMinutes"]', '15');
  await rq.page.fill('textarea[name="reason"]', 'Replay check under a CONFIGURE window (B5-05).');
  await rq.page.locator('#grant-approver-0 + label').click();
  await Promise.all([rq.page.waitForLoadState('load'), rq.page.locator('button:has-text("Request Grant")').click()]);
  const href = await rq.page.locator('#main-panel table').first().locator('tbody tr', { hasText: 'Replay check' }).first().locator('a').first().getAttribute('href');
  await approve('approver-1', new URL(href, rq.page.url()).href);
  await rq.page.goto(`${BASE}/job/batch-pipeline/${last}/`);
  const side = (await rq.page.locator('#side-panel a').allInnerTexts()).map((t) => t.trim());
  const n1 = (await job('batch-pipeline')).nextBuildNumber;
  if (side.includes('Replay')) {
    await rq.page.locator('#side-panel a:has-text("Replay")').click(); await rq.page.waitForLoadState('load');
    await Promise.all([rq.page.waitForNavigation({ waitUntil: 'load' }).catch(() => null), rq.page.locator('button:has-text("Run"), button[name="Submit"]').first().click()]);
    await sleep(4000);
  }
  const tb2 = await changeRows(/TRIGGER_BLOCKED,batch-pipeline/);
  log(L, `B5-05 requester with CONFIGURE window: build sidebar ${JSON.stringify(side)}; Replay run -> next ${n1}->${(await job('batch-pipeline')).nextBuildNumber}; landed ${rq.page.url().replace(BASE, '')}; TRIGGER_BLOCKED ${tb1.length}->${tb2.length} (coalesced per hour)`);
  await ad.page.goto(`${BASE}/batch-control/changes/`);
  const row = ad.page.locator('#main-panel tr:has-text("TRIGGER_BLOCKED"):has-text("batch-pipeline")').first();
  if (await row.count()) await shot(ad.page, row, 'B5-05-3-record', { pad: 8 });
  await close();
};

steps.upstream = async () => {
  const ad = await login('admin');
  let r = await runUpstream(ad.page);
  log(L, `B5-13 batch-upstream -> batch-daily (not activated, blockUpstream=false): upstream ${r.up}; console "${r.console}"; batch-daily ${r.daily}`);
  const act = await activation('batch-daily', 'ACTIVATE', 'Let batch-upstream trigger it (B5-13).');
  r = await runUpstream(ad.page);
  log(L, `B5-13 after ACTIVATE (${act}): upstream ${r.up}; batch-daily ${r.daily} cause ${r.cause}`);
  await ad.page.goto(`${BASE}/job/batch-upstream/`);
  await shot(ad.page, '#main-panel', 'B5-13', { pad: 6 });
  await jobProp(ad.page, 'batch-daily', { blockUpstream: true, allowed: '' });
  r = await runUpstream(ad.page);
  log(L, `B5-14 blockUpstream=true, allowed empty: upstream ${r.up}; console "${r.console}"; batch-daily ${r.daily}`);
  const rq = await login('requester');
  await rq.page.goto(`${BASE}/job/batch-daily/`);
  await shot(rq.page, rq.page.locator('#main-panel .jenkins-alert').first(), 'B5-14-notice', { pad: 8 });
  await jobProp(ad.page, 'batch-daily', { allowed: 'some-other-job' });
  r = await runUpstream(ad.page);
  log(L, `B5-15 allowed=some-other-job: upstream ${r.up}; batch-daily ${r.daily}`);
  await jobProp(ad.page, 'batch-daily', { allowed: 'batch-upstream' });
  r = await runUpstream(ad.page);
  log(L, `B5-15 allowed=batch-upstream: upstream ${r.up}; batch-daily ${r.daily} cause ${r.cause}`);
  await jobProp(ad.page, 'batch-daily', { blockUpstream: false, allowed: '' });
  const tb = await changeRows(/TRIGGER_BLOCKED,batch-daily/);
  log(L, `B5-13..15 TRIGGER_BLOCKED records for batch-daily: ${tb.map((l) => l.split(',').slice(6).join(',').slice(0, 110)).join(' || ')}`);
  await close();
};

steps.timer = async () => {
  const ad = await login('admin');
  const j0 = await job('batch-cron', 'nextBuildNumber,builds[number,timestamp]');
  const perMin = j0.builds.slice(0, 5).map((b) => new Date(b.timestamp).toISOString().slice(11, 16));
  log(L, `B5-16 batch-cron (activated, blockTimer=false): last builds at ${perMin.join(', ')} (one per minute)`);
  await jobProp(ad.page, 'batch-cron', { blockTimer: true });
  const t = Date.now();
  const n = (await job('batch-cron')).nextBuildNumber;
  await sleep(135000);
  const n2 = (await job('batch-cron')).nextBuildNumber;
  const rq = await login('requester');
  await rq.page.goto(`${BASE}/job/batch-cron/`);
  const notices = (await rq.page.locator('#main-panel .jenkins-alert').allInnerTexts()).map((x) => x.replace(/\s+/g, ' '));
  await shot(rq.page, rq.page.locator('#main-panel .jenkins-alert').first(), 'B5-17-notice', { pad: 8 });
  const tb = (await changeRows(/TRIGGER_BLOCKED,batch-cron/)).filter((l) => /blockTimer/.test(l));
  const lg = execSync('docker logs --since 3m batch-control-e2e 2>&1').toString().split('\n').filter((l) => /timer-triggered run of job 'batch-cron'|Blocked timer/i.test(l));
  log(L, `B5-17 blockTimer=true for 135 s: next ${n}->${n2}; notices ${JSON.stringify(notices)}; TRIGGER_BLOCKED(blockTimer) ${tb.length}: ${tb[0] || ''}; log lines ${lg.length}: ${lg[0] || ''}`);
  await jobProp(ad.page, 'batch-cron', { blockTimer: false });
  await close();
};

steps.running = async () => {
  const ad = await login('admin');
  if ((await api('admin', '/job/b5-long/api/json')).status === 404) {
    await ad.page.goto(`${BASE}/view/all/newJob`); await ad.page.fill('#name', 'b5-long');
    await ad.page.locator('label:has-text("Freestyle project")').first().click();
    await Promise.all([ad.page.waitForNavigation(), ad.page.locator('#ok-button').click()]);
    await ad.page.waitForTimeout(1500);
    await ad.page.locator('button.hetero-list-add:has-text("Add build step")').click(); await ad.page.waitForTimeout(500);
    await ad.page.locator('.jenkins-dropdown button, .jenkins-dropdown__item').filter({ hasText: 'Execute shell' }).first().click(); await ad.page.waitForTimeout(800);
    await ad.page.locator('textarea[name="command"]').first().evaluate((t) => { t.value = 'echo start; sleep 40; echo end'; const cm = t.nextElementSibling && t.nextElementSibling.CodeMirror; if (cm) cm.setValue(t.value); });
    await Promise.all([ad.page.waitForNavigation(), ad.page.locator('button[name="Submit"]').click()]);
  }
  const rq = await login('requester');
  const url = await requestRun(rq.page, '/job/b5-long/', { reason: 'Long approved run; controls change while it runs (B5-20).', approvers: ['approver-1'] });
  await approve('approver-1', url);
  await waitFor(async () => (await job('b5-long', 'lastBuild[building]')).lastBuild?.building, { timeout: 60000 });
  await setGlobal(ad.page, { runControlEnabled: false });
  await setGlobal(ad.page, { runControlEnabled: true });
  await ad.page.goto(`${BASE}/job/b5-long/configure`); await ad.page.waitForTimeout(1200);
  await ad.page.fill('textarea[name="description"]', 'edited while the approved run was building (B5-20)');
  await Promise.all([ad.page.waitForNavigation(), ad.page.locator('button[name="Submit"]').click()]);
  const building = (await job('b5-long', 'lastBuild[building]')).lastBuild.building;
  await waitFor(async () => !(await job('b5-long', 'lastBuild[building]')).lastBuild.building, { timeout: 90000 });
  const b = (await job('b5-long', 'lastBuild[number,result,duration]')).lastBuild;
  log(L, `B5-20 approved b5-long run: still building after run-control off/on and a job edit = ${building}; finished #${b.number} ${b.result} after ${Math.round(b.duration / 1000)} s`);
  await ad.page.goto(`${BASE}/job/b5-long/${b.number}/console`);
  await shot(ad.page, '#main-panel pre, #out, .console-output', 'B5-20', { pad: 6 });
  await close();
};

steps.self = async () => {
  const rq = await login('requester');
  const n0 = (await job('batch-self')).nextBuildNumber;
  let url = await requestRun(rq.page, '/job/batch-self/', { reason: 'Self-triggering job, not activated (B5-21).', approvers: ['approver-1'] });
  await approve('approver-1', url);
  await sleep(25000);
  const n1 = (await job('batch-self')).nextBuildNumber;
  const tb = await changeRows(/TRIGGER_BLOCKED,batch-self/);
  log(L, `B5-21 batch-self (not activated): builds ${n0}->${n1}; TRIGGER_BLOCKED ${tb[0] || 'none'}`);
  const act = await activation('batch-self', 'ACTIVATE', 'Let batch-self trigger itself once (B5-21).');
  url = await requestRun(rq.page, '/job/batch-self/', { reason: 'Self-triggering job, activated (B5-21).', approvers: ['approver-1'] });
  await approve('approver-1', url);
  await sleep(30000);
  const j = await job('batch-self', 'nextBuildNumber,builds[number,result,actions[causes[shortDescription]]]');
  log(L, `B5-21 batch-self (activated ${act}): builds ${n1}->${j.nextBuildNumber}: ${j.builds.slice(0, 2).map((b) => `#${b.number} ${b.result} ${(b.actions.find((a) => a && a.causes) || { causes: [] }).causes.map((c) => c.shortDescription).join(';')}`).join(' | ')}`);
  await rq.page.goto(`${BASE}/job/batch-self/`);
  await shot(rq.page, '#side-panel', 'B5-21', { pad: 6 });
  await close();
};

steps.sidebars = async () => {
  const rq = await login('requester');
  const out = [];
  for (const j of ['batch-cbn', 'batch-nag', 'batch-token', 'batch-rebuild', 'batch-lock']) {
    await rq.page.goto(`${BASE}/job/${j}/`);
    out.push(`${j}: ${JSON.stringify((await rq.page.locator('#side-panel .task a, #tasks a').allInnerTexts()).map((t) => t.trim()).filter(Boolean))}`);
    if (j === 'batch-cbn') await shot(rq.page, '#side-panel', 'B5-22', { pad: 6 });
  }
  log(L, `B5-22 ${out.join(' || ')}`);
  await close();
};

const wanted = process.argv.slice(2);
try { for (const s of wanted) { console.log(`== ${s}`); await steps[s](); } } finally { await close(); }
