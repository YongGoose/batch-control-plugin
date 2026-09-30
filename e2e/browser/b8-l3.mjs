import { login, close, BASE, log, groovy, sleep } from './lib.mjs';
import { execSync } from 'node:child_process';
const L = 'section-b.log';
const ad = await login('admin');
await ad.page.goto(`${BASE}/manage/`);
await ad.page.locator('a:has-text("Reload Configuration from Disk"), button:has-text("Reload Configuration from Disk")').first().click(); await ad.page.waitForTimeout(700);
const ok = ad.page.locator('dialog[open] button[data-id="ok"]').first(); if (await ok.count()) await ok.click();
await sleep(10000);
for (let i = 0; i < 40; i++) { const r = await fetch(`${BASE}/login`); if (r.ok) break; await sleep(2000); }
await sleep(3000);
const kept = await groovy(`import com.synopsys.arc.jenkins.plugins.rolestrategy.RoleType; def s = jenkins.model.Jenkins.get().authorizationStrategy; println s.class.simpleName + ' ' + RoleType.values().collect { t -> t.toString() + ':' + s.getRoleMap(t).getGrantedRolesEntries().collect { k, v -> k.name + '=' + v.collect { it.sid } } }.join(' ')`);
const lg = execSync('docker logs --since 1m batch-control-e2e 2>&1 | grep -iE "withdrawn Batch Control wrapper" | head -2', { shell: '/bin/bash' }).toString().replace(/\n/g, ' || ');
log(L, `B8-L3 wrapper around the plain RoleBasedAuthorizationStrategy, Reload Configuration from Disk: ${kept}; log ${lg}`);
const rq = await login('requester');
log(L, `B8-L3 requester after conversion: /job/team/job/app-1/ ${(await rq.page.goto(`${BASE}/job/team/job/app-1/`)).status()}`);
await close();
