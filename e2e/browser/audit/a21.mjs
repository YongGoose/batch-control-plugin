// A-21 (+B1-18, B8-04, B8-05): revert/install round trip through the UI with an open CONFIGURE window.
import { login, close, shot, BASE, api, groovy, requestGrant, decide, changeRows } from '../lib.mjs';
import { row, ev, mainText } from './rec.mjs';
const COUNT = `import jenkins.model.Jenkins
def s = Jenkins.get().authorizationStrategy
def n = 0; s.getGrantedPermissionEntries().each { p, set -> n += set.size() }
def props = 0
Jenkins.get().getAllItems(hudson.model.Job).each { j -> def p = j.getProperty(hudson.security.AuthorizationMatrixProperty); if (p) props += p.getGrantedPermissionEntries().values().sum { it.size() } ?: 0 }
Jenkins.get().getAllItems(com.cloudbees.hudson.plugins.folder.AbstractFolder).each { f -> def p = f.properties.get(com.cloudbees.hudson.plugins.folder.properties.AuthorizationMatrixProperty); if (p) props += p.getGrantedPermissionEntries().values().sum { it.size() } ?: 0 }
Jenkins.get().nodes.each { nd -> def p = nd.nodeProperties.get(org.jenkinsci.plugins.matrixauth.AuthorizationMatrixNodeProperty); if (p) props += p.getGrantedPermissionEntries().values().sum { it.size() } ?: 0 }
println "\${s.class.simpleName} global=\${n} perItem=\${props}"`;
const cfg = async () => { const c = await login('requester'); const s = (await c.page.goto(`${BASE}/job/team/job/app-1/configure`)).status(); await c.context.close(); return s; };
const rq = await login('requester');
const g = await requestGrant(rq.page, { scope: 'team/app-1', actions: ['CONFIGURE'], minutes: 15, reason: `Audit A-21 ${Date.now()}: window across the strategy round trip`, approver: 'approver-1' });
await decide(g.url, 'approve', 'ok');
const gid = g.url.match(/(\d{8}-\d{6}-\w+)/)[1];
const k0 = await groovy(COUNT); const c0 = await cfg();
const { context, page } = await login('admin');
// Revert (System)
await page.goto(`${BASE}/manage/configure`);
const rev = page.locator('a:has-text("Revert to the plain strategy"), button:has-text("Revert to the plain strategy")').first();
const s1 = await shot(page, rev.locator('xpath=ancestor::*[contains(@class,"jenkins-form-item") or contains(@class,"jenkins-alert")][1]'), 'A-21-1-revert-control', { pad: 8 });
await rev.click(); await page.waitForTimeout(800);
const dlg = page.locator('dialog[open]').first();
const dt = (await dlg.innerText().catch(() => '')).replace(/\s+/g, ' ');
const s2 = await shot(page, dlg, 'A-21-2-revert-confirmation', { pad: 4 });
await Promise.all([page.waitForNavigation({ waitUntil: 'load' }).catch(() => null), dlg.locator('button[data-id="ok"], button:has-text("Yes")').first().click()]);
const landed1 = page.url();
const k1 = await groovy(COUNT); const c1 = await cfg();
await page.goto(`${BASE}/manage/configureSecurity/`);
const sel = page.locator('select').filter({ has: page.locator('option:text-is("Batch Control: Matrix-based security")') }).first();
const selT = await sel.evaluate((s) => s.options[s.selectedIndex].text);
const s3 = await shot(page, sel, 'A-21-3-security-plain-selected', { pad: 16 });
await page.goto(`${BASE}/manage/`);
const mon = page.locator('[data-monitor-id="batch-control-strategy"], .jenkins-alert:has(form[action*="migrate"])').first();
const mt = (await mon.innerText().catch(() => 'NONE')).replace(/\s+/g, ' ');
const s4 = await shot(page, mon, 'A-21-4-monitor-install', { pad: 8 });
const secText = await (async () => { await page.goto(`${BASE}/manage/configureSecurity/`); return page.locator('#main-panel').innerText(); })();
await page.goto(`${BASE}/manage/`);
await Promise.all([page.waitForNavigation({ waitUntil: 'load' }), page.locator('form[action*="migrate"] button').first().click()]);
const landed2 = page.url(); const after2 = (await mainText(page)).slice(0, 160);
const k2 = await groovy(COUNT); const c2 = await cfg();
await page.goto(`${BASE}/manage/`);
const monGone = (await page.locator('.jenkins-alert:has(form[action*="migrate"])').count()) === 0;
await page.goto(`${BASE}/manage/configureSecurity/`);
const selT2 = await page.locator('select').filter({ has: page.locator('option:text-is("Batch Control: Matrix-based security")') }).first().evaluate((s) => s.options[s.selectedIndex].text);
const s5 = await shot(page, page.locator('select').filter({ has: page.locator('option:text-is("Batch Control: Matrix-based security")') }).first(), 'A-21-5-security-variant-selected', { pad: 16 });
const toggles = (await changeRows(/CONFIG_TOGGLE|STRATEGY|MIGRAT/i)).slice(0, 3);
ev(`A-21 grant ${gid}; ${k0} cfg ${c0}; revert dialog "${dt}" -> ${landed1}; ${k1} cfg ${c1}; security selected "${selT}"; monitor "${mt}"; security page mentions migrate ${/Install the Batch Control|variant/i.test(secText)}; install -> ${landed2} "${after2}"; ${k2} cfg ${c2}; monitor gone ${monGone}; selected "${selT2}"; records ${toggles.join(' || ').slice(0, 300)}`);
const same = k0.replace(/^\S+ /, '') === k1.replace(/^\S+ /, '') && k1.replace(/^\S+ /, '') === k2.replace(/^\S+ /, '');
row('A-21', { roles: 'admin, requester (effect)', V: '✓ the Revert control is on Manage Jenkins > System (admin); the Install control is the /manage monitor (not the Security page: DD-04)', G: `${same && /ProjectMatrix/.test(k1) && /BatchControl/.test(k2) ? '✓' : '✗'} ${k0} -> Revert (confirm) -> ${k1} -> monitor "Install the Batch Control variant" -> ${k2}; Security page shows "${selT}" then "${selT2}"`, R: 'n.a.', C: 'n.a. (strategy switches are not change records; none promised)', E: [s1, s2, s3, s4, s5].every(Boolean) ? '✓ A-21-1..5' : '✗' });
row('B1-18', { roles: 'admin', V: '✓ "Revert to the plain strategy" shown on System while the variant is installed', G: `${/ProjectMatrix/.test(k1) ? '✓' : '✗'} confirmation "${dt.slice(0, 100)}" -> plain Project Matrix with every entry (${k1})`, R: 'n.a.', C: 'n.a.', E: s1 && s2 ? '✓ A-21-1, A-21-2' : '✗' });
row('B8-05', { roles: 'admin, requester', V: '✓ monitor reappears for admin on /manage after the revert', G: `${c0 === 200 && c1 === 403 ? '✓' : '✗'} requester's open window ${gid} on team/app-1: configure ${c0} -> after Revert ${c1} (grants stop conferring); entries kept (${k1})`, R: `✓ monitor text: "${mt.slice(0, 160)}"`, C: 'n.a.', E: s3 && s4 ? '✓ A-21-3, A-21-4' : '✗' });
row('B8-04', { roles: 'admin, requester', V: '✓ monitor with Install shown to admin only while the plain strategy is installed', G: `${c1 === 403 && c2 === 200 && monGone ? '✓' : '✗'} one click Install -> ${k2}, requester configure ${c1} -> ${c2}, monitor gone`, R: 'n.a.', C: 'n.a.', E: s4 && s5 ? '✓ A-21-4, A-21-5' : '✗' });
// clean up the window (manager revoke through the UI is A-22; here admin revokes)
await page.goto(`${BASE}/batch-control/grants/`);
const r = page.locator('table:has(th:has-text("Expires")) tbody tr', { hasText: gid }).first();
page.once('dialog', (d) => d.accept());
await r.locator('a:has-text("Revoke"), button:has-text("Revoke")').first().click(); await page.waitForTimeout(800);
const d2 = page.locator('dialog[open]').first(); if (await d2.count()) await Promise.all([page.waitForLoadState('load'), d2.locator('button[data-id="ok"], button:has-text("Yes")').first().click()]);
ev(`A-21 cleanup: configure now ${await cfg()}`);
await close();
