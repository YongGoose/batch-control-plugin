// e2e-05 check 3: the double-POST path a browser double click did not reach.
//  D5 - the approve form posted twice in parallel from the approver's page (same session, same crumb)
//  D6 - approver-1 approves and approver-2 rejects at the same moment (two sessions)
//  D7 - the run-request form posted twice in parallel from the requester's page
import { login, BASE, shot, text, api, log, close, sleep } from './lib.mjs';
const flat = (s) => s.replace(/\s+/g, ' ').trim();
const builds = async () => (await api('admin', '/job/fresh-daily/api/json?tree=builds[number]')).json().builds.map((b) => b.number);
const status = async (id) => (flat((await api('admin', `/batch-control/requests/${id}/`)).body.replace(/<[^>]+>/g, ' ')).match(/Status (\w+)/) || [])[1];
const ids = async () => new Set([...(await api('admin', '/batch-control/requests/')).body.matchAll(/href="[^"]*?(\d{8}-\d{6}-[a-z0-9]{6})\/?"/g)].map((m) => m[1]));
async function request(reason, approvers) {
  const { page, context } = await login('requester');
  await page.goto(`${BASE}/job/fresh-daily/batch-control/`);
  await page.fill('textarea[name="reason"]', reason);
  for (const ap of approvers) await page.locator(`input[name="approvers"][value="${ap}"]`).check({ force: true });
  await Promise.all([page.waitForLoadState('load'), page.click('button[name="Submit"]')]);
  await context.close();
  return page.url().match(/requests\/([^/]+)/)[1];
}
// Two POSTs of the page's form at the same moment, from the logged-in browser session (its cookie, a crumb of that session).
async function twice(page, sel) {
  const { action, fields } = await page.evaluate((s) => { const f = document.querySelector(s); return { action: f.action, fields: [...new FormData(f)].filter(([k]) => k !== 'json' && k !== 'Jenkins-Crumb') }; }, sel);
  const c = await (await page.context().request.get(`${BASE}/crumbIssuer/api/json`)).json();
  const form = Object.fromEntries(fields);
  const json = JSON.stringify(form);
  const go = () => page.context().request.post(action, { form: { ...form, json }, headers: { [c.crumbRequestField]: c.crumb }, maxRedirects: 0 }).then((r) => r.status());
  return Promise.all([go(), go()]);
}

// D5
let R = await request('e2e-05 D5 parallel approve', ['approver-1']);
let b0 = await builds();
const a = await login('approver-1');
await a.page.goto(`${BASE}/batch-control/requests/${R}/`);
const r5 = await twice(a.page, 'form[action$="/approve"]');
await sleep(12000);
await a.page.reload();
log('D5', R, 'two parallel approve POSTs ->', r5, '| status', await status(R), '| builds before', b0, 'after', await builds());
await shot(a.page, '#main-panel table', 'D5-01-after-parallel-approve');
await a.context.close();

// D6
if (!process.env.SKIP_D6) {
R = await request('e2e-05 D6 approve vs reject', ['approver-1', 'approver-2']);
b0 = await builds();
const p1 = await login('approver-1'), p2 = await login('approver-2');
for (const p of [p1.page, p2.page]) await p.goto(`${BASE}/batch-control/requests/${R}/`);
await p2.page.fill('form[action$="/reject"] textarea[name="comment"]', 'racing reject');
const res = [];
for (const p of [p1.page, p2.page]) p.on('response', (r) => { if (r.request().method() === 'POST') res.push(`${r.status()} ${new URL(r.url()).pathname.split('/').pop()}`); });
await Promise.all([
  Promise.all([p1.page.waitForLoadState('load'), p1.page.locator('form[action$="/approve"] button[name="Submit"]').click()]),
  Promise.all([p2.page.waitForLoadState('load'), p2.page.locator('form[action$="/reject"] button').click()]),
]);
await sleep(12000);
log('D6', R, 'approve || reject ->', res, '| status', await status(R), '| builds before', b0, 'after', await builds(),
  '| approver-1 sees:', flat(await text(p1.page)).slice(0, 150), '| approver-2 sees:', flat(await text(p2.page)).slice(0, 150));
await shot(p1.page, '#main-panel', 'D6-01-approver1');
await shot(p2.page, '#main-panel', 'D6-02-approver2');
await p1.context.close(); await p2.context.close();
}

// D7
const before = await ids();
const q = await login('requester');
await q.page.goto(`${BASE}/job/fresh-daily/batch-control/`);
await q.page.fill('textarea[name="reason"]', 'e2e-05 D7 parallel request submit');
await q.page.locator('input[name="approvers"][value="approver-1"]').check({ force: true });
const r7 = await twice(q.page, 'form:has(textarea[name="reason"])');
const created = [...(await ids())].filter((x) => !before.has(x));
log('D7 two parallel run-request POSTs ->', r7, '| new requests', created);
await q.context.close();
await close();
