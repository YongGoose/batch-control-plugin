// B8-R*: Batch Control: Role-Based Strategy, configured in role-strategy's own UI.
// Usage: node b8-role.mjs roles | assign | check | r2 | l3
import { login, close, shot, api, BASE, log, groovy, sleep } from './lib.mjs';
import { execSync } from 'node:child_process';
const L = 'section-b.log';
const step = process.argv[2];
const strat = () => groovy('println jenkins.model.Jenkins.get().authorizationStrategy.class.simpleName');

async function addRole(page, tab, name, perms, pattern) {
  await page.goto(`${BASE}/manage/role-strategy/manage-roles`); await page.waitForTimeout(1200);
  await page.locator(`#rsp-tab-${tab === 'Item roles' ? 'projectRoles' : 'globalRoles'}`).click({ force: true }); await page.waitForTimeout(800);
  if (await page.locator(`text="${name}"`).count()) return 'exists';
  await page.click('#rsp-add-role-btn'); await page.waitForTimeout(900);
  const d = page.locator('dialog[open]').first();
  await d.locator('#rsp-role-name').fill(name);
  if (pattern) {
    const texts = await d.locator('input[type=text]').evaluateAll((es) => es.map((e) => `${e.id}|${e.name}|${e.placeholder}`));
    log(L, `B8-R1 item role dialog text inputs ${JSON.stringify(texts)}`);
    const pat = d.locator('input[type=text]:not(#rsp-role-name)').first();
    await pat.fill(pattern);
  }
  for (const id of perms) {
    const box = d.locator(`input[id$="-${id}"]`).first();
    if (!(await box.count())) { log(L, `B8-R1 permission checkbox ${id} not offered`); continue; }
    if (!(await box.isChecked())) await box.locator('xpath=following-sibling::label[1] | ../label').first().click();
  }
  await shot(page, d, `B8-R1-add-${name}`, { pad: 6 });
  await d.locator('button:has-text("Add"), button:has-text("Save"), button:has-text("Create")').last().click();
  await page.waitForTimeout(1500);
  return (await page.locator(`text="${name}"`).count()) ? 'added' : 'NOT added';
}

