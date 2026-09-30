// Scenario 2 continued: reject, cancel, race of two approvers, a non-designated approver, back button.
import fs from 'node:fs';
import { login, BASE, shot, text, api, log, close, mails, sleep, OUT } from './lib.mjs';

const S = 'S2';
const JOB = 'fresh-daily';
const flat = (s) => s.replace(/\s+/g, ' ');

async function request(user, approvers, reason, date = '2026-10-02') {
  const { page, context } = await login(user);
  await page.goto(`${BASE}/job/${JOB}/batch-control/`);
  await page.fill('textarea[name="reason"]', reason);
  await page.locator('input[name="value"]').first().fill(date);
  for (const a of approvers) await page.locator(`input[name="approvers"][value="${a}"]`).check({ force: true });
  await Promise.all([page.waitForLoadState('load'), page.click('button[name="Submit"]')]);
  const id = (page.url().match(/requests\/([^/]+)/) || [])[1];
  await context.close();
  return id;
}
const status = async (id) => flat(await (await login('admin')).page.goto(`${BASE}/batch-control/requests/${id}/`).then(async (r) => r && ''));

// A: rejection
const rej = process.env.SKIPA ? null : 1;
if (!process.env.SKIPA) {
const rej = await request('requester', ['approver-1'], 'Reject me please (fresh e2e-04)');
log('reject req', rej);
{
  const { page, context } = await login('approver-1');
  await page.goto(`${BASE}/batch-control/requests/${rej}/`);
  const rf = page.locator('form[action$="/reject"]');
  await rf.locator('button').first().click();
  await page.waitForLoadState('load');
  log(S + '-04 reject with empty comment ->', page.url(), flat(await text(page)).slice(0, 700));
  await shot(page, '#main-panel', `${S}-04-reject-empty`);
  await page.goto(`${BASE}/batch-control/requests/${rej}/`);
  await page.locator('form[action$="/reject"] textarea[name="comment"]').fill('Wrong date: the 2 Oct run is scheduled anyway');
  await Promise.all([page.waitForLoadState('load'), page.locator('form[action$="/reject"] button').first().click()]);
  log(S + '-04 after reject', flat(await text(page)).slice(0, 700));
  await context.close();
}
{
  const { page, context } = await login('requester');
  await page.goto(`${BASE}/batch-control/requests/${rej}/`);
  const t = flat(await text(page));
  log(S + '-04 requester sees rejection', t.slice(0, 700));
  await shot(page, page.locator('#main-panel table').first(), `${S}-04-requester-rejected`);
  await context.close();
}

}
// B: cancel by requester, with a double click and a Back + resubmit
const can = await request('requester', ['approver-2'], 'Cancel me (fresh e2e-04)');
log('cancel req', can);
{
  const { page, context } = await login('requester');
  await page.goto(`${BASE}/batch-control/requests/${can}/`);
  const cf = page.locator('a, button', { hasText: /^\s*Cancel Request\s*$/ }).first();
  log('cancel control', await cf.evaluate((e) => e.outerHTML.slice(0, 400)));
  await shot(page, cf, `${S}-05-cancel-form`);
  page.on('dialog', (d) => { log('dialog', d.message()); d.accept(); });
  await cf.click();
  await page.waitForTimeout(600);
  const dlg = page.locator('dialog[open]');
  if (await dlg.count()) { log('jenkins dialog', flat(await dlg.innerText())); await dlg.locator('button', { hasText: /yes|ok|cancel request/i }).first().click(); }
  await page.waitForLoadState('load');
  await sleep(800);
  log(S + '-05 after cancel', page.url(), flat(await text(page)).slice(0, 700));
  await shot(page, page.locator('#main-panel table').first(), `${S}-05-cancelled`);
  await page.goBack();
  await page.waitForLoadState('load');
  log(S + '-05 back page', flat(await text(page)).slice(0, 300));
  await context.close();
}
// approver tries to approve the cancelled request from a stale tab
{
  const { page, context } = await login('approver-2');
  await page.goto(`${BASE}/batch-control/requests/${can}/`);
  log(S + '-05 approver view of cancelled', flat(await text(page)).slice(0, 600));
  await context.close();
}

// C: two approvers in two browsers race on the same request
const race = await request('requester', ['approver-1', 'approver-2'], 'Race (fresh e2e-04)', '2026-10-03');
log('race req', race);
{
  const a = await login('approver-1');
  const b = await login('approver-2');
  await a.page.goto(`${BASE}/batch-control/requests/${race}/`);
  await b.page.goto(`${BASE}/batch-control/requests/${race}/`);
  await b.page.locator('form[action$="/reject"] textarea[name="comment"]').fill('Race: reject');
  await Promise.all([
    a.page.locator('form[action$="/approve"] button').first().click(),
    b.page.locator('form[action$="/reject"] button').first().click(),
  ]);
  await a.page.waitForLoadState('load'); await b.page.waitForLoadState('load');
  await sleep(1000);
  log(S + '-06 race A', a.page.url(), flat(await text(a.page)).slice(0, 500));
  log(S + '-06 race B', b.page.url(), flat(await text(b.page)).slice(0, 500));
  await shot(a.page, '#main-panel', `${S}-06-race-approver-1`);
  await shot(b.page, '#main-panel', `${S}-06-race-approver-2`);
  await a.context.close(); await b.context.close();
}

// D: a non-designated approver opens the request
const nd = await request('requester', ['approver-1'], 'Only approver-1 may decide (fresh e2e-04)', '2026-10-04');
log('non-designated req', nd);
for (const u of ['approver-2', 'approver-unlisted', 'admin', 'auditor']) {
  const { page, context } = await login(u);
  const r = await page.goto(`${BASE}/batch-control/requests/${nd}/`);
  const t = flat(await text(page));
  log(S + '-07', u, r.status(), 'forms:', await page.locator('form[action$="/approve"]').count(), '|', t.slice(0, 500));
  await shot(page, '#main-panel', `${S}-07-${u}-view-nondesignated`);
  // forced POST from this user's session
  const res = await page.evaluate(async (id) => {
    const crumb = document.head.dataset.crumbValue;
    const r = await fetch(`/batch-control/requests/${id}/approve`, { method: 'POST', headers: { 'Jenkins-Crumb': crumb, 'Content-Type': 'application/x-www-form-urlencoded' }, body: 'comment=x' });
    return `${r.status} ${(await r.text()).replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ').slice(0, 300)}`;
  }, nd);
  log(S + '-07', u, 'forced approve POST ->', res);
  await context.close();
}

fs.writeFileSync(`${OUT}/s2-more.json`, JSON.stringify({ rej, can, race, nd }));
await sleep(2000);
for (const x of await mails('to:requester@e2e.local')) if (/fresh-daily/.test(x.Subject)) log('mail requester', x.Created, x.Subject);
for (const x of await mails('to:approver-2@e2e.local')) if (/fresh-daily/.test(x.Subject)) log('mail approver-2', x.Created, x.Subject);
await close();
