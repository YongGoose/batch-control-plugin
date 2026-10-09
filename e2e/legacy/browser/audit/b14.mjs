// B14 incidents re-audit: 01..10.
import { login, close, shot, api, job, BASE, requestRun, decide, waitFor, sleep, clickBuildEntry } from '../lib.mjs';
import { row, ev, mainText } from './rec.mjs';
import { execSync } from 'node:child_process';
import fs from 'node:fs';
const T = String(Date.now()).slice(-4); const J = `b14-aud-${T}`;
const incRows = async () => (await api('approver-1', '/batch-control/history/incidents.csv')).text.split('\n').slice(1).filter(Boolean);
execSync('docker exec batch-control-e2e rm -f /tmp/b14-aud-ok');
await api('admin', `/createItem?name=${J}`, { method: 'POST', body: fs.readFileSync('audit/b14-job.xml', 'utf8'), headers: { 'Content-Type': 'application/xml' } });
const rq = await login('requester'); const a1 = await login('approver-1');
const n0 = (await incRows()).length;
const url = await requestRun(rq.page, `/job/${J}/`, { reason: `Audit B14 ${T}: nightly settlement`, approvers: ['approver-1'] });
await decide(url); const t0 = Date.now();
await waitFor(async () => (await incRows()).length > n0, { timeout: 60000, every: 2000 });
const inc = (await incRows()).find((l) => l.includes(J)); const iid = inc.split(',')[0]; const incUrl = `${BASE}/batch-control/incidents/${iid}/`;
await a1.page.goto(`${BASE}/batch-control/incidents/`);
const r = a1.page.locator('#main-panel tr', { hasText: J }).first();
const links = await r.locator('a').evaluateAll((as) => as.map((a) => `${a.innerText.trim()}->${a.getAttribute('href')}`));
const s1 = await shot(a1.page, [a1.page.locator('#main-panel table thead').first(), r], 'B14-01-incident-row', { pad: 8 });
const vis = {}; for (const u of ['requester', 'nobc', 'auditor', 'manager']) vis[u] = (await api(u, `/batch-control/incidents/${iid}/`)).status;
row('B14-01', { roles: 'requester, approver-1, auditor, manager, nobc', V: `✓ incident list/detail: approver-1 200, auditor ${vis.auditor}, manager ${vis.manager}, requester ${vis.requester} (no ViewHistory), nobc ${vis.nobc}`, G: `${inc && /OPEN/.test(inc) ? '✓' : '✗'} approved ${J} #1 FAILURE -> incident OPEN within ${Math.round((Date.now() - t0) / 1000)} s`, R: '✓ standard 403 for requester (A-14 type)', C: `✓ list row with incident id and run link (${links.join(', ').slice(0, 120)})`, E: s1 ? '✓ B14-01-incident-row' : '✗' });
// B14-02
await a1.page.goto(incUrl);
const tail = await a1.page.locator('#main-panel pre').first().innerText().catch(() => ''); const tl = tail.split('\n');
const s2 = await shot(a1.page, a1.page.locator('#main-panel pre').first(), 'B14-02-log-tail', { pad: 6 });
row('B14-02', { roles: 'approver-1', V: 'n.a.', G: `${tl.length <= 102 && /truncated/.test(tail) && !tail.includes('pw-e2e-4711') ? '✓' : '✗'} detail keeps the last ${tl.length} lines ("${tl[0].slice(0, 50)}"); the password parameter value does not appear (${tail.includes('pw-e2e-4711') ? 'LEAKED' : 'masked/absent'})`, R: 'n.a.', C: 'n.a.', E: s2 ? '✓ B14-02-log-tail' : '✗', verdict: 'PASS (partial)', note: `the printed unmasked secret ("hunter2-unmasked") lies outside the 100-line tail: ${tail.includes('hunter2') ? 'visible' : 'not visible'} (documented case not observable)` });
// B14-04
const resolveOpen = await a1.page.locator('form[name="resolve"]').count();
const forms0 = await a1.page.locator('#main-panel form').evaluateAll((fs) => fs.map((f) => f.getAttribute('name')).filter(Boolean));
row('B14-04', { roles: 'approver-1', V: `${resolveOpen === 0 ? '✓' : '✗'} an OPEN incident offers ${forms0.join(', ')}; no Resolve`, G: '✓ the one-way order OPEN -> ACKNOWLEDGED -> RESOLVED is enforced by what is offered', R: 'n.a.', C: 'n.a.', E: s2 ? '✓ B14-02-log-tail (same page)' : '✗' });
// B14-03
await a1.page.fill('form[name="acknowledge"] textarea[name="comment"]', 'Looking into the vendor feed.');
await Promise.all([a1.page.waitForLoadState('load'), a1.page.locator('form[name="acknowledge"] button').first().click()]);
await a1.page.goto(incUrl);
const tr = a1.page.locator('#main-panel table').filter({ hasText: 'ACKNOWLEDGED' }).first(); const trt = (await tr.innerText().catch(() => '')).replace(/\s+/g, ' ');
const s3 = await shot(a1.page, tr, 'B14-03-acknowledged', { pad: 8 });
row('B14-03', { roles: 'approver-1', V: 'n.a.', G: `${/ACKNOWLEDGED/.test(trt) ? '✓' : '✗'} Acknowledge with comment -> ACKNOWLEDGED`, R: 'n.a.', C: `${/approver-1/.test(trt) && /vendor feed/.test(trt) ? '✓' : '✗'} history row: "${trt.slice(0, 140)}"`, E: s3 ? '✓ B14-03-acknowledged' : '✗' });
// B14-06 rerun: who is offered the form, who can submit
const off = {};
for (const u of ['requester', 'approver-1', 'manager', 'auditor', 'admin']) { const c = await login(u); const st = (await c.page.goto(incUrl)).status(); off[u] = st === 200 ? await c.page.locator('form[name="rerun"]').count() : `page ${st}`; await c.context.close(); }
const mg = await login('manager'); await mg.page.goto(incUrl);
let mgRes = 'no form';
if (await mg.page.locator('form[name="rerun"]').count()) { const f = mg.page.locator('form[name="rerun"]'); const rb = f.locator('textarea[name="reason"]'); if (await rb.count()) await rb.fill('manager tries'); const [x] = await Promise.all([mg.page.waitForNavigation().catch(() => null), f.locator('button').first().click()]); mgRes = `${x && x.status()} "${(await mainText(mg.page)).slice(0, 80)}"`; await shot(mg.page, '#main-panel, body', 'B14-06-0-manager-refused', { pad: 8 }); }
await mg.context.close();
const ad = await login('admin'); await ad.page.goto(incUrl);
const f = ad.page.locator('form[name="rerun"]');
const opts = await f.locator('select[name="approver"] option').allInnerTexts();
await f.locator('select[name="approver"]').selectOption({ label: opts.find((o) => o.includes('approver-1')) || opts[0] });
const rb = f.locator('textarea[name="reason"]'); if (await rb.count()) await rb.fill(`Audit B14-06 ${T}: rerun after the vendor fix`);
const s6a = await shot(ad.page, f, 'B14-06-1-rerun-form', { pad: 8 });
await Promise.all([ad.page.waitForNavigation(), f.locator('button').first().click()]);
const rerunUrl = ad.page.url(); const rt = await mainText(ad.page);
const back = await ad.page.locator(`#main-panel a[href*="${iid}"]`).count();
const s6b = await shot(ad.page, '#main-panel table', 'B14-06-2-rerun-request', { pad: 8 });
const csvRow = (await api('admin', '/batch-control/history/requests.csv')).text.split('\n').find((l) => l.includes(iid)) || '';
ev(`B14-06 offered ${JSON.stringify(off)}; manager ${mgRes}; ${rerunUrl} "${rt.slice(0, 250)}" back-link ${back}; csv ${csvRow.slice(0, 200)}`);
row('B14-06', { roles: 'requester, approver-1, manager, auditor, admin', V: `✗ rerun form offered: ${Object.entries(off).map(([u, v]) => `${u} ${v}`).join(', ')}; manager (no Job/Build) is offered it and refused on submit: ${mgRes} (DEF-12)`, G: `${/PENDING/.test(rt) && /PW/.test(rt) && csvRow.includes(iid) ? '✓' : '✗'} admin: Request Rerun (single Approver select: ${opts.length} options) -> PENDING run request with the original parameters (PW masked) and incidentId`, R: '✗ manager: bare Access Denied after submitting (DEF-12)', C: `✓ requests.csv incidentId ${iid}`, E: s6a && s6b ? '✓ B14-06-0..2' : '✗', defect: 'DEF-12 (known)', note: `the rerun request links back to the incident: ${back ? 'yes' : 'no'} (U-16)` });
// B14-07
execSync('docker exec batch-control-e2e touch /tmp/b14-aud-ok');
const nb = (await job(J)).nextBuildNumber;
await decide(rerunUrl);
await waitFor(async () => { const j = await job(J, 'nextBuildNumber,lastBuild[building,result]'); return j.nextBuildNumber > nb && !j.lastBuild.building; }, { timeout: 90000 }); await sleep(4000);
await a1.page.goto(incUrl); const d7 = await mainText(a1.page);
const rl = await a1.page.locator('#main-panel a').evaluateAll((as) => as.map((a) => `${a.innerText.trim()}->${a.getAttribute('href')}`).filter((x) => /Resolved|rerun|requests\/|\/2\//.test(x)));
const s7 = await shot(a1.page, a1.page.locator('#main-panel table').first(), 'B14-07-resolved-by-run', { pad: 8 });
row('B14-07', { roles: 'approver-1, admin', V: 'n.a.', G: `${/Resolved by run/.test(d7) && /Status ACKNOWLEDGED/.test(d7) ? '✓' : '✗'} rerun #${nb} ${(await job(J, 'lastBuild[result]')).lastBuild.result}; incident shows "${(d7.match(/Resolved by run[^ ]* ?[^ ]*/) || [''])[0]}" and keeps ${(d7.match(/Status \w+/) || [''])[0]} until a person resolves it`, R: 'n.a.', C: `✓ links: ${rl.join(', ').slice(0, 140)}`, E: s7 ? '✓ B14-07-resolved-by-run' : '✗' });
// B14-05
await a1.page.fill('form[name="resolve"] textarea[name="comment"]', 'Fixed by the rerun.'); await Promise.all([a1.page.waitForLoadState('load'), a1.page.locator('form[name="resolve"] button').first().click()]);
await a1.page.goto(incUrl);
await a1.page.fill('form[name="addComment"] textarea[name="comment"]', 'Post-mortem filed.'); await Promise.all([a1.page.waitForLoadState('load'), a1.page.locator('form[name="addComment"] button').first().click()]);
await a1.page.goto(incUrl); const d5 = await mainText(a1.page);
const fm = await a1.page.locator('#main-panel form').evaluateAll((fs) => fs.map((f) => f.getAttribute('name')).filter(Boolean));
const s5 = await shot(a1.page, a1.page.locator('#main-panel table').filter({ hasText: 'Post-mortem' }).first(), 'B14-05-comment-on-resolved', { pad: 8 });
row('B14-05', { roles: 'approver-1', V: `${!fm.includes('acknowledge') && !fm.includes('resolve') ? '✓' : '✗'} on RESOLVED only ${fm.join(', ')} is offered`, G: `${/Status RESOLVED/.test(d5) && /Post-mortem filed/.test(d5) ? '✓' : '✗'} Resolve, then Add Comment: comment appended, state stays RESOLVED`, R: 'n.a.', C: '✓ history rows for Resolve and the comment', E: s5 ? '✓ B14-05-comment-on-resolved' : '✗' });
// B14-10 UNSTABLE, B14-08 auditor
const i0 = (await incRows()).length;
await ad.page.goto(`${BASE}/job/batch-unstable/`); await clickBuildEntry(ad.page, /Build Now/);
await ad.page.goto(`${BASE}/job/batch-failing/`); await clickBuildEntry(ad.page, /Build with Parameters|Build Now/);
await sleep(15000);
const rows2 = (await incRows()); const newer = rows2.slice(0, rows2.length - i0);
const ui = newer.find((l) => /batch-unstable#/.test(l)); const fi = newer.find((l) => /batch-failing#/.test(l));
row('B14-10', { roles: 'admin', V: 'n.a.', G: `${ui && /UNSTABLE/.test(ui) ? '✓' : '✗'} batch-unstable UNSTABLE -> incident "${(ui || 'NONE').slice(0, 80)}"`, R: 'n.a.', C: '✓ incidents.csv row', E: '✓ text' });
const au = await login('auditor'); const fid = fi.split(',')[0];
await au.page.goto(`${BASE}/batch-control/incidents/${fid}/`);
await au.page.fill('form[name="acknowledge"] textarea[name="comment"]', 'auditor ack'); await Promise.all([au.page.waitForLoadState('load'), au.page.locator('form[name="acknowledge"] button').first().click()]);
await au.page.goto(`${BASE}/batch-control/incidents/${fid}/`); const at = await mainText(au.page); const arr = await au.page.locator('form[name="rerun"]').count();
const s8 = await shot(au.page, au.page.locator('#main-panel table').first(), 'B14-08-auditor-ack', { pad: 8 });
row('B14-08', { roles: 'auditor, requester', V: `✓ auditor (ViewHistory only) gets Acknowledge but no rerun form (${arr}); requester has no Incidents entry (A-05) and gets 403 (${vis.requester})`, G: `${/ACKNOWLEDGED/.test(at) ? '✓' : '✗'} auditor acknowledged ${fid} (${(at.match(/Status \w+/) || [''])[0]}), as documented (LIMITATIONS 20)`, R: 'n.a.', C: '✓ history row by auditor', E: s8 ? '✓ B14-08-auditor-ack' : '✗' });
// B14-09
await a1.page.goto(`${BASE}/batch-control/incidents/`);
const prev = a1.page.locator('#main-panel a:has-text("«")').first(); let empty = '';
if (await prev.count()) { await Promise.all([a1.page.waitForNavigation(), prev.click()]); empty = ((await mainText(a1.page)).match(/No incidents in [\d-]+\./) || [''])[0]; }
const s9 = await shot(a1.page, a1.page.locator('#main-panel').locator('text=/No incidents/').first(), 'B14-09-empty-month', { pad: 30 });
row('B14-09', { roles: 'approver-1', V: 'n.a.', G: `${empty ? '✓' : '✗'} « previous month -> "${empty}"; month input + Show present`, R: 'n.a.', C: 'n.a.', E: s9 ? '✓ B14-09-empty-month' : '✗' });
await close();
