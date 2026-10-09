import { login, close, BASE, groovy, sleep, shot } from '../lib.mjs';
const { page } = await login('admin');
async function applySource(src) {
  await page.goto(`${BASE}/manage/configuration-as-code/`);
  await page.locator('button:has-text("Apply configuration")').first().click(); await page.waitForTimeout(800);
  await page.locator('dialog[open] input[name="_.newSource"]').fill(src);
  await Promise.all([page.waitForNavigation({ waitUntil: 'load' }).catch(() => null), page.locator('dialog[open] button[name="replace"], dialog[open] button:has-text("Apply configuration")').last().click()]);
  await sleep(3000);
}
await applySource('/var/jenkins_casc/profile-role.yaml');
console.log(await groovy('println jenkins.model.Jenkins.get().authorizationStrategy.class.simpleName'));
await page.goto(`${BASE}/manage/role-strategy/`);
console.log('role-strategy page', (await page.locator('#main-panel').innerText()).replace(/\s+/g, ' ').slice(0, 300));
await page.goto(`${BASE}/manage/role-strategy/assign-roles`); await page.waitForTimeout(3000);
const t = (await page.locator('#main-panel').innerText()).replace(/\s+/g, ' ');
console.log('assign', t.slice(0, 900));
console.log('oops count', (t.match(/Oops|Not Found/g) || []).length);
await shot(page, '#main-panel', 'B8-R1-assign-roles', { pad: 8 });
await close();
