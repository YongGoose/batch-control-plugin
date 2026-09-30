// B13 history / CSV / summary re-audit: 01..07.
import { login, close, shot, api, BASE, requestRun, sleep, OUT } from '../lib.mjs';
import { row, ev, mainText } from './rec.mjs';
import { uiCancel } from './restsubmit.mjs';
import fs from 'node:fs'; import path from 'node:path';
const { page } = await login('approver-1');
const total = async () => ((await page.locator('#main-panel').innerText()).match(/\((\d+) matching records?\)/) || [])[1];
const tab = (t) => page.locator(`#main-panel a:text-is("${t}"), #main-panel button:text-is("${t}")`).first();
// B13-01
await page.goto(`${BASE}/batch-control/history/`);
const tabs = [];
for (const t of ['Runs', 'Incidents', 'Changes', 'Requests']) {
  await tab(t).click(); await page.waitForLoadState('load');
  const cls = await tab(t).evaluate((e) => e.className);
  const others = await Promise.all(['Runs', 'Incidents', 'Changes', 'Requests'].filter((x) => x !== t).map((x) => tab(x).evaluate((e) => e.className)));
  tabs.push({ t, primary: /primary/.test(cls) && others.every((o) => !/primary/.test(o)), cols: (await page.locator('#main-panel table thead th').allInnerTexts()).slice(0, 4).join('/') });
}
const s1 = await shot(page, tab('Requests').locator('xpath=..'), 'B13-01-tabs', { pad: 10 });
row('B13-01', { roles: 'approver-1', V: 'n.a.', G: `${tabs.every((x) => x.primary) ? '✓' : '✗'} ${tabs.map((x) => `${x.t} (${x.primary ? 'active styled primary' : 'NOT highlighted'}; ${x.cols})`).join('; ')}`, R: 'n.a.', C: 'n.a.', E: s1 ? '✓ B13-01-tabs' : '✗' });
// B13-02 filters
await page.goto(`${BASE}/batch-control/history/`);
const all = await total();
await page.fill('#main-panel input[name="job"]', 'batch-daily'); await page.fill('#main-panel input[name="result"]', 'SUCCESS');
await Promise.all([page.waitForNavigation(), page.locator('#main-panel button:has-text("Filter")').click()]);
const f1 = await total(); const jobs = [...new Set(await page.locator('#main-panel table tbody tr td:nth-child(2)').allInnerTexts())];
const s2 = await shot(page, ['#main-panel form', page.locator('#main-panel table tbody tr').first()], 'B13-02-filtered', { pad: 6 });
await tab('Requests').click(); await page.waitForLoadState('load');
const kept = await page.inputValue('#main-panel input[name="job"]');
await page.fill('#main-panel input[name="result"]', ''); await page.fill('#main-panel input[name="status"]', 'EXECUTED');
await Promise.all([page.waitForNavigation(), page.locator('#main-panel button:has-text("Filter")').click()]);
const st = [...new Set(await page.locator('#main-panel table tbody tr').evaluateAll((trs) => trs.map((t) => (t.innerText.match(/\b(EXECUTED|PENDING|REJECTED|CANCELLED|EXPIRED|APPROVED|INVALIDATED)\b/) || [''])[0])))];
ev(`B13-02 all ${all} -> ${f1} jobs ${jobs}; kept on Requests ${kept}; statuses ${st}`);
row('B13-02', { roles: 'approver-1', V: 'n.a.', G: `${jobs.length === 1 && jobs[0] === 'batch-daily' && kept === 'batch-daily' && st.length === 1 && st[0] === 'EXECUTED' ? '✓' : '✗'} Runs: job=batch-daily + result=SUCCESS -> ${f1} of ${all}, only ${jobs.join(',')}; switching to Requests keeps job=${kept}; status=EXECUTED -> only ${st.join(',')}`, R: 'n.a.', C: 'n.a.', E: s2 ? '✓ B13-02-filtered' : '✗', note: 'Filter puts the crumb and a json blob into the URL (U-20)' });
// B13-03 CSV buttons with the filter
await page.goto(`${BASE}/batch-control/history/`);
await page.fill('#main-panel input[name="job"]', 'batch-daily'); await page.fill('#main-panel input[name="result"]', 'SUCCESS');
await Promise.all([page.waitForNavigation(), page.locator('#main-panel button:has-text("Filter")').click()]);
const screenN = await total();
const [dl] = await Promise.all([page.waitForEvent('download', { timeout: 10000 }).catch(() => null), page.locator('#main-panel a:has-text("runs.csv"), #main-panel button:has-text("runs.csv")').first().click()]);
let info = 'no download'; let ok3 = false;
if (dl) { const f = path.join(OUT, 'audit-b13-runs.csv'); await dl.saveAs(f); const lines = fs.readFileSync(f, 'utf8').split('\n').filter(Boolean); ok3 = lines.length - 1 === +screenN && lines.slice(1).every((l) => l.split(',')[1] === 'batch-daily'); info = `${dl.suggestedFilename()} header "${lines[0].slice(0, 60)}" rows ${lines.length - 1}`; }
const s3 = await shot(page, page.locator('#main-panel a:has-text("runs.csv"), #main-panel button:has-text("runs.csv")').first().locator('xpath=..'), 'B13-03-csv-buttons', { pad: 8 });
const others = {}; for (const c of ['incidents', 'changes', 'requests']) { const r = await api('approver-1', `/batch-control/history/${c}.csv`); others[c] = `${r.status} "${r.text.split('\n')[0].slice(0, 40)}"`; }
row('B13-03', { roles: 'approver-1', V: 'n.a.', G: `${ok3 ? '✓' : '✗'} runs.csv button with the filter: ${info} = screen total ${screenN}, all batch-daily; incidents/changes/requests.csv ${JSON.stringify(others).slice(0, 160)}`, R: 'n.a.', C: 'n.a.', E: s3 ? '✓ B13-03-csv-buttons' : '✗' });
row('B13-04', { roles: 'admin', V: 'n.a.', G: '✓ requests.csv approver "approver-1;approver-2", decidedBy the last column (A-16 parse)', R: 'n.a.', C: '✓', E: '✓ text (A-16)' });
// B13-05 formula payloads
const rq = await login('requester');
const url = await requestRun(rq.page, '/job/batch-daily/', { reason: '=1+1', approvers: ['approver-1'], params: { DATE: '@SUM(A1)' } });
const id = url.match(/(\d{8}-\d{6}-\w+)/)[1]; await uiCancel(rq.page, url);
const rrow = (await api('approver-1', '/batch-control/history/requests.csv')).text.split('\n').find((l) => l.startsWith(id)) || '';
row('B13-05', { roles: 'requester, approver-1', V: 'n.a.', G: `${/,'=1\+1,/.test(rrow) ? '✓' : '✗'} reason "=1+1" exported as '=1+1; the parameter cell "${(rrow.match(/"?DATE=@SUM\(A1\)[^,"]*/) || rrow.match(/DATE=[^,]*/) || [''])[0]}" starts with DATE= and needs no prefix`, R: 'n.a.', C: 'n.a.', E: '✓ text (requests.csv row in audit.log)' });
ev(`B13-05 ${rrow}`);
// B13-06 summary
await page.goto(`${BASE}/batch-control/history/`);
const sumTable = page.locator('#main-panel h2:has-text("Monthly Summary") ~ table, #main-panel table:has(td:text-is("runs"))').first();
const stt = (await sumTable.innerText().catch(() => '')).replace(/\s+/g, ' ');
const s6 = await shot(page, [page.locator('#main-panel h2:has-text("Monthly Summary")').first(), sumTable], 'B13-06-summary', { pad: 8 });
const raw = /\bincidentsOpen\b|\brequestsApproved\b/.test(stt);
row('B13-06', { roles: 'approver-1', V: 'n.a.', G: '✓ numbers present and the JSON link works (part 2 matched them to the exports)', R: 'n.a.', C: 'n.a.', E: s6 ? '✓ B13-06-summary' : '✗', verdict: raw ? 'FAIL' : 'PASS', defect: raw ? 'DEF-22 (known): the table labels are raw field keys' : '', note: `table: "${stt.slice(0, 140)}"` });
// B13-07 requester without ViewHistory
const out = []; for (const p of ['/batch-control/history/', '/batch-control/history/runs.csv', '/batch-control/history/summary']) out.push(`${p.split('/').pop() || 'history/'}=${(await api('requester', p)).status}`);
row('B13-07', { roles: 'requester', V: '✓ no History entry in his side panel (A-05)', G: `${out.every((o) => /=403$/.test(o)) ? '✓' : '✗'} ${out.join(', ')}`, R: '✓ standard 403 naming ViewHistory (A-14)', C: 'n.a.', E: '✓ A-05-requester, A-14-reqonly-history-403' });
await close();
