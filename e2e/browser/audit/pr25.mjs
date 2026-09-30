// PR-02 (+B5-08) rebuild and PR-05 (+B5-09, B5-10) naginator: refusal as the user sees it and the audit record (DEF-01/DEF-03 fixes).
import { login, close, shot, BASE, api, job, queue, requestRun, waitFor, changeRows, sleep, clickBuildEntry } from '../lib.mjs';
import { row, ev, mainText, sidebar } from './rec.mjs';
const only = process.argv.slice(2); const on = (x) => !only.length || only.includes(x);
async function approvedRun(J, reason) {
  const before = (await job(J, 'nextBuildNumber')).nextBuildNumber;
  const { context, page } = await login('requester');
  const url = await requestRun(page, `/job/${J}/`, { reason, approvers: ['approver-1'] });
  await context.close();
  const a = await login('approver-1'); await a.page.goto(url);
  await a.page.fill('form[name="approve"] textarea[name="comment"]', 'ok'); await Promise.all([a.page.waitForLoadState('load'), a.page.locator('form[name="approve"] button').first().click()]); await a.context.close();
  await waitFor(async () => { const j = await job(J, 'lastBuild[number,building]'); return j.lastBuild && j.lastBuild.number >= before && !j.lastBuild.building; }, { timeout: 90000 });
  return { n: before, url };
}
async function clickOnBuild(user, J, n, label, name) {
  const { context, page } = await login(user);
  await page.goto(`${BASE}/job/${J}/${n}/`);
  const sb = await sidebar(page);
  const r = await clickBuildEntry(page, label);
  const toast = page.locator('#notification-bar, .jenkins-notification').first();
  const tt = (await toast.innerText().catch(() => '')).replace(/\s+/g, ' ');
  const notice = await page.locator('.jenkins-alert:has-text("manual runs of this job need")').count();
  const s = await shot(page, [page.locator('#side-panel a').filter({ hasText: label }).first(), toast, '#main-panel .jenkins-app-bar, #main-panel h1'], name, { pad: 10 });
  await context.close();
  return { sb, r, tt, notice, s };
}
if (on('PR-02')) {
  const J = 'batch-rebuild';
  const { n } = await approvedRun(J, `Audit PR-02 ${Date.now()}: approved run, then rebuilt`);
  const next0 = (await job(J, 'nextBuildNumber')).nextBuildNumber;
  const rec0 = await changeRows(/MARKER_REUSE_BLOCKED|TRIGGER_BLOCKED/);
  const vis = {}; for (const u of ['nobc', 'approver-1', 'admin']) { const c = await login(u); await c.page.goto(`${BASE}/job/${J}/${n}/`); vis[u] = await sidebar(c.page); await c.context.close(); }
  const c1 = await clickOnBuild('requester', J, n, 'Rebuild', 'PR-02-1-requester-rebuild-on-build-page');
  // Rebuild Last from the job page of batch-cbn (approved #2 from A-02)
  const { context, page } = await login('requester');
  await page.goto(`${BASE}/job/batch-cbn/`);
  const cbnNext0 = (await job('batch-cbn', 'nextBuildNumber')).nextBuildNumber;
  const rl = await clickBuildEntry(page, 'Rebuild Last');
  const tt2 = (await page.locator('#notification-bar, .jenkins-notification').first().innerText().catch(() => '')).replace(/\s+/g, ' ');
  const n2 = await page.locator('.jenkins-alert:has-text("manual runs of this job need")').count();
  const s2 = await shot(page, [page.locator('#side-panel a:has-text("Rebuild Last")').first(), '#notification-bar, .jenkins-notification', '.jenkins-alert:has-text("manual runs of this job need")'], 'PR-02-2-requester-rebuild-last-job-page', { pad: 10 });
  await context.close();
  await sleep(20000);
  const next1 = (await job(J, 'nextBuildNumber')).nextBuildNumber; const cbnNext1 = (await job('batch-cbn', 'nextBuildNumber')).nextBuildNumber;
  const q = (await queue()).filter((i) => [J, 'batch-cbn'].includes(i.task.name)).length;
  const rec1 = await changeRows(/MARKER_REUSE_BLOCKED|TRIGGER_BLOCKED/);
  const newRecs = rec1.slice(0, rec1.length - rec0.length);
  const ad = await login('admin'); await ad.page.goto(`${BASE}/batch-control/changes/`);
  const rr = ad.page.locator('#main-panel tr', { hasText: /MARKER_REUSE_BLOCKED|REBUILD/ }).filter({ hasText: /batch-rebuild|batch-cbn/ });
  const s3 = (await rr.count()) ? await shot(ad.page, rr, 'PR-02-3-records', { pad: 8 }) : null;
  await ad.context.close();
  ev(`PR-02 #${n}; vis ${JSON.stringify(vis)}; requester build sidebar ${c1.sb}; Rebuild -> ${c1.r.status} ${c1.r.url} "${c1.r.text.slice(0, 160)}" toast "${c1.tt}" notice ${c1.notice}; Rebuild Last on batch-cbn -> ${rl.status} ${rl.url} toast "${tt2}" notice ${n2}; next ${next0}->${next1}, cbn ${cbnNext0}->${cbnNext1}, queue ${q}; new records ${newRecs.join(' || ')}`);
  const refusalUx = c1.tt || c1.r.text.slice(0, 80);
  for (const id of ['PR-02', 'B5-08']) row(id, {
    roles: 'requester, nobc, approver-1, admin',
    V: `✗ Rebuild is offered on the approved build to everyone with Build (requester [${c1.sb.filter((x) => /Rebuild/.test(x))}], nobc [${vis.nobc.filter((x) => /Rebuild/.test(x))}], admin) and Rebuild Last on the job page, although it can never run here (DEF-25); approver-1 has none`,
    G: `${next1 === next0 && cbnNext1 === cbnNext0 && q === 0 ? '✓' : '✗'} refused: no build (batch-rebuild next ${next0}->${next1}, batch-cbn ${cbnNext0}->${cbnNext1}, queue ${q})`,
    R: `${c1.notice ? '✓' : '✗'} on the build page Rebuild ends in "${refusalUx}" with no approval notice (the new notice is only on the job page); Rebuild Last on the job page: toast "${tt2}" + the job-page notice (${n2 ? 'shown' : 'absent'})`,
    C: `${newRecs.length ? '✓' : '✗'} ${newRecs.length} new record(s): ${newRecs.map((l) => l.split(',').slice(1, 4).join(',') + ' ' + l.split(',').slice(6).join(',').slice(0, 110)).join(' || ')}`,
    E: c1.s && s2 ? `✓ PR-02-1-requester-rebuild-on-build-page, PR-02-2-requester-rebuild-last-job-page${s3 ? ', PR-02-3-records' : ''}` : '✗',
    defect: 'DEF-25 (known)',
  });
}
if (on('PR-05')) {
  const J = 'batch-nag';
  const rec0 = await changeRows(/TRIGGER_BLOCKED|MARKER_REUSE_BLOCKED/);
  const { n } = await approvedRun(J, `Audit PR-05 ${Date.now()}: approved run that fails, naginator retries`);
  await sleep(30000);
  const next0 = (await job(J, 'nextBuildNumber,lastBuild[result]'));
  const recA = await changeRows(/TRIGGER_BLOCKED|MARKER_REUSE_BLOCKED/);
  const autoRecs = recA.slice(0, recA.length - rec0.length);
  const { context, page } = await login('requester');
  await page.goto(`${BASE}/job/${J}/${n}/`);
  const sb = await sidebar(page);
  const bpText = await mainText(page);
  const s1 = await shot(page, ['#side-panel', '#main-panel .jenkins-app-bar, #main-panel h1'], 'PR-05-1-failed-build-page', { pad: 8 });
  await context.close();
  const c = sb.some((x) => /Retry/.test(x)) ? await clickOnBuild('requester', J, n, 'Retry', 'PR-05-2-requester-manual-retry') : null;
  await sleep(20000);
  const next1 = (await job(J, 'nextBuildNumber')).nextBuildNumber;
  const recB = await changeRows(/TRIGGER_BLOCKED|MARKER_REUSE_BLOCKED/);
  const allRecs = recB.slice(0, recB.length - rec0.length);
  const ad = await login('admin'); await ad.page.goto(`${BASE}/batch-control/changes/`);
  const rr = ad.page.locator('#main-panel tr', { hasText: 'batch-nag' }).filter({ hasText: /TRIGGER_BLOCKED|MARKER_REUSE/ });
  const s3 = (await rr.count()) ? await shot(ad.page, rr.first(), 'PR-05-3-record', { pad: 8 }) : null;
  await ad.context.close();
  ev(`PR-05 #${n} ${next0.lastBuild.result}; after 30 s next ${next0.nextBuildNumber}; auto-retry records ${autoRecs.join(' || ')}; build page sidebar ${sb}; build page mentions approval: ${/approv/i.test(bpText)}; manual Retry -> ${c && c.r.status} ${c && c.r.url} toast "${c && c.tt}"; next ${next1}; all new records ${allRecs.join(' || ')}`);
  const g = next1 === n + 1 && next0.nextBuildNumber === n + 1;
  for (const id of ['PR-05', 'B5-09', 'B5-10']) row(id, {
    roles: 'requester, approver-1, admin (records)',
    V: `✗ Retry is offered on the failed approved build (requester [${sb.filter((x) => /Retry|Rebuild/.test(x))}]) although it can never run (DEF-25)`,
    G: `${g ? '✓' : '✗'} approved #${n} ${next0.lastBuild.result}; naginator's automatic retry and the requester's Retry both refused: no further build (next stays ${n + 1})`,
    R: `${c && c.notice ? '✓' : '✗'} manual Retry: "${c ? (c.tt || c.r.text.slice(0, 100)) : 'no Retry link'}" on the build page, no approval notice there; the automatic retry leaves nothing on the build page`,
    C: `${autoRecs.length && allRecs.length ? '✓' : '✗'} (DEF-03 fixed) automatic retry: ${autoRecs.map((l) => l.split(',').slice(1, 4).join(',') + ' "' + l.split(',').slice(6).join(',').slice(0, 100) + '"').join(' || ') || 'NONE'}; after the manual Retry ${allRecs.length} record(s) in total (coalesced per job and cause per hour)`,
    E: s1 && c && c.s ? `✓ PR-05-1-failed-build-page, PR-05-2-requester-manual-retry${s3 ? ', PR-05-3-record' : ''}` : '✗',
    defect: 'DEF-25 (known)',
  });
}
await close();
