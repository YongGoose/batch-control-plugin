import { login, close, shot, BASE, job } from '../lib.mjs';
import { row, ev } from '../audit/rec.mjs';
async function filtered(page, jobName) {
  await page.goto(`${BASE}/batch-control/history/`);
  await page.fill('#main-panel input[name="job"]', jobName);
  await Promise.all([page.waitForNavigation(), page.locator('#main-panel button:has-text("Filter")').click()]);
  return page.locator('#main-panel table:has(th:text-is("Job"))').first().locator('tbody tr');
}
const a = await login('approver-1');
const rows = await filtered(a.page, 'team/secret-job');
const n = await rows.count(); const r = rows.first();
const links = n ? await r.locator('a').evaluateAll((as) => as.map((x) => `${x.innerText.trim()}->${x.getAttribute('href')}`)) : [];
const s = n ? await shot(a.page, r, 'B2-05-secret-job-row', { pad: 8 }) : null;
ev(`V8b secret-job rows ${n} links ${links}`);
row('B2-05', { roles: 'approver-1 (no Read on team/secret-job)', V: `${n && links.length === 0 ? '✓' : '✗'} History lists ${n} team/secret-job rows; the run cell and the request id are plain text (links: ${links.join(', ') || 'none'}), so nothing leads to a 404 (DEF-11 fixed)`, G: '✓ rows listed (LIMITATIONS 19)', R: 'n.a.', C: 'n.a.', E: s ? '✓ B2-05-secret-job-row' : '✗' });
const ad = await login('admin');
const low = Math.min(...(await job('batch-cron', 'builds[number]')).builds.map((b) => b.number));
const cr = await filtered(ad.page, 'batch-cron');
await ad.page.locator('#main-panel a:has-text("Older")').first().click().catch(() => {}); await ad.page.waitForLoadState('load');
// walk to the last page
for (let i = 0; i < 12; i++) { const o = ad.page.locator('#main-panel a:has-text("Older")').first(); if (!(await o.count())) break; await Promise.all([ad.page.waitForNavigation(), o.click()]); }
const all = await ad.page.locator('#main-panel table:has(th:text-is("Job")) tbody tr').evaluateAll((trs) => trs.map((t) => ({ n: (t.innerText.match(/#(\d+)/) || [])[1], a: !!t.querySelector('a[href*="/job/batch-cron/"]') })));
const del = all.filter((x) => x.n && +x.n < low); const s2 = del.length ? await shot(ad.page, ad.page.locator('#main-panel table:has(th:text-is("Job")) tbody tr').last(), 'B12-08-deleted-build-row', { pad: 8 }) : null;
ev(`V8b B12-08 low ${low}; last page rows ${JSON.stringify(all.slice(-5))}`);
row('B12-08', { roles: 'admin', V: `${del.length && del.every((x) => !x.a) ? '✓' : '✗'} batch-cron keeps builds from #${low} (log rotation); ${del.length} rows of deleted builds on the oldest History page, linked: ${del.filter((x) => x.a).length} (DEF-23)`, G: '✓ the records stay listed', R: 'n.a.', C: 'n.a.', E: s2 ? '✓ B12-08-deleted-build-row' : '✗' });
await close();
