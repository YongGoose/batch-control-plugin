// DEF-37 / D-50a/b: a global default build authorization removes the warning; a default with Configure keeps it.
// Usage: node f4b.mjs add <user> | remove
import { login, close, shot, BASE, groovy } from '../lib.mjs';
import { ev } from '../audit/rec.mjs';
const [op, user] = process.argv.slice(2);
const { page } = await login('admin');
await page.goto(`${BASE}/manage/configureSecurity/`); await page.waitForTimeout(1500);
const sec = page.locator('.jenkins-section', { hasText: 'Access Control for Builds' }).first();
const chunk = () => sec.locator('.repeated-chunk', { hasText: 'Project default Build Authorization' }).first();
if (op === 'add') {
  if (!(await chunk().count())) {
    await sec.locator('button:has-text("Add")').last().click(); await page.waitForTimeout(600);
    await page.locator('.jenkins-dropdown button, .jenkins-dropdown__item').filter({ hasText: 'Project default Build Authorization' }).first().click(); await page.waitForTimeout(1200);
  }
  const c = chunk();
  const sel = c.locator('select').first();
  const opts = await sel.locator('option').allInnerTexts();
  await sel.selectOption({ label: opts.find((o) => /Specific User/.test(o)) }); await page.waitForTimeout(800);
  const uid = c.locator('input[name="_.userid"], input[name="userid"]').first();
  await uid.fill(user); await page.waitForTimeout(500);
  const pw = c.locator('input[type="password"]:visible').first(); if (await pw.count()) { /* only needed when the configuring user is not an admin */ }
  await shot(page, c, `C-08-3-global-default-${user}`, { pad: 8 });
} else {
  const c = chunk();
  if (await c.count()) { await c.locator('button:has-text("Delete"), button[title="Delete"]').last().click(); await page.waitForTimeout(800); }
}
await Promise.all([page.waitForNavigation().catch(() => null), page.locator('button[name="Submit"]').click()]);
ev(`F4b ${op} ${user || ''}: ${(await groovy('println jenkins.security.QueueItemAuthenticatorConfiguration.get().authenticators.collect{ it.class.simpleName + (it.respondsTo("getStrategy") ? ":" + it.strategy?.class?.simpleName + (it.strategy?.respondsTo("getUserid") ? "(" + it.strategy.userid + ")" : "") : "") }')).trim()}`);
await page.goto(`${BASE}/manage/`);
const w = (await page.locator('#main-panel .jenkins-alert', { hasText: /run as SYSTEM|account with Configure/i }).first().innerText().catch(() => 'NONE')).replace(/\s+/g, ' ');
ev(`F4b monitor after ${op} ${user || ''}: "${w.slice(0, 200)}"`);
await shot(page, page.locator('#main-panel .jenkins-alert', { hasText: /run as SYSTEM|account with Configure/i }).first(), `C-08-4-monitor-after-${op}-${user || 'none'}`, { pad: 8 });
await close();
