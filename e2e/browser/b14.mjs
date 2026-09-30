// B14 incidents.
import { login, close, shot, api, job, BASE, log, requestRun, decide, waitFor, sleep, clickBuildEntry, errText } from './lib.mjs';
import { execSync } from 'node:child_process';
const L = 'section-b.log';
const incRows = async () => (await api('approver-1', '/batch-control/history/incidents.csv')).text.split('\n').slice(1).filter(Boolean);
execSync(`docker exec batch-control-e2e rm -f /tmp/b14-ok`);
if ((await api('admin', '/job/b14-flaky/api/json')).status === 404) {
  const xml = `<?xml version='1.1' encoding='UTF-8'?><project><description>B14: fails until /tmp/b14-ok exists</description><properties><hudson.model.ParametersDefinitionProperty><parameterDefinitions><hudson.model.PasswordParameterDefinition><name>PW</name><defaultValue>pw-e2e-4711</defaultValue></hudson.model.PasswordParameterDefinition></parameterDefinitions></hudson.model.ParametersDefinitionProperty></properties><builders><hudson.tasks.Shell><command>set +x
echo "token=$PW"
echo "other secret: hunter2-unmasked"
for i in $(seq 1 120); do echo "line $i"; done
[ -f /tmp/b14-ok ]</command></hudson.tasks.Shell></builders></project>`;
  await api('admin', '/createItem?name=b14-flaky', { method: 'POST', body: xml, headers: { 'Content-Type': 'application/xml' } });
}
const rq = await login('requester');
const a1 = await login('approver-1');
// B14-01 an approved run fails -> incident
const n0 = (await incRows()).length;
const existing = (await incRows()).find((l) => l.includes(',b14-flaky#1,'));
const url = existing ? null : await requestRun(rq.page, '/job/b14-flaky/', { reason: 'Nightly settlement for the 29th (B14).', approvers: ['approver-1'] });
if (url) await decide(url);
const t0 = Date.now();
const got = await waitFor(async () => (await incRows()).length > n0, { timeout: 60000, every: 2000 });
const inc = (await incRows()).find((l) => l.includes('b14-flaky'));
log(L, `B14-01 b14-flaky FAILURE -> incident within ${Math.round((Date.now() - t0) / 1000)} s: ${inc}`);
await a1.page.goto(`${BASE}/batch-control/incidents/`);
const row = a1.page.locator('#main-panel tr:has-text("b14-flaky")').first();
const rlinks = await row.locator('a').evaluateAll((as) => as.map((a) => a.innerText.trim() + '->' + a.getAttribute('href')));
await shot(a1.page, [a1.page.locator('#main-panel table thead').first(), row], 'B14-01', { pad: 8 });
log(L, `B14-01 list row "${(await row.innerText()).replace(/\s+/g, ' ')}" links ${JSON.stringify(rlinks)}`);
const incUrl = `${BASE}/batch-control/incidents/${inc.split(',')[0]}/`;
// B14-02 detail and log tail
await a1.page.goto(incUrl);
const detail = (await a1.page.locator('#main-panel').innerText());
const tail = (await a1.page.locator('#main-panel pre').first().innerText().catch(() => ''));
const tl = tail.split('\n');
await shot(a1.page, '#main-panel', 'B14-02', { pad: 6 });
log(L, `B14-02 log tail ${tl.length} lines, first "${tl[0]}", last "${tl[tl.length - 1]}"; contains pw-e2e-4711: ${tail.includes('pw-e2e-4711')}; token line "${tl.find((l) => l.startsWith('token=')) || 'not in tail'}"; hunter2 verbatim: ${tail.includes('hunter2-unmasked')}; controls ${JSON.stringify(await a1.page.locator('#main-panel form button, #main-panel input[type=submit]').allInnerTexts())}`);
// B14-04 (resolve directly from OPEN) on this incident first
await a1.page.goto(incUrl);
const resForm = await a1.page.locator('form[name="resolve"]').count();
let r4 = 'no resolve form while OPEN';
if (resForm) { await a1.page.fill('form[name="resolve"] textarea[name="comment"]', 'resolve straight away?'); const [r] = await Promise.all([a1.page.waitForNavigation().catch(() => null), a1.page.locator('form[name="resolve"] button').first().click()]); r4 = `${r && r.status()} "${errText(await a1.page.content())}"`; }
log(L, `B14-04 Resolve from OPEN: ${r4}`);
// B14-03 acknowledge
await a1.page.goto(incUrl);
await a1.page.fill('form[name="acknowledge"] textarea[name="comment"]', 'Looking into the vendor feed.');
await Promise.all([a1.page.waitForLoadState('load'), a1.page.locator('form[name="acknowledge"] button').first().click()]);
await a1.page.goto(incUrl);
const trans = (await a1.page.locator('#main-panel table').allInnerTexts()).map((t) => t.replace(/\s+/g, ' ')).find((t) => /ACKNOWLEDGED/.test(t));
await shot(a1.page, a1.page.locator('#main-panel table').filter({ hasText: 'ACKNOWLEDGED' }).first(), 'B14-03', { pad: 8 });
log(L, `B14-03 after Acknowledge: "${(trans || '').slice(0, 300)}"`);
// B14-06 rerun request
await rq.page.goto(incUrl);
const rr = rq.page.locator('form[name="rerun"]');
const rrCount = await rr.count();
log(L, `B14-06 requester on the incident: status ${(await rq.page.goto(incUrl)).status()} rerun form ${rrCount}`);
let rerunUrl = null;
for (const who of rrCount ? [rq] : [a1]) {
  const f = who.page.locator('form[name="rerun"]');
  if (!(await f.count())) continue;
  const opts = await f.locator('select[name="approver"] option').allInnerTexts();
  await f.locator('select[name="approver"]').selectOption({ label: opts.find((o) => o.includes('approver-1')) || opts[0] });
  const reasonBox = f.locator('textarea[name="reason"]'); if (await reasonBox.count()) await reasonBox.fill('Rerun after the vendor fix (B14-06).');
  await shot(who.page, f, 'B14-06-1-rerun-form', { pad: 8 });
  await Promise.all([who.page.waitForNavigation(), f.locator('button').first().click()]);
  rerunUrl = who.page.url();
  const params = (await who.page.locator('table:has(th:text-is("Name"))').first().innerText().catch(() => '')).replace(/\s+/g, ' ');
  const links = await who.page.locator('#main-panel table a').evaluateAll((as) => as.map((a) => a.innerText.trim() + '->' + a.getAttribute('href')));
  await shot(who.page, '#main-panel table', 'B14-06-2-rerun-request', { pad: 8 });
  log(L, `B14-06 rerun approver options ${JSON.stringify(opts)}; -> ${rerunUrl.replace(BASE, '')} params "${params}" links ${JSON.stringify(links)}; incidentId in requests.csv: ${(await api('admin', '/batch-control/history/requests.csv')).text.split('\n').find((l) => l.startsWith(rerunUrl.split('/requests/')[1]?.replace('/', '')))?.split(',').slice(-3, -2)}`);
}
// B14-07 approve rerun after the fix -> SUCCESS -> resolvedByRunId
execSync(`docker exec batch-control-e2e touch /tmp/b14-ok`);
const nb = (await job('b14-flaky')).nextBuildNumber;
if (rerunUrl) await decide(rerunUrl);
await waitFor(async () => { const j = await job('b14-flaky', 'nextBuildNumber,lastBuild[building,result]'); return j.nextBuildNumber > nb && !j.lastBuild.building; }, { timeout: 60000 });
await sleep(4000);
await a1.page.goto(incUrl);
const d7 = (await a1.page.locator('#main-panel').innerText()).replace(/\s+/g, ' ');
await shot(a1.page, a1.page.locator('#main-panel table').first(), 'B14-07', { pad: 8 });
log(L, `B14-07 rerun build ${(await job('b14-flaky', 'lastBuild[number,result]')).lastBuild.result}; incident "${d7.slice(0, 420)}"; csv ${(await incRows()).find((l) => l.includes('b14-flaky'))}`);
// B14-05 resolve then comment on RESOLVED
await a1.page.goto(incUrl);
await a1.page.fill('form[name="resolve"] textarea[name="comment"]', 'Fixed by the rerun.');
await Promise.all([a1.page.waitForLoadState('load'), a1.page.locator('form[name="resolve"] button').first().click()]);
await a1.page.goto(incUrl);
const hasComment = await a1.page.locator('form[name="addComment"]').count();
if (hasComment) { await a1.page.fill('form[name="addComment"] textarea[name="comment"]', 'Post-mortem filed.'); await Promise.all([a1.page.waitForLoadState('load'), a1.page.locator('form[name="addComment"] button').first().click()]); }
await a1.page.goto(incUrl);
const d5 = (await a1.page.locator('#main-panel').innerText()).replace(/\s+/g, ' ');
await shot(a1.page, '#main-panel', 'B14-05', { pad: 6 });
log(L, `B14-05 after Resolve + Add Comment: forms ack=${await a1.page.locator('form[name="acknowledge"]').count()} resolve=${await a1.page.locator('form[name="resolve"]').count()} comment=${await a1.page.locator('form[name="addComment"]').count()}; "${(d5.match(/Status \w+/) || [''])[0]}" comment shown ${d5.includes('Post-mortem filed.')}`);
// B14-08 auditor acts on another incident; requester cannot
const ad = await login('admin');
await ad.page.goto(`${BASE}/job/batch-failing/`); await clickBuildEntry(ad.page, /Build with Parameters|Build Now/);
await ad.page.goto(`${BASE}/job/batch-unstable/`); await clickBuildEntry(ad.page, /Build Now/);
await sleep(15000);
const rows2 = await incRows();
const fi = rows2.find((l) => l.includes(',batch-failing#'));
const ui = rows2.find((l) => l.includes(',batch-unstable#'));
log(L, `B14-10 batch-unstable -> incident ${ui || 'NONE'}; batch-failing -> ${fi}`);
const au = await login('auditor');
const fid = fi.split(',')[0];
await au.page.goto(`${BASE}/batch-control/incidents/${fid}/`);
await au.page.fill('form[name="acknowledge"] textarea[name="comment"]', 'auditor ack');
await Promise.all([au.page.waitForLoadState('load'), au.page.locator('form[name="acknowledge"] button').first().click()]);
await au.page.goto(`${BASE}/batch-control/incidents/${fid}/`);
const tail2 = await au.page.locator('#main-panel pre').first().innerText().catch(() => '');
log(L, `B14-08 auditor acknowledged ${fid}: ${(await au.page.locator('#main-panel').innerText()).match(/Status \w+/)?.[0]}; rerun form for auditor ${await au.page.locator('form[name="rerun"]').count()}; requester /batch-control/incidents/ -> ${(await rq.page.goto(`${BASE}/batch-control/incidents/`)).status()}; B14-02 batch-failing tail masks TOKEN: ${!tail2.includes('tok-e2e-secret-4711')} ("${tail2.split('\n').find((l) => l.includes('token=')) || ''}")`);
// B14-09 month nav / paging / empty
await a1.page.goto(`${BASE}/batch-control/incidents/`);
const ctrl = await a1.page.locator('#main-panel a.jenkins-button, #main-panel button, #main-panel input').evaluateAll((es) => es.filter((e) => e.offsetParent !== null).map((e) => (e.innerText || e.value || e.name || '').trim().slice(0, 20)));
const prev = a1.page.locator('#main-panel a:has-text("« ")').first();
let empty = '';
if (await prev.count()) { await prev.click(); await a1.page.waitForLoadState('load'); empty = (await a1.page.locator('#main-panel').innerText()).replace(/\s+/g, ' ').slice(0, 200); await shot(a1.page, '#main-panel', 'B14-09', { pad: 6 }); }
log(L, `B14-09 incidents controls ${JSON.stringify(ctrl)}; previous month "${empty}"`);
await close();
