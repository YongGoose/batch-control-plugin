import { login, close, shot, BASE, log, groovy, sleep, globalCfg, api } from './lib.mjs';
import fs from 'node:fs';
import path from 'node:path';
import { execSync } from 'node:child_process';
const L = 'section-b.log';
const CASC = path.resolve('../casc/jenkins.yaml');
const ad = await login('admin'); const p = ad.page;
async function exportText() {
  await p.goto(`${BASE}/manage/configuration-as-code/`);
  await Promise.all([p.waitForNavigation({ waitUntil: 'load' }), p.locator('form[action="viewExport"] button').click()]);
  await p.waitForTimeout(1500);
  return p.locator('body').innerText();
}
async function reload() {
  await p.goto(`${BASE}/manage/configuration-as-code/`);
  await Promise.all([p.waitForNavigation({ waitUntil: 'load' }).catch(() => null), p.locator('form[action="reload"] button').first().click()]);
  await sleep(4000);
}
// B19-03 role variant: apply profile-role.yaml from the UI, export, then reload the matrix profile
async function applySource(src) {
  await p.goto(`${BASE}/manage/configuration-as-code/`);
  await p.locator('button:has-text("Apply configuration")').first().click(); await p.waitForTimeout(800);
  await p.locator('dialog[open] input[name="_.newSource"]').fill(src);
  await Promise.all([p.waitForNavigation({ waitUntil: 'load' }).catch(() => null), p.locator('dialog[open] button[name="replace"], dialog[open] button:has-text("Apply configuration")').last().click()]);
}
await applySource('/var/jenkins_casc/profile-role.yaml');
await sleep(3000);
const s1 = (await groovy('println jenkins.model.Jenkins.get().authorizationStrategy.class.simpleName')).trim();
const t3 = await exportText();
const roleExport = t3.slice(t3.indexOf('batchControlRoleBased'), t3.indexOf('batchControlRoleBased') + 700);
await shot(p, p.getByText('batchControlRoleBased', { exact: false }).first(), 'B19-03', { pad: 60 });
const c3 = await login('requester');
const r3 = `${(await c3.page.goto(`${BASE}/job/team/job/app-1/`)).status()}/${(await c3.page.goto(`${BASE}/job/batch-daily/`)).status()}`;
log(L, `B19-03 applied profile-role.yaml: strategy ${s1}; export has batchControlRoleBased ${!!roleExport} roles ${JSON.stringify((roleExport.match(/name: "?[\w-]+"?/g) || []).slice(0, 6))}; requester team/app-1 / batch-daily -> ${r3}`);
// back: the source list may now hold profile-role.yaml; re-apply jenkins.yaml explicitly
await applySource('/var/jenkins_casc/jenkins.yaml');
await sleep(3000);
log(L, `B19-03 restored: ${(await groovy('def s=jenkins.model.Jenkins.get().authorizationStrategy; def n=0; s.getGrantedPermissionEntries().each{k,v->n+=v.size()}; println s.class.simpleName+" "+n')).trim()}; casc source ${(await p.locator('#main-panel').innerText()).match(/\/var\/jenkins_casc\/[\w.-]+/g)}`);
await close();
