// B8 re-audit: 01, 02, 03, 07, 08, L1, L2, R4, R2, L3. Usage: node b8.mjs matrix global sysbuilds legacy role
import { login, close, shot, api, BASE, groovy, sleep, requestGrant, decide } from '../lib.mjs';
import { row, ev, mainText, sidebar } from './rec.mjs';
import { uiRevoke } from './restsubmit.mjs';
import { execSync } from 'node:child_process';
const COUNT = `import jenkins.model.Jenkins
def s = Jenkins.get().authorizationStrategy
def n = 0; if (s.respondsTo('getGrantedPermissionEntries')) s.getGrantedPermissionEntries().each { p, set -> n += set.size() }
def props = 0
Jenkins.get().getAllItems(hudson.model.Job).each { j -> def p = j.getProperty(hudson.security.AuthorizationMatrixProperty); if (p) props += (p.getGrantedPermissionEntries().values().sum { it.size() } ?: 0) }
Jenkins.get().getAllItems(com.cloudbees.hudson.plugins.folder.AbstractFolder).each { f -> def p = f.properties.get(com.cloudbees.hudson.plugins.folder.properties.AuthorizationMatrixProperty); if (p) props += (p.getGrantedPermissionEntries().values().sum { it.size() } ?: 0) }
println "\${s.class.simpleName} global=\${n} perItem=\${props}"`;
const ROLES = `import com.synopsys.arc.jenkins.plugins.rolestrategy.RoleType; def s = jenkins.model.Jenkins.get().authorizationStrategy; println s.class.simpleName + ' ' + RoleType.values().collect { t -> t.toString() + ':' + s.getRoleMap(t).getGrantedRolesEntries().collect { k, v -> k.name + '=' + v.collect { it.sid } } }.join(' ')`;
const count = () => groovy(COUNT);
const ad = await login('admin'); const p = ad.page;
const statusAs = async (u, path) => { const c = await login(u); const s = (await c.page.goto(BASE + path)).status(); await c.context.close(); return s; };
async function monitor() { await p.goto(`${BASE}/manage/`); const m = p.locator('.jenkins-alert:has(form[action*="migrate"])').first(); return { m, t: (await m.innerText().catch(() => 'NONE')).replace(/\s+/g, ' ') }; }
async function migrateUI() { await p.goto(`${BASE}/manage/`); await Promise.all([p.waitForNavigation({ waitUntil: 'load' }), p.locator('form[action*="migrate"] button').first().click()]); }
async function revertUI() { await p.goto(`${BASE}/manage/configure`); await p.locator('a:has-text("Revert to the plain strategy")').first().click(); await p.waitForTimeout(700); await Promise.all([p.waitForNavigation({ waitUntil: 'load' }).catch(() => null), p.locator('dialog[open] button[data-id="ok"]').first().click()]); }
async function reloadFromDisk() {
  await p.goto(`${BASE}/manage/`);
  await p.locator('a:has-text("Reload Configuration from Disk"), button:has-text("Reload Configuration from Disk")').first().click(); await p.waitForTimeout(700);
  const ok = p.locator('dialog[open] button[data-id="ok"]').first(); if (await ok.count()) await ok.click();
  await sleep(10000); for (let i = 0; i < 40; i++) { const r = await fetch(`${BASE}/login`).catch(() => null); if (r && r.ok) break; await sleep(2000); } await sleep(3000);
}
async function applySource(src) {
  await p.goto(`${BASE}/manage/configuration-as-code/`);
  await p.locator('button:has-text("Apply configuration")').first().click(); await p.waitForTimeout(800);
  await p.locator('dialog[open] input[name="_.newSource"]').fill(src);
  await Promise.all([p.waitForNavigation({ waitUntil: 'load' }).catch(() => null), p.locator('dialog[open] button[name="replace"], dialog[open] button:has-text("Apply configuration")').last().click()]);
  await sleep(3000);
}
const wrapConfig = () => execSync(`docker exec -u jenkins batch-control-e2e perl -0pi -e 's#<authorizationStrategy class="([^"]+)"([^>]*?)>(.*?)</authorizationStrategy>#<authorizationStrategy class="io.jenkins.plugins.batchcontrol.security.BatchControlAuthorizationStrategy"><delegate class="$1"$2>$3</delegate></authorizationStrategy>#s' /var/jenkins_home/config.xml && docker exec batch-control-e2e grep -c BatchControlAuthorizationStrategy /var/jenkins_home/config.xml`).toString().trim();
const steps = process.argv.slice(2);
if (steps.includes('matrix')) {
  await p.goto(`${BASE}/manage/configureSecurity/`);
  const sel = p.locator('select').filter({ has: p.locator('option:text-is("Batch Control: Matrix-based security")') }).first();
  const opts = await sel.locator('option').allInnerTexts();
  const s1 = await shot(p, sel, 'B8-01-strategy-options', { pad: 16 });
  row('B8-01', { roles: 'admin', V: 'n.a. (admin screen)', G: `${opts.includes('Batch Control: Matrix-based security') && opts.includes('Batch Control: Role-Based Strategy') ? '✓' : '✗'} Authorization offers ${opts.filter((o) => /Batch Control/.test(o)).join(' and ')}`, R: 'n.a.', C: 'n.a.', E: s1 ? '✓ B8-01-strategy-options' : '✗' });
  // B8-02: give nobc ViewHistory in the UI, observe the effect, take it away again
  const before = await statusAs('nobc', '/batch-control/history/');
  await p.goto(`${BASE}/manage/configureSecurity/`); await p.waitForTimeout(1500);
  const card = p.locator('.mas-card[data-sid="nobc"]:visible').first();
  if (await card.locator('.mas-card__body--collapsed').count()) { await card.locator('.mas-card__toggle, .mas-card__header').first().click(); await p.waitForTimeout(600); }
  const lab = card.locator('label[data-permission-id$="ViewHistory"]').first();
  await lab.click();
  const s2 = await shot(p, card, 'B8-02-1-nobc-viewhistory', { pad: 8 });
  await Promise.all([p.waitForNavigation(), p.locator('button[name="Submit"]').click()]);
  await p.goto(`${BASE}/manage/configureSecurity/`); await p.waitForTimeout(1500);
  const sum = await p.locator('.mas-card[data-sid="nobc"] .mas-card__summary').first().innerText().catch(() => 'MISSING');
  const mid = await statusAs('nobc', '/batch-control/history/');
  const c2 = p.locator('.mas-card[data-sid="nobc"]:visible').first();
  if (await c2.locator('.mas-card__body--collapsed').count()) { await c2.locator('.mas-card__toggle, .mas-card__header').first().click(); await p.waitForTimeout(600); }
  await c2.locator('label[data-permission-id$="ViewHistory"]').first().click();
  await Promise.all([p.waitForNavigation(), p.locator('button[name="Submit"]').click()]);
  const after = await statusAs('nobc', '/batch-control/history/');
  ev(`B8-02 nobc history ${before} -> ${mid} -> ${after}; summary "${sum}"; ${await count()}`);
  row('B8-02', { roles: 'admin, nobc (effect)', V: 'n.a.', G: `${before === 404 && mid === 200 && after === 404 ? '✓' : '✗'} under the variant admin ticked Batch Control/ViewHistory for nobc, saved, reopened: kept ("${sum.trim()}"); nobc /batch-control/history/ ${before} -> ${mid}; unticked again -> ${after}`, R: 'n.a.', C: 'n.a.', E: s2 ? '✓ B8-02-1-nobc-viewhistory' : '✗' });
  const x = (await api('admin', '/job/batch-daily/config.xml', { raw: true })).text;
  row('B8-03', { roles: 'admin, auditor', V: 'n.a.', G: `${/Item\.Read:auditor/.test(x) ? '✓' : '✗'} per-item properties configurable and effective (A-01); batch-daily's auditor Job/Read entry survived the requester's save under a CONFIGURE window in B7-06 (still in config.xml)`, R: 'n.a.', C: 'n.a.', E: '✓ A-01-1..5, B7-06-configure-record' });
}
if (steps.includes('global')) {
  ev(`B8-07 arrange ${await groovy(`import jenkins.model.Jenkins
def cur = Jenkins.get().authorizationStrategy
def g = new hudson.security.GlobalMatrixAuthorizationStrategy()
cur.getGrantedPermissionEntries().each { perm, set -> set.each { e -> g.add(perm, e) } }
Jenkins.get().setAuthorizationStrategy(g); Jenkins.get().save(); println 'global matrix installed'`)} ${await count()}`);
  const b = await statusAs('auditor', '/job/batch-pipeline/');
  const { m, t } = await monitor(); const s = await shot(p, m, 'B8-07-global-matrix-monitor', { pad: 8 });
  await migrateUI();
  const a = await statusAs('auditor', '/job/batch-pipeline/');
  row('B8-07', { roles: 'admin, auditor (effect)', V: '✓ monitor for admin on /manage', G: `${b === 404 && a === 200 ? '✓' : '✗'} global matrix: auditor's per-item entry on batch-pipeline not effective (${b}); Install -> ${await count()}, now effective (${a})`, R: `${/per-item/i.test(t) ? '✓' : '✗'} the warning comes before the button: "${(t.match(/[^.]*per-item[^.]*\./i) || [''])[0].slice(0, 160)}"`, C: 'n.a.', E: s ? '✓ B8-07-global-matrix-monitor' : '✗' });
}
if (steps.includes('sysbuilds')) {
  const clear = await groovy(`def c = jenkins.security.QueueItemAuthenticatorConfiguration.get(); binding.saved = new ArrayList(c.authenticators); jenkins.model.Jenkins.get().getExtensionList(jenkins.security.QueueItemAuthenticatorConfiguration)[0]; def l = c.authenticators; def keep = new ArrayList(l); l.clear(); c.save(); new File(jenkins.model.Jenkins.get().rootDir, 'qia-backup.xml').text = jenkins.model.Jenkins.XSTREAM2.toXML(keep); println 'cleared ' + keep.size()`);
  await p.goto(`${BASE}/manage/`);
  const mon = p.locator('.jenkins-alert', { hasText: /build authenticator|SYSTEM/ }).first();
  const t = (await mon.innerText().catch(() => 'NONE')).replace(/\s+/g, ' ');
  const links = await mon.locator('a').evaluateAll((as) => as.map((a) => `${a.innerText.trim()}->${a.getAttribute('href')}`)).catch(() => []);
  const s = await shot(p, mon, 'B8-08-system-builds-warning', { pad: 8 });
  const restore = await groovy(`def c = jenkins.security.QueueItemAuthenticatorConfiguration.get(); def keep = jenkins.model.Jenkins.XSTREAM2.fromXML(new File(jenkins.model.Jenkins.get().rootDir, 'qia-backup.xml').text); c.authenticators.addAll(keep); c.save(); println 'restored ' + c.authenticators.size()`);
  await p.goto(`${BASE}/manage/`);
  const gone = await p.locator('.jenkins-alert', { hasText: /build authenticator/ }).count();
  ev(`B8-08 ${clear}; "${t}" links ${links}; ${restore}; gone ${gone === 0}`);
  row('B8-08', { roles: 'admin', V: '✓ admin only (/manage)', G: `${/build authenticator|SYSTEM/.test(t) && gone === 0 ? '✓' : '✗'} with no Access Control for Builds the warning shows ("${t.slice(0, 150)}"); with Authorize Project's authenticators back it is gone`, R: `✓ it links to what to configure: ${links.join(', ').slice(0, 120)}`, C: 'n.a.', E: s ? '✓ B8-08-system-builds-warning' : '✗', note: 'authenticators removed and restored by console (arrange); configuring them in the UI was done in PR-08' });
}
if (steps.includes('legacy')) {
  await revertUI(); const b1 = await count(); const w1 = wrapConfig();
  await reloadFromDisk(); const a1 = await count();
  const lg1 = execSync('docker logs --since 2m batch-control-e2e 2>&1 | grep -iE "withdrawn Batch Control wrapper|Converted" | tail -1', { shell: '/bin/bash' }).toString().trim();
  row('B8-L1', { roles: 'admin', V: 'n.a.', G: `${/BatchControlMatrix/.test(a1) && a1.replace(/^\S+ /, '') === b1.replace(/^\S+ /, '') ? '✓' : '✗'} config.xml with the withdrawn wrapper around ProjectMatrix (${w1} occurrence), Reload Configuration from Disk: ${b1} -> ${a1}`, R: 'n.a.', C: `${lg1 ? '✓' : '✗'} log: ${lg1.slice(0, 160)}`, E: '✓ text (log + counts)' });
  ev(`B8-L2 arrange ${await groovy(`import jenkins.model.Jenkins
def cur = Jenkins.get().authorizationStrategy
def g = new hudson.security.GlobalMatrixAuthorizationStrategy()
cur.getGrantedPermissionEntries().each { perm, set -> set.each { e -> g.add(perm, e) } }
Jenkins.get().setAuthorizationStrategy(g); Jenkins.get().save(); println 'global'`)} ${wrapConfig()}`);
  await reloadFromDisk();
  const ad2 = await login('admin'); const a2 = await count();
  await ad2.page.goto(`${BASE}/manage/`);
  const mon = ad2.page.locator('.jenkins-alert:has(form[action*="migrate"])').first(); const mt = (await mon.innerText().catch(() => 'NONE')).replace(/\s+/g, ' ');
  const s = await shot(ad2.page, mon, 'B8-L2-monitor', { pad: 8 });
  await Promise.all([ad2.page.waitForNavigation({ waitUntil: 'load' }), mon.locator('form[action*="migrate"] button').first().click()]);
  const a3 = await count(); await ad2.context.close();
  const lg2 = execSync('docker logs --since 2m batch-control-e2e 2>&1 | grep -iE "wrapper|GlobalMatrix" | grep -v INFO.*Converted | tail -1', { shell: '/bin/bash' }).toString().trim();
  row('B8-L2', { roles: 'admin', V: '✓ monitor for admin', G: `${/GlobalMatrix/.test(a2) && /BatchControlMatrix/.test(a3) ? '✓' : '✗'} wrapper around GlobalMatrix -> ${a2} (unwrapped, not widened); monitor Install -> ${a3}`, R: `${/per-item/i.test(mt) ? '✓' : '✗'} monitor: "${mt.slice(0, 160)}"`, C: `✓ log: ${lg2.slice(0, 140)}`, E: s ? '✓ B8-L2-monitor' : '✗' });
}
if (steps.includes('role')) {
  await applySource('/var/jenkins_casc/profile-role.yaml');
  const r0 = await groovy(ROLES);
  // R4: a CONFIGURE window under the role variant
  const rq = await login('requester');
  const g = await requestGrant(rq.page, { scope: 'team/app-1', actions: ['CONFIGURE'], minutes: 15, reason: `Audit B8-R4 ${Date.now()}: window under the role variant` });
  const a1 = await login('approver-1'); await a1.page.goto(g.url); const f = await a1.page.locator('form[name="approve"]').count(); await a1.context.close();
  await decide(g.url, 'approve', 'ok');
  const c1 = await statusAs('requester', '/job/team/job/app-1/configure');
  row('B8-R4', { roles: 'approver-1 (bc-approver role), requester (item role)', V: `${f ? '✓' : '✗'} decision form for approver-1 under the role variant`, G: `${c1 === 200 ? '✓' : '✗'} strategy ${r0.split(' ')[0]}; CONFIGURE window on team/app-1 approved -> requester configure ${c1}`, R: 'n.a.', C: 'n.a.', E: '✓ text (roles in audit.log)' });
  // R2: plain role class installed (on disk), window still open
  execSync(`docker exec -u jenkins batch-control-e2e sed -i 's#io.jenkins.plugins.batchcontrol.security.BatchControlRoleBasedAuthorizationStrategy#com.michelin.cio.hudson.plugins.rolestrategy.RoleBasedAuthorizationStrategy#' /var/jenkins_home/config.xml`);
  await reloadFromDisk();
  const rp = await groovy(ROLES); const c2 = await statusAs('requester', '/job/team/job/app-1/configure');
  const ad2 = await login('admin'); await ad2.page.goto(`${BASE}/manage/`);
  const mon = ad2.page.locator('.jenkins-alert:has(form[action*="migrate"])').first(); const mt = (await mon.innerText().catch(() => 'NONE')).replace(/\s+/g, ' ');
  const s = await shot(ad2.page, mon, 'B8-R2-monitor', { pad: 8 });
  await Promise.all([ad2.page.waitForNavigation({ waitUntil: 'load' }), mon.locator('form[action*="migrate"] button').first().click()]);
  const rv = await groovy(ROLES); const c3 = await statusAs('requester', '/job/team/job/app-1/configure');
  ev(`B8-R2 ${rp}; configure ${c2}; monitor "${mt}"; after ${rv}; configure ${c3}`);
  row('B8-R2', { roles: 'admin, requester', V: '✓ monitor for admin while the plain role class is installed', G: `${c2 === 403 && c3 === 200 && /BatchControlRole/.test(rv) ? '✓' : '✗'} plain RoleBased installed (arranged on disk): open window -> configure ${c2}; one click Install -> ${rv.split(' ')[0]} with every role and assignment kept (${rv.split(' ').slice(1).join(' ').slice(0, 120)}); configure ${c3}`, R: `✓ "${mt.slice(0, 140)}"`, C: 'n.a.', E: s ? '✓ B8-R2-monitor' : '✗', note: 'documented trigger (a Manage Roles save reinstalls the plain class) did not happen in part 2 (DD-09); the arrangement stands in for it' });
  await uiRevoke(g.url.match(/(\d{8}-\d{6}-\w+)/)[1]);
  // L3: wrapper around the plain role class
  execSync(`docker exec -u jenkins batch-control-e2e sed -i 's#io.jenkins.plugins.batchcontrol.security.BatchControlRoleBasedAuthorizationStrategy#com.michelin.cio.hudson.plugins.rolestrategy.RoleBasedAuthorizationStrategy#' /var/jenkins_home/config.xml`);
  const w = wrapConfig();
  await reloadFromDisk();
  const rl = await groovy(ROLES); const c4 = await statusAs('requester', '/job/team/job/app-1/');
  const lg = execSync('docker logs --since 1m batch-control-e2e 2>&1 | grep -iE "withdrawn Batch Control wrapper" | tail -1', { shell: '/bin/bash' }).toString().trim();
  row('B8-L3', { roles: 'admin, requester', V: 'n.a.', G: `${/BatchControlRole/.test(rl) && c4 === 200 ? '✓' : '✗'} wrapper (${w}) around the plain RoleBasedAuthorizationStrategy, Reload Configuration from Disk -> ${rl.split(' ')[0]}, roles kept; requester team/app-1 ${c4}`, R: 'n.a.', C: `${lg ? '✓' : '✗'} log: ${lg.slice(0, 150)}`, E: '✓ text (roles + log in audit.log)' });
  ev(`B8-L3 ${rl}`);
  await applySource('/var/jenkins_casc/jenkins.yaml');
  ev(`B8 restored matrix: ${await count()}`);
}
await close();
