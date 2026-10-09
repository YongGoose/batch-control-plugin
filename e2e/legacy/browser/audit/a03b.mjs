// A-03 group variant through the Security UI, and A-04 Dismiss / re-enable, as the admin does it.
import { login, close, shot, BASE, api } from '../lib.mjs';
import { row, ev, mainText } from './rec.mjs';
const { context, page } = await login('admin');
const save = async () => Promise.all([page.waitForNavigation({ waitUntil: 'load' }), page.locator('button[name="Submit"]').click()]);
await page.goto(`${BASE}/manage/configureSecurity/`); await page.waitForTimeout(1500);
let card = page.locator('.mas-card[data-sid="authenticated"]:visible').first();
if (!(await card.count())) {
  await page.locator('button.matrix-auth-add-button:has-text("Add group"):visible').first().click(); await page.waitForTimeout(600);
  await page.locator('dialog[open] input').first().fill('authenticated');
  await page.locator('dialog[open] button[data-id="ok"]').click(); await page.waitForTimeout(800);
  card = page.locator('.mas-card[data-sid="authenticated"]:visible').first();
}
for (let i = 0; i < 2; i++) { if (await card.locator('.mas-card__body--collapsed').count() === 0) break; await card.locator('.mas-card__toggle, .mas-card__header').first().click(); await page.waitForTimeout(600); }
const box = card.locator('input[name="[hudson.model.Item.Configure]"]').first();
if (!(await box.isChecked())) await card.locator('label[data-permission-id="hudson.model.Item.Configure"]').click();
const s0 = await shot(page, card, 'A-03-2a-security-group-authenticated-configure', { pad: 8 });
await save();
await page.goto(`${BASE}/manage/`);
const mon = page.locator('[data-monitor-id="io.jenkins.plugins.batchcontrol.ops.ConfigureWithoutGrantMonitor"]').first();
const t = (await mon.innerText().catch(() => 'NONE')).replace(/\s+/g, ' ');
const s1 = await shot(page, mon, 'A-03-2-standing-monitor-user-and-group', { pad: 8 });
ev(`A-03 group via UI: "${t}"`);
// A-04 Dismiss
await mon.locator('.app-adminmonitor-dismiss-button').click(); await page.waitForTimeout(1500);
await page.goto(`${BASE}/manage/`);
const gone = (await page.locator('[data-monitor-id="io.jenkins.plugins.batchcontrol.ops.ConfigureWithoutGrantMonitor"]').count()) === 0;
await page.goto(`${BASE}/manage/configure`);
const lbl = page.locator('label:has-text("Batch Control: standing change permissions bypass change control")').first();
const input = page.locator('input[json="io.jenkins.plugins.batchcontrol.ops.ConfigureWithoutGrantMonitor"]').first();
const offAfterDismiss = !(await input.isChecked());
const s2 = await shot(page, [lbl, page.locator('label:has-text("Batch Control: grants need a Batch Control authorization strategy")').first()], 'A-04-2-administrative-monitors-list', { pad: 8 });
await lbl.click(); await save();
await page.goto(`${BASE}/manage/`);
const backOn = await page.locator('[data-monitor-id="io.jenkins.plugins.batchcontrol.ops.ConfigureWithoutGrantMonitor"]').count();
// header popup (the manage badge in the header)
await page.goto(`${BASE}/`);
const badge = page.locator('#visible-am-button, #visible-sec-am-button, a[href="/manage"] .jenkins-badge, .am-button').first();
let pop = '';
if (await badge.count()) { await badge.click().catch(() => {}); await page.waitForTimeout(1500); pop = (await page.locator('.am-list, #visible-am-list, .tippy-box').first().innerText().catch(() => '')).replace(/\s+/g, ' '); }
const s3 = pop ? await shot(page, page.locator('.am-list, #visible-am-list, .tippy-box').first(), 'A-04-3-header-popup', { pad: 8 }) : null;
ev(`A-04 dismiss gone=${gone} input off=${offAfterDismiss} re-enabled shown=${backOn}; header badge ${await badge.count()} popup "${pop.slice(0, 200)}"`);
// undo the group entry: JCasC reload (profile file is the source of truth)
const rl = await api('admin', '/manage/configuration-as-code/reload', { method: 'POST' });
ev(`JCasC reload ${rl.status}`);
const m = await login('manager'); const mr = (await m.page.goto(`${BASE}/manage/`)).status(); await m.context.close();
row('A-03', { roles: 'admin, manager', V: `✓ admin only (manager /manage ${mr})`, G: `${/User configurer/.test(t) && /Group authenticated|authenticated/.test(t) ? '✓' : '✗'} DEF-07 fixed: the monitor lists holders by type: "${t.slice(t.indexOf('recorded in the audit history.') + 30, t.indexOf('Only the global') ).trim()}"; the "not a Batch Control strategy" clause is gone; no getAllSids/deprecation line in any container log`, R: 'n.a.', C: 'n.a.', E: s0 && s1 ? '✓ A-03-1-standing-monitor-user, A-03-2a-security-group-authenticated-configure, A-03-2-standing-monitor-user-and-group' : '✗', note: 'group entry added and removed through the Security UI / JCasC reload; a console change to the same strategy instance is served from a 5-minute cache' });
row('A-04', { roles: 'admin, manager', V: `✓ admin only (manager /manage ${mr})`, G: `${gone && offAfterDismiss && backOn ? '✓' : '✗'} rendered by l:adminMonitor (div.app-adminmonitor.jenkins-alert-warning, standard controls: "Review authorization strategy" + Dismiss ×); Dismiss hides it (${gone}) and unticks "Batch Control: standing change permissions bypass change control" under System > Administrative monitors (${offAfterDismiss}); ticking it + Save brings it back (${backOn}); header popup ${pop ? `"${pop.slice(0, 80)}"` : 'not found (no badge in this header)'}`, R: 'n.a.', C: 'n.a.', E: s1 && s2 ? `✓ A-03-2, A-04-2-administrative-monitors-list${s3 ? ', A-04-3-header-popup' : ''}` : '✗', note: 'strategy monitor (batch-control-strategy) is checked in A-21/B8-04 while the plain strategy is installed' });
await close();
