// D-05 (faked clock on :8081, plugin zone Asia/Seoul, OS UTC): month selectors, defaults and placement as auditor.
import { chromium } from 'playwright';
import { password, shot } from '../lib.mjs';
import { row, ev } from '../audit/rec.mjs';
const BASE = 'http://localhost:8081';
const b = await chromium.launch({ channel: 'chrome' });
const page = await (await b.newContext({ viewport: { width: 1280, height: 900 }, locale: 'en-US' })).newPage();
page.setDefaultTimeout(30000);
await page.goto(`${BASE}/login`); await page.fill('#j_username', 'auditor'); await page.fill('input[name="j_password"]', password('auditor'));
await Promise.all([page.waitForLoadState('load'), page.click('button[name="Submit"], button[type="submit"]')]);
const txt = async () => (await page.locator('#main-panel').innerText()).replace(/\s+/g, ' ');
const res = {}; const S = {};
// Change Records: default month, previous month
await page.goto(`${BASE}/batch-control/changes/`); let t = await txt();
res.changesDefault = { month: (t.match(/(\d+) records? in (\d{4}-\d{2})/) || t.match(/in (\d{4}-\d{2})/) || [])[0], nov: /nov-job/.test(t), seed: /batch-pt-source|team\b/.test(t), nav: await page.locator('#main-panel a, #main-panel input').evaluateAll((es) => es.map((e) => (e.innerText || e.value || '').trim()).filter((x) => /20\d\d-\d\d|«|»/.test(x))) };
S.c1 = await shot(page, [page.locator('#main-panel tr', { hasText: 'nov-job' }).first(), page.locator('#main-panel').locator('text=/records? in 20/').first()], 'D-05-1-changes-default-november', { pad: 8 });
await Promise.all([page.waitForNavigation(), page.locator('#main-panel a', { hasText: '«' }).first().click()]); t = await txt();
res.changesPrev = { url: page.url().replace(BASE, ''), month: (t.match(/\d+ records? in (\d{4}-\d{2})/) || [])[0], nov: /nov-job/.test(t), last: (t.match(/2026-10-31 23:5\d:\d\d KST/g) || []).slice(0, 2) };
S.c2 = await shot(page, [page.locator('#main-panel tr', { hasText: '2026-10-31 23:5' }).first(), page.locator('#main-panel').locator('text=/records? in 20/').first()], 'D-05-2-changes-previous-october', { pad: 8 });
// Incidents
await page.goto(`${BASE}/batch-control/incidents/`); t = await txt();
res.incDefault = { run2: /batch-unstable#2/.test(t), run1: /batch-unstable#1/.test(t), label: (t.match(/in (\d{4}-\d{2})|(\d{4}-\d{2})/) || [])[0] };
S.i1 = await shot(page, page.locator('#main-panel tr', { hasText: 'batch-unstable' }).first(), 'D-05-3-incidents-default-november', { pad: 8 });
await page.goto(`${BASE}/batch-control/incidents/?month=2026-10`); t = await txt();
res.incOct = { run1: /batch-unstable#1/.test(t), run2: /batch-unstable#2/.test(t) };
// History: default filter dates, runs and changes tabs
await page.goto(`${BASE}/batch-control/history/`);
res.histDefaults = { from: await page.inputValue('#main-panel input[name="from"]').catch(() => '?'), to: await page.inputValue('#main-panel input[name="to"]').catch(() => '?') };
t = await txt(); res.histRuns = { r1: (t.match(/#1 batch-unstable[^#]{0,80}/) || [''])[0], r2: (t.match(/#2 batch-unstable[^#]{0,80}/) || [''])[0] };
S.h1 = await shot(page, [page.locator('#main-panel input[name="from"]').first(), page.locator('#main-panel table:has(th:text-is("Job")) tbody tr', { hasText: 'batch-unstable' }).first()], 'D-05-4-history-runs', { pad: 8 });
await page.fill('#main-panel input[name="from"]', '2026-11-01'); await page.fill('#main-panel input[name="to"]', '2026-11-01');
await Promise.all([page.waitForNavigation(), page.locator('#main-panel button:has-text("Filter")').click()]); t = await txt();
res.histNov1 = { r1: /#1 batch-unstable/.test(t), r2: /#2 batch-unstable/.test(t), total: (t.match(/\((\d+) matching/) || [])[1] };
await page.fill('#main-panel input[name="from"]', '2026-10-31'); await page.fill('#main-panel input[name="to"]', '2026-10-31');
await Promise.all([page.waitForNavigation(), page.locator('#main-panel button:has-text("Filter")').click()]); t = await txt();
res.histOct31 = { r1: /#1 batch-unstable/.test(t), r2: /#2 batch-unstable/.test(t) };
// Monthly summary (history page, current month) and the October summary
await page.goto(`${BASE}/batch-control/history/`); t = await txt();
res.summary = (t.match(/Monthly Summary.{0,220}/) || [''])[0];
S.s1 = await shot(page, [page.locator('#main-panel h2:has-text("Monthly Summary")').first(), page.locator('#main-panel h2:has-text("Monthly Summary") ~ table').first()], 'D-05-5-monthly-summary', { pad: 8 });
const sj = page.locator('#main-panel a:has-text("Monthly aggregate as JSON")').first();
res.summaryHref = await sj.getAttribute('href').catch(() => null);
const sjNov = res.summaryHref ? await (await page.request.get(new URL(res.summaryHref, `${BASE}/batch-control/history/`).href)).text() : '';
const sjOct = await (await page.request.get(`${BASE}/batch-control/history/summary?month=2026-10`)).text();
res.summaryJson = { current: sjNov.replace(/\s+/g, ' ').slice(0, 200), oct: sjOct.replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ').slice(0, 200) };
// Dashboard
await page.goto(`${BASE}/batch-control/dashboard/`); t = await txt();
res.dash = { r1: (t.match(/#1 batch-unstable.{0,70}/) || [''])[0], r2: (t.match(/#2 batch-unstable.{0,70}/) || [''])[0] };
S.d1 = await shot(page, page.locator('#main-panel table tbody tr', { hasText: 'batch-unstable' }), 'D-05-6-dashboard', { pad: 8 });
// CSV exports with day filters in the plugin zone
const csv = async (q) => (await (await page.request.get(`${BASE}/batch-control/history/${q}`)).text()).split('\n').filter((l) => /batch-unstable|nov-job/.test(l));
res.csv = { runsNov1: await csv('runs.csv?from=2026-11-01&to=2026-11-01'), runsOct31: await csv('runs.csv?from=2026-10-31&to=2026-10-31'), changesNov1: await csv('changes.csv?from=2026-11-01&to=2026-11-01'), incidentsAll: await csv('incidents.csv') };
ev(`D-05 ${JSON.stringify(res)}`);
await b.close();
