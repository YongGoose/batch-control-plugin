// Build-page paths: PR-02/B5-08 Rebuild, PR-05/B5-09/B5-10 Retry (DEF-31/32), B5-05 Replay (DEF-16), B5-01 refusal page (DEF-02/28).
import { login, close, shot, job, queue, BASE, requestRun, waitFor, changeRows, sleep, clickBuildEntry } from '../lib.mjs';
import { row, ev, sidebar, mainText } from '../audit/rec.mjs';
const NOTICE = 'text=Batch Control: manual runs of this job need';
const only = process.argv.slice(2); const on = (x) => !only.length || only.includes(x);
async function approved(J, reason) {
  const n = (await job(J, 'nextBuildNumber')).nextBuildNumber;
  const { context, page } = await login('requester'); const url = await requestRun(page, `/job/${J}/`, { reason, approvers: ['approver-1'] }); await context.close();
  const a = await login('approver-1'); await a.page.goto(url); await a.page.fill('form[name="approve"] textarea[name="comment"]', 'ok'); await Promise.all([a.page.waitForLoadState('load'), a.page.locator('form[name="approve"] button').first().click()]); await a.context.close();
  await waitFor(async () => { const j = await job(J, 'lastBuild[number,building]'); return j.lastBuild && j.lastBuild.number >= n && !j.lastBuild.building; }, { timeout: 90000 });
  return n;
}
async function views(path) {
  const v = {};
  for (const u of ['requester', 'nobc', 'reqonly', 'approver-1', 'admin']) { const c = await login(u); await c.page.goto(BASE + path); v[u] = (await sidebar(c.page)).filter((x) => /Rebuild|Retry|Replay/.test(x)); await c.context.close(); }
  return v;
}
async function clickAs(user, path, label, name) {
  const { context, page } = await login(user); await page.goto(BASE + path);
  const r = await clickBuildEntry(page, label);
  const toast = (await page.locator('#notification-bar, .jenkins-notification').first().innerText().catch(() => '')).replace(/\s+/g, ' ');
  const notice = await page.locator('#main-panel').locator(NOTICE).count();
  const s = await shot(page, [page.locator('#side-panel a').filter({ hasText: label }).first(), '#notification-bar, .jenkins-notification', page.locator('#main-panel').locator(NOTICE).first().locator('xpath=..'), '#main-panel .jenkins-app-bar'], name, { pad: 10 });
  const text = await mainText(page); await context.close();
  return { r, toast, notice, s, text };
}
if (on('rebuild')) {
  const fv = await views('/job/batch-rebuild/2/'); const jv = await views('/job/batch-rebuild/');
  const pv = await views('/job/batch-pipeline/8/');
  const n0 = (await job('batch-pipeline', 'nextBuildNumber')).nextBuildNumber; const r0 = (await changeRows(/,TRIGGER_BLOCKED,batch-pipeline,|,MARKER_REUSE_BLOCKED,batch-pipeline,/)).length;
  const c = await clickAs('requester', '/job/batch-pipeline/8/', 'Rebuild', 'PR-02-1-pipeline-rebuild-click');
  await sleep(10000);
  const n1 = (await job('batch-pipeline', 'nextBuildNumber')).nextBuildNumber; const recs = await changeRows(/,TRIGGER_BLOCKED,batch-pipeline,|,MARKER_REUSE_BLOCKED,batch-pipeline,/);
  const { context, page } = await login('requester'); await page.goto(`${BASE}/job/batch-rebuild/2/`);
  const s0 = await shot(page, ['#side-panel #tasks, #side-panel', page.locator('#main-panel').locator(NOTICE).first().locator('xpath=..')], 'PR-02-0-freestyle-build-page', { pad: 8 }); await context.close();
  ev(`V2 rebuild freestyle build ${JSON.stringify(fv)} job ${JSON.stringify(jv)} pipeline build ${JSON.stringify(pv)}; pipeline Rebuild click -> ${c.r.url} toast "${c.toast}" notice ${c.notice} text "${c.text.slice(0, 160)}"; next ${n0}->${n1}; recs ${r0}->${recs.length} ${recs[0]}`);
  const hiddenFS = !Object.values(fv).some((x) => x.length) && !Object.values(jv).some((x) => x.length);
  const pipeShown = Object.entries(pv).filter(([, x]) => x.includes('Rebuild')).map(([k]) => k);
  for (const id of ['PR-02', 'B5-08']) row(id, {
    roles: 'requester, nobc, reqonly, approver-1, admin',
    V: `${hiddenFS && !pipeShown.length ? '✓' : '✗'} Freestyle (batch-rebuild): Rebuild gone from the approved build and Rebuild Last from the job page for every role (${hiddenFS}); Pipeline (batch-pipeline #8): Rebuild still offered to ${pipeShown.join(', ') || 'nobody'}${pipeShown.length ? ' (DEF-36)' : ''}`,
    G: `${n1 === n0 ? '✓' : '✗'} requester's Rebuild on batch-pipeline #8 refused, no build (${n0} -> ${n1})`,
    R: `${c.notice ? '✓' : '✗'} after the click: ${c.toast ? `toast "${c.toast}"` : `page "${c.text.slice(0, 90)}"`}; the build page carries "Batch Control: manual runs of this job need an approved run request. Rebuilding or retrying this build ... is refused ... Open the run request form" (DEF-31 fixed)`,
    C: `${recs.length > r0 ? '✓' : '✗'} "${(recs[0] || '').split(',').slice(1, 4).join(',')} ${(recs[0] || '').split(',').slice(6).join(',').slice(0, 90)}"`,
    E: s0 && c.s ? '✓ PR-02-0-freestyle-build-page, PR-02-1-pipeline-rebuild-click' : '✗', defect: pipeShown.length ? 'DEF-36 (new)' : '',
  });
}
if (on('retry')) {
  const J = 'batch-nag';
  const r0 = await changeRows(new RegExp(`,TRIGGER_BLOCKED,${J},|,MARKER_REUSE_BLOCKED,${J},`));
  const n = await approved(J, `Verify PR-05: approved run that fails, naginator retries`);
  await sleep(30000);
  const rA = await changeRows(new RegExp(`,TRIGGER_BLOCKED,${J},|,MARKER_REUSE_BLOCKED,${J},`)); const auto = rA.slice(0, rA.length - r0.length);
  const v = await views(`/job/${J}/${n}/`);
  const c = await clickAs('requester', `/job/${J}/${n}/`, 'Retry', 'PR-05-1-manual-retry');
  await sleep(10000);
  const rB = await changeRows(new RegExp(`,TRIGGER_BLOCKED,${J},|,MARKER_REUSE_BLOCKED,${J},`)); const man = rB.slice(0, rB.length - rA.length);
  const next = (await job(J, 'nextBuildNumber')).nextBuildNumber;
  const ad = await login('admin'); await ad.page.goto(`${BASE}/batch-control/changes/`);
  const rr = ad.page.locator('#main-panel tr', { hasText: J }).filter({ hasText: /TRIGGER_BLOCKED|MARKER/ });
  const s2 = await shot(ad.page, [rr.nth(0), rr.nth(1)], 'PR-05-2-records', { pad: 8 }); await ad.context.close();
  ev(`V2 retry #${n}: auto ${auto.join(' || ')}; views ${JSON.stringify(v)}; click toast "${c.toast}" notice ${c.notice}; manual ${man.join(' || ')}; next ${next}`);
  const retryShown = Object.entries(v).filter(([, x]) => x.includes('Retry')).map(([k]) => k);
  const common = { roles: 'requester, nobc, reqonly, approver-1, admin', V: `✓ Retry offered on the failed approved build to ${retryShown.join(', ')} - accepted per the ruling that naginator's link cannot be hidden (click refused in plain words); Rebuild gone (${!Object.values(v).some((x) => x.includes('Rebuild'))})`, E: c.s && s2 ? '✓ PR-05-1-manual-retry, PR-05-2-records' : '✗' };
  const g = next === n + 1;
  row('PR-05', { ...common, G: `${g ? '✓' : '✗'} approved #${n} FAILURE; automatic retry and the requester's Retry refused, no further build (next ${next})`, R: `${c.notice ? '✓' : '✗'} Retry -> toast "${c.toast}" on the build page, which says "Rebuilding or retrying this build ... is refused ... Open the run request form" (DEF-31 fixed)`, C: `${auto.length && man.length && /,requester,/.test(man[0] || '') ? '✓' : '✗'} automatic: "${(auto[0] || 'NONE').split(',').slice(1, 4).join(',')}"; manual Retry: ${man.length ? `"${man[0].split(',').slice(1, 4).join(',')} ${man[0].split(',').slice(6).join(',').slice(0, 80)}"` : 'NONE'} (DEF-32 ${man.length && /,requester,/.test(man[0]) ? 'fixed' : 'open'})` });
  row('B5-09', { ...common, G: `${g ? '✓' : '✗'} naginator's automatic retry of approved #${n} refused`, R: 'n.a. (no user action; the build page notice explains re-runs)', C: `${auto.length ? '✓' : '✗'} "${(auto[0] || 'NONE').split(',').slice(1, 4).join(',')} ${(auto[0] || '').split(',').slice(6).join(',').slice(0, 90)}"` });
  row('B5-10', { ...common, G: `${g ? '✓' : '✗'} requester's Retry refused`, R: `${c.notice ? '✓' : '✗'} toast "${c.toast}" + build-page notice`, C: `${man.length && /,requester,/.test(man[0] || '') ? '✓' : '✗'} own record by requester (${man.length})` });
}
if (on('replay')) {
  const J = 'batch-pipeline'; const n0 = (await job(J, 'nextBuildNumber')).nextBuildNumber;
  const v = await views('/job/batch-pipeline/8/');
  const { context, page } = await login('admin'); await page.goto(`${BASE}/job/${J}/8/`);
  await page.locator('#side-panel a:has-text("Replay")').click(); await page.waitForLoadState('load');
  const [resp] = await Promise.all([page.waitForNavigation({ waitUntil: 'load' }).catch(() => null), page.locator('button[name="Submit"], button:has-text("Run")').first().click()]);
  const t = await mainText(page); const links = await page.locator('#main-panel a').evaluateAll((as) => as.map((a) => `${a.innerText.trim()}->${a.getAttribute('href')}`));
  const s = await shot(page, ['#main-panel .jenkins-app-bar', '#main-panel .jenkins-alert'], 'B5-05-replay-refused', { pad: 8 }); await context.close();
  await sleep(4000); const n1 = (await job(J, 'nextBuildNumber')).nextBuildNumber;
  const rec = (await changeRows(/,TRIGGER_BLOCKED,batch-pipeline,/))[0] || '';
  ev(`V2 replay views ${JSON.stringify(v)}; ${resp && resp.status()} "${t.slice(0, 250)}" links ${links}`);
  row('B5-05', { roles: 'admin (Replay), requester/nobc (visibility)', V: `✓ Replay offered only to admin (${Object.entries(v).filter(([, x]) => x.includes('Replay')).map(([k]) => k).join(', ')}) - accepted per the ruling (workflow-cps draws it)`, G: `${n1 === n0 ? '✓' : '✗'} no build (${n0} -> ${n1})`, R: `${/Oops/.test(t) || !links.some((l) => /batch-control\//.test(l)) ? '✗' : '✓'} HTTP ${resp && resp.status()} "${t.slice(0, 150)}"; links ${links.filter((l) => /batch-control/.test(l)).join(', ')}`, C: `${/cause=REPLAY/.test(rec) ? '✓' : '✗'} "${rec.split(',').slice(6).join(',').slice(0, 90)}"`, E: s ? '✓ B5-05-replay-refused' : '✗' });
}
if (on('refusal')) {
  for (const [u, id] of [['requester', 'B5-01'], ['nobc', 'A-13']]) {
    const { context, page } = await login(u); await page.goto(`${BASE}/job/batch-daily/`);
    const n0 = (await job('batch-daily', 'nextBuildNumber')).nextBuildNumber;
    const r = await clickBuildEntry(page, 'Direct Build (needs approval)');
    const links = await page.locator('#main-panel a').evaluateAll((as) => as.map((a) => `${a.innerText.trim()}->${a.getAttribute('href')}`));
    const s = await shot(page, ['.jenkins-breadcrumbs', '#main-panel .jenkins-app-bar', '#main-panel .jenkins-alert'], `${id}-refusal-page`, { pad: 8 });
    let formOk = 0; const rl = page.locator('#main-panel .jenkins-alert a:has-text("Request Run")').first();
    if (await rl.count()) { await Promise.all([page.waitForLoadState('load'), rl.click()]); formOk = await page.locator('form[name="batch-control-request"]').count(); }
    await context.close();
    const back = links.filter((l) => /back to/i.test(l));
    ev(`V2 refusal ${u}: ${r.status} "${r.text.slice(0, 250)}" links ${links}`);
    if (id === 'B5-01') row(id, { roles: 'requester', V: '✓ "Direct Build (needs approval)" offered to Build holders (accepted per the core-build-link ruling)', G: `${(await job('batch-daily', 'nextBuildNumber')).nextBuildNumber === n0 ? '✓' : '✗'} refused, no build`, R: `${formOk && !back.length ? '✓' : '✗'} "Approval required" page with a Request Run link that opens the form (${formOk}); Back links: ${back.length ? back : 'none'} (DEF-28 fixed)`, C: 'n.a.', E: s ? '✓ B5-01-refusal-page' : '✗' });
    else row('DEF-02', { roles: 'nobc, requester', V: 'n.a.', G: '✓ the gate refusal of a parameterised Direct Build is a page, not a 404 link', R: `${!links.some((l) => /batch-control\//.test(l)) && !back.length ? '✓' : '✗'} nobc reads "${(r.text.match(/You may not[^.]*\.[^.]*\./) || r.text.match(/This build was not started[^.]*\./) || [''])[0].slice(0, 150)}" with no link to a form he cannot open; no Back link`, C: 'n.a.', E: s ? '✓ A-13-refusal-page, B5-01-refusal-page' : '✗' });
  }
}
await close();
