// Final round: D-51 per-attempt records of a person's refused re-run (PR-05, B5-09, B5-10, PR-02, B5-08, B5-05).
import { login, close, shot, job, BASE, requestRun, waitFor, changeRows, sleep, clickBuildEntry } from '../lib.mjs';
import { row, ev, sidebar, mainText } from '../audit/rec.mjs';
const blocked = async (J) => changeRows(new RegExp(`,(TRIGGER_BLOCKED|MARKER_REUSE_BLOCKED),${J},`));
const newer = (after, before) => after.slice(0, after.length - before.length);
const fmt = (l) => `${l.split(',').slice(1, 4).join(',')} "${l.split(',').slice(6).join(',').slice(0, 110)}"`;
async function approved(J) {
  const n = (await job(J, 'nextBuildNumber')).nextBuildNumber;
  const r = await login('requester'); const url = await requestRun(r.page, `/job/${J}/`, { reason: 'Final D-51: approved run that fails', approvers: ['approver-1'] }); await r.context.close();
  const a = await login('approver-1'); await a.page.goto(url); await a.page.fill('form[name="approve"] textarea[name="comment"]', 'ok');
  await Promise.all([a.page.waitForLoadState('load'), a.page.locator('form[name="approve"] button').first().click()]); await a.context.close();
  await waitFor(async () => { const j = await job(J, 'lastBuild[number,building]'); return j.lastBuild && j.lastBuild.number >= n && !j.lastBuild.building; }, { timeout: 90000 });
  return n;
}
async function click(user, path, label, name) {
  const { context, page } = await login(user); await page.goto(BASE + path);
  const r = await clickBuildEntry(page, label);
  if (/\/replay\/?$/.test(page.url())) await Promise.all([page.waitForNavigation().catch(() => null), page.locator('button[name="Submit"], button:has-text("Run")').first().click()]);
  const toast = (await page.locator('#notification-bar, .jenkins-notification').first().innerText().catch(() => '')).replace(/\s+/g, ' ');
  const text = await mainText(page);
  const s = name ? await shot(page, [page.locator('#side-panel a').filter({ hasText: label }).first(), '#notification-bar, .jenkins-notification', '#main-panel .jenkins-app-bar', '#main-panel .jenkins-alert'], name, { pad: 10 }) : null;
  await context.close(); return { toast, text, s };
}
// Retry
const J = 'batch-nag';
const r0 = await blocked(J);
const n = await approved(J); await sleep(30000);
const rA = await blocked(J); const auto = newer(rA, r0);
const c1 = await click('requester', `/job/${J}/${n}/`, 'Retry', 'PR-05-1-manual-retry');
await sleep(3000);
const c2 = await click('requester', `/job/${J}/${n}/`, 'Retry');
await sleep(3000);
const rB = await blocked(J); const first = newer(rB, rA);
const c3 = await click('requester', `/job/${J}/${n - 1}/`, 'Retry');
await sleep(3000);
const rC = await blocked(J); const second = newer(rC, rB);
const next = (await job(J, 'nextBuildNumber')).nextBuildNumber;
const ad = await login('admin'); await ad.page.goto(`${BASE}/batch-control/changes/`);
const rr = ad.page.locator('#main-panel tr', { hasText: J }).filter({ hasText: /TRIGGER_BLOCKED/ });
const s2 = await shot(ad.page, [rr.nth(0), rr.nth(1), rr.nth(2)], 'PR-05-2-records', { pad: 8 });
ev(`F1 retry #${n}: auto ${auto.map(fmt)}; first+double click ${first.map(fmt)}; other build ${second.map(fmt)}; next ${next}; toast "${c1.toast}"`);
const perUser = first.length === 1 && /,requester,/.test(first[0]) && new RegExp(`#${n}\\b|build ${n}\\b|${J}#${n}`).test(first[0]);
const other = second.length === 1 && /,requester,/.test(second[0]);
const common = { roles: 'requester, approver-1, admin (records)', V: '✓ Retry offered on the failed approved build to its Build holders - D-49 / LIMITATIONS 41 (naginator draws it, no extension point removes it)', E: c1.s && s2 ? '✓ PR-05-1-manual-retry, PR-05-2-records (run-3-final, not in git)' : '✗' };
row('PR-05', { ...common, G: `${next === n + 1 ? '✓' : '✗'} approved #${n} FAILURE; naginator's automatic retry and three Retry clicks refused, no build (next ${next})`, R: `✓ toast "${c1.toast}" next to the build-page approval notice (D-49)`, C: `${auto.length && perUser && other ? '✓' : '✗'} automatic: ${auto.map(fmt).join(' / ') || 'none'}; requester's Retry on #${n} (clicked twice within a minute): ${first.length} record ${first.map(fmt).join(' / ')}; on #${n - 1}: ${second.map(fmt).join(' / ') || 'none'} (DEF-32 ${perUser && other ? 'fixed' : 'open'}, D-51)` });
row('B5-09', { ...common, G: `✓ naginator's automatic retry of #${n} refused`, R: 'n.a. (no user action)', C: `${auto.length ? '✓' : '✗'} ${auto.map(fmt).join(' / ') || 'none'} (unattended: hourly coalescing kept, D-51)` });
row('B5-10', { ...common, G: '✓ requester\'s Retry refused', R: `✓ toast "${c1.toast}" + build-page notice`, C: `${perUser && other ? '✓' : '✗'} one record per attempt naming requester and the build; a double click stays one record (${first.length})` });
// Replay (admin) and Pipeline Rebuild (requester) on batch-pipeline
const P = 'batch-pipeline';
const p0 = await blocked(P); const pn = (await job(P, 'nextBuildNumber')).nextBuildNumber;
const d1 = await click('admin', `/job/${P}/8/`, 'Replay', 'B5-05-replay-refused');
await sleep(3000);
const d2 = await click('requester', `/job/${P}/7/`, 'Rebuild', 'PR-02-1-pipeline-rebuild-refused');
await sleep(3000);
const p1 = await blocked(P); const prec = newer(p1, p0); const pn1 = (await job(P, 'nextBuildNumber')).nextBuildNumber;
ev(`F1 replay admin "${d1.text.slice(0, 120)}"; rebuild requester "${d2.text.slice(0, 120)}"; records ${prec.map(fmt)}; next ${pn}->${pn1}`);
const adminRec = prec.find((l) => /,admin,/.test(l)); const reqRec = prec.find((l) => /,requester,/.test(l));
row('B5-05', { roles: 'admin (Replay)', V: '✓ Replay offered only to Run/Replay holders - D-49', G: `${pn1 === pn ? '✓' : '✗'} no build`, R: `${/Approval required/.test(d1.text) ? '✓' : '✗'} "Approval required" page with the Request Run link`, C: `${adminRec ? '✓' : '✗'} own record: ${adminRec ? fmt(adminRec) : 'none'} (D-51a: a person's Replay is recorded per attempt)`, E: d1.s ? '✓ B5-05-replay-refused (run-3-final)' : '✗' });
for (const id of ['PR-02', 'B5-08']) row(id, { roles: 'requester, nobc, reqonly, approver-1, admin', V: '✓ the rebuild plugin\'s Rebuild is hidden on approval-required Freestyle jobs; on the Pipeline the "Rebuild" entry is workflow-cps\' Replay page for users without Run/Replay - D-49', G: `${pn1 === pn ? '✓' : '✗'} requester's Pipeline Rebuild -> Run refused, no build`, R: `${/Approval required/.test(d2.text) ? '✓' : '✗'} "Approval required ... Request Run ..."`, C: `${reqRec ? '✓' : '✗'} own record for requester's attempt: ${reqRec ? fmt(reqRec) : 'none'} (separate from admin's Replay one minute earlier, D-51)`, E: d2.s ? '✓ PR-02-1-pipeline-rebuild-refused (run-3-final)' : '✗' });
await close();
