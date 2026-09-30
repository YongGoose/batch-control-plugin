import { login, close, BASE, groovy, shot, log } from './lib.mjs';
const L = 'section-b.log';
async function probe(tag) {
  const { page, context } = await login('admin');
  const bad = [];
  page.on('response', (r) => { if (r.status() >= 400) bad.push(`${r.status()} ${r.request().method()} ${r.url().replace(BASE, '')}`); });
  await page.goto(`${BASE}/manage/role-strategy/`); await page.waitForTimeout(2500);
  const oops = await page.locator('#main-panel :text("Oops!")').count();
  await shot(page, page.locator('#main-panel table').first(), `B8-R1-assign-roles-${tag}`, { pad: 8 });
  log(L, `B8-R1 ${tag}: /manage/role-strategy/ embedded "Oops!" blocks ${oops}; failed requests ${JSON.stringify(bad)}`);
  await context.close();
}
log(L, `B8-R1 strategy ${await groovy('println jenkins.model.Jenkins.get().authorizationStrategy.class.simpleName')}`);
await probe('batch-control-variant');
// compare: the plain role-strategy class with the same roles (arrange)
log(L, `B8-R1 arrange plain: ${await groovy(`import jenkins.model.Jenkins
def s = Jenkins.get().authorizationStrategy
def plain = new com.michelin.cicd.plugins.jenkins.role.RoleBasedAuthorizationStrategy(s.getRoleMaps())
Jenkins.get().setAuthorizationStrategy(plain); Jenkins.get().save(); println plain.class.simpleName`).catch((e) => e.message)}`);
await probe('plain');
await close();
