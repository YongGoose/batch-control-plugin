// B8 Batch Control strategies, migration, monitors, legacy wrapper. Usage: node b8.mjs <step> ...
import { login, close, shot, api, BASE, log, groovy, sleep } from './lib.mjs';
import { execSync } from 'node:child_process';
const L = 'section-b.log';
const steps = {};
const COUNT = `import jenkins.model.Jenkins
def s = Jenkins.get().authorizationStrategy
def n = 0; if (s.respondsTo('getGrantedPermissionEntries')) s.getGrantedPermissionEntries().each { p, set -> n += set.size() }
def props = 0
Jenkins.get().getAllItems(hudson.model.Job).each { j -> def p = j.getProperty(hudson.security.AuthorizationMatrixProperty); if (p) props += (p.getGrantedPermissionEntries().values().sum { it.size() } ?: 0) }
Jenkins.get().getAllItems(com.cloudbees.hudson.plugins.folder.AbstractFolder).each { f -> def p = f.properties.get(com.cloudbees.hudson.plugins.folder.properties.AuthorizationMatrixProperty); if (p) props += (p.getGrantedPermissionEntries().values().sum { it.size() } ?: 0) }
println "\${s.class.simpleName} global=\${n} perItem=\${props}"`;
const count = () => groovy(COUNT);
const monitorText = async (p) => { await p.goto(`${BASE}/manage/`); return (await p.locator('.jenkins-alert:has-text("Batch Control")').allInnerTexts()).map((t) => t.replace(/\s+/g, ' ')); };
async function grantConfigure(reason) {
  const rq = await login('requester');
  await rq.page.goto(`${BASE}/batch-control/grants/?scopeType=JOB&scopeFullName=batch-pipeline`);
  await rq.page.selectOption('select[name="durationMinutes"]', '15');
  await rq.page.fill('textarea[name="reason"]', reason);
  await rq.page.locator('#grant-approver-0 + label').click();
  await Promise.all([rq.page.waitForLoadState('load'), rq.page.locator('button:has-text("Request Grant")').click()]);
  const href = await rq.page.locator('#main-panel table').first().locator('tbody tr', { hasText: reason.slice(0, 25) }).first().locator('a').first().getAttribute('href');
  const ap = await login('approver-1');
  await ap.page.goto(new URL(href, rq.page.url()).href);
  await ap.page.fill('form[name="approve"] textarea[name="comment"]', 'ok');
  await Promise.all([ap.page.waitForLoadState('load'), ap.page.locator('form[name="approve"] button').first().click()]);
  await ap.context.close();
  return rq;
}
const cfgStatus = async (rq) => (await rq.page.goto(`${BASE}/job/batch-pipeline/configure`)).status();
async function revertUI(p) {
  await p.goto(`${BASE}/manage/configure`);
  await p.locator('a:has-text("Revert to the plain strategy")').first().click(); await p.waitForTimeout(700);
  await Promise.all([p.waitForNavigation({ waitUntil: 'load' }).catch(() => null), p.locator('dialog[open] button[data-id="ok"]').first().click()]);
}
async function migrateUI(p) {
  await p.goto(`${BASE}/manage/`);
  await Promise.all([p.waitForNavigation({ waitUntil: 'load' }), p.locator('form[action*="migrate"] button').first().click()]);
}

