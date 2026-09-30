import { login, close, BASE, log, groovy, sleep, shot } from './lib.mjs';
const L = 'section-b.log';
const ROLES = `import com.synopsys.arc.jenkins.plugins.rolestrategy.RoleType; def s = jenkins.model.Jenkins.get().authorizationStrategy; println s.class.simpleName + ' ' + RoleType.values().collect { t -> t.toString() + ':' + s.getRoleMap(t).getGrantedRolesEntries().collect { k, v -> k.name + '=' + v.collect { it.sid } } }.join(' ')`;
const ad = await login('admin');
await ad.page.goto(`${BASE}/manage/`);
await ad.page.locator('a:has-text("Reload Configuration from Disk"), button:has-text("Reload Configuration from Disk")').first().click(); await ad.page.waitForTimeout(700);
{ const ok = ad.page.locator('dialog[open] button[data-id="ok"]').first(); if (await ok.count()) await ok.click(); }
await sleep(10000);
for (let i = 0; i < 40; i++) { const r = await fetch(`${BASE}/login`); if (r.ok) break; await sleep(2000); }
await sleep(3000);
const before = await groovy(ROLES);
const ad2 = await login('admin');
// the requester's CONFIGURE window on team/app-1 (B8-R4) is still open
const rq = await login('requester');
const c1 = (await rq.page.goto(`${BASE}/job/team/job/app-1/configure`)).status();
await ad2.page.goto(`${BASE}/manage/`);
const mon = ad2.page.locator('.jenkins-alert:has(form[action*="migrate"])').first();
const mt = (await mon.innerText().catch(() => 'NO MONITOR')).replace(/\s+/g, ' ');
await shot(ad2.page, mon, 'B8-R2-monitor', { pad: 8 });
await Promise.all([ad2.page.waitForNavigation({ waitUntil: 'load' }), mon.locator('form[action*="migrate"] button').first().click()]);
const after = await groovy(ROLES);
const c2 = (await rq.page.goto(`${BASE}/job/team/job/app-1/configure`)).status();
log(L, `B8-R2 plain role-strategy installed: ${before}; open window -> configure ${c1}; monitor "${mt.slice(0, 280)}"; after its button: ${after}; configure ${c2}`);
await close();
