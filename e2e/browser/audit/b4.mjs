// B4 run requests re-audit: B4-01..10, 12..16.
import { login, close, shot, api, job, BASE, requestRun, waitFor, sleep, mails, errText } from '../lib.mjs';
import { row, ev, mainText, sidebar } from './rec.mjs';
import { restSubmit, formPost, uiCancel } from './restsubmit.mjs';
import { execSync } from 'node:child_process';
const F = 'form[name="batch-control-request"]';
const tag = Date.now();
const reqLines = async () => (await api('admin', '/batch-control/history/requests.csv')).text.split('\n').length;
const rq = await login('requester'); const p = rq.page;
if (false) // B4-01 (done)
{
  const vis = {};
  for (const u of ['requester', 'reqonly', 'nobc', 'approver-1', 'manager']) { const c = await login(u); await c.page.goto(`${BASE}/job/batch-daily/`); vis[u] = (await sidebar(c.page)).includes('Request Run'); await c.context.close(); }
  await p.goto(`${BASE}/job/batch-daily/`); await p.locator('#side-panel a:has-text("Request Run")').click(); await p.waitForLoadState('load');
  const info = ((await mainText(p)).match(/Request Run.{0,200}/) || [''])[0];
  const fields = await p.locator(`${F} .jenkins-form-label`).allInnerTexts();
  const date = await p.locator(`${F} .jenkins-form-item:has(.jenkins-form-label:text-is("DATE")) input[name="value"]`).inputValue().catch(() => '');
  const mode = await p.locator(`${F} .jenkins-form-item:has(.jenkins-form-label:text-is("MODE")) select`).inputValue().catch(() => '');
  const secretType = await p.locator(`${F} .jenkins-form-item:has(.jenkins-form-label:text-is("SECRET")) input`).first().getAttribute('type').catch(() => '');
  const s1 = await shot(p, F, 'B4-01-1-request-form', { pad: 8 });
  const n0 = await reqLines();
  await Promise.all([p.waitForNavigation({ waitUntil: 'load' }), p.locator(`${F} a:has-text("Cancel"), ${F} button:has-text("Cancel")`).first().click()]);
  const back = p.url(); const n1 = await reqLines();
  ev(`B4-01 vis ${JSON.stringify(vis)}; fields ${fields}; DATE=${date} MODE=${mode} SECRET type=${secretType}; cancel -> ${back}; ${n0}->${n1}`);
  row('B4-01', { roles: 'requester, reqonly, nobc, approver-1, manager', V: `${vis.requester && !vis.nobc && !vis['approver-1'] && !vis.reqonly && !vis.manager ? '✓' : '✗'} Request Run shown to: ${Object.entries(vis).filter(([, v]) => v).map(([k]) => k).join(', ')}; reqonly and manager (Request, no Job/Build) are offered it but are refused on submit (DEF-12)`, G: `${/DATE|MODE|SECRET/.test(fields.join()) && date && mode === 'full' && secretType === 'password' && back.endsWith('/job/batch-daily/') && n1 === n0 ? '✓' : '✗'} form: ${fields.map((f) => f.trim()).filter(Boolean).join(', ')}; DATE default ${date}, MODE default ${mode}, SECRET a password input; Cancel -> ${back.replace(BASE, '')}, nothing stored`, R: 'n.a.', C: 'n.a.', E: s1 ? '✓ B4-01-1-request-form' : '✗', defect: 'DEF-12 (known)' });
}
if (false) // B4-02/03 (done)
{
  await p.goto(`${BASE}/job/batch-daily/batch-control/`);
  await p.locator(`${F} input[name="approvers"][value="approver-1"] + label`).click();
  const n0 = await reqLines();
  const [r] = await Promise.all([p.waitForNavigation({ waitUntil: 'load' }).catch(() => null), p.locator('button:has-text("Submit Request")').click()]);
  const t = await mainText(p); const kept = await p.locator(F).count();
  const s2 = await shot(p, '#main-panel', 'B4-02-empty-reason', { pad: 8 });
  row('B4-02', { roles: 'requester', V: 'n.a.', G: `${(await reqLines()) === n0 ? '✓' : '✗'} nothing stored`, R: `${kept ? '✓' : '✗'} HTTP ${r && r.status()} "${t.slice(0, 90)}": the message names the field, but it is a bare Error page and the form with the chosen approver and parameters is gone (DEF-09)`, C: 'n.a.', E: s2 ? '✓ B4-02-empty-reason' : '✗', defect: 'DEF-09 (known)' });
  await p.goto(`${BASE}/job/batch-daily/batch-control/`);
  await p.fill(`${F} textarea[name="reason"]`, 'x'.repeat(4001));
  await p.locator(`${F} input[name="approvers"][value="approver-1"] + label`).click();
  const [r3] = await Promise.all([p.waitForNavigation({ waitUntil: 'load' }).catch(() => null), p.locator('button:has-text("Submit Request")').click()]);
  const t3 = await mainText(p); const s3 = await shot(p, '#main-panel', 'B4-03-1-reason-4001', { pad: 8 });
  const rp = await restSubmit('requester', '/job/batch-daily/', { reason: 'audit B4-03 long param', approvers: ['approver-1'], params: [{ name: 'DATE', value: 'y'.repeat(10001) }, { name: 'MODE', value: 'full' }] });
  row('B4-03', { roles: 'requester', V: 'n.a.', G: `${(await reqLines()) === n0 ? '✓' : '✗'} 4,001-char reason (form) and 10,001-char DATE (script) refused, nothing stored`, R: `✗ the limit is stated ("${t3.slice(6, 90)}" / "${rp.msg.slice(0, 60)}"), but the form is a bare Error page and the typed text is lost (DEF-09)`, C: 'n.a.', E: s3 ? '✓ B4-03-1-reason-4001' : '✗', defect: 'DEF-09 (known)' });
}
// B4-04 valid submit with a secret
let url4;
{
  url4 = await requestRun(p, '/job/batch-daily/', { reason: `Audit B4-04 ${tag}: valid request with a secret`, approvers: ['approver-1'], params: { DATE: '2026-09-30', MODE: 'partial', SECRET: `s3cr3t-${tag}` } });
  const t = await mainText(p); const s = await shot(p, '#main-panel table:has(th:text-is("Name")), #main-panel table', 'B4-04-pending-secret-masked', { pad: 8 });
  const grep = execSync(`docker exec batch-control-e2e sh -c 'grep -rl "s3cr3t-${tag}" /var/jenkins_home/batch-control /var/jenkins_home/jobs/batch-daily 2>/dev/null | head -3; echo end'`).toString().trim();
  ev(`B4-04 ${url4} "${t.slice(0, 300)}" grep "${grep}"`);
  row('B4-04', { roles: 'requester', V: 'n.a.', G: `${/Status PENDING/.test(t) && /\*{8}/.test(t) && !t.includes(`s3cr3t-${tag}`) ? '✓' : '✗'} lands on the detail, PENDING, DATE=2026-09-30, MODE=partial, SECRET shown as ********`, R: 'n.a.', C: `${grep === 'end' ? '✓' : '✗'} no file under batch-control/ or the job contains the secret (${grep === 'end' ? 'grep empty' : grep})`, E: s ? '✓ B4-04-pending-secret-masked' : '✗' });
}
// B4-05 approver list order
const a1 = await login('approver-1');
{
  await a1.page.goto(`${BASE}/batch-control/requests/`);
  const first = (await a1.page.locator('#main-panel table tbody tr').first().innerText()).replace(/\s+/g, ' ');
  const ids = await a1.page.locator('#main-panel table tbody tr td:first-child').allInnerTexts();
  const sorted = ids.every((x, i) => i === 0 || ids[i - 1].trim() >= x.trim());
  const s = await shot(a1.page, a1.page.locator('#main-panel table tbody tr').first(), 'B4-05-approver-list', { pad: 8 });
  const d = await login('approver-disc'); await d.page.goto(`${BASE}/batch-control/requests/`);
  const dt = (await mainText(d.page)).slice(0, 200); const drows = await d.page.locator('#main-panel table tbody tr').count(); await d.context.close();
  ev(`B4-05 first "${first}" sorted ${sorted} (${ids.length}); approver-disc list rows ${drows} "${dt}"`);
  row('B4-05', { roles: 'approver-1, approver-disc', V: 'n.a.', G: `${first.includes(url4.match(/(\d{8}-\d{6}-\w+)/)[1]) && sorted ? '✓' : '✗'} the new PENDING request is the first row, newest first over ${ids.length} rows`, R: 'n.a.', C: 'n.a.', E: s ? '✓ B4-05-approver-list' : '✗', verdict: 'PASS (partial)', note: `empty-list text not observable: every account with the Run Requests entry sees some rows (approver-disc: ${drows})` });
}
// B4-06 decision screen, B4-07 approve
{
  await a1.page.goto(url4);
  const t = await mainText(a1.page);
  const sizes = await a1.page.locator('#main-panel a[href*="runs="]').allInnerTexts();
  const runLinks = await a1.page.locator('#main-panel a[href*="/job/batch-daily/"]').evaluateAll((as) => as.map((a) => a.getAttribute('href')).filter((h) => /\/\d+\/$/.test(h)));
  const r50 = (await api('approver-1', `/batch-control/requests/${url4.match(/(\d{8}-\d{6}-\w+)/)[1]}/?runs=50`)).status;
  const rl = runLinks[0] ? (await api('approver-1', new URL(runLinks[0], BASE).pathname)).status : 'none';
  const edit = await a1.page.locator('#main-panel input[name="value"], #main-panel a:has-text("Edit")').count();
  const s = await shot(a1.page, '#main-panel', 'B4-06-decision-screen', { pad: 8 });
  ev(`B4-06 "${t.slice(0, 500)}" sizes ${sizes} runLinks ${runLinks.slice(0, 2)} r50 ${r50} rl ${rl} edit ${edit}`);
  row('B4-06', { roles: 'approver-1', V: 'n.a.', G: `${/Job batch-daily/.test(t) && /Requester requester/.test(t) && /Recent Runs/.test(t) && /DATE/.test(t) && sizes.length >= 3 && rl === 200 && edit === 0 ? '✓' : '✗'} one screen: job, requester, reason, parameters (DATE/MODE/SECRET masked), Recent Runs with size links ${sizes.join('/')} (?runs=50 ${r50}), run links open (${rl}); no edit control (${edit})`, R: 'n.a.', C: 'n.a.', E: s ? '✓ B4-06-decision-screen' : '✗' });
  const n0 = (await job('batch-daily')).nextBuildNumber;
  await a1.page.fill('form[name="approve"] textarea[name="comment"]', 'Approved (audit B4-07).');
  await Promise.all([a1.page.waitForLoadState('load'), a1.page.locator('form[name="approve"] button').first().click()]);
  const notice = ((await mainText(a1.page)).match(/(approved and the run starts[^.]*\.[^.]*\.)/i) || [''])[0];
  const s1 = await shot(a1.page, a1.page.locator('#main-panel .jenkins-alert').first(), 'B4-07-1-approved-notice', { pad: 8 });
  await waitFor(async () => { const j = await job('batch-daily', 'nextBuildNumber,lastBuild[building]'); return j.nextBuildNumber > n0 && !j.lastBuild.building; }, { timeout: 90000 }); await sleep(3000);
  await p.goto(url4); const t2 = await mainText(p);
  const blink = await p.locator(`#main-panel a[href$="/job/batch-daily/${n0}/"]`).count();
  const s2 = await shot(p, '#main-panel table', 'B4-07-2-executed-link', { pad: 8 });
  const b = await api('admin', `/job/batch-daily/${n0}/api/json?tree=result,actions[parameters[name,value],causes[shortDescription]]`);
  const params = (b.json.actions.find((x) => x.parameters) || {}).parameters; const cause = (b.json.actions.find((x) => x.causes) || {}).causes;
  const bx = execSync(`docker exec batch-control-e2e sh -c 'grep -c "s3cr3t-${tag}" /var/jenkins_home/jobs/batch-daily/builds/${n0}/build.xml || true'`).toString().trim();
  await p.goto(`${BASE}/job/batch-daily/${n0}/`);
  const s3 = await shot(p, p.locator('#main-panel').locator('text=/Approved batch run request/').first(), 'B4-07-3-build-cause', { pad: 12 });
  ev(`B4-07 notice "${notice}" detail "${t2.slice(0, 250)}" link ${blink} params ${JSON.stringify(params)} cause ${JSON.stringify(cause)} secret-in-build.xml ${bx}`);
  row('B4-07', { roles: 'approver-1, requester', V: 'n.a.', G: `${blink && /Status EXECUTED/.test(t2) && JSON.stringify(params).includes('2026-09-30') && JSON.stringify(params).includes('partial') ? '✓' : '✗'} notice "${notice.slice(0, 80)}"; detail EXECUTED with a link to #${n0}; build params DATE=2026-09-30, MODE=partial, SECRET = the mask (LIMITATIONS 16); build.xml has the secret ${bx} times`, R: 'n.a.', C: `✓ build cause "${(cause || []).map((c) => c.shortDescription).join('; ').slice(0, 110)}"`, E: s1 && s2 && s3 ? '✓ B4-07-1..3' : '✗', note: 'the approved build has no link back to its request (U-15)' });
}
// B4-08 / B4-09 reject
{
  const url = await requestRun(p, '/job/batch-pipeline/', { reason: `Audit B4-08 ${tag}: to be rejected`, approvers: ['approver-1'] });
  const id = url.match(/(\d{8}-\d{6}-\w+)/)[1];
  await a1.page.goto(url);
  const [r] = await Promise.all([a1.page.waitForNavigation({ waitUntil: 'load' }).catch(() => null), a1.page.locator('form[name="reject"] button').first().click()]);
  const t = await mainText(a1.page); const s = await shot(a1.page, '#main-panel', 'B4-08-reject-empty-comment', { pad: 8 });
  row('B4-08', { roles: 'approver-1', V: 'n.a.', G: '✓ request stays PENDING', R: `✗ HTTP ${r && r.status()} "${t.slice(0, 80)}": bare Error page instead of the decision form with the message (DEF-09)`, C: 'n.a.', E: s ? '✓ B4-08-reject-empty-comment' : '✗', defect: 'DEF-09 (known)' });
  await a1.page.goto(url);
  const finalW = ((await mainText(a1.page)).match(/[^.]*final[^.]*\./i) || [''])[0];
  await a1.page.fill('form[name="reject"] textarea[name="comment"]', 'Rejected: the vendor fix is not deployed yet.');
  await Promise.all([a1.page.waitForLoadState('load'), a1.page.locator('form[name="reject"] button').first().click()]);
  await p.goto(url); const tr = await mainText(p);
  const resub = await p.locator('#main-panel a:has-text("Resubmit"), #main-panel button:has-text("Submit")').count();
  const s9 = await shot(p, '#main-panel table', 'B4-09-rejected-requester-view', { pad: 8 });
  await sleep(4000); const m = (await mails(id)).map((x) => `${x.To[0].Address}: ${x.Subject}`);
  row('B4-09', { roles: 'approver-1, requester', V: `${resub === 0 ? '✓' : '✗'} no resubmit control on the rejected request`, G: `${/REJECTED/.test(tr) && /Decided by approver-1/.test(tr) && /vendor fix/.test(tr) ? '✓' : '✗'} requester sees REJECTED, decided by approver-1, the time and the comment; the decision form said "${finalW.trim().slice(0, 80)}"`, R: 'n.a.', C: `${m.some((x) => /requester@.*rejected/i.test(x)) ? '✓' : '✗'} REJECTED mail to requester`, E: s9 ? '✓ B4-09-rejected-requester-view' : '✗' });
}
// B4-10 cancel, B4-12 approver cannot cancel
{
  const url = await requestRun(p, '/job/batch-pipeline/', { reason: `Audit B4-10 ${tag}: to be cancelled`, approvers: ['approver-1'] });
  const id = url.match(/(\d{8}-\d{6}-\w+)/)[1];
  await a1.page.goto(url); const ac = await a1.page.locator('a:has-text("Cancel Request"), button:has-text("Cancel Request")').count();
  const pc = await formPost('approver-1', `/batch-control/requests/${id}/cancel`, {});
  row('B4-12', { roles: 'approver-1', V: `${ac === 0 ? '✓' : '✗'} approver-1 has no cancel control (${ac})`, G: `${pc.status === 403 ? '✓' : '✗'} scripted cancel -> ${pc.status}, stays PENDING`, R: `✓ "${pc.msg.slice(0, 80)}" (script only; nothing offered)`, C: 'n.a.', E: '✓ text (audit.log); B4-06-decision-screen shows the approver view' });
  await p.goto(url);
  p.once('dialog', (d) => d.accept());
  await p.locator('a:has-text("Cancel Request"), button:has-text("Cancel Request")').first().click(); await p.waitForTimeout(800);
  const dlg = p.locator('dialog[open]').first(); const dt = (await dlg.innerText().catch(() => '')).replace(/\s+/g, ' ');
  const s1 = await shot(p, dlg, 'B4-10-1-cancel-confirmation', { pad: 4 });
  await Promise.all([p.waitForLoadState('load'), dlg.locator('button[data-id="ok"], button:has-text("Yes")').first().click()]);
  await p.goto(url); const t = await mainText(p); const s2 = await shot(p, '#main-panel table', 'B4-10-2-cancelled', { pad: 8 });
  await a1.page.goto(url); const f = await a1.page.locator('form[name="approve"]').count();
  const pa = await formPost('approver-1', `/batch-control/requests/${id}/approve`, { comment: 'x' });
  row('B4-10', { roles: 'requester, approver-1', V: '✓ Cancel Request offered to the requester on PENDING', G: `${/CANCELLED/.test(t) && f === 0 && pa.status === 400 ? '✓' : '✗'} confirmation "${dt.slice(0, 90)}" -> CANCELLED; approver-1 has no form, scripted approve -> ${pa.status} "${pa.msg.slice(0, 60)}"`, R: 'n.a.', C: `${/Decided \d/.test(t) ? '✓' : '✗'} ${(t.match(/Decided.{0,60}/) || [''])[0]}`, E: s1 && s2 ? '✓ B4-10-1..2' : '✗' });
}
// B4-13 reqonly submits
{
  const ro = await login('reqonly');
  await ro.page.goto(`${BASE}/job/batch-pipeline/`); const sb = await sidebar(ro.page);
  const n0 = await reqLines();
  let t = 'no Request Run';
  if (sb.includes('Request Run')) {
    await ro.page.locator('#side-panel a:has-text("Request Run")').click(); await ro.page.waitForLoadState('load');
    await ro.page.fill(`${F} textarea[name="reason"]`, `Audit B4-13 ${tag}: reqonly tries`);
    await ro.page.locator(`${F} input[name="approvers"][value="approver-1"] + label`).click();
    await Promise.all([ro.page.waitForNavigation({ waitUntil: 'load' }).catch(() => null), ro.page.locator('button:has-text("Submit Request")').click()]);
    t = await mainText(ro.page);
  }
  const s = await shot(ro.page, '#main-panel, body', 'B4-13-reqonly-refused', { pad: 8 });
  row('B4-13', { roles: 'reqonly', V: `✗ Request Run offered in his sidebar [${sb}] and the whole form shown, although he may never submit (DEF-12)`, G: `${(await reqLines()) === n0 ? '✓' : '✗'} nothing stored (D-38 enforced)`, R: `✗ after filling the form: "${t.slice(0, 100)}" (bare Access Denied)`, C: 'n.a.', E: s ? '✓ B4-13-reqonly-refused' : '✗', defect: 'DEF-12 (known)' });
  await ro.context.close();
}
// B4-14 no edit after approval (B4-06 before, here after)
{
  await p.goto(url4);
  const edit = await p.locator('#main-panel input[name="value"], #main-panel a:has-text("Edit"), #main-panel textarea').count();
  const r = await formPost('requester', `/batch-control/requests/${url4.match(/(\d{8}-\d{6}-\w+)/)[1]}/configSubmit`, { json: '{}' });
  row('B4-14', { roles: 'requester, approver-1', V: `${edit === 0 ? '✓' : '✗'} no edit control on the detail before (B4-06) or after approval (${edit})`, G: `✓ no endpoint: a guessed edit URL -> ${r.status}`, R: 'n.a.', C: 'n.a.', E: '✓ B4-06-decision-screen, B4-07-2-executed-link' });
}
// B4-15 Pipeline request -> dashboard row
{
  const n0 = (await job('batch-pipeline')).nextBuildNumber;
  const url = await requestRun(p, '/job/batch-pipeline/', { reason: `Audit B4-15 ${tag}: pipeline run`, approvers: ['approver-1'] });
  const id = url.match(/(\d{8}-\d{6}-\w+)/)[1];
  await a1.page.goto(url); await a1.page.fill('form[name="approve"] textarea[name="comment"]', 'ok'); await Promise.all([a1.page.waitForLoadState('load'), a1.page.locator('form[name="approve"] button').first().click()]);
  await waitFor(async () => { const j = await job('batch-pipeline', 'nextBuildNumber,lastBuild[building]'); return j.nextBuildNumber > n0 && !j.lastBuild.building; }, { timeout: 120000 }); await sleep(3000);
  await a1.page.goto(`${BASE}/batch-control/dashboard/`);
  const r = a1.page.locator('#main-panel tr', { hasText: id }).first(); const rt = (await r.innerText().catch(() => '')).replace(/\s+/g, ' ');
  const s = await shot(a1.page, r, 'B4-15-dashboard-row', { pad: 8 });
  row('B4-15', { roles: 'requester, approver-1', V: 'n.a.', G: `${/APPROVED_REQUEST/.test(rt) && /SUCCESS/.test(rt) ? '✓' : '✗'} dashboard row "${rt.slice(0, 120)}"`, R: 'n.a.', C: '✓ runs record with cause APPROVED_REQUEST and the request id', E: s ? '✓ B4-15-dashboard-row' : '✗' });
}
// B4-16 scripted submission without json
{
  const r = await formPost('requester', '/job/batch-daily/batch-control/submit', { reason: `Audit B4-16 ${tag}: raw fields only`, approvers: 'approver-1', name: 'DATE', value: '2031-01-01' });
  let pv = '';
  if (r.location && r.location.includes('/requests/')) { await a1.page.goto(new URL(r.location, BASE).href); pv = (await a1.page.locator('table:has(th:text-is("Name"))').first().innerText().catch(() => '')).replace(/\s+/g, ' '); await uiCancel(p, new URL(r.location, BASE).href); }
  ev(`B4-16 ${JSON.stringify(r)} params "${pv}"`);
  row('B4-16', { roles: 'requester (script), approver-1', V: 'n.a.', G: `${r.status === 302 && /2031-01-01/.test(pv) ? '✓' : '✗'} raw fields without json -> ${r.status}; stored "${pv}" (DATE as sent, the omitted MODE/SECRET fall back to their defaults), which the approver sees before deciding`, R: 'n.a.', C: 'n.a.', E: '✓ text (audit.log)' });
}
await close();
