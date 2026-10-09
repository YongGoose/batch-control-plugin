// manager revokes every open window of requester (UI), admin deletes the contaminated items (UI).
import { login, close, BASE, log, api } from './lib.mjs';
const L = 'section-b.log';
const m = await login('manager');
for (;;) {
  await m.page.goto(`${BASE}/batch-control/grants/`);
  const rev = m.page.locator('table:has(th:has-text("Expires")) tbody tr a:has-text("Revoke"), table:has(th:has-text("Expires")) tbody tr button:has-text("Revoke")').first();
  if (!(await rev.count())) break;
  m.page.once('dialog', (d) => d.accept());
  await rev.click(); await m.page.waitForTimeout(700);
  const ok = m.page.locator('dialog[open] button[data-id="ok"]').first(); if (await ok.count()) await Promise.all([m.page.waitForLoadState('load'), ok.click()]);
}
log(L, 'B7 cleanup: manager revoked all open windows');
const ad = await login('admin');
for (const p of ['/job/team/job/app-x/', '/job/team/job/evil/', '/job/team/job/app-8%20/']) {
  if ((await api('admin', p + 'api/json')).status !== 200) continue;
  await ad.page.goto(BASE + p);
  ad.page.once('dialog', (d) => d.accept());
  await ad.page.locator('#side-panel a:has-text("Delete Project")').click(); await ad.page.waitForTimeout(700);
  const ok = ad.page.locator('dialog[open] button[data-id="ok"]').first(); if (await ok.count()) await Promise.all([ad.page.waitForNavigation().catch(() => null), ok.click()]);
  log(L, `B7 cleanup: deleted ${p} -> ${(await api('admin', p + 'api/json')).status}`);
}
await close();
