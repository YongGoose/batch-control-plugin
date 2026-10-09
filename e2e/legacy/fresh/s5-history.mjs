// Scenario 5: history filters, CSV exports, monthly summary, dashboard, as the auditor.
import fs from 'node:fs';
import { login, BASE, shot, text, log, close, OUT } from './lib.mjs';

const flat = (s) => s.replace(/\s+/g, ' ');
const S = 'S5';
const { page, context } = await login('auditor');
await page.goto(`${BASE}/batch-control/history/`);
await page.locator('a', { hasText: /^Requests$/ }).first().click();
await page.waitForLoadState('load');
await page.fill('input[name="job"]', 'fresh-daily');
await page.fill('input[name="user"]', 'requester');
await Promise.all([page.waitForLoadState('load'), page.locator('#main-panel form button, #main-panel form input[type=submit]').first().click()]);
log(S + '-h url', page.url());
const t = flat(await text(page));
log(S + '-h requests filtered', t.slice(t.indexOf('Filter'), t.indexOf('Filter') + 1500));
await shot(page, page.locator('#main-panel table').last(), `${S}-hist-01-requests-filtered`);
const csvLinks = await page.$$eval('#main-panel a[href*=".csv"]', (as) => as.map((a) => a.getAttribute('href')));
log(S + '-h csv links after filter', csvLinks);
for (const h of csvLinks) {
  const r = await context.request.get(new URL(h, page.url()).href);
  const body = await r.text();
  const name = h.split('?')[0];
  fs.writeFileSync(`${OUT}/${name}`, body);
  const lines = body.split('\n');
  log(S + '-csv', name, r.status(), r.headers()['content-type'], r.headers()['content-disposition'], 'lines', lines.length, '| header:', lines[0], '| fresh rows:', lines.filter((l) => l.includes('fresh-')).length, '| other-job rows:', lines.filter((l) => l && !l.includes('fresh-daily')).length - 1);
  const f = lines.filter((l) => /(^|,)'[=+\-@]/.test(l)).slice(0, 2);
  if (f.length) log('   sanitised cells sample', f);
}
// monthly summary
await page.locator('a', { hasText: 'Monthly summary' }).first().click();
await page.waitForLoadState('load');
log(S + '-m monthly', page.url(), flat(await text(page)).slice(0, 1200));
await shot(page, '#main-panel', `${S}-hist-02-monthly`);
const js = await context.request.get(`${BASE}/batch-control/history/summary?month=2026-09`);
log(S + '-m json', js.status(), (await js.text()).slice(0, 400));
// changes kind filtered by job
await page.goto(`${BASE}/batch-control/history/?kind=changes&job=fresh-cron`);
log(S + '-h changes fresh-cron', flat(await text(page)).split('Type')[1]?.slice(0, 900));
await shot(page, page.locator('#main-panel table').last(), `${S}-hist-03-changes-fresh-cron`);
// invalid date input
await page.goto(`${BASE}/batch-control/history/?kind=runs&from=2026-13-45&to=yesterday`);
log(S + '-h bad dates', flat(await text(page)).slice(0, 500));
await shot(page, '#main-panel', `${S}-hist-04-bad-dates`);
await page.goto(`${BASE}/batch-control/history/?kind=runs&from=2026-09-30&to=2026-09-01`);
log(S + '-h reversed dates', flat(await text(page)).slice(0, 500));
// dashboard
await page.goto(`${BASE}/batch-control/dashboard/`);
const row = page.locator('tr', { hasText: '20260930-080845-fvazja' }).first();
log(S + '-d dashboard approved row', flat(await row.innerText()));
log('   links in row', await row.locator('a').evaluateAll((as) => as.map((a) => a.getAttribute('href'))));
await shot(page, [page.locator('#main-panel thead').first(), row], `${S}-dash-01-approved-row`);
await context.close();
await close();