if (step === 'roles') {
  const { page } = await login('admin');
  const BC = 'io.jenkins.plugins.batchcontrol.security.BatchControlPermissions';
  await page.goto(`${BASE}/manage/role-strategy/manage-roles`); await page.waitForTimeout(1000);
  await page.click('#rsp-add-role-btn'); await page.waitForTimeout(900);
  const ids = await page.locator('dialog[open] input[type=checkbox]').evaluateAll((es) => es.map((e) => e.id.replace(/^_r_\d+_-/, '')).filter((i) => /batchcontrol/i.test(i)));
  const dlg = await page.locator('dialog[open]').innerText();
  log(L, `B8-R1 Add Role dialog offers Batch Control permissions: ${JSON.stringify(ids)}; dialog has pattern field: ${/pattern/i.test(dlg)}`);
  await page.keyboard.press('Escape');
  const r1 = await addRole(page, 'Global roles', 'bc-requester', ['hudson.model.Hudson.Read', ...ids.filter((i) => /Request$|RequestGrant$/.test(i))]);
  const r2 = await addRole(page, 'Global roles', 'bc-approver', ['hudson.model.Hudson.Read', 'hudson.model.Item.Read', ...ids.filter((i) => /Approve$|ViewHistory$/.test(i))]);
  const r3 = await addRole(page, 'Item roles', 'team-items', ['hudson.model.Item.Read', 'hudson.model.Item.Build'], 'team/.*');
  log(L, `B8-R1 roles via Manage Roles: bc-requester ${r1}, bc-approver ${r2}, team-items (pattern team/.*) ${r3}; strategy now ${await strat()}`);
  await page.goto(`${BASE}/manage/role-strategy/manage-roles`); await page.waitForTimeout(1000);
  await shot(page, '#main-panel', 'B8-R1-roles', { pad: 6 });
  await close();
}
if (step === 'teamall') {
  const { page } = await login('admin');
  log(L, `B8-R1 item role team-all (pattern team(/.*)?): ${await addRole(page, 'Item roles', 'team-all', ['hudson.model.Item.Read', 'hudson.model.Item.Build'], 'team(/.*)?')}`);
  await close();
}
if (step === 'assign') {
  const { page } = await login('admin');
  await page.goto(`${BASE}/manage/role-strategy/`); await page.waitForTimeout(1500);
  const tables = page.locator('#main-panel table');
  log(L, `B8-R1 Assign Roles tables: ${await tables.count()}; headers ${JSON.stringify(await page.locator('#main-panel table thead, #main-panel table tr:first-child').allInnerTexts())}`);
  async function addUser(sectionIdx, user) {
    const btn = page.locator('#main-panel button:has-text("Add User")').nth(sectionIdx);
    await btn.click(); await page.waitForTimeout(600);
    await page.locator('dialog[open] input').first().fill(user);
    await page.locator('dialog[open] button[data-id="ok"], dialog[open] button:has-text("OK")').first().click(); await page.waitForTimeout(1200);
  }
  async function tick(tableIdx, user, role) {
    const t = tables.nth(tableIdx);
    const pos = await t.evaluate((tb, [u, r]) => {
      const head = [...tb.querySelectorAll('tr')].find((tr) => tr.querySelector('th span[data-html-tooltip]'));
      const cols = [...head.children].map((c) => c.innerText.trim());
      const col = cols.indexOf(r);
      const rows = [...tb.querySelectorAll('tr')].filter((tr) => tr.querySelector('input[type=checkbox]'));
      const ri = rows.findIndex((tr) => [...tr.children].some((c) => c.innerText.trim() === u));
      return { col, ri, rowCount: rows.length };
    }, [user, role]);
    const row = t.locator('tr:has(input[type=checkbox])').nth(pos.ri);
    const cell = row.locator(':scope > *').nth(pos.col);
    const box = cell.locator('input[type=checkbox]');
    if (!(await box.isChecked())) await cell.locator('label').first().click().catch(async () => box.check({ force: true }));
    return `${user}/${role}(row ${pos.ri} col ${pos.col})=${await box.isChecked()}`;
  }
  await addUser(0, 'requester'); await addUser(0, 'approver-1');
  await addUser(1, 'requester');
  const res = [await tick(0, 'requester', 'bc-requester'), await tick(0, 'approver-1', 'bc-approver'), await tick(1, 'requester', 'team-items')];
  const oops = await page.locator('#main-panel :text("Oops!")').count();
  await shot(page, tables.first(), 'B8-R1-assign', { pad: 6 });
  await Promise.all([page.waitForNavigation({ waitUntil: 'load' }).catch(() => null), page.locator('#main-panel button:has-text("Save")').last().click()]);
  log(L, `B8-R1 Assign Roles: ${res.join(', ')}; embedded Oops blocks while editing ${oops}; saved -> ${page.url().replace(BASE, '')}; strategy after Save ${await strat()}`);
  await close();
}
if (step === 'check') {
  const rq = await login('requester');
  const c = async (p) => (await rq.page.goto(BASE + p)).status();
  log(L, `B8-R1 requester under roles: /job/team/job/app-1/ ${await c('/job/team/job/app-1/')}, /job/batch-daily/ ${await c('/job/batch-daily/')}, /batch-control/ ${await c('/batch-control/')}; strategy ${await strat()}`);
  await close();
}
if (step === 'r4') {
  // grant CONFIGURE on team/app-1 under whichever role strategy is installed
  const rq = await login('requester');
  await rq.page.goto(`${BASE}/batch-control/grants/?scopeType=JOB&scopeFullName=team/app-1`);
  await rq.page.selectOption('select[name="durationMinutes"]', '15');
  await rq.page.fill('textarea[name="reason"]', 'Configure app-1 under role strategy (B8-R4).');
  await rq.page.locator('#grant-approver-0 + label').click();
  const [s] = await Promise.all([rq.page.waitForNavigation().catch(() => null), rq.page.locator('button:has-text("Request Grant")').click()]);
  const href = await rq.page.locator('#main-panel table').first().locator('tbody tr', { hasText: 'under role strategy' }).first().locator('a').first().getAttribute('href').catch(() => null);
  if (!href) { log(L, `B8-R4 grant request failed: ${s && s.status()} ${(await rq.page.locator('body').innerText()).replace(/\s+/g, ' ').slice(0, 200)}`); await close(); process.exit(0); }
  const ap = await login('approver-1');
  await ap.page.goto(new URL(href, rq.page.url()).href);
  const f = await ap.page.locator('form[name="approve"]').count();
  if (f) { await ap.page.fill('form[name="approve"] textarea[name="comment"]', 'ok'); await Promise.all([ap.page.waitForLoadState('load'), ap.page.locator('form[name="approve"] button').first().click()]); }
  const cfg = (await rq.page.goto(`${BASE}/job/team/job/app-1/configure`)).status();
  log(L, `B8-R4 approver-1 decision form ${f}; requester team/app-1/configure under ${await strat()} -> ${cfg}`);
  await close();
}
if (step === 'r2') {
  // Manage Roles save (e.g. editing a role) and what follows
  const { page } = await login('admin');
  await page.goto(`${BASE}/manage/role-strategy/`); await page.waitForTimeout(1200);
  await Promise.all([page.waitForNavigation({ waitUntil: 'load' }).catch(() => null), page.locator('#main-panel button:has-text("Save")').last().click()]);
  const s1 = await strat();
  const rq = await login('requester');
  const cfg1 = (await rq.page.goto(`${BASE}/job/team/job/app-1/configure`)).status();
  await page.goto(`${BASE}/manage/`);
  const mon = page.locator('.jenkins-alert:has(form[action*="migrate"])').first();
  const mt = (await mon.innerText().catch(() => 'NO MONITOR')).replace(/\s+/g, ' ');
  await shot(page, mon, 'B8-R2-monitor', { pad: 8 });
  await Promise.all([page.waitForNavigation({ waitUntil: 'load' }), mon.locator('button').first().click()]);
  const s2 = await strat();
  const cfg2 = (await rq.page.goto(`${BASE}/job/team/job/app-1/configure`)).status();
  const kept = await groovy(`import com.synopsys.arc.jenkins.plugins.rolestrategy.RoleType; def s = jenkins.model.Jenkins.get().authorizationStrategy; println s.class.simpleName + ' ' + RoleType.values().collect { t -> t.toString() + ':' + s.getRoleMap(t).getRoles().collect { it.name } }.join(' ')`);
  log(L, `B8-R2 after Save on role-strategy's page: strategy ${s1}, requester configure (open window) -> ${cfg1}; monitor "${mt.slice(0, 260)}"; after its button: ${s2}, configure -> ${cfg2}; roles ${kept}`);
  await close();
}
if (step === 'l3') {
  const ad = await login('admin');
  const b = await strat();
  const w = execSync(`docker exec batch-control-e2e perl -0pi -e 's#<authorizationStrategy class="([^"]+)"([^>]*?)>(.*?)</authorizationStrategy>#<authorizationStrategy class="io.jenkins.plugins.batchcontrol.security.BatchControlAuthorizationStrategy"><delegate class="$1"$2>$3</delegate></authorizationStrategy>#s' /var/jenkins_home/config.xml && docker exec batch-control-e2e grep -o 'delegate class="[^"]*"' /var/jenkins_home/config.xml`).toString().trim();
  await ad.page.goto(`${BASE}/manage/`);
  await ad.page.locator('a:has-text("Reload Configuration from Disk"), button:has-text("Reload Configuration from Disk")').first().click(); await ad.page.waitForTimeout(700);
  const ok = ad.page.locator('dialog[open] button[data-id="ok"]').first(); if (await ok.count()) await ok.click();
  await sleep(10000);
  for (let i = 0; i < 40; i++) { const r = await fetch(`${BASE}/login`); if (r.ok) break; await sleep(2000); }
  await sleep(3000);
  const kept = await groovy(`import com.synopsys.arc.jenkins.plugins.rolestrategy.RoleType; def s = jenkins.model.Jenkins.get().authorizationStrategy; println s.class.simpleName + ' ' + RoleType.values().collect { t -> t.toString() + ':' + s.getRoleMap(t).getRoles().collect { it.name } }.join(' ')`);
  log(L, `B8-L3 before ${b}; wrapper written (${w}); after Reload Configuration from Disk: ${kept}`);
  await close();
}
await close();
