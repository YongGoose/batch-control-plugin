// Scenario 5: incident lifecycle and rerun.
import { login, BASE, shot, text, api, log, close, sleep } from './lib.mjs';

const flat = (s) => s.replace(/\s+/g, ' ');
const S = 'S5';
const INC = process.argv[2];
const url = `${BASE}/batch-control/incidents/${INC}/`;

async function act(user, action, comment, tag) {
  const { page, context } = await login(user);
  await page.goto(url);
  const f = page.locator(`form[action$="/${action}"]`);
  if (!(await f.count())) { log(S, user, action, 'form absent'); await context.close(); return; }
  if (comment !== null) await f.locator('textarea[name="comment"]').fill(comment);
  await Promise.all([page.waitForLoadState('load'), f.locator('button').first().click()]);
  await sleep(500);
  log(S, user, action, '->', page.url(), '|', flat(await text(page)).slice(0, 300));
  await shot(page, page.locator('#main-panel table').nth(2).or(page.locator('#main-panel')).first(), `${S}-inc-${tag}`);
  await context.close();
}

if (!process.env.SKIP1) {
await act('approver-1', 'resolve', 'try resolving an OPEN incident directly', '00-resolve-from-open');
await act('approver-1', 'acknowledge', 'Looking at it (approver-1)', '01-ack');
await act('auditor', 'comment', '', '02-empty-comment');
await act('auditor', 'comment', 'Auditor note: P=x is a known bad value', '03-comment');

}
// rerun by admin with the prefilled parameters edited to P=ok
{
  const { page, context } = await login('admin');
  await page.goto(url);
  const link = page.locator('#main-panel a, #main-panel button', { hasText: /Rerun/i }).first();
  log('rerun control', await link.evaluate((e) => e.outerHTML.slice(0, 300)).catch(() => 'none'));
  await shot(page, link, `${S}-inc-04-rerun-control`);
  if (!process.env.SKIP1) { await link.click(); await page.waitForLoadState('load'); }
  log('rerun form', page.url(), flat(await text(page)).slice(0, 600));
  await shot(page, '#main-panel', `${S}-inc-05-rerun-form`);
  await page.locator('input[name="approvers"][value="approver-1"]').check({ force: true });
  await Promise.all([page.waitForLoadState('load'), page.locator('#main-panel button[value="Request Rerun"]').first().click()]);
  log('rerun submitted', page.url(), flat(await text(page)).slice(0, 700));
  await shot(page, '#main-panel', `${S}-inc-06-rerun-request`);
  const id = (page.url().match(/requests\/([^/]+)/) || [])[1];
  await context.close();
  if (id) {
    const a = await login('approver-1');
    await a.page.goto(`${BASE}/batch-control/requests/${id}/`);
    log('approver sees rerun request', flat(await text(a.page)).slice(0, 500));
    await Promise.all([a.page.waitForLoadState('load'), a.page.locator('form[action$="/approve"] button').first().click()]);
    await a.context.close();
    await sleep(8000);
  }
}
await act('approver-1', 'resolve', 'Rerun with P=ok succeeded', '07-resolve');
{
  const { page, context } = await login('auditor');
  await page.goto(url);
  log('final incident', flat(await text(page)).slice(0, 1200));
  await shot(page, '#main-panel', `${S}-inc-08-final`);
  await context.close();
}
await close();