steps.matrix = async () => {
  const ad = await login('admin'); const p = ad.page;
  await p.goto(`${BASE}/manage/configureSecurity/`); await p.waitForTimeout(1500);
  await p.locator('button.matrix-auth-add-button:has-text("Add user"):visible').first().click(); await p.waitForTimeout(500);
  await p.locator('dialog[open] input').first().fill('b8-user');
  await p.locator('dialog[open] button[data-id="ok"]').click(); await p.waitForTimeout(800);
  const card = p.locator('.mas-card[data-sid="b8-user"]').first();
  if (await card.locator('.mas-card__body--collapsed').count()) { await card.locator('.mas-card__toggle, .mas-card__header').first().click(); await p.waitForTimeout(500); }
  await card.locator('label[data-permission-id="hudson.model.Hudson.Read"]').click();
  await card.locator('label[data-permission-id="io.jenkins.plugins.batchcontrol.security.BatchControlPermissions.ViewHistory"], label:has(.mas-card__permission-name:text-is("ViewHistory"))').first().click();
  await Promise.all([p.waitForNavigation(), p.locator('button[name="Submit"]').click()]);
  await p.goto(`${BASE}/manage/configureSecurity/`); await p.waitForTimeout(1500);
  const sum = await p.locator('.mas-card[data-sid="b8-user"] .mas-card__summary').first().innerText().catch(() => 'MISSING');
  await shot(p, p.locator('.mas-card[data-sid="b8-user"]').first(), 'B8-02', { pad: 8 });
  log(L, `B8-02 added b8-user under Batch Control: Matrix-based security, saved, reopened: "${sum}"; strategy ${await count()}`);
  // remove it again (UI)
  await p.locator('.mas-card[data-sid="b8-user"] button.remove').first().click(); await p.waitForTimeout(500);
  const ok = p.locator('dialog[open] button[data-id="ok"]'); if (await ok.count()) await ok.click();
  await Promise.all([p.waitForNavigation(), p.locator('button[name="Submit"]').click()]);
  // B8-03 batch-daily's auditor entry survived requester's save under a window (B7-06)
  const x = (await api('admin', '/job/batch-daily/config.xml', { raw: true })).text;
  log(L, `B8-03 batch-daily after the requester's B7-06 save: auditor Job/Read still in the job matrix: ${/USER:hudson.model.Item.Read:auditor|<permission>USER:hudson\.model\.Item\.Read:auditor/.test(x) || /Item\.Read:auditor/.test(x)}`);
  await close();
};

steps.migrate = async () => {
  const ad = await login('admin'); const p = ad.page;
  await revertUI(p);
  log(L, `B8-05 after "Revert to the plain strategy": ${await count()}`);
  const rq = await grantConfigure('Grant under the plain strategy (B8-04).');
  const s1 = await cfgStatus(rq);
  const m1 = await monitorText(p);
  await shot(p, p.locator('.jenkins-alert:has(form[action*="migrate"])').first(), 'B8-04-1-monitor', { pad: 8 });
  await migrateUI(p);
  const s2 = await cfgStatus(rq);
  log(L, `B8-04 plain strategy + approved CONFIGURE window: requester configure -> ${s1}; monitor ${JSON.stringify(m1.filter((t) => /grants do not confer|not a Batch Control strategy/.test(t)).map((t) => t.slice(0, 200)))}; after Install -> ${await count()}, configure -> ${s2}`);
  await revertUI(p);
  const s3 = await cfgStatus(rq);
  const m3 = (await monitorText(p)).filter((t) => /not a Batch Control strategy/.test(t)).length;
  log(L, `B8-05 revert again: configure -> ${s3}; monitor back: ${m3}`);
  await migrateUI(p);
  log(L, `B8-05 re-installed: ${await count()}; configure -> ${await cfgStatus(rq)}`);
  await p.goto(`${BASE}/manage/configureSecurity/`);
  log(L, `B8-06 migration control on Manage Jenkins -> Security: ${await p.locator('#main-panel form[action*="migrate"], #main-panel button:has-text("Install the Batch Control"), #main-panel a:has-text("Migrate")').count()}`);
  await close();
};

