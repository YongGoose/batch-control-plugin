import { login, close, BASE, groovy } from '../lib.mjs';
import { row, ev } from '../audit/rec.mjs';
const strat = async () => (await groovy('println jenkins.model.Jenkins.get().authorizationStrategy.class.simpleName')).trim();
const { page } = await login('admin');
async function revertUI() { await page.goto(`${BASE}/manage/configure`); await page.waitForTimeout(1200); await page.locator('a:has-text("Revert to the plain strategy")').first().click(); await page.waitForTimeout(700); await Promise.all([page.waitForNavigation().catch(() => null), page.locator('dialog[open] button[data-id="ok"]').first().click()]); }
const crumbOf = async () => { const r = await page.request.get(`${BASE}/crumbIssuer/api/json`); const j = await r.json(); return [j.crumbRequestField, j.crumb]; };
const out = [];
for (const ref of ['http://evil.example/steal', '//evil.example/x']) {
  if (/BatchControl/.test(await strat())) await revertUI();
  const [f, c] = await crumbOf();
  const r = await page.request.post(`${BASE}/administrativeMonitor/batch-control-strategy/migrate`, { headers: { Referer: ref, [f]: c }, maxRedirects: 0, failOnStatusCode: false });
  out.push(`migrate Referer ${ref} -> ${r.status()} Location ${r.headers()['location']} (strategy now ${await strat()})`);
}
// revert with a hostile Referer, then install again from the monitor
{ const [f, c] = await crumbOf();
  const r = await page.request.post(`${BASE}/manage/administrativeMonitor/batch-control-strategy/revert`, { headers: { Referer: 'http://evil.example/', [f]: c }, maxRedirects: 0, failOnStatusCode: false });
  out.push(`revert Referer http://evil.example/ -> ${r.status()} Location ${r.headers()['location']} (strategy ${await strat()})`); }
if (!/BatchControl/.test(await strat())) { await page.goto(`${BASE}/manage/`); await Promise.all([page.waitForNavigation(), page.locator('form[action*="migrate"] button').first().click()]); }
ev(`C-11 ${out.join(' | ')}; final ${await strat()}`);
const safe = out.every((o) => { const l = (o.match(/Location (\S+)/) || [])[1] || ''; return !/evil/.test(l) && (l.startsWith('/') && !l.startsWith('//') || l.startsWith(BASE)); });
row('C-11', { roles: 'admin (script with crumb and a hostile Referer)', V: 'n.a.', G: `${safe ? '✓' : '✗'} ${out.join('; ')}`, R: 'n.a.', C: 'n.a.', E: '✓ text' });
await close();
