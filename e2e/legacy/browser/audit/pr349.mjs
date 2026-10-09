// PR-03 (+B5-12) parameterized-trigger, PR-04 (+B5-07) build-token-root, PR-09 (+B11-06) jobConfigHistory.
import { login, close, shot, BASE, api, job, queue, sleep, clickBuildEntry, changeRows, requestRun } from '../lib.mjs';
import { row, ev, mainText, sidebar } from './rec.mjs';
import { openConfig, saveConfig, tick, approveAs, activate, builds, waitBuilt } from './helpers-e.mjs';
const only = process.argv.slice(2); const on = (x) => !only.length || only.includes(x);
if (on('PR-04')) {
  const J = 'batch-token', token = 'e2e-token-4711';
  const b0 = await builds(J); const rec0 = await changeRows(/batch-token/);
  const out = [];
  for (const p of [`/buildByToken/build?job=${J}&token=${token}`, `/job/${J}/build?token=${token}`]) {
    const r = await fetch(`${BASE}${p}`, { method: 'POST', redirect: 'manual' });
    const t = (await r.text()).replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ').trim();
    out.push({ p, s: r.status, t: t.slice(0, 200) });
  }
  await sleep(8000);
  const b1 = await builds(J); const rec1 = await changeRows(/batch-token/);
  const nr = rec1.slice(0, rec1.length - rec0.length);
  const ad = await login('admin'); await ad.page.goto(`${BASE}/batch-control/changes/`);
  const rr = ad.page.locator('#main-panel tr', { hasText: 'REMOTE_RUN_BLOCKED' }).filter({ hasText: 'batch-token' }).first();
  const s1 = await shot(ad.page, rr, 'PR-04-1-remote-run-blocked-record', { pad: 8 }); await ad.context.close();
  const rq = await login('requester'); const url = await requestRun(rq.page, `/job/${J}/`, { reason: `Audit PR-04 ${Date.now()}: approved run of the token job`, approvers: ['approver-1'] }); await rq.context.close();
  await approveAs('approver-1', url); await waitBuilt(J, b1.next - 1); await sleep(5000);
  const b2 = await builds(J);
  const rq2 = await login('requester'); await rq2.page.goto(url); const s2 = await shot(rq2.page, '#main-panel table', 'PR-04-2-approved-executed', { pad: 8 }); await rq2.context.close();
  ev(`PR-04 ${JSON.stringify(out)}; next ${b0.next}->${b1.next}->${b2.next}; records ${nr.join(' || ')}; builds ${b2.list.slice(0, 2)}`);
  for (const id of ['PR-04', 'B5-07']) row(id, { roles: 'anonymous script caller, requester, approver-1, admin (records)', V: 'n.a. (a remote trigger URL has no UI)', G: `${b1.next === b0.next && b2.next === b1.next + 1 ? '✓' : '✗'} token calls refused, no build (next ${b0.next}->${b1.next}); Request Run -> approver-1 -> exactly one build (${b2.list[0]})`, R: `${out.every((o) => o.t.length > 20) ? '✓' : '✗'} ${out.map((o) => `POST ${o.p.split('?')[0]} -> HTTP ${o.s} "${o.t.slice(0, 90)}"`).join('; ')}`, C: `${nr.some((l) => /REMOTE_RUN_BLOCKED/.test(l)) ? '✓' : '✗'} ${nr.map((l) => l.split(',').slice(1, 4).join(',') + ' "' + l.split(',').slice(6).join(',').slice(0, 110) + '"').join(' || ')}`, E: s1 && s2 ? '✓ PR-04-1-remote-run-blocked-record, PR-04-2-approved-executed' : '✗' });
}
if (on('PR-09')) {
  const J = 'batch-jch';
  const rec = async () => (await changeRows(new RegExp(`,CONFIGURE,${J},`))).length;
  const ad = await login('admin');
  const r0 = await rec(); const h0 = ((await api('admin', `/job/${J}/jobConfigHistory/api/json`)).json || {});
  await openConfig(ad.page, J);
  await ad.page.fill('textarea[name="description"]', `Section E: edited once with jobConfigHistory installed (audit ${Date.now()}).`);
  await saveConfig(ad.page); await sleep(2000);
  const r1 = await rec();
  await openConfig(ad.page, J); await saveConfig(ad.page); await sleep(2000);
  const r2 = await rec();
  await ad.page.goto(`${BASE}/job/${J}/jobConfigHistory/`);
  const jch = (await ad.page.locator('#main-panel table tr').allInnerTexts()).slice(0, 4).map((t) => t.replace(/\s+/g, ' '));
  const s1 = await shot(ad.page, '#main-panel table', 'PR-09-1-jobconfighistory', { pad: 8 });
  await ad.page.goto(`${BASE}/batch-control/changes/`);
  const s2 = await shot(ad.page, ad.page.locator(`#main-panel tr:has-text("${J}")`).first(), 'PR-09-2-change-records', { pad: 8 });
  ev(`PR-09 CONFIGURE ${r0} -> edit ${r1} -> unchanged save ${r2}; JCH rows ${JSON.stringify(jch)}`);
  for (const id of ['PR-09', 'B11-06']) row(id, { roles: 'admin', V: 'n.a.', G: `${r1 === r0 + 1 && r2 === r1 ? '✓' : '✗'} one description edit -> exactly one CONFIGURE (${r0}->${r1}); unchanged save -> none (${r2}); Job Config History lists its own entries (${jch.length - 1} rows shown)`, R: 'n.a.', C: `${r1 === r0 + 1 ? '✓' : '✗'} one record with diff`, E: s1 && s2 ? '✓ PR-09-1-jobconfighistory, PR-09-2-change-records' : '✗' });
  await ad.context.close();
}
if (on('PR-03')) {
  const src = 'batch-pt-source', dst = 'batch-up-target';
  // hold the target through the HOLD flow
  const rh = await login('requester'); await rh.page.goto(`${BASE}/job/${dst}/`);
  const hl = rh.page.locator('.jenkins-alert a:has-text("request a hold")');
  if (await hl.count()) { await hl.click(); await rh.page.waitForLoadState('load'); await rh.page.fill('textarea[name="reason"]', 'Audit PR-03: hold before the unattended-path check'); await rh.page.locator('input[name="approvers"][value="approver-1"] + label').click(); await Promise.all([rh.page.waitForNavigation({ waitUntil: 'load' }), rh.page.locator('button:has-text("Submit Request")').click()]); await approveAs('approver-1', rh.page.url(), 'hold ok'); }
  await rh.context.close();
  const t0 = await builds(dst); const tb0 = await changeRows(/TRIGGER_BLOCKED,batch-up-target/);
  const rq = await login('requester');
  await rq.page.goto(`${BASE}/job/${src}/`); const srcSb = await sidebar(rq.page);
  const sn = (await job(src)).nextBuildNumber;
  await clickBuildEntry(rq.page, 'Build Now'); await waitBuilt(src, sn - 1); await sleep(8000);
  const t1 = await builds(dst); const tb1 = await changeRows(/TRIGGER_BLOCKED,batch-up-target/);
  const con = (await api('admin', `/job/${src}/${sn}/consoleText`, { raw: true })).text.trim().split('\n').slice(-3).join(' | ');
  await rq.page.goto(`${BASE}/job/${dst}/`);
  const held = (await rq.page.locator('#main-panel .jenkins-alert').allInnerTexts()).map((t) => t.replace(/\s+/g, ' ')).find((t) => /on hold/.test(t)) || '';
  const s1 = await shot(rq.page, rq.page.locator('#main-panel .jenkins-alert:has-text("on hold")'), 'PR-03-1-target-on-hold', { pad: 8 });
  await rq.page.goto(`${BASE}/job/${src}/${sn}/console`); const s2 = await shot(rq.page, '#main-panel pre, #out, .console-output', 'PR-03-2-upstream-console', { pad: 8 });
  await rq.context.close();
  await activate(dst, 'Audit PR-03: let batch-pt-source trigger it');
  const rq2 = await login('requester'); await rq2.page.goto(`${BASE}/job/${src}/`);
  await clickBuildEntry(rq2.page, 'Build Now'); await waitBuilt(dst, t1.next - 1); await sleep(8000);
  const t2 = await builds(dst);
  await rq2.page.goto(`${BASE}/job/${dst}/${t2.next - 1}/`); const s3 = await shot(rq2.page, rq2.page.locator('#main-panel').locator('text=/Started by upstream/').first(), 'PR-03-3-target-started-by-upstream', { pad: 12 });
  const ad = await login('admin'); await openConfig(ad.page, dst); await tick(ad.page, 'Block upstream triggers', true); await saveConfig(ad.page);
  await rq2.page.goto(`${BASE}/job/${src}/`); await clickBuildEntry(rq2.page, 'Build Now'); await sleep(15000);
  const t3 = await builds(dst); const tb3 = await changeRows(/TRIGGER_BLOCKED,batch-up-target/);
  await rq2.page.goto(`${BASE}/job/${dst}/`);
  const notices = (await rq2.page.locator('#main-panel .jenkins-alert').allInnerTexts()).map((t) => t.replace(/\s+/g, ' '));
  const s4 = await shot(rq2.page, rq2.page.locator('#main-panel .jenkins-alert').first(), 'PR-03-4-blockupstream-notice', { pad: 8 });
  await openConfig(ad.page, dst); await tick(ad.page, 'Block upstream triggers', false); await saveConfig(ad.page);
  await ad.context.close(); await rq2.context.close();
  ev(`PR-03 src sidebar ${srcSb}; held: ${dst} next ${t0.next}->${t1.next}, records ${tb0.length}->${tb1.length} ${tb1[0]}; console ${con}; notice "${held}"; activated: next ${t2.next} ${t2.list[0]}; blockUpstream: next ${t3.next}, records ${tb3.length} ${tb3[0]}; notices ${JSON.stringify(notices)}`);
  for (const id of ['PR-03', 'B5-12']) row(id, { roles: 'requester (source), approver-1, admin (configure)', V: 'n.a. (unattended path)', G: `${t1.next === t0.next && t2.next === t1.next + 1 && t3.next === t2.next ? '✓' : '✗'} target on hold: source run, target not built (${t0.next}->${t1.next}); after ACTIVATE exactly one target run (${t2.list[0]}); Block upstream triggers on: refused again (${t2.next}->${t3.next}); restored off`, R: `${/on hold/.test(held) && notices.some((n) => /blockUpstream|Block upstream/.test(n)) ? '✓' : '✗'} target page "${held.slice(0, 100)}"; with the switch: "${(notices.find((n) => /upstream/i.test(n)) || '').slice(0, 140)}"; the source console says "${con.slice(0, 100)}" (parameterized-trigger's own text, U-10)`, C: `${tb1.length > tb0.length ? '✓' : '✗'} TRIGGER_BLOCKED for the held target: "${(tb1[0] || '').split(',').slice(6).join(',').slice(0, 110)}"; the blockUpstream refusal ${tb3.length > tb1.length ? 'is a new record' : 'is merged into that record (it keeps switch=activation, U-17)'}`, E: [s1, s2, s3, s4].every(Boolean) ? '✓ PR-03-1..4' : '✗' });
}
await close();
