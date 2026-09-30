// Re-verification F: FD-15 (typed values kept on a refused config save), UX-17 (Rebuild Last on a refused submit page).
import { login, BASE, shot, groovy, log, close } from '../lib.mjs';
const { page, context } = await login('manager');
await page.goto(`${BASE}/batch-control-configuration/`);
await page.fill('input[name="_.maxGrantMinutes"]', '123');
await page.fill('input[name="_.retentionMonths"]', '0');
await Promise.all([page.waitForLoadState('load'), page.click('button[name="Submit"]')]);
log('FD-15 kept maxGrant/retention', await page.locator('input[name="_.maxGrantMinutes"]').inputValue(), await page.locator('input[name="_.retentionMonths"]').inputValue(), '| next-to-field errors', await page.$$eval('.jenkins-form-item .error', (es) => es.map((e) => e.innerText.trim())));
await shot(page, [page.locator('.jenkins-alert-danger').first(), page.locator('input[name="_.retentionMonths"]').locator('xpath=ancestor::div[contains(@class,"jenkins-form-item")][1]')], 'FD15-typed-values-reset');
log('stored', await groovy('def c=io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration.get(); println([c.maxGrantMinutes,c.retentionMonths])'));
await context.close();
const r = await login('requester');
await r.page.goto(`${BASE}/job/fresh-daily/batch-control/`);
await Promise.all([r.page.waitForLoadState('load'), r.page.click('button[name="Submit"]')]);
log('UX-17 sidebar after refused submit', (await r.page.locator('#tasks').innerText()).replace(/\s+/g, ' '));
await close();
