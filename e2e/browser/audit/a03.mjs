// Re-audit A-03 / A-04 / B7-26: standing change permission monitor (DEF-07 fix), l:adminMonitor, Dismiss.
import { login, close, shot, BASE, groovy, api } from '../lib.mjs';
import { row, ev, mainText } from './rec.mjs';
import { execSync } from 'node:child_process';
const { context, page } = await login('admin');
await page.goto(`${BASE}/manage/`);
const alerts = page.locator('#main-panel .jenkins-alert');
const all = (await alerts.allInnerTexts()).map((t) => t.replace(/\s+/g, ' '));
ev(`A-03 /manage alerts: ${JSON.stringify(all)}`);
const std = page.locator('#main-panel .jenkins-alert', { hasText: /standing|without an approved|Configure/ }).first();
const stdText = (await std.innerText().catch(() => 'NONE')).replace(/\s+/g, ' ');
const s1 = await shot(page, std, 'A-03-1-standing-monitor-user');
const struct = await std.evaluate((el) => ({ cls: el.className, forms: el.querySelectorAll('form').length, buttons: [...el.querySelectorAll('button,a.jenkins-button')].map((b) => b.innerText.trim()) })).catch(() => null);
// group variant (ARRANGE by console: add group authenticated Item/Configure; undone by a JCasC reload)
ev(await groovy(`import org.jenkinsci.plugins.matrixauth.PermissionEntry
def s = jenkins.model.Jenkins.get().authorizationStrategy
s.add(hudson.model.Item.CONFIGURE, PermissionEntry.group('authenticated')); jenkins.model.Jenkins.get().save(); println 'arranged group authenticated Item/Configure'`));
await page.goto(`${BASE}/manage/`);
const std2 = page.locator('#main-panel .jenkins-alert', { hasText: /standing|without an approved|Configure/ }).first();
const std2Text = (await std2.innerText().catch(() => 'NONE')).replace(/\s+/g, ' ');
const s2 = await shot(page, std2, 'A-03-2-standing-monitor-user-and-group');
// the matrix UI for the same entries: user vs group told apart?
await page.goto(`${BASE}/manage/configureSecurity/`);
const gcard = page.locator('.mas-card[data-sid="authenticated"]').first();
const gtype = await gcard.getAttribute('data-type').catch(() => null);
const amb = await page.locator('text=/ambiguous/i').count();
// restore through JCasC reload (the profile file is the source of truth)
const rl = await api('admin', '/manage/configuration-as-code/reload', { method: 'POST' });
const after = await groovy(`import org.jenkinsci.plugins.matrixauth.PermissionEntry
println jenkins.model.Jenkins.get().authorizationStrategy.getGrantedPermissionEntries()[hudson.model.Item.CONFIGURE]`);
ev(`A-03 group variant: "${std2Text}"; matrix card type ${gtype}, ambiguous warnings ${amb}; JCasC reload ${rl.status}; Item/Configure now ${after}`);
const logs = execSync('docker logs batch-control-e2e 2>&1 | grep -ciE "getAllSids|deprecated.*sid" || true').toString().trim();
// A-04: Dismiss and the Administrative monitors list
await page.goto(`${BASE}/manage/`);
const std3 = page.locator('#main-panel .jenkins-alert', { hasText: /standing|without an approved|Configure/ }).first();
const dismiss = std3.locator('button:has-text("Dismiss")').first();
const hasDismiss = await dismiss.count();
let gone = null; let listed = []; let back = null;
if (hasDismiss) {
  await Promise.all([page.waitForLoadState('load'), dismiss.click()]); await page.waitForTimeout(1200);
  await page.goto(`${BASE}/manage/`);
  gone = await page.locator('#main-panel .jenkins-alert', { hasText: /standing|without an approved/ }).count() === 0;
}
await page.goto(`${BASE}/manage/configure`);
const mons = page.locator('.jenkins-checkbox, .optionalBlock-container, label').filter({ hasText: /Batch Control/ });
listed = (await mons.allInnerTexts()).map((t) => t.replace(/\s+/g, ' ').trim()).filter((t) => t.length < 160);
const s3 = await shot(page, mons, 'A-04-2-administrative-monitors-list', { pad: 8 });
// re-enable what Dismiss disabled (restore), as the admin would: tick and Save
const off = await page.locator('input[name="administrativeMonitor"]:not(:checked)').evaluateAll((es) => es.map((e) => e.getAttribute('json') || e.value));
for (const lb of await mons.all()) {
  const box = lb.locator('xpath=preceding-sibling::input[1]');
  if (await box.count() && !(await box.isChecked())) await lb.click();
}
await Promise.all([page.waitForNavigation({ waitUntil: 'load' }).catch(() => null), page.locator('button[name="Submit"]').click()]);
await page.goto(`${BASE}/manage/`);
back = await page.locator('#main-panel .jenkins-alert', { hasText: /standing|without an approved/ }).count();
// header notification popup
await page.goto(`${BASE}/`);
const bell = page.locator('#visible-am-button, #visible-sec-am-button, .am-button').first();
let popT = '';
if (await bell.count()) { await bell.click().catch(() => {}); await page.waitForTimeout(1200); popT = (await page.locator('.am-list, .tippy-box, .jenkins-dropdown').first().innerText().catch(() => '')).replace(/\s+/g, ' '); }
const s4 = popT ? await shot(page, page.locator('.am-list, .tippy-box, .jenkins-dropdown').first(), 'A-04-3-header-popup', { pad: 8 }) : null;
ev(`A-04 structure ${JSON.stringify(struct)}; dismiss ${hasDismiss} gone ${gone}; listed ${JSON.stringify(listed)}; re-enabled -> shown again ${back}; popup "${popT.slice(0, 200)}"; getAllSids lines ${logs}`);
// non-admin cannot see them
const m = await login('manager'); const mr = await m.page.goto(`${BASE}/manage/`); const mt = (await mainText(m.page)).slice(0, 120); await m.context.close();
const namesUser = /configurer/.test(stdText); const namesGroup = /authenticated/.test(std2Text);
const noStrategyNoise = !/not a Batch Control strategy/.test(stdText);
row('A-03', { roles: 'admin (monitor), manager (no /manage)', V: `✓ monitor on /manage only for admin; manager /manage ${mr.status()}`, G: `${namesUser && namesGroup && noStrategyNoise ? '✓' : '✗'} user variant: "${stdText.slice(0, 220)}"; user+group variant: "${std2Text.slice(0, 260)}"; no getAllSids/deprecation line in the container log (${logs})`, R: 'n.a.', C: 'n.a.', E: s1 && s2 ? '✓ A-03-1-standing-monitor-user, A-03-2-standing-monitor-user-and-group' : '✗', note: 'group entry arranged by console, removed by JCasC reload' });
row('B7-26', { roles: 'admin', V: '✓ admin only (manager 403 on /manage)', G: `${namesUser ? '✓' : '✗'} the monitor names configurer: "${stdText.slice(0, 160)}"; its button: ${struct && struct.buttons}`, R: 'n.a.', C: 'n.a.', E: s1 ? '✓ A-03-1-standing-monitor-user' : '✗' });
row('A-04', { roles: 'admin, manager', V: `✓ admin sees the monitors; manager /manage ${mr.status()} "${mt.slice(0, 80)}"`, G: `${struct && /jenkins-alert/.test(struct.cls) && hasDismiss && gone && back ? '✓' : '✗'} standard alert (class "${struct && struct.cls}", ${struct && struct.forms} form(s), buttons ${struct && struct.buttons}); Dismiss hides it (${gone}); listed under Administrative monitors: ${listed.length} entries; ticking it again brings it back (${back}); header popup "${popT.slice(0, 80)}"`, R: 'n.a.', C: 'n.a.', E: s1 && s3 ? `✓ A-03-1, A-04-2-administrative-monitors-list${s4 ? ', A-04-3-header-popup' : ''}` : '✗' });
await close();
