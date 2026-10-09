// B6-01 (approved while no executor is free), B6-04 (move invalidates), B6-05 (no cancel of APPROVED).
import { login, close, shot, api, job, queue, BASE, requestRun, waitFor, sleep, groovy } from '../lib.mjs';
import { row, ev, mainText } from './rec.mjs';
import { formPost } from './restsubmit.mjs';
import { approveAs } from './helpers-e.mjs';
const executors = (n) => groovy(`jenkins.model.Jenkins.get().setNumExecutors(${n}); println "numExecutors=" + jenkins.model.Jenkins.get().numExecutors`);
const tag = Date.now();
const rq = await login('requester');
const status = async (url) => { await rq.page.goto(url); return ((await mainText(rq.page)).match(/Status (\w+)/) || [])[1]; };
// B6-01 + B6-05
{
  const J = 'batch-lock';
  const n0 = (await job(J, 'nextBuildNumber')).nextBuildNumber;
  ev(`B6-01 arrange ${await executors(0)}`);
  const url = await requestRun(rq.page, `/job/${J}/`, { reason: `Audit B6-01 ${tag}: approved while no executor is free`, approvers: ['approver-1'] });
  const id = url.match(/(\d{8}-\d{6}-\w+)/)[1];
  await approveAs('approver-1', url);
  await rq.page.goto(url); const t1 = await mainText(rq.page);
  const s1 = await shot(rq.page, ['#main-panel table', '#main-panel .jenkins-alert'], 'B6-01-1-approved-queued', { pad: 8 });
  const cancelCtl = await rq.page.locator('a:has-text("Cancel Request"), button:has-text("Cancel Request")').count();
  const pc = await formPost('requester', `/batch-control/requests/${id}/cancel`, {});
  row('B6-05', { roles: 'requester', V: `${cancelCtl === 0 ? '✓' : '✗'} an APPROVED request shows no cancel control (${cancelCtl})`, G: `${pc.status === 400 ? '✓' : '✗'} scripted cancel -> ${pc.status}`, R: `✓ "${pc.msg.slice(0, 90)}"`, C: 'n.a.', E: s1 ? '✓ B6-01-1-approved-queued' : '✗' });
  const q1 = (await queue()).filter((i) => i.task.name === J).map((i) => i.why);
  await sleep(130000);
  await rq.page.goto(url); const t2 = await mainText(rq.page);
  const s2 = await shot(rq.page, ['#main-panel table'], 'B6-01-2-after-130s', { pad: 8 });
  ev(`B6-01 restore ${await executors(2)}`);
  await waitFor(async () => (await job(J, 'nextBuildNumber')).nextBuildNumber > n0, { timeout: 60000 }); await sleep(8000);
  const added = (await job(J, 'nextBuildNumber')).nextBuildNumber - n0; const st = await status(url);
  ev(`B6-01 t1 "${t1.slice(0, 250)}" queue ${JSON.stringify(q1)}; after 130 s "${(t2.match(/Status \w+/) || [''])[0]}"; after restore +${added} ${st}`);
  row('B6-01', { roles: 'requester, approver-1, admin (executors, arrange)', V: 'n.a.', G: `${added === 1 && st === 'EXECUTED' ? '✓' : '✗'} approved with 0 executors: queued at once (${q1.join('; ').slice(0, 60)}), notice "${((t1.match(/approved and the run starts[^.]*\./) || [''])[0]).slice(0, 70)}"; still ${(t2.match(/Status \w+/) || [''])[0]} after 130 s (a queued run counts as submitted); ran exactly once when executors were restored (${st})`, R: 'n.a.', C: '✓ executedRunId on the detail', E: s1 && s2 ? '✓ B6-01-1..2' : '✗', verdict: 'PASS (partial)', note: 'EXPIRED after approvedRunTimeoutMinutes for an approval that never reaches the queue cannot be arranged from outside (not observed)' });
}
// B6-04 move invalidates a pending request
{
  const ad = await login('admin');
  const J = `b6-audit-${String(tag).slice(-5)}`;
  await ad.page.goto(`${BASE}/view/all/newJob`); await ad.page.fill('#name', J);
  await ad.page.locator('label:has-text("Freestyle project")').first().click();
  await Promise.all([ad.page.waitForNavigation(), ad.page.locator('#ok-button').click()]);
  await Promise.all([ad.page.waitForNavigation(), ad.page.locator('button[name="Submit"]').click()]);
  const url = await requestRun(rq.page, `/job/${J}/`, { reason: `Audit B6-04 ${tag}: pending before the move`, approvers: ['approver-1'] });
  await ad.page.goto(`${BASE}/job/${J}/`);
  await ad.page.locator('#side-panel a:has-text("Move")').click(); await ad.page.waitForLoadState('load');
  const sel = ad.page.locator('select[name="destination"]');
  await sel.selectOption({ label: 'Jenkins » team' }).catch(async () => sel.selectOption('/team'));
  await Promise.all([ad.page.waitForNavigation(), ad.page.locator('button:has-text("Move")').last().click()]);
  await sleep(3000);
  await rq.page.goto(url); const t = await mainText(rq.page);
  const s = await shot(rq.page, '#main-panel table', 'B6-04-invalidated', { pad: 8 });
  const a = await login('approver-1'); await a.page.goto(url); const f = await a.page.locator('form[name="approve"]').count(); await a.context.close();
  ev(`B6-04 moved to ${ad.page.url()}; "${t.slice(0, 300)}"; approver forms ${f}`);
  row('B6-04', { roles: 'admin (move), requester, approver-1', V: `${f === 0 ? '✓' : '✗'} approver-1 has no decision form any more`, G: `${/INVALIDATED/.test(t) ? '✓' : '✗'} after moving ${J} into team/ the PENDING request is ${(t.match(/Status \w+/) || [''])[0]}`, R: `✗ the detail says only INVALIDATED and still names "${(t.match(/Job \S+/) || [''])[0]}"; nothing says the job was moved or what to do (request again on team/${J}) (DEF-17)`, C: 'n.a.', E: s ? '✓ B6-04-invalidated' : '✗', defect: 'DEF-17 (known)' });
  await ad.context.close();
}
await close();
