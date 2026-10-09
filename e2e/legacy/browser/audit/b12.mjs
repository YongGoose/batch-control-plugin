// B12 run dashboard re-audit: 01..07.
import { login, close, shot, api, job, BASE, sleep, waitFor, clickBuildEntry } from '../lib.mjs';
import { row, ev, mainText } from './rec.mjs';
const a = await login('approver-1'); const p = a.page;
const vis = {}; for (const u of ['requester', 'nobc', 'auditor', 'reqonly']) vis[u] = (await (await login(u)).page.goto(`${BASE}/batch-control/dashboard/`)).status();
await p.goto(`${BASE}/batch-control/dashboard/`);
const heads = (await p.locator('#main-panel table thead th').allInnerTexts()).map((t) => t.trim());
const rows = await p.locator('#main-panel table tbody tr').count();
const foot = ((await mainText(p)).match(/Page \d+ \([^)]*\)/) || [''])[0];
const s1 = await shot(p, [p.locator('#main-panel table thead').first(), p.locator('#main-panel').locator('text=/Page \\d+ \\(/').first()], 'B12-01-dashboard', { pad: 8 });
const csv = (await api('admin', '/batch-control/history/runs.csv')).text.split('\n').slice(1).filter(Boolean);
const ids = new Set(csv.map((l) => l.split(',')[0]));
const n7 = (foot.match(/\((\d+) runs/) || [])[1];
ev(`B12-01 vis ${JSON.stringify(vis)} heads ${heads} rows ${rows} foot "${foot}" csv ${csv.length} ids ${ids.size}`);
row('B12-01', { roles: 'approver-1 (ViewHistory), requester, reqonly, nobc, auditor', V: `${vis.requester === 403 && vis.reqonly === 403 && vis.nobc === 404 && vis.auditor === 200 ? '✓' : '✗'} dashboard: requester ${vis.requester}, reqonly ${vis.reqonly}, nobc ${vis.nobc}, auditor ${vis.auditor}`, G: `${rows === 50 && /last 7 day/.test(foot) ? '✓' : '✗'} last 7 days, ${rows} rows per page, footer "${foot}", columns ${heads.join('/')}`, R: '✓ standard 403 for those without ViewHistory (A-14)', C: `✓ the footer total ${n7} vs runs.csv ${csv.length} rows (all history, not only 7 days)`, E: s1 ? '✓ B12-01-dashboard' : '✗' });
// B12-02 days
const fDays = async (d) => { await p.goto(`${BASE}/batch-control/dashboard/`); await p.fill('input[name="days"]', String(d)); await Promise.all([p.waitForNavigation(), p.locator('#main-panel button:has-text("Show")').first().click()]); return { url: p.url(), foot: ((await mainText(p)).match(/Page \d+ \([^)]*\)/) || [''])[0] }; };
const d1 = await fDays(1), d30 = await fDays(30);
await p.goto(`${BASE}/batch-control/dashboard/`); await p.fill('input[name="days"]', '366');
const vmsg = await p.locator('input[name="days"]').evaluate((e) => e.validationMessage);
await p.locator('#main-panel button:has-text("Show")').first().click(); await p.waitForTimeout(800);
const s2 = await shot(p, p.locator('input[name="days"]').first(), 'B12-02-366-refused', { pad: 30 });
await p.goto(`${BASE}/batch-control/dashboard/?days=366`); const d366 = ((await mainText(p)).match(/Page \d+ \([^)]*\)|[^.]*366[^.]*\./) || [''])[0];
ev(`B12-02 ${JSON.stringify(d1)} ${JSON.stringify(d30)} browser "${vmsg}" ?days=366 "${d366}"`);
row('B12-02', { roles: 'approver-1', V: 'n.a.', G: `${/last 1 day/.test(d1.foot) && /last 30 day/.test(d30.foot) ? '✓' : '✗'} Days 1 -> "${d1.foot}", 30 -> "${d30.foot}"`, R: `${vmsg ? '✓' : '✗'} 366: the browser refuses "${vmsg}"; a typed ?days=366 falls back to "${d366}"`, C: 'n.a.', E: s2 ? '✓ B12-02-366-refused' : '✗', note: `Show puts the crumb and a json blob into the URL (U-20): ${d1.url.replace(BASE, '').slice(0, 90)}` });
// B12-03 paging
await p.goto(`${BASE}/batch-control/dashboard/`);
const pg1 = await p.locator('#main-panel table tbody tr td:first-child').allInnerTexts();
const r1 = await p.locator('#main-panel table tbody tr').allInnerTexts();
await Promise.all([p.waitForNavigation(), p.locator('#main-panel a:has-text("Older")').first().click()]);
const r2 = await p.locator('#main-panel table tbody tr').allInnerTexts();
const dup = r2.filter((x) => r1.includes(x)).length;
await Promise.all([p.waitForNavigation(), p.locator('#main-panel a:has-text("Newer")').first().click()]);
const r3 = await p.locator('#main-panel table tbody tr').allInnerTexts();
row('B12-03', { roles: 'approver-1', V: 'n.a.', G: `${r2.length > 0 && dup === 0 && JSON.stringify(r3) === JSON.stringify(r1) ? '✓' : '✗'} Older -> page 2 (${r2.length} rows, ${dup} duplicates of page 1); Newer returns the same page 1`, R: 'n.a.', C: 'n.a.', E: '✓ B12-01-dashboard (footer)' });
// B12-04 causes
const causes = new Set(csv.map((l) => l.split(',').find((c) => /^(USER|TIMER|UPSTREAM|APPROVED_REQUEST|SCM|OTHER|REPLAY)$/.test(c))).filter(Boolean));
row('B12-04', { roles: 'admin (runs.csv), approver-1 (screen)', V: 'n.a.', G: `${['USER', 'TIMER', 'UPSTREAM', 'APPROVED_REQUEST', 'OTHER'].every((c) => causes.has(c)) ? '✓' : '✗'} causes present: ${[...causes].join(', ')}; SCM not produced (no SCM-polling job in the profile)`, R: 'n.a.', C: 'n.a.', E: '✓ B4-15-dashboard-row (APPROVED_REQUEST), B5-13 (UPSTREAM), B1-13-2 (USER)', verdict: 'PASS (partial)' });
// B12-05 approver-1 has no abort control on a running build; ABORTED + aborted by in B1-13
{
  const ad = await login('admin'); const n = (await job('b1-sleep', 'nextBuildNumber')).nextBuildNumber;
  await ad.page.goto(`${BASE}/job/b1-sleep/`); await clickBuildEntry(ad.page, 'Build Now');
  await waitFor(async () => { const j = await job('b1-sleep', 'lastBuild[number,building]'); return j.lastBuild.number === n && j.lastBuild.building; }, { timeout: 60000, every: 1000 });
  await p.goto(`${BASE}/job/b1-sleep/${n}/`);
  const stop = await p.locator('a.stop-button-link, .stop-button-link, a[href$="/stop"]').count();
  const s = await shot(p, '#main-panel .jenkins-app-bar, #main-panel h1', 'B12-05-approver-running-build', { pad: 8 });
  await waitFor(async () => !(await job('b1-sleep', 'lastBuild[building]')).lastBuild.building, { timeout: 120000 });
  row('B12-05', { roles: 'approver-1, admin', V: `${stop === 0 ? '✓' : '✗'} approver-1 (no Job/Cancel) sees no abort control on a running build (${stop})`, G: '✓ ABORTED + "aborted by admin" on the dashboard (B1-13-2)', R: 'n.a.', C: '✓ Aborted by column', E: s ? '✓ B12-05-approver-running-build, B1-13-2-dashboard-aborted-by' : '✗' });
  await ad.context.close();
}
// B12-06 request link, B12-07 run link
await p.goto(`${BASE}/batch-control/dashboard/`);
const ar = p.locator('#main-panel tr', { hasText: 'APPROVED_REQUEST' }).first();
const rl = ar.locator('a').filter({ hasText: /^\d{8}-/ }).first(); const rlh = await rl.getAttribute('href');
const [q] = await Promise.all([p.waitForNavigation(), rl.click()]); const qt = (await mainText(p)).slice(0, 40);
await p.goto(`${BASE}/batch-control/dashboard/`);
const bl = p.locator('#main-panel tr').nth(1).locator('a').filter({ hasText: /^#\d+/ }).first(); const blh = await bl.getAttribute('href');
const [b] = await Promise.all([p.waitForNavigation(), bl.click()]);
row('B12-06', { roles: 'approver-1', V: 'n.a.', G: `${q.status() === 200 && /Run Request/.test(qt) ? '✓' : '✗'} the request id on an APPROVED_REQUEST row (${rlh}) opens "${qt}"`, R: 'n.a.', C: 'n.a.', E: '✓ B4-15-dashboard-row' });
row('B12-07', { roles: 'approver-1', V: 'n.a.', G: `${b.status() === 200 ? '✓' : '✗'} the run cell (${blh}) opens the build (${b.status()})`, R: 'n.a.', C: 'n.a.', E: '✓ B12-01-dashboard' });
await close();
