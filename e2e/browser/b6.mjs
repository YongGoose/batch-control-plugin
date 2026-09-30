// B6 expiry, cancellation, invalidation.
import { login, close, shot, api, job, waitFor, sleep, BASE, log, requestRun, groovy, queue } from './lib.mjs';
const L = 'section-b.log';
const txt = async (p, sel = '#main-panel, body') => (await p.locator(sel).first().innerText()).replace(/\s+/g, ' ');
const status = async (url) => (await api('admin', '/batch-control/history/requests.csv')).text.split('\n').find((l) => l.startsWith(url.split('/requests/')[1].replace('/', '')))?.split(',').slice(-9)[0];
const rowOf = async (url) => (await api('admin', '/batch-control/history/requests.csv')).text.split('\n').find((l) => l.startsWith(url.split('/requests/')[1].replace('/', '')));
async function approve(url) {
  const { context, page } = await login('approver-1');
  await page.goto(url);
  await page.fill('form[name="approve"] textarea[name="comment"]', 'ok');
  await Promise.all([page.waitForLoadState('load'), page.locator('form[name="approve"] button').first().click()]);
  await context.close();
}
const executors = (n) => groovy(`jenkins.model.Jenkins.get().setNumExecutors(${n}); println "numExecutors=" + jenkins.model.Jenkins.get().numExecutors`);

const ad = await login('admin');
for (const name of ['b6-job']) {
  if ((await api('admin', `/job/${name}/api/json`)).status === 404) {
    await api('admin', `/createItem?name=${name}`, { method: 'POST', body: `<?xml version='1.1' encoding='UTF-8'?><project><description>B6 scratch job</description><builders><hudson.tasks.Shell><command>echo b6</command></hudson.tasks.Shell></builders></project>`, headers: { 'Content-Type': 'application/xml' } });
  }
}
const rq = await login('requester');
// ---- B6-01 approved but not run
log(L, `B6-01 arrange: ${await executors(0)}`);
const r1 = await requestRun(rq.page, '/job/b6-job/', { reason: 'Approved while no executor is free (B6-01).', approvers: ['approver-1'] });
await approve(r1);
await rq.page.goto(r1);
const notice1 = (await rq.page.locator('#main-panel .jenkins-alert').allInnerTexts()).join(' ').replace(/\s+/g, ' ');
await shot(rq.page, ['#main-panel table', '#main-panel .jenkins-alert'], 'B6-01-1-approved-not-run', { pad: 8 });
const q1 = (await queue()).filter((i) => i.task?.name === 'b6-job');
log(L, `B6-01 status ${await status(r1)}; notice "${notice1}"; queue items for b6-job: ${JSON.stringify(q1)}`);
// ---- B6-05 cancel an APPROVED request
const cc = await rq.page.locator('a:has-text("Cancel Request")').count();
const cpost = await api('requester', r1.replace(BASE, '') + 'cancel', { method: 'POST' });
log(L, `B6-05 APPROVED: cancel control ${cc}; POST cancel -> ${cpost.status}; status ${await status(r1)}`);
await sleep(130000);
await rq.page.goto(r1);
await shot(rq.page, ['#main-panel table', '#main-panel .jenkins-alert'], 'B6-01-2-after-2-min', { pad: 8 });
const s1 = await status(r1);
const q2 = (await queue()).filter((i) => i.task?.name === 'b6-job');
log(L, `B6-01 after 130 s with 0 executors: status ${s1}; queue ${JSON.stringify(q2)}; page "${(await txt(rq.page, '#main-panel')).slice(0, 260)}"`);
log(L, `B6-01 restore: ${await executors(2)}`);
await sleep(15000);
const j1 = await job('b6-job', 'nextBuildNumber,builds[number,result]');
log(L, `B6-01 after executors restored: b6-job builds ${JSON.stringify(j1.builds)}; request ${await status(r1)} row ${await rowOf(r1)}`);
// ---- B6-03 rename with a PENDING and an APPROVED-not-run request
log(L, `B6-03 arrange: ${await executors(0)}`);
const r2 = await requestRun(rq.page, '/job/b6-job/', { reason: 'Pending when the job is renamed (B6-03).', approvers: ['approver-1'] });
const r3 = await requestRun(rq.page, '/job/b6-job/', { reason: 'Approved-not-run when the job is renamed (B6-03).', approvers: ['approver-1'] });
await approve(r3);
log(L, `B6-03 before rename: r2 ${await status(r2)}, r3 ${await status(r3)}`);
await ad.page.goto(`${BASE}/job/b6-job/confirm-rename`);
await ad.page.fill('input[name="newName"]', 'b6-job-renamed');
await ad.page.waitForTimeout(800);
await Promise.all([ad.page.waitForNavigation(), ad.page.locator('button[name="Submit"], button:has-text("Rename")').first().click()]);
await sleep(3000);
for (const [n, u] of [['pending', r2], ['approved', r3]]) {
  await rq.page.goto(u);
  const t = await txt(rq.page, '#main-panel');
  log(L, `B6-03 ${n} after rename: ${await status(u)}; page "${t.slice(t.indexOf('Status'), t.indexOf('Status') + 120)}" reason shown: ${/renam|moved|invalid/i.test(t)} "${(t.match(/[^.]*(renam|invalidat)[^.]*\./i) || [''])[0]}"`);
  await shot(rq.page, ['#main-panel table', '#main-panel .jenkins-alert'], `B6-03-${n}`, { pad: 8 });
}
log(L, `B6-03 restore: ${await executors(2)}`);
await sleep(15000);
log(L, `B6-03 after executors restored: b6-job-renamed builds ${JSON.stringify((await job('b6-job-renamed', 'builds[number]')).builds)}; queue ${JSON.stringify(await queue())}`);
// ---- B6-04 move into team/
const r4 = await requestRun(rq.page, '/job/b6-job-renamed/', { reason: 'Pending when the job is moved (B6-04).', approvers: ['approver-1'] });
await ad.page.goto(`${BASE}/job/b6-job-renamed/move`);
await ad.page.waitForTimeout(1000);
const sel = ad.page.locator('select[name="destination"]');
await sel.selectOption({ label: 'Jenkins » team' }).catch(async () => sel.selectOption('/team'));
await Promise.all([ad.page.waitForNavigation(), ad.page.locator('button[name="Submit"], button:has-text("Move")').first().click()]);
await sleep(3000);
await rq.page.goto(r4);
const t4 = await txt(rq.page, '#main-panel');
log(L, `B6-04 after move to ${ad.page.url().replace(BASE, '')}: ${await status(r4)} "${(t4.match(/[^.]*(mov|invalidat)[^.]*\./i) || [''])[0]}"`);
await shot(rq.page, ['#main-panel table', '#main-panel .jenkins-alert'], 'B6-04', { pad: 8 });
await close();
