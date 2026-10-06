import { login, close, shot, api, BASE, requestRun, requestGrant, decide } from '../lib.mjs';
import { row, ev, mainText } from '../audit/rec.mjs';
import { uiCancel, uiRevoke } from '../audit/restsubmit.mjs';
const T = String(Date.now()).slice(-4); const id = (u) => u.match(/(\d{8}-\d{6}-\w+)/)[1];
const rq = await login('requester');
const r = await requestRun(rq.page, '/job/batch-pipeline/', { reason: `Verify C-03b ${T}`, approvers: ['approver-1'] });
const g = (await requestGrant(rq.page, { scope: 'batch-pipeline', actions: ['CONFIGURE'], minutes: 15, reason: `Verify C-03b ${T} grant` })).url;
const a = (await requestGrant(rq.page, { scope: 'batch-daily', actions: ['CONFIGURE'], minutes: 15, reason: `Verify C-03b ${T} active` })).url; await decide(a, 'approve', 'ok');
const inc = (await api('admin', '/batch-control/history/incidents.csv')).text.split('\n').slice(1).find((l) => /,ACKNOWLEDGED,/.test(l)).split(',')[0];
const ad = await login('admin'); await ad.page.goto(`${BASE}/manage/configure`);
const revert = await ad.page.locator('a:has-text("Revert to the plain strategy"), [data-url*="revert"], form[action*="revert"]').first().evaluate((e) => e.getAttribute('href') || e.getAttribute('data-url') || e.getAttribute('action')).catch(() => null);
const paths = [`/batch-control/requests/${id(r)}/cancel`, `/batch-control/grants/${id(g)}/cancel`, `/batch-control/grants/${id(a)}/revoke`, `/batch-control/incidents/${inc}/resolve`, '/administrativeMonitor/batch-control-strategy/migrate', revert && new URL(revert, BASE + '/manage/').pathname].filter(Boolean);
const out = [];
for (const p of paths) {
  const po = await ad.page.request.post(BASE + p, { form: { comment: 'x' }, maxRedirects: 0, failOnStatusCode: false });
  const ge = await ad.page.request.get(BASE + p, { maxRedirects: 0, failOnStatusCode: false });
  out.push(`${p}: POST-no-crumb ${po.status()} GET ${ge.status()}`);
}
const stR = ((await mainText(await (async () => { await ad.page.goto(r); return ad.page; })())).match(/Status (\w+)/) || [])[1];
const active = (await api('admin', '/batch-control/grants/')).text.includes(id(a));
const strat = (await (await import('../lib.mjs')).groovy('println jenkins.model.Jenkins.get().authorizationStrategy.class.simpleName')).trim();
ev(`C-03b ${out.join(' | ')}; run ${stR} active ${active} strategy ${strat}`);
row('C-03', { roles: 'admin session without crumb', V: 'n.a.', G: `${out.every((o) => /POST-no-crumb 403/.test(o)) && stR === 'PENDING' && active ? '✓' : '✗'} 14 form actions (first pass) plus the dialog-driven ones: ${out.map((o) => o.split(':')[0].replace(/\d{8}-\d{6}-\w+/, '<id>') + ' ' + (o.match(/POST-no-crumb \d+/) || [''])[0]).join('; ')}; the request stays PENDING, the window active, the strategy ${strat}`, R: 'n.a. (core crumb check)', C: 'n.a.', E: '✓ text (codes in audit.log)' });
row('C-04', { roles: 'admin session', V: 'n.a.', G: `${out.every((o) => /GET (405|404)/.test(o)) ? '✓' : '✗'} GET on approve/reject/changeApprover/submit/create/rerun/acknowledge/comment (405, first pass) and ${out.map((o) => o.split(':')[0].replace(/\d{8}-\d{6}-\w+/, '<id>').split('/').pop() + ' ' + (o.match(/GET \d+/) || [''])[0]).join(', ')}; no state change`, R: 'n.a.', C: 'n.a.', E: '✓ text' });
// C-29/32 messages as the user sees them
const msgs = [];
for (const s of ['', '../../batch-daily', 'batch%2Fdaily']) {
  await rq.page.goto(`${BASE}/batch-control/grants/`); await rq.page.fill('input[name="scopeFullName"]', s);
  if (!(await rq.page.locator('#grant-action-configure').isChecked())) await rq.page.locator('#grant-action-configure + label').click();
  await rq.page.fill('textarea[name="reason"]', 'scope check'); await rq.page.locator('input[name="approvers"][value="approver-1"] + label').click();
  await Promise.all([rq.page.waitForNavigation().catch(() => null), rq.page.locator('button:has-text("Request Grant")').click()]);
  msgs.push(`"${s}": ${(await rq.page.locator('#main-panel .error').allInnerTexts()).join(' / ')}`);
}
const s = await shot(rq.page, ['input[name="scopeFullName"]', '#main-panel .error'], 'C-32-path-scope-refused', { pad: 12 });
ev(`C-29/32 msgs ${msgs.join(' | ')}`);
await uiCancel(rq.page, r); await uiCancel(rq.page, g); await uiRevoke(id(a));
await close();
console.log('MSGS', msgs.join(' | '));
