// B3 approver designation.
import { login, close, shot, api, job, waitFor, sleep, BASE, MAIL, log, requestRun, groovy, mails } from './lib.mjs';
const L = 'section-b.log';
const form = 'form[name="batch-control-request"]';
const post = (user, p, fields) => api(user, p, { method: 'POST', body: new URLSearchParams(fields), headers: { 'Content-Type': 'application/x-www-form-urlencoded' } });
const errText = (r) => (r.text.replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ').match(/Error (.{0,200}?) REST API/) || [null, r.text.slice(0, 120)])[1];
const reqCount = async () => (await api('admin', '/batch-control/history/requests.csv')).text.split('\n').filter(Boolean).length;
const cancel = async (page, url) => {
  await page.goto(url); page.once('dialog', (d) => d.accept());
  await page.locator('a:has-text("Cancel Request")').first().click(); await page.waitForTimeout(800);
  const ok = page.locator('dialog[open] button[data-id="ok"]').first(); if (await ok.count()) await Promise.all([page.waitForLoadState('load'), ok.click()]);
};

const rq = await login('requester');
// B3-01 form offers the listed approvers only
await rq.page.goto(`${BASE}/job/batch-pipeline/batch-control/`);
const offered = await rq.page.locator(`${form} input[name="approvers"]`).evaluateAll((es) => es.map((e) => e.value + (e.checked ? '(checked)' : '')));
await shot(rq.page, rq.page.locator(`${form} input[name="approvers"]`).first().locator('xpath=ancestor::*[contains(@class,"jenkins-form-item")][1]'), 'B3-01', { pad: 8 });
log(L, `B3-01 requester form offers ${JSON.stringify(offered)} (list: approver-1, approver-2, approver-disc, admin; approver-unlisted not listed)`);
// B3-02 none checked
const c0 = await reqCount();
await rq.page.fill(`${form} textarea[name="reason"]`, 'No approver ticked (B3-02).');
const [r2] = await Promise.all([rq.page.waitForNavigation({ waitUntil: 'load' }).catch(() => null), rq.page.locator('button:has-text("Submit Request")').click()]);
const t2 = (await rq.page.locator('#main-panel, body').first().innerText()).replace(/\s+/g, ' ').slice(0, 220);
await shot(rq.page, '#main-panel', 'B3-02', { pad: 8 });
log(L, `B3-02 submit with no approver -> ${r2 ? r2.status() : 'no nav'} "${t2}"; requests ${c0}->${await reqCount()}; form values kept: ${await rq.page.locator(`${form} textarea[name="reason"]`).count() ? await rq.page.inputValue(`${form} textarea[name="reason"]`) : 'form gone'}`);
// B3-03 REST with an unlisted approver
const r3 = await post('requester', '/job/batch-pipeline/batch-control/submit', { reason: 'unlisted approver (B3-03)', approvers: 'approver-unlisted' });
log(L, `B3-03 REST approvers=approver-unlisted -> ${r3.status} "${errText(r3)}"; requests ${c0}->${await reqCount()}`);
// C-28-ish: requester designates himself
const r3b = await post('requester', '/job/batch-pipeline/batch-control/submit', { reason: 'self (B3-03b)', approvers: 'requester' });
log(L, `B3-03 REST approvers=requester -> ${r3b.status} "${errText(r3b)}"`);
// B3-05 CSV of the A-16 request
const csv = (await api('admin', '/batch-control/history/requests.csv')).text.split('\n');
log(L, `B3-05 requests.csv A-16 row: ${csv.find((l) => l.startsWith('20260929-200048-tmkwlm'))}`);

// B3-06 two approvers click Approve at the same moment
{
  const url = await requestRun(rq.page, '/job/batch-pipeline/', { reason: 'Race between two approvers (B3-06).', approvers: ['approver-1', 'approver-2'] });
  const n0 = (await job('batch-pipeline')).nextBuildNumber;
  const a1 = await login('approver-1'); const a2 = await login('approver-2');
  for (const a of [a1, a2]) { await a.page.goto(url); await a.page.fill('form[name="approve"] textarea[name="comment"]', 'race'); }
  await Promise.all([a1, a2].map((a) => Promise.all([a.page.waitForNavigation({ waitUntil: 'load' }).catch(() => null), a.page.locator('form[name="approve"] button').first().click()])));
  const t = [];
  for (const [n, a] of [['approver-1', a1], ['approver-2', a2]]) { t.push(`${n}: ${a.page.url().replace(BASE, '')} "${(await a.page.locator('#main-panel, body').first().innerText()).replace(/\s+/g, ' ').slice(0, 140)}"`); }
  await waitFor(async () => (await job('batch-pipeline', 'nextBuildNumber')).nextBuildNumber > n0, { timeout: 60000 });
  await sleep(10000);
  const loser = t.find((x) => /Error|already/i.test(x)) ? (t[0].includes('Error') || /already/i.test(t[0]) ? a1 : a2) : a2;
  await shot(loser.page, '#main-panel, body', 'B3-06-loser', { pad: 8 });
  log(L, `B3-06 ${t.join(' || ')}; builds added ${(await job('batch-pipeline')).nextBuildNumber - n0}`);
  await a1.context.close(); await a2.context.close();
}
// B3-07 admin, not designated
{
  const url = await requestRun(rq.page, '/job/batch-pipeline/', { reason: 'Designated approver-1 only (B3-07/B3-08).', approvers: ['approver-1'] });
  const ad = await login('admin');
  await ad.page.goto(url);
  const forms = await ad.page.locator('form[name="approve"], form[name="reject"]').count();
  const r = await post('admin', url.replace(BASE, '') + 'approve', { comment: 'admin override' });
  log(L, `B3-07 admin on a request designated to approver-1: decision forms ${forms}; POST approve -> ${r.status} "${errText(r)}"`);
  await shot(ad.page, '#main-panel', 'B3-07', { pad: 8 });
  // B3-08 requester changes approver-1 -> approver-2
  await rq.page.goto(url);
  const cf = rq.page.locator('form[name="changeApprover"]');
  await cf.locator('input[value="approver-1"] + label').click();
  await cf.locator('input[value="approver-2"] + label').click();
  await Promise.all([rq.page.waitForLoadState('load'), cf.locator('button:has-text("Change Approvers")').click()]);
  await rq.page.goto(url);
  const changes = (await rq.page.locator('h2:has-text("Approver Changes") + table, table:has(th:has-text("From"))').first().innerText().catch(() => 'NO TABLE')).replace(/\s+/g, ' ');
  await shot(rq.page, ['#main-panel table', 'h2:has-text("Approver Changes")'], 'B3-08', { pad: 8 });
  const a1 = await login('approver-1');
  await a1.page.goto(url);
  const a1forms = await a1.page.locator('form[name="approve"]').count();
  const a1status = (await a1.page.locator('#main-panel, body').first().innerText()).replace(/\s+/g, ' ').slice(0, 100);
  const r8 = await post('approver-1', url.replace(BASE, '') + 'approve', { comment: 'still me?' });
  await sleep(3000);
  const id = url.split('/requests/')[1].replace('/', '');
  const m = (await mails(id)).map((x) => `${x.To[0].Address}: ${x.Subject}`);
  log(L, `B3-08 approver changes table "${changes}"; approver-1 now: forms ${a1forms} page "${a1status}", POST approve -> ${r8.status}; mails ${JSON.stringify(m)}`);
  // B3-09 after decision: approver-2 rejects, then the change form must be gone
  const a2 = await login('approver-2');
  await a2.page.goto(url);
  await a2.page.fill('form[name="reject"] textarea[name="comment"]', 'Not needed after all.');
  await Promise.all([a2.page.waitForLoadState('load'), a2.page.locator('form[name="reject"] button').first().click()]);
  await rq.page.goto(url);
  const cfCount = await rq.page.locator('form[name="changeApprover"]').count();
  const r9 = await post('requester', url.replace(BASE, '') + 'changeApprover', { approvers: 'approver-1' });
  log(L, `B3-09 after REJECTED: change form ${cfCount}; POST changeApprover -> ${r9.status} "${errText(r9)}"`);
  await shot(rq.page, '#main-panel', 'B3-09', { pad: 8 });
  for (const c of [ad, a1, a2]) await c.context.close();
}
// B3-10 approver-1 loses Approve after designation
{
  const url = await requestRun(rq.page, '/job/batch-pipeline/', { reason: 'Approve revoked before the decision (B3-10).', approvers: ['approver-1'] });
  const a1 = await login('approver-1');
  await a1.page.goto(url);
  await a1.page.fill('form[name="approve"] textarea[name="comment"]', 'after losing Approve');
  const REVOKE = `import org.jenkinsci.plugins.matrixauth.*; import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions as P
def s = jenkins.model.Jenkins.get().authorizationStrategy
def e = new PermissionEntry(AuthorizationType.USER, 'approver-1')
def m = s.getGrantedPermissionEntries(); m.get(P.APPROVE)?.remove(e)
jenkins.model.Jenkins.get().save(); println 'approve removed: ' + !s.getGrantedPermissionEntries().get(P.APPROVE).contains(e)`;
  log(L, `B3-10 arrange: ${await groovy(REVOKE)}`);
  const [r] = await Promise.all([a1.page.waitForNavigation({ waitUntil: 'load' }).catch(() => null), a1.page.locator('form[name="approve"] button').first().click()]);
  const t = (await a1.page.locator('#main-panel, body').first().innerText()).replace(/\s+/g, ' ').slice(0, 200);
  await shot(a1.page, '#main-panel, body', 'B3-10', { pad: 8 });
  const st = (await api('admin', '/batch-control/history/requests.csv')).text.split('\n').find((l) => l.startsWith(url.split('/requests/')[1].replace('/', '')));
  log(L, `B3-10 approver-1 clicks Approve without Approve -> ${r ? r.status() : 'no nav'} "${t}"; row ${st && st.split(',')[6]}`);
  log(L, `B3-10 restore: ${await groovy(`import org.jenkinsci.plugins.matrixauth.*; import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions as P
def s = jenkins.model.Jenkins.get().authorizationStrategy; s.add(P.APPROVE, new PermissionEntry(AuthorizationType.USER, 'approver-1')); jenkins.model.Jenkins.get().save(); println 'restored'`)}`);
  await cancel(rq.page, url);
  await a1.context.close();
}
// B3-11 job-level approvers on batch-daily = approver-2
{
  const ad = await login('admin');
  await ad.page.goto(`${BASE}/job/batch-daily/configure`); await ad.page.waitForTimeout(1500);
  const ta = ad.page.locator('textarea[name="_.jobApproversText"]').first();
  await ta.fill('approver-2');
  await shot(ad.page, ta.locator('xpath=ancestor::*[contains(@class,"jenkins-form-item")][1]'), 'B3-11-1-job-approvers', { pad: 8 });
  await Promise.all([ad.page.waitForNavigation(), ad.page.locator('button[name="Submit"]').click()]);
  await rq.page.goto(`${BASE}/job/batch-daily/batch-control/`);
  const offered = await rq.page.locator(`${form} input[name="approvers"]`).evaluateAll((es) => es.map((e) => e.value + (e.checked ? '(checked)' : '')));
  await shot(rq.page, rq.page.locator(`${form} input[name="approvers"]`).first().locator('xpath=ancestor::*[contains(@class,"jenkins-form-item")][1]'), 'B3-11-2-form', { pad: 8 });
  const r = await post('requester', '/job/batch-daily/batch-control/submit', { reason: 'job-level list (B3-11)', approvers: 'approver-1', DATE: '2026-10-02', MODE: 'full' });
  const url = await requestRun(rq.page, '/job/batch-daily/', { reason: 'Job-level approver (B3-11).', approvers: [] , params: { DATE: '2026-10-02' } });
  const rc = await post('requester', url.replace(BASE, '') + 'changeApprover', { approvers: 'approver-1' });
  log(L, `B3-11 job approvers=approver-2: form offers ${JSON.stringify(offered)}; REST designate approver-1 -> ${r.status} "${errText(r)}"; request ${url.split('/requests/')[1]} changeApprover to approver-1 -> ${rc.status} "${errText(rc)}"`);
  await cancel(rq.page, url);
  await ad.page.goto(`${BASE}/job/batch-daily/configure`); await ad.page.waitForTimeout(1200);
  await ad.page.locator('textarea[name="_.jobApproversText"]').first().fill('');
  await Promise.all([ad.page.waitForNavigation(), ad.page.locator('button[name="Submit"]').click()]);
  await ad.context.close();
}
// B3-12 grant request Change Approvers
{
  await rq.page.goto(`${BASE}/batch-control/grants/`);
  await rq.page.fill('input[name="scopeFullName"]', 'batch-pipeline');
  await rq.page.locator('#grant-action-configure + label').click();
  await rq.page.fill('textarea[name="reason"]', 'Grant approver change (B3-12).');
  await rq.page.locator('#grant-approver-0 + label').click();
  await Promise.all([rq.page.waitForLoadState('load'), rq.page.locator('button:has-text("Request Grant")').click()]);
  const href = await rq.page.locator('#main-panel table').first().locator('tbody tr', { hasText: 'Grant approver change (B3-12)' }).first().locator('a').first().getAttribute('href');
  const url = new URL(href, rq.page.url()).href;
  await rq.page.goto(url);
  const cf = rq.page.locator('form[name="changeApprover"]');
  const has = await cf.count();
  if (has) {
    await cf.locator('input[value="approver-1"] + label').click();
    await cf.locator('input[value="approver-2"] + label').click();
    await Promise.all([rq.page.waitForLoadState('load'), cf.locator('button:has-text("Change Approvers")').click()]);
    await rq.page.goto(url);
  }
  const t = (await rq.page.locator('#main-panel').innerText()).replace(/\s+/g, ' ');
  await shot(rq.page, ['#main-panel table', 'h2:has-text("Approver Changes")'], 'B3-12', { pad: 8 });
  log(L, `B3-12 grant request change form present=${has}; after change: ${(t.match(/Approvers \S+/) || [''])[0]} | changes: ${(t.match(/Approver Changes.{0,160}/) || ['none'])[0]}`);
  await cancel(rq.page, url);
}
await close();
