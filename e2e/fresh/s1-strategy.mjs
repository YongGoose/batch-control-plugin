// Scenario 1, strategy migration: revert to plain, then install the variant from the monitor.
import { login, BASE, shot, text, groovy, log, close } from './lib.mjs';

const S = 'S1';
const who = process.argv[2] || 'admin';
const strat = () => groovy('def s=Jenkins.get().authorizationStrategy; println s.class.simpleName + " entries=" + (s.respondsTo("getGrantedPermissionEntries")? s.grantedPermissionEntries.values().flatten().size() : "?")');
log('start', await strat());

{
  const { page } = await login(who);
  await page.goto(BASE + '/batch-control-configuration/');
  const btn = page.locator('button:has-text("Revert to the plain strategy"), input[value="Revert to the plain strategy"], a:has-text("Revert to the plain strategy")').first();
  log(who, 'revert button visible', await btn.isVisible().catch(() => false));
  const form = await btn.evaluate((b) => { const f = b.closest('form'); return f ? `${f.method} ${f.action}` : b.outerHTML.slice(0, 300); });
  log('revert form', form);
  await shot(page, btn.locator('xpath=ancestor::div[contains(@class,"jenkins-form-item") or contains(@class,"setting-main")][1]'), `${S}-04-${who}-revert-button`);
  page.on('dialog', async (d) => { log('dialog:', d.message()); await d.accept(); });
  await btn.click();
  await page.waitForTimeout(800);
  const dlg = page.locator('dialog[open], .jenkins-dialog').first();
  log('jenkins dialog', (await dlg.innerText().catch(() => 'none')).replace(/\s+/g, ' '));
  await shot(page, dlg, `${S}-04-${who}-revert-confirm`);
  const resp = page.waitForResponse((r) => r.url().includes('revert'), { timeout: 10000 }).catch(() => null);
  await dlg.locator('button', { hasText: /yes|ok|confirm|revert/i }).first().click();
  const rr = await resp;
  log('revert POST status', rr && rr.status());
  await page.waitForLoadState('load');
  await page.waitForTimeout(1500);
  const t = await text(page);
  log(who, 'after revert url', page.url(), '|', t.replace(/\s+/g, ' ').slice(0, 400));
  await shot(page, '#main-panel', `${S}-04-${who}-after-revert`);
  log('strategy after revert', await strat());
  await page.context().close();
}

if (process.argv[3] === 'reinstall') {
  const { page } = await login('admin');
  await page.goto(BASE + '/manage/');
  const mon = page.locator('.jenkins-alert, .alert', { hasText: /grant/i }).first();
  log('monitor text', (await mon.innerText().catch(() => 'none')).replace(/\s+/g, ' '));
  await shot(page, mon, `${S}-04-monitor-plain`);
  const b = mon.locator('button, input[type=submit]', { hasText: /Install/ }).first();
  const b2 = (await b.count()) ? b : mon.locator('input[value*="Install"]').first();
  page.on('dialog', async (d) => { log('dialog:', d.message()); await d.accept(); });
  await Promise.all([page.waitForLoadState('load'), b2.click()]);
  await page.waitForTimeout(1500);
  log('after install url', page.url(), (await text(page)).replace(/\s+/g, ' ').slice(0, 300));
  await shot(page, '#main-panel', `${S}-04-after-install`);
  log('strategy after install', await strat());
  await page.context().close();
}
await close();
