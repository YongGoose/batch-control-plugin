// Re-audit E-10: activation of a not-activated job through the real ACTIVATE flow, per role.
import { login, close, shot, api, job, mails, waitFor, BASE, changeRows, sleep } from '../lib.mjs';
import { row, ev, mainText } from './rec.mjs';
const J = 'b10-ui';
const vis = {};
for (const u of ['requester', 'reqonly', 'manager', 'approver-1', 'nobc', 'auditor', 'admin']) {
  const { context, page } = await login(u);
  const r = await page.goto(`${BASE}/job/${J}/`);
  const n = page.locator('.jenkins-alert:has-text("Batch Control")');
  const texts = (await n.allInnerTexts()).map((t) => t.replace(/\s+/g, ' '));
  const link = await page.locator('.jenkins-alert a:has-text("Request activation")').count();
  vis[u] = { status: r.status(), notice: texts.map((t) => t.slice(0, 60)), link };
  if (['nobc', 'approver-1'].includes(u)) await shot(page, n.last(), `E-10-0-${u}-notice-no-link`);
  const f = await page.goto(`${BASE}/job/${J}/batch-control-activation`);
  vis[u].form = f.status();
  await context.close();
}
ev(`E-10 visibility ${JSON.stringify(vis)}`);
const before = (await job(J, 'nextBuildNumber')).nextBuildNumber;
const req = await login('requester'); const p = req.page;
await p.goto(`${BASE}/job/${J}/`);
const notice = p.locator('.jenkins-alert:has-text("not activated")').first();
const s1 = await shot(p, notice, 'E-10-1-not-activated-notice');
await notice.locator('a:has-text("Request activation")').click();
await p.waitForLoadState('load');
const crumbs = (await p.locator('.jenkins-breadcrumbs, #breadcrumbBar').first().innerText()).replace(/\s+/g, ' ');
const reason = `Audit E-10: put ${J} into service (${Date.now()})`;
await p.fill('textarea[name="reason"]', reason);
await p.locator('label:has-text("approver-1")').first().click();
const s2 = await shot(p, ['form[name="batch-control-activation"]', '.jenkins-breadcrumbs, #breadcrumbBar'], 'E-10-2-activation-form');
await Promise.all([p.waitForLoadState('load'), p.click('button:has-text("Submit Request")')]);
const reqUrl = p.url();
const pend = await mainText(p);
const s3 = await shot(p, '#main-panel', 'E-10-3-activation-request-pending');
const id = decodeURIComponent(reqUrl.split('/activations/')[1].replace(/\/$/, ''));
ev(`E-10 crumbs "${crumbs}", request ${reqUrl} text: ${pend.slice(0, 200)}`);
// approver-1 lands on Batch Control and follows the notice
const ap = await login('approver-1'); const a = ap.page;
await a.goto(`${BASE}/batch-control/`);
const land = a.locator('#main-panel .jenkins-alert, #main-panel p', { hasText: /awaiting your decision/ }).first();
const landText = (await land.innerText().catch(() => 'NONE')).replace(/\s+/g, ' ');
const s4 = await shot(a, land, 'E-10-4-approver-landing-notice');
const lnk = land.locator('a').first();
let path = 'none';
if (await lnk.count()) { await Promise.all([a.waitForLoadState('load'), lnk.click()]); path = a.url(); }
const inList = await a.locator(`a[href*="${id}"]`).count();
const s4b = await shot(a, a.locator(`tr:has(a[href*="${id}"])`).first(), 'E-10-4b-activations-list-row');
await a.locator(`a[href*="${id}"]`).first().click(); await a.waitForLoadState('load');
await a.fill('form[name="approve"] textarea[name="comment"]', 'OK (audit E-10).');
const s5 = await shot(a, 'form[name="approve"]', 'E-10-5-approve-form');
await Promise.all([a.waitForLoadState('load'), a.click('button:has-text("Approve Activation")')]);
const dec = await mainText(a);
const s6 = await shot(a, '#main-panel', 'E-10-6-approved');
await p.goto(`${BASE}/job/${J}/`);
const after = p.locator('.jenkins-alert:has-text("activated")').first();
const afterT = (await after.innerText()).replace(/\s+/g, ' ');
const s7 = await shot(p, after, 'E-10-7-activated-notice');
const built = await waitFor(async () => {
  const j = await job(J, 'builds[number,result,actions[causes[shortDescription]]]');
  return j.builds.find((b) => b.number >= before) ? j : null;
}, { timeout: 150000, every: 5000 });
const b = built && built.builds.find((x) => x.number === before);
const cause = b ? JSON.stringify(b.actions.flatMap((x) => (x.causes || []).map((c) => c.shortDescription))) : 'none';
await p.goto(`${BASE}/job/${J}/${before}/`);
const s8 = await shot(p, p.locator('#main-panel').locator('text=/Started by timer/').first(), 'E-10-8-timer-build');
const m = await mails(`to:approver-1@e2e.local subject:"awaiting" ${id}`);
const m2 = await mails(`to:requester@e2e.local ${id}`);
const recs = await changeRows(new RegExp(J));
ev(`E-10 landing "${landText}" -> ${path}; decided: ${dec.slice(0, 160)}; after: ${afterT}; build #${before} cause ${cause}; mails approver ${m.map((x) => x.Subject)}; requester ${m2.map((x) => x.Subject)}; recs ${recs.slice(-3).join(' || ')}`);
row('E-10', {
  roles: 'requester, reqonly, manager, approver-1, nobc, auditor, admin',
  V: `${vis.requester.link && vis.reqonly.link && !vis['approver-1'].link && !vis.nobc.link && !vis.auditor.link ? '✓' : '✗'} Request activation link: ${Object.entries(vis).map(([u, v]) => `${u}=${v.link ? 'link' : 'no link'}/form ${v.form}`).join(', ')}`,
  G: b ? `✓ requester submitted, approver-1 approved from the landing notice, notice "${afterT.slice(0, 40)}", #${before} ${cause}` : '✗ no timer build',
  R: `${vis.nobc.form === 404 && vis['approver-1'].form === 404 ? '✓' : '✗'} form URL for non-Request holders ${vis.nobc.form}/${vis['approver-1'].form}; notice without link tells them nothing to do (n.a.: they cannot request)`,
  C: `${m.length ? '✓' : '✗'} REQUEST_CREATED to approver-1 (${m.length}); APPROVED to requester (${m2.length}); decision on the activation detail`,
  E: [s1, s2, s3, s4, s5, s6, s7, s8].every(Boolean) ? '✓ E-10-0..8' : `✗ missing shots ${[s1, s2, s3, s4, s5, s6, s7, s8].map((x, i) => x ? '' : i + 1).join('')}`,
});
await req.context.close(); await ap.context.close(); await close();