steps.global = async () => {
  const ad = await login('admin'); const p = ad.page;
  log(L, `B8-07 arrange global matrix: ${await groovy(`import jenkins.model.Jenkins
def cur = Jenkins.get().authorizationStrategy
def g = new hudson.security.GlobalMatrixAuthorizationStrategy()
cur.getGrantedPermissionEntries().each { perm, set -> set.each { e -> g.add(perm, e) } }
Jenkins.get().setAuthorizationStrategy(g); Jenkins.get().save(); println 'global matrix installed'`)} -> ${await count()}`);
  const au = await login('auditor');
  const before = (await au.page.goto(`${BASE}/job/batch-daily/`)).status();
  const m = await monitorText(p);
  const mon = p.locator('.jenkins-alert:has(form[action*="migrate"])').first();
  await shot(p, mon, 'B8-07', { pad: 8 });
  await migrateUI(p);
  const after = (await au.page.goto(`${BASE}/job/batch-daily/`)).status();
  log(L, `B8-07 global matrix: auditor /job/batch-daily/ -> ${before}; monitor ${JSON.stringify(m.filter((t) => /migrat|Install|per-item|become effective/i.test(t)).map((t) => t.slice(0, 320)))}; after Install -> ${await count()}; auditor -> ${after}`);
  await close();
};

steps.legacy = async () => {
  const ad = await login('admin'); const p = ad.page;
  const wrap = (from) => execSync(`docker exec batch-control-e2e python3 -c "print(1)" 2>/dev/null || true`).toString();
  const rewrite = () => execSync(`docker exec batch-control-e2e perl -0pi -e 's#<authorizationStrategy class="([^"]+)"([^>]*?)>(.*?)</authorizationStrategy>#<authorizationStrategy class="io.jenkins.plugins.batchcontrol.security.BatchControlAuthorizationStrategy"><delegate class="$1"$2>$3</delegate></authorizationStrategy>#s' /var/jenkins_home/config.xml && docker exec batch-control-e2e grep -c BatchControlAuthorizationStrategy /var/jenkins_home/config.xml`).toString().trim();
  async function reloadFromDisk() {
    await p.goto(`${BASE}/manage/`);
    await p.locator('a:has-text("Reload Configuration from Disk"), button:has-text("Reload Configuration from Disk")').first().click(); await p.waitForTimeout(700);
    const ok = p.locator('dialog[open] button[data-id="ok"]').first(); if (await ok.count()) await ok.click();
    await sleep(8000);
    for (let i = 0; i < 40; i++) { const r = await fetch(`${BASE}/login`); if (r.ok) break; await sleep(2000); }
    await sleep(3000);
  }
  // L1: wrapper around ProjectMatrix
  await revertUI(p);
  const b1 = await count();
  log(L, `B8-L1 arrange: plain ${b1}; wrapper written: ${rewrite()}`);
  await reloadFromDisk();
  const a1 = await count();
  log(L, `B8-L1 after Reload Configuration from Disk: ${a1}`);
  // L2: wrapper around GlobalMatrix
  log(L, `B8-L2 arrange: ${await groovy(`import jenkins.model.Jenkins
def cur = Jenkins.get().authorizationStrategy
def g = new hudson.security.GlobalMatrixAuthorizationStrategy()
cur.getGrantedPermissionEntries().each { perm, set -> set.each { e -> g.add(perm, e) } }
Jenkins.get().setAuthorizationStrategy(g); Jenkins.get().save(); println 'global'`)}; wrapper written: ${rewrite()}`);
  await reloadFromDisk();
  const ad2 = await login('admin');
  const a2 = await count();
  const m = await monitorText(ad2.page);
  await shot(ad2.page, ad2.page.locator('.jenkins-alert:has(form[action*="migrate"])').first(), 'B8-L2-monitor', { pad: 8 });
  log(L, `B8-L2 after reload: ${a2}; monitor ${JSON.stringify(m.filter((t) => /migrat|Install|per-item/i.test(t)).map((t) => t.slice(0, 240)))}`);
  await migrateUI(ad2.page);
  log(L, `B8-L2 converted from the monitor: ${await count()}`);
  const lg = execSync('docker logs --since 4m batch-control-e2e 2>&1 | grep -iE "wrapper|BatchControlAuthorizationStrategy|unwrap|convert" | head -4', { shell: '/bin/bash' }).toString();
  log(L, `B8-L log: ${lg.replace(/\n/g, ' || ')}`);
  await close();
};

const wanted = process.argv.slice(2);
try { for (const s of wanted) { console.log(`== ${s}`); await steps[s](); } } finally { await close(); }
