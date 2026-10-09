// Helper: requester files a run request through the job's Request Run form, approver-1 approves it.
// node req-approve.mjs <job> [requester] [--no-approve]
import { login, BASE, text, log, close, sleep } from './lib.mjs';

const [job, user = 'requester', flag] = process.argv.slice(2);
const jp = job.split('/').map((s) => 'job/' + encodeURIComponent(s)).join('/');
const { page, context } = await login(user);
await page.goto(`${BASE}/${jp}/batch-control/`);
await page.fill('textarea[name="reason"]', `fresh e2e-04 run of ${job}`);
await page.locator('input[name="approvers"][value="approver-1"]').check({ force: true });
await Promise.all([page.waitForLoadState('load'), page.click('button[name="Submit"]')]);
const id = (page.url().match(/requests\/([^/]+)/) || [])[1];
log('request', job, id, page.url());
await context.close();
if (id && flag !== '--no-approve') {
  const a = await login('approver-1');
  await a.page.goto(`${BASE}/batch-control/requests/${id}/`);
  await Promise.all([a.page.waitForLoadState('load'), a.page.locator('form[action$="/approve"] button').first().click()]);
  log('approved', id, (await text(a.page)).replace(/\s+/g, ' ').slice(0, 200));
  await a.context.close();
  await sleep(1000);
}
console.log(id);
await close();
