// Scenario 4: a BatchControl/Manage holder revokes an active window early.
import { login, BASE, shot, text, log, close, sleep } from './lib.mjs';
const flat = (s) => s.replace(/\s+/g, ' ');
const G = process.argv[2];
let { page, context } = await login('manager');
await page.goto(`${BASE}/batch-control/grants/`);
const row = page.locator('tr', { hasText: G }).filter({ hasText: /min|sec/ }).first();
log('manager active row', flat(await row.innerText().catch(() => 'none')));
const ctl = page.locator(`a[data-url*="${G}"], form[action*="${G}"] button`).first();
log('revoke control', await ctl.evaluate((e) => e.outerHTML.slice(0, 300)).catch(() => 'none'));
await page.goto(`${BASE}/batch-control/grants/${G}/`);
log('grant detail for manager', flat(await text(page)).slice(0, 700));
await page.goto(`${BASE}/batch-control/grants/`);
const rv = page.locator(`a[data-url*="${G}/revoke"]`).first();
log('revoke on detail', await rv.count());
if (await rv.count()) {
  await shot(page, rv, 'S4-revoke-01-control');
  await rv.click(); await page.waitForTimeout(600);
  const dlg = page.locator('dialog[open]');
  if (await dlg.count()) { log('dialog', flat(await dlg.innerText())); await dlg.locator('button', { hasText: /yes|revoke/i }).first().click(); }
  const cf = page.locator('form[action*="revoke"] textarea');
  if (await cf.count()) { await cf.fill('Revoked early by manager (fresh e2e-04)'); await Promise.all([page.waitForLoadState('load'), page.locator('form[action*="revoke"] button').first().click()]); }
  await page.waitForLoadState('load'); await sleep(800);
  log('after revoke', page.url(), flat(await text(page)).slice(0, 600));
}
await context.close();
({ page, context } = await login('requester'));
const r = await page.goto(`${BASE}/job/fresh-daily/configure`);
log('requester configure after revoke', r.status());
await page.goto(`${BASE}/batch-control/grants/`);
const er = page.locator('tr', { hasText: G }).last();
log('requester ended row', flat(await er.innerText()));
await shot(page, er, 'S4-revoke-02-requester-ended-row');
await context.close();
({ page, context } = await login('admin'));
await page.goto(`${BASE}/batch-control/changes/`);
log('revoke record', (await page.locator('tbody tr', { hasText: 'GRANT_REVOKE' }).allInnerTexts()).slice(0, 1).map(flat));
await close();
