// Scenario 1: install the Batch Control variant from the Manage Jenkins monitor.
import { login, BASE, shot, text, groovy, log, close } from './lib.mjs';
const strat = () => groovy('def s=Jenkins.get().authorizationStrategy; println s.class.simpleName + " entries=" + s.grantedPermissionEntries.values().flatten().size()');
const { page } = await login('admin');
await page.goto(BASE + '/manage/');
const mon = page.locator('.jenkins-alert', { hasText: 'Install the Batch Control variant' }).first();
await shot(page, mon, 'S1-04-monitor-plain');
const html = await mon.evaluate((e) => e.outerHTML.replace(/\s+/g, ' ').slice(0, 1500));
log('monitor html', html);
const b = mon.locator('button, input[type=submit], a.jenkins-button').filter({ hasText: /Install/ }).first();
page.on('dialog', async (d) => { log('native dialog', d.message()); await d.accept(); });
await b.click();
await page.waitForTimeout(800);
const dlg = page.locator('dialog[open]').first();
if (await dlg.count()) { log('jenkins dialog', (await dlg.innerText()).replace(/\s+/g, ' ')); await shot(page, dlg, 'S1-04-install-confirm'); await dlg.locator('button', { hasText: /yes|ok|install/i }).first().click(); }
await page.waitForLoadState('load'); await page.waitForTimeout(1500);
log('after install', page.url(), (await text(page)).replace(/\s+/g, ' ').slice(0, 300));
await shot(page, '#main-panel', 'S1-04-after-install');
log('strategy', await strat());
await page.goto(BASE + '/batch-control/changes/');
log('recent non-trigger records', (await page.locator('tbody tr').allInnerTexts()).filter((r) => !r.startsWith('TRIGGER_BLOCKED')).slice(0, 5));
await close();
