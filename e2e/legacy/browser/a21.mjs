// A-21: one-click migration from plain Project Matrix and the revert path.
import { login, close, BASE, shot, log, groovy } from './lib.mjs';
const L = 'section-a.log';
const COUNT = `
import jenkins.model.Jenkins
def s = Jenkins.get().authorizationStrategy
def n = 0; s.getGrantedPermissionEntries().each { p, set -> n += set.size() }
def items = 0
Jenkins.get().allItems.each { it -> def pr = (it.respondsTo('getProperties') ? null : null) }
def props = 0
Jenkins.get().getAllItems(hudson.model.Job).each { j -> def p = j.getProperty(hudson.security.AuthorizationMatrixProperty); if (p) props += p.getGrantedPermissionEntries().values().sum { it.size() } ?: 0 }
Jenkins.get().getAllItems(com.cloudbees.hudson.plugins.folder.AbstractFolder).each { f -> def p = f.properties.get(com.cloudbees.hudson.plugins.folder.properties.AuthorizationMatrixProperty); if (p) props += p.getGrantedPermissionEntries().values().sum { it.size() } ?: 0 }
Jenkins.get().nodes.each { nd -> def p = nd.nodeProperties.get(org.jenkinsci.plugins.matrixauth.AuthorizationMatrixNodeProperty); if (p) props += p.getGrantedPermissionEntries().values().sum { it.size() } ?: 0 }
println "\${s.class.simpleName} global=\${n} perItem=\${props}"`;
const before = await groovy(COUNT);
// ARRANGE: install a plain ProjectMatrixAuthorizationStrategy with the same entries (the state an existing instance is in)
const arr = await groovy(`
import jenkins.model.Jenkins
import hudson.security.ProjectMatrixAuthorizationStrategy
def cur = Jenkins.get().authorizationStrategy
def plain = new ProjectMatrixAuthorizationStrategy()
cur.getGrantedPermissionEntries().each { p, set -> set.each { e -> plain.add(p, e) } }
Jenkins.get().setAuthorizationStrategy(plain); Jenkins.get().save(); println 'arranged plain'`);
const plainCount = await groovy(COUNT);
log(L, `A-21 before: ${before} | arranged: ${arr} -> ${plainCount}`);
const { page } = await login('admin');
await page.goto(BASE + '/manage/configureSecurity/');
const secText = await page.locator('#main-panel').innerText();
log(L, `A-21 Security page offers a migration button: ${/Install the Batch Control|Migrate|Batch Control variant/i.test(secText)}`);
await page.goto(BASE + '/manage/');
const mon = page.locator('.jenkins-alert:has(form[action*="migrate"])').first();
log(L, `A-21 monitor text: ${(await mon.innerText().catch(() => 'NO MONITOR')).replace(/\s+/g, ' ')}`);
await shot(page, mon, 'A-21-before', { pad: 10 });
await Promise.all([page.waitForNavigation({ waitUntil: 'load' }), mon.locator('form[action*="migrate"] button').click()]);
log(L, `A-21 after migrate click landed on ${page.url()}`);
const after = await groovy(COUNT);
await page.goto(BASE + '/manage/configureSecurity/');
await shot(page, page.locator('select:has(option:text-is("Batch Control: Matrix-based security"))').first(), 'A-21-after', { pad: 20 });
log(L, `A-21 after migrate: ${after}; selected=${await page.locator('select:has(option:text-is("Batch Control: Matrix-based security"))').first().evaluate((s) => s.options[s.selectedIndex].text)}`);
// Revert from System configuration
await page.goto(BASE + '/manage/configure');
const rev = page.locator('a:has-text("Revert to the plain strategy")').first();
await shot(page, rev.locator('xpath=ancestor::*[contains(@class,"jenkins-alert") or contains(@class,"jenkins-form-item")][1]'), 'A-21-revert-link', { pad: 10 });
await rev.click();
await page.waitForTimeout(800);
const ok = page.locator('dialog[open] button[data-id="ok"], dialog[open] button:has-text("Yes")').first();
await Promise.all([page.waitForNavigation({ waitUntil: 'load' }).catch(() => null), ok.click()]);
const reverted = await groovy(COUNT);
await page.goto(BASE + '/manage/configureSecurity/');
await shot(page, page.locator('select:has(option:text-is("Batch Control: Matrix-based security"))').first(), 'A-21-revert', { pad: 20 });
log(L, `A-21 after revert: ${reverted}`);
// restore the variant through the monitor again (the environment continues on it)
await page.goto(BASE + '/manage/');
await Promise.all([page.waitForNavigation({ waitUntil: 'load' }), page.locator('form[action*="migrate"] button').first().click()]);
log(L, `A-21 restored: ${await groovy(COUNT)}`);
await close();
