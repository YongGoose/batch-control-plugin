// Tight, red-boxed evidence for the defects of e2e-04.
import { login, BASE, shot, log, close } from './lib.mjs';
{ // FD manager revert
  const { page, context } = await login('manager');
  await page.goto(`${BASE}/batch-control-configuration/`);
  await shot(page, page.locator('a.confirmation-link', { hasText: 'Revert' }), 'FD-revert-01-manager-sees-button');
  await page.goto(`${BASE}/batch-control-configuration/`);
  await page.locator('a.confirmation-link', { hasText: 'Revert' }).click();
  await page.locator('dialog[open] button', { hasText: 'Yes' }).click();
  await page.waitForLoadState('load'); await page.waitForTimeout(800);
  await shot(page, [page.locator('#main-panel h1').first(), page.locator('#main-panel p, #main-panel .error').first()], 'FD-revert-02-access-denied');
  // config page claim
  await page.goto(`${BASE}/batch-control-configuration/`);
  await shot(page, page.locator('#main-panel p, #main-panel .jenkins-description', { hasText: 'recorded in the change history' }).first(), 'FD-config-claim-01-text');
  await context.close();
}
{ // grants form position
  const { page, context } = await login('requester');
  await page.goto(`${BASE}/job/fresh-daily/`);
  await page.locator('#tasks a', { hasText: 'Request Change Permission' }).click();
  await page.waitForLoadState('load');
  const y = await page.evaluate(() => Math.round(document.querySelector('form[action$="grants/create"]').getBoundingClientRect().top));
  log('form top after sidebar link', y, 'viewport', page.viewportSize().height);
  await page.screenshot({ path: 'screenshots/FD-grants-01-landing-viewport.png' });
  await context.close();
}
{ // History runs: user column empty for approved runs
  const { page, context } = await login('approver-1');
  await page.goto(`${BASE}/batch-control/dashboard/`);
  const row = page.locator('tr', { hasText: '20260930-080845-fvazja' }).first();
  await shot(page, [page.locator('#main-panel thead th', { hasText: 'User' }).first(), row.locator('td').nth(3)], 'FD-user-01-dashboard-empty-user');
  await context.close();
}
{ // expired approved-not-run: empty "Why it expired"
  const { page, context } = await login('requester');
  await page.goto(`${BASE}/batch-control/requests/20260930-083100-jrddtn/`);
  await shot(page, [page.locator('tr', { hasText: 'EXPIRED' }).first(), page.locator('tr', { hasText: 'Why it expired' })], 'FD-expired-01-empty-reason');
  await context.close();
}
{ // non-designated approver sees no explanation
  const { page, context } = await login('approver-unlisted');
  await page.goto(`${BASE}/batch-control/requests/20260930-083056-cuim56/`);
  await shot(page, page.locator('#main-panel h2, #main-panel h3').last(), 'FD-nondesignated-01-no-decision-no-reason');
  await context.close();
}
{ // incident rerun guidance
  const { page, context } = await login('approver-1');
  await page.goto(`${BASE}/batch-control/incidents/20260930-082024-zztwrv/`);
  await shot(page, page.locator('#main-panel p, #main-panel div', { hasText: /^A rerun is a run request/ }).last(), 'FD-rerun-01-guidance');
  await context.close();
  const r = await login('requester');
  await r.page.goto(`${BASE}/batch-control/incidents/20260930-082024-zztwrv/`);
  await shot(r.page, r.page.locator('#main-panel').first(), 'FD-rerun-02-requester-403');
  await r.context.close();
}
await close();
