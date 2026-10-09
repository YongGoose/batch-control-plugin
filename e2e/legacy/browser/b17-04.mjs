import { login, close, shot, BASE, log, requestGrant, decide } from './lib.mjs';
const L = 'section-b.log';
const phase = process.argv[2];
if (phase === 'before') {
  const rq = await login('requester');
  const g = await requestGrant(rq.page, { scope: 'team/app-1', actions: ['CONFIGURE'], minutes: 1, reason: 'Expires while Jenkins is down (B17-04 redo).' }); await decide(g.url);
  log(L, `B17-04 redo: 1-min window approved at ${new Date().toISOString()}; configure now ${(await rq.page.goto(`${BASE}/job/team/job/app-1/configure`)).status()}`);
  await close();
} else {
  const rq = await login('requester');
  const c = (await rq.page.goto(`${BASE}/job/team/job/app-1/configure`)).status();
  await rq.page.goto(`${BASE}/batch-control/grants/`);
  const rows = (await rq.page.locator('table:has(th:has-text("Expires")) tbody tr').allInnerTexts()).map((t) => t.replace(/\s+/g, ' '));
  const empty = (await rq.page.locator('#main-panel').innerText()).includes('No active grants');
  await shot(rq.page, rq.page.locator('h2:has-text("Active Grants")').first().locator('xpath=following-sibling::*[position()<=3]').first(), 'B17-04', { pad: 8 });
  log(L, `B17-04 redo, first check right after start (${new Date().toISOString()}): team/app-1 configure -> ${c}; active rows ${JSON.stringify(rows.filter((r) => r.includes('team/app-1')))}; "No active grants" shown ${empty}`);
  await close();
}
