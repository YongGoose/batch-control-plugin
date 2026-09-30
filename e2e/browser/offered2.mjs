import { login, close, shot, BASE, log, errText } from './lib.mjs';
const L = 'section-b.log';
for (const u of ['configurer', 'manager']) {
  const { context, page } = await login(u);
  await page.goto(`${BASE}/job/batch-pipeline/`);
  const entry = page.locator('#side-panel a:has-text("Request Run")');
  await shot(page, entry, `OFFERED-${u}-request-run-entry`, { pad: 10 });
  await entry.click(); await page.waitForLoadState('load');
  await page.fill('form[name="batch-control-request"] textarea[name="reason"]', `Offered to ${u} without Job/Build.`);
  await page.locator('form[name="batch-control-request"] input[name="approvers"][value="approver-1"] + label').click();
  const [r] = await Promise.all([page.waitForNavigation().catch(() => null), page.locator('button:has-text("Submit Request")').click()]);
  const t = errText(await page.content());
  await shot(page, ['#main-panel h1', '#main-panel .error, #main-panel p'], `OFFERED-${u}-request-run-refused`, { pad: 12 });
  log(L, `OFFERED ${u} (Request, no Job/Build): Request Run entry and form shown; submit -> ${r && r.status()} "${t}"`);
  await context.close();
}
// configurer: the delete veto's own advice leads to a 403
const { context, page } = await login('configurer');
await page.goto(`${BASE}/job/team/job/app-free/`);
page.once('dialog', (d) => d.accept());
await page.locator('#side-panel a:has-text("Delete Project")').click(); await page.waitForTimeout(800);
const ok = page.locator('dialog[open] button[data-id="ok"]').first(); if (await ok.count()) await Promise.all([page.waitForNavigation().catch(() => null), ok.click()]);
const veto = errText(await page.content());
const g = await page.goto(`${BASE}/batch-control/grants/`);
const gt = errText(await page.content());
await shot(page, ['#main-panel h1', '#main-panel .error, #main-panel p'], 'OFFERED-configurer-grants-403', { pad: 12 });
await page.goto(`${BASE}/batch-control/`);
const rootSide = (await page.locator('#side-panel a').allInnerTexts()).map((x) => x.trim()).filter(Boolean);
log(L, `OFFERED configurer Delete Project -> "${veto}"; following it: /batch-control/grants/ -> ${g.status()} "${gt}"; Batch Control links for configurer ${JSON.stringify(rootSide)}`);
await close();
