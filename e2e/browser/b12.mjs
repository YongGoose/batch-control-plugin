// B12 run records and dashboard.
import { login, close, shot, api, job, BASE, log, sleep, waitFor, clickBuildEntry } from './lib.mjs';
const L = 'section-b.log';
const { page } = await login('approver-1');
const footer = async () => ((await page.locator('#main-panel').innerText()).match(/Page \d+[^\n]*/) || [''])[0];
const rowIds = async () => page.locator('#main-panel table tbody tr').evaluateAll((rs) => rs.map((r) => [...r.children].slice(0, 2).map((c) => c.innerText.trim()).join(' ')));
await page.goto(`${BASE}/batch-control/dashboard/`);
const heads = await page.locator('#main-panel table thead th').allInnerTexts();
const rows1 = await rowIds();
const f1 = await footer();
const ctrls = await page.locator('#main-panel input, #main-panel button, #main-panel a.jenkins-button').evaluateAll((es) => es.filter((e) => e.offsetParent !== null).map((e) => `${e.tagName}:${e.name || ''}:${(e.innerText || e.value || '').trim().slice(0, 20)}`));
await shot(page, ['#main-panel .jenkins-app-bar, #main-panel form', '#main-panel table thead'], 'B12-01', { pad: 8 });
// server truth: all builds of all jobs in the last 7 days
const all = (await api('admin', '/api/json?tree=jobs[fullName,builds[number,timestamp],jobs[fullName,builds[number,timestamp],jobs[fullName,builds[number,timestamp]]]]')).json;
let nb = 0; const walk = (js) => js.forEach((j) => { if (j.builds) nb += j.builds.filter((b) => Date.now() - b.timestamp < 7 * 86400000).length; if (j.jobs) walk(j.jobs); });
walk(all.jobs);
log(L, `B12-01 dashboard columns ${JSON.stringify(heads)}; controls ${JSON.stringify(ctrls)}; ${rows1.length} rows on page 1; footer "${f1}"; api builds in 7 days ${nb}`);
// B12-02 days
for (const d of ['1', '30', '366']) {
  await page.goto(`${BASE}/batch-control/dashboard/`);
  const inp = page.locator('#main-panel input[name="days"]').first();
  await inp.fill(d);
  const [r] = await Promise.all([page.waitForNavigation().catch(() => null), page.locator('#main-panel button:has-text("Show"), #main-panel button[type=submit]').first().click()]);
  const t = (await page.locator('#main-panel').innerText()).replace(/\s+/g, ' ');
  log(L, `B12-02 days=${d} -> ${r && r.status()} ${page.url().replace(BASE, '')}; field now ${await page.locator('#main-panel input[name="days"]').first().inputValue().catch(() => '?')}; "${(t.match(/(Page \d+[^.]*\.?)|(Error.{0,120})|(at most.{0,80})|(limited.{0,80})/) || [''])[0]}"`);
  if (d === '366') await shot(page, '#main-panel', 'B12-02-366', { pad: 6 });
}
// B12-03 paging
await page.goto(`${BASE}/batch-control/dashboard/`);
const p1 = await rowIds();
await page.locator('#main-panel a:has-text("Older")').first().click(); await page.waitForLoadState('load');
const p2 = await rowIds(); const f2 = await footer();
await page.locator('#main-panel a:has-text("Newer")').first().click(); await page.waitForLoadState('load');
const p1b = await rowIds();
const dup = p1.filter((x) => p2.includes(x));
log(L, `B12-03 page1 ${p1.length} rows, Older -> ${p2.length} rows "${f2}", duplicates between pages ${dup.length}, Newer returns to page 1: ${JSON.stringify(p1) === JSON.stringify(p1b)}`);
// B12-04 causes present
await page.goto(`${BASE}/batch-control/history/runs.csv`).catch(() => {});
const csv = (await api('approver-1', '/batch-control/history/runs.csv')).text.split('\n');
const causes = [...new Set(csv.slice(1).map((l) => l.split(',')[3]).filter(Boolean))];
log(L, `B12-04 causes in runs.csv: ${JSON.stringify(causes)}`);
// B12-06 / 07 links on an APPROVED_REQUEST row
await page.goto(`${BASE}/batch-control/dashboard/`);
const ar = page.locator('#main-panel tr:has-text("APPROVED_REQUEST"):has-text("batch-daily")').first();
const links = await ar.locator('a').evaluateAll((as) => as.map((a) => a.innerText.trim() + '->' + a.getAttribute('href')));
const reqLink = links.find((l) => l.includes('/requests/')); const runLink = links.find((l) => l.includes('/job/'));
const s6 = reqLink ? (await page.goto(new URL(reqLink.split('->')[1], BASE).href)).status() : 'no link';
const h6 = reqLink ? (await page.locator('#main-panel h1, #main-panel .jenkins-app-bar').first().innerText()) : '';
const s7 = runLink ? (await page.goto(new URL(runLink.split('->')[1], BASE).href)).status() : 'no link';
log(L, `B12-06/07 APPROVED_REQUEST row links ${JSON.stringify(links)}; request -> ${s6} "${h6}"; run -> ${s7}`);
await page.goto(`${BASE}/batch-control/dashboard/`);
await shot(page, page.locator('#main-panel tr:has-text("APPROVED_REQUEST"):has-text("batch-daily")').first(), 'B12-06', { pad: 8 });
// B12-08 build deleted by log rotation
const ad = await login('admin');
await ad.page.goto(`${BASE}/job/batch-cron/configure`); await ad.page.waitForTimeout(1500);
const dis = ad.page.locator('label:has-text("Discard old builds")').first();
if (!(await dis.evaluate((x) => x.parentElement.querySelector('input[type=checkbox]').checked))) { await dis.click(); await ad.page.waitForTimeout(500); }
await ad.page.locator('input[name="_.numToKeepStr"]').first().fill('1');
await Promise.all([ad.page.waitForNavigation(), ad.page.locator('button[name="Submit"]').click()]);
const before = (await job('batch-cron', 'lastBuild[number]')).lastBuild.number;
await waitFor(async () => (await job('batch-cron', 'lastBuild[number]')).lastBuild.number > before, { timeout: 90000 });
await sleep(5000);
const kept = (await job('batch-cron', 'builds[number]')).builds.map((b) => b.number);
await page.goto(`${BASE}/batch-control/dashboard/`);
const oldRow = page.locator(`#main-panel tr:has-text("batch-cron")`).nth(3);
const oldTxt = (await oldRow.innerText()).replace(/\s+/g, ' ');
const oldLinks = await oldRow.locator('a').evaluateAll((as) => as.map((a) => a.innerText.trim() + '->' + a.getAttribute('href')));
const dead = oldLinks.length ? (await page.goto(new URL(oldLinks[0].split('->')[1], BASE).href)).status() : 'no link';
log(L, `B12-08 batch-cron keeps ${JSON.stringify(kept)}; an older dashboard row "${oldTxt.slice(0, 90)}" links ${JSON.stringify(oldLinks)} -> ${dead}`);
await page.goto(`${BASE}/batch-control/dashboard/`);
await shot(page, page.locator('#main-panel tr:has-text("batch-cron")').nth(3), 'B12-08', { pad: 8 });
await ad.page.goto(`${BASE}/job/batch-cron/configure`); await ad.page.waitForTimeout(1200);
await ad.page.locator('input[name="_.numToKeepStr"]').first().fill('');
await ad.page.locator('label:has-text("Discard old builds")').first().click();
await Promise.all([ad.page.waitForNavigation(), ad.page.locator('button[name="Submit"]').click()]);
await close();
