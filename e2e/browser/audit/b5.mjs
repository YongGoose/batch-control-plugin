// B5 blocked paths re-audit: B5-01, 02, 03, 05, 13, 14, 15, 16, 20, 22 (B5-21 separately).
import { login, close, shot, api, job, queue, BASE, requestRun, waitFor, sleep, changeRows, clickBuildEntry, setGlobal } from '../lib.mjs';
import { row, ev, mainText, sidebar } from './rec.mjs';
import { formPost } from './restsubmit.mjs';
const only = process.argv.slice(2); const on = (x) => !only.length || only.includes(x);
const tag = Date.now();
const ad = await login('admin');
async function setProp(J, { blockUpstream, allowed }) {
  await ad.page.goto(`${BASE}/job/${J}/configure`); await ad.page.waitForTimeout(1500);
  if (blockUpstream !== undefined) { const b = ad.page.locator('[name="_.blockUpstream"]').first(); if ((await b.isChecked()) !== blockUpstream) await b.locator('xpath=following-sibling::label[1]').click(); }
  if (allowed !== undefined) await ad.page.locator('[name="_.allowedUpstreamJobsText"]').first().fill(allowed);
  await Promise.all([ad.page.waitForNavigation(), ad.page.locator('button[name="Submit"]').click()]);
}
async function runUpstream() {
  const n = (await job('batch-upstream', 'nextBuildNumber')).nextBuildNumber; const d0 = (await job('batch-daily', 'nextBuildNumber')).nextBuildNumber;
  await ad.page.goto(`${BASE}/job/batch-upstream/`); await clickBuildEntry(ad.page, 'Build Now');
  const u = await waitFor(async () => { const j = await job('batch-upstream', 'lastBuild[number,building,result]'); return j.lastBuild && j.lastBuild.number === n && !j.lastBuild.building ? j.lastBuild : null; }, { timeout: 120000, every: 2000 });
  await sleep(4000);
  const d1 = (await job('batch-daily', 'nextBuildNumber')).nextBuildNumber;
  const con = (await api('admin', `/job/batch-upstream/${n}/consoleText`, { raw: true })).text.trim().split('\n').filter((l) => /batch-daily|Failed|Starting|ERROR/.test(l)).slice(-2).join(' | ');
  return { n, result: u && u.result, built: d1 - d0, d0, con };
}
if (on('B5-01')) {
  const rq = await login('requester');
  await rq.page.goto(`${BASE}/job/batch-daily/`);
  const n0 = (await job('batch-daily')).nextBuildNumber;
  const r = await clickBuildEntry(rq.page, 'Direct Build (needs approval)');
  const link = rq.page.locator('#main-panel .jenkins-alert a:has-text("Request Run")').first();
  const s1 = await shot(rq.page, ['#main-panel .jenkins-app-bar', '#main-panel .jenkins-alert'], 'B5-01-1-refusal-page', { pad: 8 });
  const has = await link.count();
  if (has) { await Promise.all([rq.page.waitForLoadState('load'), link.click()]); }
  const formOk = await rq.page.locator('form[name="batch-control-request"]').count();
  const s2 = formOk ? await shot(rq.page, 'form[name="batch-control-request"]', 'B5-01-2-link-opens-form', { pad: 8 }) : null;
  await sleep(4000); const n1 = (await job('batch-daily')).nextBuildNumber;
  ev(`B5-01 ${r.status} "${r.text.slice(0, 200)}" link ${has} form ${formOk} next ${n0}->${n1}`);
  row('B5-01', { roles: 'requester (nobc variant in A-13)', V: '✗ the Direct Build (needs approval) entry leading here is offered although it can never start a build (DEF-25)', G: `${n1 === n0 ? '✓' : '✗'} refused, no build (${n0} -> ${n1})`, R: `${has && formOk ? '✓' : '✗'} (DEF-02 fixed) page "Approval required": "${r.text.replace(/^.*?This build/, 'This build').slice(0, 150)}"; the Request Run link opens the form`, C: 'n.a.', E: s1 && s2 ? '✓ B5-01-1..2' : '✗', defect: 'DEF-25 (known); the page also carries the Back link of DEF-28' });
  await rq.context.close();
}
if (on('B5-02')) {
  const out = [];
  for (const [p, body] of [['/job/batch-lock/build', ''], ['/job/batch-daily/buildWithParameters?DATE=2031-02-02&MODE=full', ''], ['/job/batch-daily/build', '']]) {
    const n0 = (await job(p.split('/')[2], 'nextBuildNumber')).nextBuildNumber;
    const r = await api('requester', p, { method: 'POST' }); await sleep(3000);
    const n1 = (await job(p.split('/')[2], 'nextBuildNumber')).nextBuildNumber;
    out.push({ p, s: r.status, t: r.text.replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ').replace(/^.*?(Approval required|Error)/, '$1').slice(0, 170), built: n1 - n0 });
  }
  ev(`B5-02/03 ${JSON.stringify(out)}`);
  row('B5-02', { roles: 'requester (script with crumb)', V: 'n.a.', G: `${out[0].built === 0 ? '✓' : '✗'} POST /job/batch-lock/build -> ${out[0].s}, no build`, R: `${/Request Run|run request/.test(out[0].t) ? '✓' : '✗'} body: "${out[0].t}"`, C: 'n.a.', E: '✓ text (audit.log)', note: `on the parameterised batch-daily core answers first: POST /build -> ${out[2].s} "${out[2].t.slice(0, 60)}"` });
  row('B5-03', { roles: 'requester (script with crumb)', V: 'n.a.', G: `${out[1].built === 0 ? '✓' : '✗'} POST buildWithParameters -> ${out[1].s}, no build`, R: `${/Request Run|run request/.test(out[1].t) ? '✓' : '✗'} body: "${out[1].t}"`, C: 'n.a.', E: '✓ text (audit.log)' });
}
if (on('B5-05')) {
  const J = 'batch-pipeline';
  const last = (await job(J, 'lastSuccessfulBuild[number]')).lastSuccessfulBuild.number;
  const n0 = (await job(J, 'nextBuildNumber')).nextBuildNumber; const r0 = (await changeRows(/,TRIGGER_BLOCKED,batch-pipeline,/)).length;
  await ad.page.goto(`${BASE}/job/${J}/${last}/`);
  const sb = await sidebar(ad.page);
  await ad.page.locator('#side-panel a:has-text("Replay")').click(); await ad.page.waitForLoadState('load');
  const [resp] = await Promise.all([ad.page.waitForNavigation({ waitUntil: 'load' }).catch(() => null), ad.page.locator('button[name="Submit"], button:has-text("Run")').first().click()]);
  const t = await mainText(ad.page);
  const s = await shot(ad.page, '#main-panel, body', 'B5-05-replay-refused', { pad: 8 });
  await sleep(4000);
  const n1 = (await job(J, 'nextBuildNumber')).nextBuildNumber; const recs = await changeRows(/,TRIGGER_BLOCKED,batch-pipeline,/);
  ev(`B5-05 sidebar ${sb}; replay -> ${resp && resp.status()} "${t.slice(0, 200)}"; next ${n0}->${n1}; records ${r0}->${recs.length} ${recs[0]}`);
  row('B5-05', { roles: 'admin (Replay; the CONFIGURE-window variant as in part 2)', V: `✗ Replay offered on the approval-required job [${sb.filter((x) => /Replay/.test(x))}] although it can never run (DEF-16)`, G: `${n1 === n0 ? '✓' : '✗'} no build (${n0} -> ${n1})`, R: `✗ HTTP ${resp && resp.status()} "${t.slice(0, 110)}" (DEF-16)`, C: `${/cause=REPLAY/.test(recs[0] || '') ? '✓' : '✗'} TRIGGER_BLOCKED cause=REPLAY (${r0} -> ${recs.length}, coalesced per hour)`, E: s ? '✓ B5-05-replay-refused' : '✗', defect: 'DEF-16 (known)' });
}
if (on('B5-13')) {
  const r = await runUpstream();
  const cause = r.built ? JSON.stringify((await api('admin', `/job/batch-daily/${r.d0}/api/json?tree=actions[causes[shortDescription]]`)).json.actions.flatMap((a) => (a.causes || []).map((c) => c.shortDescription))) : '';
  await ad.page.goto(`${BASE}/job/batch-daily/${r.d0}/`); const s = r.built ? await shot(ad.page, ad.page.locator('#main-panel').locator('text=/Started by upstream/').first(), 'B5-13-started-by-upstream', { pad: 12 }) : null;
  ev(`B5-13 ${JSON.stringify(r)} ${cause}`);
  row('B5-13', { roles: 'admin (starts the uncontrolled upstream)', V: 'n.a.', G: `${r.result === 'SUCCESS' && r.built === 1 && /upstream/.test(cause) ? '✓' : '✗'} batch-daily activated, blockUpstream false: batch-upstream #${r.n} ${r.result}, batch-daily #${r.d0} ${cause}`, R: 'n.a.', C: '✓ runs record cause UPSTREAM (dashboard)', E: s ? '✓ B5-13-started-by-upstream' : '✗' });
}
if (on('B5-14') || on('B5-15')) {
  await setProp('batch-daily', { blockUpstream: true, allowed: '' });
  const r1 = await runUpstream();
  const rq = await login('requester'); await rq.page.goto(`${BASE}/job/batch-daily/`);
  const nt = (await rq.page.locator('#main-panel .jenkins-alert', { hasText: /blockUpstream|upstream triggers/ }).first().innerText().catch(() => 'NONE')).replace(/\s+/g, ' ');
  const s1 = await shot(rq.page, rq.page.locator('#main-panel .jenkins-alert', { hasText: /blockUpstream|upstream triggers/ }).first(), 'B5-14-notice', { pad: 8 });
  const rec = (await changeRows(/,TRIGGER_BLOCKED,batch-daily,/))[0] || '';
  row('B5-14', { roles: 'admin (configure), requester (job page)', V: 'n.a.', G: `${r1.result === 'FAILURE' && r1.built === 0 ? '✓' : '✗'} blockUpstream on, allowed empty: batch-upstream #${r1.n} ${r1.result} ("${r1.con.slice(0, 90)}"), no batch-daily build`, R: `${/blockUpstream/.test(nt) ? '✓' : '✗'} job page: "${nt.slice(0, 160)}"`, C: `${/cause=UPSTREAM/.test(rec) ? '✓' : '✗'} "${rec.split(',').slice(6).join(',').slice(0, 110)}" (a refusal inside the hour is merged into an earlier record; its switch may name an earlier cause, U-17)`, E: s1 ? '✓ B5-14-notice' : '✗' });
  await setProp('batch-daily', { allowed: 'some-other-job' }); const r2 = await runUpstream();
  await setProp('batch-daily', { allowed: 'batch-upstream' }); const r3 = await runUpstream();
  await setProp('batch-daily', { blockUpstream: false, allowed: '' });
  ev(`B5-14 ${JSON.stringify(r1)} notice "${nt}" rec ${rec}; B5-15 other ${JSON.stringify(r2)} listed ${JSON.stringify(r3)}`);
  row('B5-15', { roles: 'admin', V: 'n.a.', G: `${r2.built === 0 && r3.built === 1 ? '✓' : '✗'} allowed=some-other-job: refused (#${r2.n} ${r2.result}, ${r2.built} build); allowed=batch-upstream: passes (#${r3.n} ${r3.result}, ${r3.built} build)`, R: 'n.a.', C: 'n.a.', E: '✓ text (audit.log); notice B5-14-notice' });
  await rq.context.close();
}
if (on('B5-16')) {
  const j = (await job('batch-cron', 'builds[number,timestamp,actions[causes[shortDescription]]]')).builds.slice(0, 5);
  const ts = j.map((b) => new Date(b.timestamp).toISOString().slice(11, 16));
  await ad.page.goto(`${BASE}/job/batch-cron/`);
  const s = await shot(ad.page, ['#main-panel .jenkins-alert:has-text("activated")', '#side-panel .app-builds-container, #buildHistory'], 'B5-16-cron-builds', { pad: 8 });
  row('B5-16', { roles: 'admin', V: 'n.a.', G: `${ts.length === 5 && j.every((b) => /timer/i.test(JSON.stringify(b.actions))) ? '✓' : '✗'} activated batch-cron, blockTimer=false: last five builds at ${ts.join(', ')} UTC, all "Started by timer"`, R: 'n.a.', C: '✓ TIMER rows on the dashboard (B4-15/A-19 dashboard read)', E: s ? '✓ B5-16-cron-builds' : '✗' });
}
if (on('B5-20')) {
  const rq = await login('requester');
  const n = (await job('b5-long', 'nextBuildNumber')).nextBuildNumber;
  const url = await requestRun(rq.page, '/job/b5-long/', { reason: `Audit B5-20 ${tag}: long approved run`, approvers: ['approver-1'] });
  const a = await login('approver-1'); await a.page.goto(url); await a.page.fill('form[name="approve"] textarea[name="comment"]', 'ok'); await Promise.all([a.page.waitForLoadState('load'), a.page.locator('form[name="approve"] button').first().click()]); await a.context.close();
  await waitFor(async () => { const j = await job('b5-long', 'lastBuild[number,building]'); return j.lastBuild && j.lastBuild.number === n && j.lastBuild.building; }, { timeout: 60000, every: 1000 });
  await setGlobal(ad.page, { runControlEnabled: false }); await setGlobal(ad.page, { runControlEnabled: true });
  await ad.page.goto(`${BASE}/job/b5-long/configure`); await ad.page.waitForTimeout(1200); await ad.page.fill('textarea[name="description"]', `edited during the approved run (audit ${tag})`); await Promise.all([ad.page.waitForNavigation(), ad.page.locator('button[name="Submit"]').click()]);
  const mid = (await job('b5-long', 'lastBuild[number,building]')).lastBuild;
  const fin = await waitFor(async () => { const j = await job('b5-long', 'lastBuild[number,building,result,duration]'); return j.lastBuild.number === n && !j.lastBuild.building ? j.lastBuild : null; }, { timeout: 120000, every: 2000 });
  await ad.page.goto(`${BASE}/job/b5-long/${n}/`); const s = await shot(ad.page, '#main-panel .jenkins-app-bar, #main-panel h1', 'B5-20-finished', { pad: 8 });
  ev(`B5-20 #${n} mid ${JSON.stringify(mid)} fin ${JSON.stringify(fin)}`);
  row('B5-20', { roles: 'requester, approver-1, admin', V: 'n.a.', G: `${mid.building && fin && fin.result === 'SUCCESS' ? '✓' : '✗'} approved b5-long #${n} kept building while admin turned run control off and on and edited the job; finished ${fin && fin.result} after ${fin && Math.round(fin.duration / 1000)} s`, R: 'n.a.', C: 'n.a.', E: s ? '✓ B5-20-finished' : '✗', note: 'grant expiry mid-build not run (integration tests)' });
  await rq.context.close();
}
if (on('B5-22')) {
  const res = {};
  const rq = await login('requester');
  for (const J of ['batch-cbn', 'batch-nag', 'batch-token', 'batch-rebuild', 'batch-lock']) { await rq.page.goto(`${BASE}/job/${J}/`); res[J] = await sidebar(rq.page); }
  const s = await shot(rq.page, '#side-panel #tasks, #side-panel', 'B5-22-batch-lock-sidebar', { pad: 8 });
  ev(`B5-22 ${JSON.stringify(res)}`);
  row('B5-22', { roles: 'requester', V: `✗ every plugin-modified job offers Request Run (✓) but also Direct Build (needs approval) and Rebuild Last, which are refused on every click (DEF-25): ${Object.entries(res).map(([k, v]) => `${k} [${v.filter((x) => /Request Run|Direct|Rebuild/.test(x))}]`).join('; ')}`, G: `${Object.values(res).every((v) => v.includes('Request Run')) ? '✓' : '✗'} Request Run on all five`, R: 'n.a.', C: 'n.a.', E: s ? '✓ B5-22-batch-lock-sidebar' : '✗', defect: 'DEF-25 (known)' });
  await rq.context.close();
}
await close();
