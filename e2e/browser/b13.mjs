// B13 history, CSV, monthly summary.
import { login, close, shot, api, BASE, log, requestRun, sleep } from './lib.mjs';
import fs from 'node:fs';
import path from 'node:path';
import { OUT } from './lib.mjs';
const L = 'section-b.log';
const { page, context } = await login('approver-1');
const total = async () => ((await page.locator('#main-panel').innerText()).match(/\((\d+) matching records?\)/) || [])[1];
// B13-01 tabs
await page.goto(`${BASE}/batch-control/history/`);
const tabs = [];
for (const t of ['Runs', 'Incidents', 'Changes', 'Requests']) {
  await page.locator(`#main-panel a:text-is("${t}"), #main-panel button:text-is("${t}")`).first().click(); await page.waitForLoadState('load');
  const active = await page.locator(`#main-panel a:text-is("${t}"), #main-panel button:text-is("${t}")`).first().evaluate((e) => `${e.className} aria-selected=${e.getAttribute('aria-selected')} aria-current=${e.getAttribute('aria-current')}`);
  const heads = await page.locator('#main-panel table thead th').allInnerTexts();
  tabs.push(`${t}: url ${page.url().replace(BASE, '')} active(${active}) cols ${JSON.stringify(heads.slice(0, 6))} total ${await total()}`);
  if (t === 'Changes') await shot(page, page.locator(`#main-panel a:text-is("${t}"), #main-panel button:text-is("${t}")`).first().locator('xpath=..'), 'B13-01', { pad: 10 });
}
log(L, `B13-01 ${tabs.join(' || ')}`);
// B13-02 filters
await page.goto(`${BASE}/batch-control/history/`);
const all = await total();
await page.fill('#main-panel input[name="job"]', 'batch-daily');
await page.fill('#main-panel input[name="result"]', 'SUCCESS');
await Promise.all([page.waitForNavigation(), page.locator('#main-panel button:has-text("Filter")').click()]);
const f1 = await total(); const u1 = page.url().replace(BASE, '');
const jobs = [...new Set(await page.locator('#main-panel table tbody tr td:nth-child(2)').allInnerTexts())];
await shot(page, ['#main-panel form', '#main-panel table'], 'B13-02', { pad: 6 });
await page.locator('#main-panel a:text-is("Requests"), #main-panel button:text-is("Requests")').first().click(); await page.waitForLoadState('load');
const kept = { url: page.url().replace(BASE, ''), job: await page.inputValue('#main-panel input[name="job"]'), result: await page.inputValue('#main-panel input[name="result"]') };
const reqTotal = await total();
await page.fill('#main-panel input[name="result"]', '');
await page.fill('#main-panel input[name="status"]', 'EXECUTED');
await Promise.all([page.waitForNavigation(), page.locator('#main-panel button:has-text("Filter")').click()]);
const exec = await total(); const statuses = [...new Set(await page.locator('#main-panel table tbody tr td').evaluateAll((tds) => tds.map((t) => t.innerText.trim()).filter((x) => /^(EXECUTED|PENDING|APPROVED|REJECTED|CANCELLED|EXPIRED|INVALIDATED)$/.test(x))))];
await page.fill('#main-panel input[name="user"]', 'admin'); await page.fill('#main-panel input[name="job"]', ''); await page.fill('#main-panel input[name="status"]', '');
await Promise.all([page.waitForNavigation(), page.locator('#main-panel button:has-text("Filter")').click()]);
const byAdmin = await total();
log(L, `B13-02 runs all ${all}; job=batch-daily result=SUCCESS -> ${f1} (${u1}) jobs shown ${JSON.stringify(jobs)}; switch to Requests keeps ${JSON.stringify(kept)} total ${reqTotal}; status=EXECUTED -> ${exec} statuses ${JSON.stringify(statuses)}; user=admin -> ${byAdmin}`);
// B13-03 CSV with the filter applied
await page.goto(`${BASE}/batch-control/history/?job=batch-daily&result=SUCCESS`);
const screenN = await total();
const [dl] = await Promise.all([page.waitForEvent('download', { timeout: 10000 }).catch(() => null), page.locator('#main-panel a:has-text("runs.csv"), #main-panel button:has-text("runs.csv")').first().click()]);
let csvInfo = 'no download event';
if (dl) {
  const f = path.join(OUT, 'b13-runs-filtered.csv'); await dl.saveAs(f);
  const lines = fs.readFileSync(f, 'utf8').split('\n').filter(Boolean);
  csvInfo = `${dl.suggestedFilename()} header "${lines[0]}" rows ${lines.length - 1}, all batch-daily ${lines.slice(1).every((l) => l.split(',')[1] === 'batch-daily')}`;
} else {
  csvInfo = `navigated to ${page.url().replace(BASE, '')}`;
}
log(L, `B13-03 screen total ${screenN}; runs.csv click -> ${csvInfo}`);
for (const c of ['incidents', 'changes', 'requests']) {
  const r = await api('approver-1', `/batch-control/history/${c}.csv`);
  log(L, `B13-03 ${c}.csv -> ${r.status} header "${r.text.split('\n')[0]}" rows ${r.text.split('\n').filter(Boolean).length - 1}`);
}
// B13-05 formula payloads
const rq = await login('requester');
const url = await requestRun(rq.page, '/job/batch-daily/', { reason: '=1+1', approvers: ['approver-1'], params: { DATE: '@SUM(A1)' } });
const id = url.split('/requests/')[1].replace('/', '');
rq.page.once('dialog', (d) => d.accept()); await rq.page.locator('a:has-text("Cancel Request")').first().click(); await rq.page.waitForTimeout(800);
{ const ok = rq.page.locator('dialog[open] button[data-id="ok"]'); if (await ok.count()) await Promise.all([rq.page.waitForLoadState('load'), ok.click()]); }
const row = (await api('approver-1', '/batch-control/history/requests.csv')).text.split('\n').find((l) => l.startsWith(id));
log(L, `B13-05 requests.csv row for reason "=1+1", DATE "@SUM(A1)": ${row}`);
// B13-06 monthly summary
await page.goto(`${BASE}/batch-control/history/`);
const sum = page.locator('#main-panel h2:has-text("Monthly Summary")').first();
const sumTable = page.locator('#main-panel h2:has-text("Monthly Summary") ~ table, #main-panel table:has(td:text-is("runs"))').first();
const st = (await sumTable.innerText()).replace(/\s+/g, ' ');
await shot(page, [sum, sumTable, page.locator('#main-panel a:has-text("Monthly aggregate as JSON")')], 'B13-06', { pad: 8 });
const js = page.locator('#main-panel a:has-text("Monthly aggregate as JSON")');
const jsHref = await js.getAttribute('href');
const jr = await api('approver-1', new URL(jsHref, `${BASE}/batch-control/history/`).pathname + (new URL(jsHref, `${BASE}/batch-control/history/`).search));
const runs = (await api('approver-1', '/batch-control/history/runs.csv')).text.split('\n').filter(Boolean).length - 1;
const incidents = (await api('approver-1', '/batch-control/history/incidents.csv')).text.split('\n').filter(Boolean).slice(1);
log(L, `B13-06 summary table "${st}"; JSON link ${jsHref} -> ${jr.status} ${jr.text.slice(0, 200)}; runs.csv rows ${runs}; incidents.csv rows ${incidents.length} (OPEN ${incidents.filter((l) => l.includes(',OPEN')).length})`);
// B13-07 requester
const out = [];
for (const p of ['/batch-control/history/', '/batch-control/history/runs.csv', '/batch-control/history/summary']) out.push(`${p}=${(await rq.page.goto(BASE + p).catch(() => null))?.status() ?? (await api('requester', p)).status}`);
log(L, `B13-07 requester: ${out.join(' ')}`);
await close();
