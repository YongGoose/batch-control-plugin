// Re-verification B: FD-04, FD-05, FD-06 and the run-request neighbours.
import fs from 'node:fs';
import { login, BASE, shot, text, api, log, close, sleep, mails, OUT } from '../lib.mjs';

const flat = (s) => s.replace(/\s+/g, ' ');
const step = process.argv[2];
async function request(job, approvers, reason) {
  const { page, context } = await login('requester');
  await page.goto(`${BASE}/job/${job}/batch-control/`);
  await page.fill('textarea[name="reason"]', reason);
  for (const a of approvers) await page.locator(`input[name="approvers"][value="${a}"]`).check({ force: true });
  await Promise.all([page.waitForLoadState('load'), page.click('button[name="Submit"]')]);
  const id = (page.url().match(/requests\/([^/]+)/) || [])[1];
  await context.close();
  return id;
}
async function mailsFor(id) {
  const r = await fetch(`http://localhost:8025/api/v1/search?query=${id}&limit=20`);
  const ms = (await r.json()).messages || [];
  return ms.map((m) => `${m.To[0].Address}: ${m.Subject}`);
}
async function mailText(id, subj) {
  const r = await fetch(`http://localhost:8025/api/v1/search?query=${id}&limit=20`);
  const m = ((await r.json()).messages || []).find((x) => subj.test(x.Subject));
  if (!m) return '(none)';
  return flat((await (await fetch(`http://localhost:8025/api/v1/message/${m.ID}`)).json()).Text);
}
const out = {};

if (step === 'a') {
  // cancel: approvers told, requester not
  const can = await request('fresh-daily', ['approver-1', 'approver-2'], 'verify: cancel me');
  {
    const { page, context } = await login('requester');
    await page.goto(`${BASE}/batch-control/requests/${can}/`);
    await page.locator('a', { hasText: /^\s*Cancel Request\s*$/ }).click();
    await page.locator('dialog[open] button', { hasText: 'Yes' }).click();
    await page.waitForLoadState('load'); await sleep(800);
    log('FD-04 cancel status', flat(await text(page)).slice(0, 300));
    await shot(page, page.locator('#main-panel table').first(), 'FD04-cancelled');
    await context.close();
  }
  await sleep(2500);
  log('FD-04 cancel mails', can, await mailsFor(can));
  log('FD-04 cancel mail text to approver', await mailText(can, /cancel/i));

  // disabled job: approve refused, reject possible
  log('disable', (await api('admin', '/job/fresh-token/disable', { method: 'POST' })).status);
  const dis = await request('fresh-token', ['approver-1'], 'verify: approve while disabled');
  {
    const { page, context } = await login('approver-1');
    await page.goto(`${BASE}/batch-control/requests/${dis}/`);
    log('FD-06 decision section (disabled)', flat(await text(page)).split('Decision')[1]?.slice(0, 500));
    await shot(page, page.locator('#main-panel h2, #main-panel h3', { hasText: 'Decision' }).locator('xpath=following-sibling::*[1]').or(page.locator('#main-panel')).first(), 'FD06-decision-disabled');
    const af = page.locator('form[action$="/approve"]');
    log('FD-06 approve form present', await af.count(), 'button disabled?', await af.locator('button').first().isDisabled().catch(() => 'n/a'));
    if (await af.count()) {
      // forced POST as a user would get by clicking an enabled button or a stale tab
      const res = await page.evaluate(async (id) => {
        const r = await fetch(`/batch-control/requests/${id}/approve`, { method: 'POST', headers: { 'Jenkins-Crumb': document.head.dataset.crumbValue, 'Content-Type': 'application/x-www-form-urlencoded' }, body: 'comment=' });
        return `${r.status} ${(await r.text()).replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ').match(/(disabled[^.]*\.[^.]*\.)/i)?.[1] || ''}`;
      }, dis);
      log('FD-06 approve POST on disabled', res);
    }
    await page.goto(`${BASE}/batch-control/requests/${dis}/`);
    log('FD-06 status after approve attempt', flat(await text(page)).slice(0, 200));
    await shot(page, '#main-panel', 'FD06-after-approve-attempt');
    await context.close();
  }
  log('enable', (await api('admin', '/job/fresh-token/enable', { method: 'POST' })).status);
  out.can = can; out.dis = dis; 
  fs.writeFileSync(`${OUT}/vb.json`, JSON.stringify(out));
}

if (step === 'b') {
  // rename -> INVALIDATED with mail to requester and approvers
  const inv = await request('fresh-up-dst', ['approver-2'], 'verify: pending while renamed');
  const { page, context } = await login('admin');
  await page.goto(`${BASE}/job/fresh-up-dst/confirm-rename`);
  await page.fill('input[name="newName"]', 'fresh-up-dst2');
  await page.waitForTimeout(800);
  await Promise.all([page.waitForNavigation(), page.locator('#main-panel button[name="Submit"], #main-panel button.jenkins-button--primary').first().click()]);
  await context.close();
  await sleep(2500);
  const q = await login('requester');
  await q.page.goto(`${BASE}/batch-control/requests/${inv}/`);
  log('FD-04 invalidated view', flat(await text(q.page)).slice(0, 400));
  await shot(q.page, q.page.locator('#main-panel table').first(), 'FD04-invalidated');
  await q.context.close();
  log('FD-04 invalidated mails', inv, await mailsFor(inv));
  log('FD-04 invalidated mail text (requester)', await mailText(inv, /invalid/i));
  // rename back
  const a = await login('admin');
  await a.page.goto(`${BASE}/job/fresh-up-dst2/confirm-rename`);
  await a.page.fill('input[name="newName"]', 'fresh-up-dst');
  await a.page.waitForTimeout(800);
  await Promise.all([a.page.waitForNavigation(), a.page.locator('#main-panel button[name="Submit"], #main-panel button.jenkins-button--primary').first().click()]);
  log('renamed back', a.page.url());
  await a.context.close();
}
await close();
