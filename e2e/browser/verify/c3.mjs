// C-03 CSRF, C-04 RequirePOST, C-05 read-only POST, C-29 empty scope, C-32 path scopes.
import { login, close, shot, api, BASE, requestRun, requestGrant, decide, groovy } from '../lib.mjs';
import { row, ev, mainText } from '../audit/rec.mjs';
import { formPost, uiCancel, uiRevoke } from '../audit/restsubmit.mjs';
const T = String(Date.now()).slice(-4);
const rq = await login('requester');
const rUrl = await requestRun(rq.page, '/job/batch-pipeline/', { reason: `Verify C-03 ${T}`, approvers: ['approver-1'] });
const gUrl = (await requestGrant(rq.page, { type: 'JOB', scope: 'batch-pipeline', actions: ['CONFIGURE'], minutes: 15, reason: `Verify C-03 ${T} grant` })).url;
const aUrl = (await requestGrant(rq.page, { type: 'JOB', scope: 'batch-daily', actions: ['CONFIGURE'], minutes: 15, reason: `Verify C-03 ${T} active` })).url; await decide(aUrl, 'approve', 'ok');
await rq.page.goto(`${BASE}/job/b10-ui/batch-control/activation`);
await rq.page.fill('textarea[name="reason"]', `Verify C-03 ${T} activation`); await rq.page.locator('input[name="approvers"][value="approver-1"] + label').click();
await Promise.all([rq.page.waitForNavigation(), rq.page.locator('button:has-text("Submit Request")').click()]); const actUrl = rq.page.url();
const inc = (await api('admin', '/batch-control/history/incidents.csv')).text.split('\n').slice(1).find((l) => /,OPEN,/.test(l)).split(',')[0];
// collect form actions as each owner sees them
const collect = async (user, urls) => { const c = await login(user); const out = []; for (const u of urls) { await c.page.goto(u); out.push(...await c.page.locator('form[method="post" i], form[method="POST"]').evaluateAll((fs) => fs.map((f) => f.getAttribute('action')).filter(Boolean)).then((xs) => xs.map((x) => new URL(x, u).pathname))); } await c.context.close(); return out; };
const acts = new Set([
  ...await collect('approver-1', [rUrl, gUrl, actUrl, `${BASE}/batch-control/incidents/${inc}/`]),
  ...await collect('requester', [rUrl, gUrl, `${BASE}/job/batch-pipeline/batch-control/`, `${BASE}/job/b10-ui/batch-control/activation`, `${BASE}/batch-control/grants/`]),
  ...await collect('manager', [`${BASE}/batch-control/grants/`]),
  ...await collect('admin', [`${BASE}/batch-control/incidents/${inc}/`, `${BASE}/manage/configure`]),
]);
const list = [...acts].filter((a) => /batch-control|Monitor|migrate|revert/i.test(a));
ev(`C-03 actions ${JSON.stringify(list)}`);
// C-03: session cookie, no crumb
const sess = await login('admin'); const csrf = []; const getr = [];
for (const a of list) {
  const r = await sess.page.request.post(BASE + a, { form: { comment: 'csrf', reason: 'csrf', approvers: 'approver-2' }, maxRedirects: 0, failOnStatusCode: false });
  const body = (await r.text()).replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ');
  csrf.push(`${a}=${r.status()}${r.status() === 403 && /crumb/i.test(body) ? '(crumb)' : ''}`);
  const g = await sess.page.request.get(BASE + a, { maxRedirects: 0, failOnStatusCode: false }); getr.push(`${a}=${g.status()}`);
}
const st = async (u) => ((await mainText((await (async () => { await sess.page.goto(u); return sess.page; })()))).match(/Status (\w+)/) || [])[1];
const after = { run: await st(rUrl), grant: await st(gUrl), act: await st(actUrl) };
const stillActive = (await api('admin', '/batch-control/grants/')).text.includes(aUrl.match(/(\d{8}-\d{6}-\w+)/)[1]);
ev(`C-03 ${csrf.join(' ')}\nC-04 ${getr.join(' ')}\nafter ${JSON.stringify(after)} active ${stillActive}`);
row('C-03', { roles: 'admin session (cookie) without crumb', V: 'n.a.', G: `${csrf.every((c) => /=403/.test(c)) && after.run === 'PENDING' && after.grant === 'PENDING' && after.act === 'PENDING' ? '✓' : '✗'} ${csrf.length} state-changing form actions POSTed without a crumb: ${csrf.join(', ')}; afterwards run/grant/activation still ${JSON.stringify(after)}, active window still listed ${stillActive}`, R: 'n.a. (core crumb check)', C: 'n.a.', E: '✓ text (codes in audit.log)' });
row('C-04', { roles: 'admin session', V: 'n.a.', G: `${getr.every((c) => /=(405|404)$/.test(c)) ? '✓' : '✗'} GET on the same ${getr.length} actions: ${getr.join(', ')}; no state change (as C-03)`, R: 'n.a.', C: 'n.a.', E: '✓ text' });
// C-05 POST on read-only screens with a crumb
const ro = []; for (const p of ['/batch-control/history/', '/batch-control/dashboard/', '/batch-control/changes/', '/batch-control/incidents/', '/batch-control/requests/']) ro.push(`${p}=${(await api('admin', p, { method: 'POST', body: 'x=1', headers: { 'Content-Type': 'application/x-www-form-urlencoded' } })).status}`);
row('C-05', { roles: 'admin (script with crumb)', V: 'n.a.', G: `${ro.every((c) => /=(200|405)$/.test(c)) ? '✓' : '✗'} ${ro.join(', ')}; these screens have no state to change (read-only render or 405)`, R: 'n.a.', C: 'n.a.', E: '✓ text' });
// C-29 / C-32 scopes
const scopes = [['JOB', ''], ['FOLDER', ''], ['FOLDER', '/'], ['JOB', '../../batch-daily'], ['FOLDER', '../..'], ['JOB', 'team/../batch-daily'], ['JOB', 'batch%2Fdaily'], ['JOB', '%2E%2E/batch-daily']];
const sc = [];
for (const [t, s] of scopes) { const g = await requestGrant(rq.page, { type: t, scope: s, actions: ['CONFIGURE'], minutes: 15, reason: `Verify C-29/32 ${T} ${s}` }); sc.push(`${t}:"${s}" -> ${g.error ? `${g.status} "${g.error.slice(0, 60)}"` : 'STORED ' + g.url}`); if (!g.error) await uiCancel(rq.page, g.url); }
const s1 = await shot(rq.page, ['#main-panel form:has(button:has-text("Request Grant"))'], 'C-32-path-scope-refused', { pad: 8 });
ev(`C-29/32 ${sc.join(' | ')}`);
row('C-29', { roles: 'requester', V: 'n.a.', G: `${sc.slice(0, 3).every((x) => !/STORED/.test(x)) ? '✓' : '✗'} ${sc.slice(0, 3).join('; ')}`, R: `${sc.slice(0, 3).every((x) => /400/.test(x)) ? '✓' : '✗'} refused on the form with a message (DEF-09 layout)`, C: 'n.a.', E: s1 ? '✓ C-32-path-scope-refused' : '✗' });
row('C-32', { roles: 'requester', V: 'n.a.', G: `${sc.slice(3).every((x) => !/STORED/.test(x)) ? '✓' : '✗'} ${sc.slice(3).join('; ')}`, R: `${sc.slice(3).every((x) => /400/.test(x)) ? '✓' : '✗'} each refused on the re-rendered form`, C: 'n.a.', E: s1 ? '✓ C-32-path-scope-refused' : '✗' });
// cleanup
await uiCancel(rq.page, rUrl); await uiCancel(rq.page, gUrl); await uiCancel(rq.page, actUrl); await uiRevoke(aUrl.match(/(\d{8}-\d{6}-\w+)/)[1]);
await close();
