// Scenario 2: run approval, end to end, each role in its own context.
import fs from 'node:fs';
import { login, BASE, shot, text, api, log, close, mails, sleep, OUT } from './lib.mjs';

const S = 'S2';
const JOB = 'fresh-daily';
const state = {};

// 01 requester: empty reason first, then a real request
if (process.env.REQ) { state.reqId = process.env.REQ; } else {
  const { page } = await login('requester');
  await page.goto(`${BASE}/job/${JOB}/`);
  await shot(page, page.locator('#tasks a, #side-panel a', { hasText: 'Request Run' }).first(), `${S}-01-sidebar-request-run`);
  await page.locator('#tasks a, #side-panel a', { hasText: 'Request Run' }).first().click();
  await page.waitForLoadState('load');
  // empty reason, keep other inputs to see whether they survive
  await page.locator('input[name="value"]').first().fill('2026-10-01');
  await page.locator('select[name="value"]').first().selectOption('DELTA');
  await page.locator('input[name="approvers"][value="approver-1"]').check({ force: true });
  await Promise.all([page.waitForLoadState('load'), page.click('button[name="Submit"], input[name="Submit"]')]);
  const t0 = (await text(page)).replace(/\s+/g, ' ');
  const kept = {
    url: page.url(),
    date: await page.locator('input[name="value"]').first().inputValue().catch(() => '(gone)'),
    mode: await page.locator('select[name="value"]').first().inputValue().catch(() => '(gone)'),
    appr: await page.locator('input[name="approvers"][value="approver-1"]').isChecked().catch(() => '(gone)'),
  };
  log(S + '-01 empty reason ->', kept, t0.slice(0, 400));
  await shot(page, '#main-panel', `${S}-01-empty-reason`);

  await page.goto(`${BASE}/job/${JOB}/batch-control/`);
  await page.fill('textarea[name="reason"]', 'Re-run of the 1 Oct settlement batch in DELTA mode (fresh e2e-04)');
  await page.locator('input[name="value"]').first().fill('2026-10-01');
  await page.locator('select[name="value"]').first().selectOption('DELTA');
  await page.locator('input[name="approvers"][value="approver-1"]').check({ force: true });
  await page.locator('input[name="approvers"][value="approver-2"]').check({ force: true });
  await shot(page, 'form[action="submit"]', `${S}-01-filled-form`);
  await Promise.all([page.waitForLoadState('load'), page.click('button[name="Submit"], input[name="Submit"]')]);
  const t = (await text(page)).replace(/\s+/g, ' ');
  log(S + '-01 after submit url', page.url(), '|', t.slice(0, 600));
  await shot(page, '#main-panel', `${S}-01-submitted`);
  state.reqUrl = page.url();
  state.reqId = (page.url().match(/requests\/([^/]+)/) || [])[1];
  log('request id', state.reqId);
  await page.context().close();
}
await sleep(3000);
const m = await mails(`to:approver-1@e2e.local subject:${JOB}`);
log(S + '-01 mail to approver-1', m.slice(0, 2).map((x) => `${x.Created} | ${x.Subject}`));

// 02 approver-1: inbox, detail, approve
{
  const { page } = await login('approver-1');
  await page.goto(`${BASE}/batch-control/requests/`);
  const row = page.locator('tr', { hasText: state.reqId }).first();
  log(S + '-02 inbox row', (await row.innerText().catch(() => 'not in list')).replace(/\s+/g, ' '));
  log(S + '-02 inbox head', (await text(page)).replace(/\s+/g, ' ').slice(0, 500));
  await shot(page, row, `${S}-02-inbox-row`);
  await page.goto(`${BASE}/batch-control/requests/${state.reqId}/`);
  const t = (await text(page)).replace(/\s+/g, ' ');
  log(S + '-02 detail', t.slice(0, 1500));
  await shot(page, '#main-panel', `${S}-02-detail`);
  fs.writeFileSync(`${OUT}/s2-detail.html`, await page.content());
  // approve (double-click on purpose)
  const af = page.locator('form[action$="/approve"]');
  await af.locator('textarea[name="comment"]').fill('OK, DELTA for 1 Oct');
  await shot(page, af, `${S}-02-approve-form`);
  await af.locator('button').first().dblclick();
  await page.waitForLoadState('load');
  await sleep(1500);
  log(S + '-02 after approve url', page.url(), '|', (await text(page)).replace(/\s+/g, ' ').slice(0, 800));
  await shot(page, '#main-panel', `${S}-02-after-approve`);
  await page.context().close();
}

// wait for the build
let build;
for (let i = 0; i < 30; i++) {
  const r = await api('admin', `/job/${JOB}/api/json?tree=builds[number,result,building,actions[parameters[name,value],causes[shortDescription]]]`);
  const b = r.json().builds;
  if (b.length && !b[0].building && b[0].result) { build = b; break; }
  await sleep(2000);
}
log(S + '-03 builds', JSON.stringify(build && build.map((b) => ({ n: b.number, r: b.result, p: b.actions.filter((a) => a.parameters).flatMap((a) => a.parameters), c: b.actions.filter((a) => a.causes).flatMap((a) => a.causes.map((c) => c.shortDescription)) }))));
const q = await api('admin', '/queue/api/json');
log('queue items', q.json().items.map((i) => i.task.name));

// 03 requester sees the outcome
{
  const { page } = await login('requester');
  await page.goto(`${BASE}/batch-control/requests/${state.reqId}/`);
  const t = (await text(page)).replace(/\s+/g, ' ');
  log(S + '-03 requester detail', t.slice(0, 1500));
  await shot(page, '#main-panel', `${S}-03-requester-detail`);
  if (build) {
    await page.goto(`${BASE}/job/${JOB}/${build[0].number}/`);
    log(S + '-03 build page', (await text(page)).replace(/\s+/g, ' ').slice(0, 800));
    await shot(page, '#main-panel', `${S}-03-build-page`);
  }
  await page.context().close();
}
await sleep(2000);
const m2 = await mails(`to:requester@e2e.local subject:${JOB}`);
log(S + '-03 mail to requester', m2.slice(0, 3).map((x) => `${x.Created} | ${x.Subject}`));
fs.writeFileSync(`${OUT}/s2-state.json`, JSON.stringify(state));
await close();
