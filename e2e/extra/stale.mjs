// e2e-05 check 3: two tabs, Back button and double submit, in a real browser.
//   node stale.mjs <tabs|back|double>
// Server state is read separately: new request/grant ids (list diff) and builds of the job.
import { login, BASE, shot, text, api, log, close, sleep } from './lib.mjs';

const JOB = 'fresh-daily';
const flat = (s) => s.replace(/\s+/g, ' ').trim();
const step = process.argv[2];
const trace = (t) => /Exception|Stack trace|at io\.jenkins|at hudson\.|at org\.kohsuke|Oops!|A problem occurred/.test(t);

async function ids(kind) {
  const r = await api('admin', `/batch-control/${kind}/`);
  // List links are relative ("<id>/") on some screens and absolute on others: match the bare id in any href.
  return new Set([...r.body.matchAll(/href="[^"]*?(\d{8}-\d{6}-[a-z0-9]{6})\/?"/g)].map((m) => m[1]));
}
async function builds(job = JOB) {
  const r = await api('admin', `/job/${job}/api/json?tree=builds[number],inQueue`);
  const j = r.json();
  return { n: j.builds.map((b) => b.number), inQueue: j.inQueue };
}
async function status(kind, id) {
  const r = await api('admin', `/batch-control/${kind}/${id}/`);
  return (flat(r.body.replace(/<[^>]+>/g, ' ')).match(/Status (\w+)/) || [])[1];
}
// Records every POST the page sends and the answer it gets.
function watch(page, tag) {
  const posts = [];
  page.on('response', (r) => { if (r.request().method() === 'POST') posts.push(`${r.status()} ${new URL(r.url()).pathname}`); });
  return posts;
}
async function outcome(page, name, target = '#main-panel') {
  await sleep(500);
  const t = flat(await text(page));
  const errEl = page.locator('.error, .jenkins-alert-danger, .jenkins-alert-warning, .jenkins-alert').first();
  await shot(page, (await errEl.count()) ? errEl : target, name);
  return { url: page.url().replace(BASE, ''), trace: trace(t), head: t.slice(0, 260) };
}
async function newRunRequest(user, reason, approvers = ['approver-1', 'approver-2']) {
  const { page, context } = await login(user);
  await page.goto(`${BASE}/job/${JOB}/batch-control/`);
  await page.fill('textarea[name="reason"]', reason);
  for (const ap of approvers) await page.locator(`input[name="approvers"][value="${ap}"]`).check({ force: true });
  await Promise.all([page.waitForLoadState('load'), page.click('button[name="Submit"]')]);
  const id = (page.url().match(/requests\/([^/]+)/) || [])[1];
  await context.close();
  return id;
}
async function newGrant(reason, approvers = ['approver-1', 'approver-2']) {
  const { page, context } = await login('requester');
  await page.goto(`${BASE}/batch-control/grants/?scopeType=JOB&scopeFullName=${JOB}`);
  const f = page.locator('form[action$="grants/create"]');
  await f.locator('input[name="actions"][value="CONFIGURE"]').check({ force: true });
  await f.locator('textarea[name="reason"]').fill(reason);
  for (const ap of approvers) await f.locator(`input[name="approvers"][value="${ap}"]`).check({ force: true });
  const before = await ids('grants');
  await Promise.all([page.waitForLoadState('load'), f.locator('button[name="Submit"]').click()]);
  const id = [...(await ids('grants'))].find((x) => !before.has(x));
  await context.close();
  return id;
}
const approveBtn = (p) => p.locator('form[action$="/approve"] button[name="Submit"]');
async function reject(p, comment) {
  await p.locator('form[action$="/reject"] textarea[name="comment"]').fill(comment);
  await Promise.all([p.waitForLoadState('load'), p.locator('form[action$="/reject"] button').click()]);
}

if (step === 'tabs' && process.argv[3] !== 'activation') {
  // --- run request: approver-1 has three tabs, approver-2 and the requester one each, all loaded while PENDING.
  const R = await newRunRequest('requester', 'e2e-05 X1 two tabs');
  const b0 = await builds();
  log('X1 request', R, 'builds before', b0);
  const ap1 = await login('approver-1');
  const [A, B, C] = [ap1.page, await ap1.context.newPage(), await ap1.context.newPage()];
  const pB = watch(B), pC = watch(C);
  for (const p of [A, B, C]) await p.goto(`${BASE}/batch-control/requests/${R}/`);
  const ap2 = await login('approver-2'); const pD = watch(ap2.page);
  await ap2.page.goto(`${BASE}/batch-control/requests/${R}/`);
  const rq = await login('requester'); const pE = watch(rq.page);
  await rq.page.goto(`${BASE}/batch-control/requests/${R}/`);
  const rq2 = await rq.context.newPage(); const pF = watch(rq2);
  await rq2.goto(`${BASE}/batch-control/requests/${R}/`);
  await shot(B, approveBtn(B), 'X1-01-tabB-stale-form');
  await Promise.all([A.waitForLoadState('load'), approveBtn(A).click()]);
  log('X1 tab A approve ->', await outcome(A, 'X1-02-tabA-approved'));
  await Promise.all([B.waitForLoadState('load'), approveBtn(B).click()]);
  log('X1 tab B stale approve ->', pB, await outcome(B, 'X1-03-tabB-stale-approve'));
  await reject(C, 'stale reject from tab C');
  log('X1 tab C stale reject ->', pC, await outcome(C, 'X1-04-tabC-stale-reject'));
  await reject(ap2.page, 'stale reject by the other designated approver');
  log('X1 approver-2 stale reject ->', pD, await outcome(ap2.page, 'X1-05-approver2-stale-reject'));
  // requester: stale change-approvers form, then stale Cancel Request (confirmation dialog)
  const ch = rq.page.locator('form[action$="/changeApprover"]');
  await ch.locator('input[name="approvers"][value="approver-2"]').setChecked(false, { force: true });
  await Promise.all([rq.page.waitForLoadState('load'), ch.locator('button[name="Submit"]').click()]);
  log('X1 requester stale changeApprover ->', pE, await outcome(rq.page, 'X1-06-requester-stale-change-approvers'));
  await rq2.locator('#main-panel a', { hasText: 'Cancel Request' }).click();
  await sleep(500);
  const dlg = rq2.locator('dialog[open] button, .jenkins-dialog button').filter({ hasText: /yes|ok|cancel request|confirm/i }).first();
  if (await dlg.count()) await Promise.all([rq2.waitForLoadState('load'), dlg.click()]);
  await sleep(1500);
  log('X1 requester stale cancel ->', pF, await outcome(rq2, 'X1-07-requester-stale-cancel'));
  await sleep(12000);
  log('X1 server: status', await status('requests', R), 'builds after', await builds());
  for (const c of [ap1, ap2, rq]) await c.context.close();

  // --- grant: approver-1 two tabs, approver-2 stale reject.
  const G = await newGrant('e2e-05 X2 two tabs grant');
  log('X2 grant', G);
  const g1 = await login('approver-1');
  const [GA, GB] = [g1.page, await g1.context.newPage()]; const pGB = watch(GB);
  for (const p of [GA, GB]) await p.goto(`${BASE}/batch-control/grants/${G}/`);
  const g2 = await login('approver-2'); const pG2 = watch(g2.page);
  await g2.page.goto(`${BASE}/batch-control/grants/${G}/`);
  await Promise.all([GA.waitForLoadState('load'), approveBtn(GA).click()]);
  log('X2 tab A approve ->', await outcome(GA, 'X2-01-tabA-grant-approved'));
  await Promise.all([GB.waitForLoadState('load'), approveBtn(GB).click()]);
  log('X2 tab B stale approve ->', pGB, await outcome(GB, 'X2-02-tabB-grant-stale-approve'));
  await reject(g2.page, 'stale grant reject by approver-2');
  log('X2 approver-2 stale reject ->', pG2, await outcome(g2.page, 'X2-03-approver2-grant-stale-reject'));
  log('X2 server: status', await status('grants', G));
  for (const c of [g1, g2]) await c.context.close();

}
if (step === 'tabs') {
  // --- activation (HOLD/ACTIVATE on fresh-secret): approver-1 two tabs.
  const act = await login('requester');
  const before = await ids('activations');
  await act.page.goto(`${BASE}/job/fresh-secret/batch-control-activation`);
  const af = act.page.locator('form[action*="activation/submit"]').first();
  await af.locator('textarea[name="reason"]').fill('e2e-05 X3 two tabs activation');
  await af.locator('input[name="approvers"][value="approver-1"]').check({ force: true });
  await Promise.all([act.page.waitForLoadState('load'), af.locator('button[name="Submit"]').click()]);
  const V = [...(await ids('activations'))].find((x) => !before.has(x));
  await act.context.close();
  log('X3 activation', V);
  const v1 = await login('approver-1');
  const [VA, VB] = [v1.page, await v1.context.newPage()]; const pVB = watch(VB);
  for (const p of [VA, VB]) await p.goto(`${BASE}/batch-control/activations/${V}/`);
  await Promise.all([VA.waitForLoadState('load'), approveBtn(VA).click()]);
  log('X3 tab A approve ->', await outcome(VA, 'X3-01-tabA-activation-approved'));
  await Promise.all([VB.waitForLoadState('load'), approveBtn(VB).click()]);
  log('X3 tab B stale approve ->', pVB, await outcome(VB, 'X3-02-tabB-activation-stale-approve'));
  const hist = await api('admin', '/batch-control/changes/');
  const actRecs = (flat(hist.body.replace(/<[^>]+>/g, ' ')).match(/ACTIVATED fresh-secret/g) || []).length;
  log('X3 server: status', await status('activations', V), '| ACTIVATED fresh-secret records on the current Change Records page:', actRecs);
  await v1.context.close();
}

if (step === 'back' && process.argv[3] !== 'grant') {
  // B1: requester submits a run request, goes Back, submits again.
  const rq = await login('requester'); const p = rq.page; const pp = watch(p);
  const before = await ids('requests');
  await p.goto(`${BASE}/job/${JOB}/batch-control/`);
  await p.fill('textarea[name="reason"]', 'e2e-05 B1 back button');
  await p.locator('input[name="approvers"][value="approver-1"]').check({ force: true });
  await Promise.all([p.waitForLoadState('load'), p.click('button[name="Submit"]')]);
  const R1 = (p.url().match(/requests\/([^/]+)/) || [])[1];
  await p.goBack(); await sleep(800);
  const kept = await p.evaluate(() => ({ reason: document.querySelector('textarea[name="reason"]')?.value, checked: [...document.querySelectorAll('input[name="approvers"]:checked')].map((e) => e.value) }));
  await shot(p, 'form', 'B1-01-back-on-request-form');
  log('B1 after Back: url', p.url().replace(BASE, ''), 'form kept', kept);
  if (!kept.reason) { await p.fill('textarea[name="reason"]', 'e2e-05 B1 back button'); }
  if (!kept.checked.length) await p.locator('input[name="approvers"][value="approver-1"]').check({ force: true });
  await Promise.all([p.waitForLoadState('load'), p.click('button[name="Submit"]')]);
  const created = [...(await ids('requests'))].filter((x) => !before.has(x));
  log('B1 resubmit ->', pp, await outcome(p, 'B1-02-resubmitted'), '| new requests:', created);
  await rq.context.close();

  // B2: approver approves R1, goes Back to the page with the Approve form, approves again.
  const b0 = await builds();
  const ap = await login('approver-1'); const a = ap.page; const pa = watch(a);
  await a.goto(`${BASE}/batch-control/requests/${R1}/`);
  await Promise.all([a.waitForLoadState('load'), approveBtn(a).click()]);
  log('B2 approve ->', await outcome(a, 'B2-01-approved'));
  await a.goBack(); await sleep(800);
  const formBack = await approveBtn(a).count();
  await shot(a, formBack ? approveBtn(a) : '#main-panel', 'B2-02-after-back');
  log('B2 after Back: url', a.url().replace(BASE, ''), 'Approve form shown:', formBack, '| page', flat(await text(a)).slice(0, 160));
  if (formBack) {
    await Promise.all([a.waitForLoadState('load'), approveBtn(a).click()]);
    log('B2 re-approve from Back ->', pa, await outcome(a, 'B2-03-reapprove-from-back'));
  }
  await sleep(12000);
  log('B2 server: status', await status('requests', R1), 'builds before', b0, 'after', await builds());
  await ap.context.close();

}
if (step === 'back') {
  // B3: grant request submit, Back, resubmit; then approve, Back, approve again.
  const g = await login('requester'); const gp = g.page; const pg = watch(gp);
  const gb = await ids('grants');
  await gp.goto(`${BASE}/batch-control/grants/?scopeType=JOB&scopeFullName=${JOB}`);
  const f = gp.locator('form[action$="grants/create"]');
  await f.locator('input[name="actions"][value="CONFIGURE"]').check({ force: true });
  await f.locator('textarea[name="reason"]').fill('e2e-05 B3 back button grant');
  await f.locator('input[name="approvers"][value="approver-1"]').check({ force: true });
  await Promise.all([gp.waitForLoadState('load'), f.locator('button[name="Submit"]').click()]);
  const afterFirst = [...(await ids('grants'))].filter((x) => !gb.has(x));
  await gp.goBack(); await sleep(800);
  const gkept = await gp.evaluate(() => ({ reason: document.querySelector('form[action$="grants/create"] textarea[name="reason"]')?.value, checked: [...document.querySelectorAll('form[action$="grants/create"] input:checked')].map((e) => e.value) }));
  await shot(gp, 'form[action$="grants/create"]', 'B3-01-back-on-grant-form');
  log('B3 after Back: url', gp.url().replace(BASE, ''), 'form kept', gkept);
  const f2 = gp.locator('form[action$="grants/create"]');
  if (!gkept.reason) await f2.locator('textarea[name="reason"]').fill('e2e-05 B3 back button grant');
  // After Back the browser restores some checkboxes; set the state directly rather than toggling it.
  await f2.evaluate((f) => { f.querySelector('input[name="actions"][value="CONFIGURE"]').checked = true; f.querySelector('input[name="approvers"][value="approver-1"]').checked = true; });
  await Promise.all([gp.waitForLoadState('load'), f2.locator('button[name="Submit"]').click()]);
  const gcreated = [...(await ids('grants'))].filter((x) => !gb.has(x));
  log('B3 resubmit ->', pg, await outcome(gp, 'B3-02-grant-resubmitted'), '| grants after first submit', afterFirst, 'after resubmit', gcreated);
  await g.context.close();
  const G1 = afterFirst[0];
  const ga = await login('approver-1'); const gap = ga.page; const pga = watch(gap);
  await gap.goto(`${BASE}/batch-control/grants/${G1}/`);
  await Promise.all([gap.waitForLoadState('load'), approveBtn(gap).click()]);
  log('B3 approve grant ->', await outcome(gap, 'B3-03-grant-approved'));
  await gap.goBack(); await sleep(800);
  const gform = await approveBtn(gap).count();
  log('B3 after Back: Approve form shown:', gform);
  if (gform) {
    await Promise.all([gap.waitForLoadState('load'), approveBtn(gap).click()]);
    log('B3 re-approve from Back ->', pga, await outcome(gap, 'B3-04-grant-reapprove-from-back'));
  }
  log('B3 server: status', await status('grants', G1));
  await ga.context.close();
}

if (step === 'double') {
  // D1: double click on the run-request submit button.
  const rq = await login('requester'); const p = rq.page; const pp = watch(p);
  const before = await ids('requests');
  await p.goto(`${BASE}/job/${JOB}/batch-control/`);
  await p.fill('textarea[name="reason"]', 'e2e-05 D1 double submit');
  await p.locator('input[name="approvers"][value="approver-1"]').check({ force: true });
  await Promise.all([p.waitForLoadState('load'), p.dblclick('button[name="Submit"]')]);
  await sleep(1500);
  const created = [...(await ids('requests'))].filter((x) => !before.has(x));
  log('D1 double submit ->', pp, await outcome(p, 'D1-01-double-submit'), '| new requests:', created);
  await rq.context.close();

  // D2: double click on Approve.
  const R = created[0];
  const b0 = await builds();
  const ap = await login('approver-1'); const a = ap.page; const pa = watch(a);
  await a.goto(`${BASE}/batch-control/requests/${R}/`);
  await Promise.all([a.waitForLoadState('load'), approveBtn(a).dblclick()]);
  await sleep(1500);
  log('D2 double approve ->', pa, await outcome(a, 'D2-01-double-approve'));
  await sleep(12000);
  log('D2 server: status', await status('requests', R), 'builds before', b0, 'after', await builds());
  // D2b: two simultaneous approve POSTs from the same page's form (same crumb), as a fast double submit would send.
  await ap.context.close();

  // D3: double click on the grant request submit; D4: double click on the grant Approve.
  const g = await login('requester'); const gp = g.page; const pg = watch(gp);
  const gb = await ids('grants');
  await gp.goto(`${BASE}/batch-control/grants/?scopeType=JOB&scopeFullName=${JOB}`);
  const f = gp.locator('form[action$="grants/create"]');
  await f.locator('input[name="actions"][value="CONFIGURE"]').check({ force: true });
  await f.locator('textarea[name="reason"]').fill('e2e-05 D3 double submit grant');
  await f.locator('input[name="approvers"][value="approver-1"]').check({ force: true });
  await Promise.all([gp.waitForLoadState('load'), f.locator('button[name="Submit"]').dblclick()]);
  await sleep(1500);
  const gcreated = [...(await ids('grants'))].filter((x) => !gb.has(x));
  log('D3 double submit grant ->', pg, await outcome(gp, 'D3-01-grant-double-submit'), '| new grants:', gcreated);
  await g.context.close();
  const ga = await login('approver-1'); const pga = watch(ga.page);
  await ga.page.goto(`${BASE}/batch-control/grants/${gcreated[0]}/`);
  await Promise.all([ga.page.waitForLoadState('load'), approveBtn(ga.page).dblclick()]);
  await sleep(1500);
  log('D4 double approve grant ->', pga, await outcome(ga.page, 'D4-01-grant-double-approve'), '| status', await status('grants', gcreated[0]));
  await ga.context.close();
}
await close();
