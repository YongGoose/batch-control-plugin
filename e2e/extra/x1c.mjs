// e2e-05 EX-01 neighbours: the requester's stale change-approvers form on a grant and on an activation request.
import { login, BASE, shot, text, api, log, close } from './lib.mjs';
const flat = (s) => s.replace(/\s+/g, ' ').trim();
const ids = async (k) => new Set([...(await api('admin', `/batch-control/${k}/`)).body.matchAll(/href="[^"]*?(\d{8}-\d{6}-[a-z0-9]{6})\/?"/g)].map((m) => m[1]));
for (const kind of ['grants', 'activations']) {
  const { page } = await login('requester');
  const before = await ids(kind);
  if (kind === 'grants') {
    await page.goto(`${BASE}/batch-control/grants/?scopeType=JOB&scopeFullName=fresh-daily`);
    const f = page.locator('form[action$="grants/create"]');
    await f.locator('input[name="actions"][value="CONFIGURE"]').check({ force: true });
    await f.locator('textarea[name="reason"]').fill('e2e-05 X1c stale change approvers (grant)');
    for (const ap of ['approver-1', 'approver-2']) await f.locator(`input[name="approvers"][value="${ap}"]`).check({ force: true });
    await Promise.all([page.waitForLoadState('load'), f.locator('button[name="Submit"]').click()]);
  } else {
    await page.goto(`${BASE}/job/fresh-secret/batch-control-activation`);
    const f = page.locator('form[action*="activation/submit"]').first();
    await f.locator('textarea[name="reason"]').fill('e2e-05 X1c stale change approvers (activation)');
    for (const ap of ['approver-1', 'approver-2']) await f.locator(`input[name="approvers"][value="${ap}"]`).check({ force: true });
    await Promise.all([page.waitForLoadState('load'), f.locator('button[name="Submit"]').click()]);
  }
  const id = [...(await ids(kind))].find((x) => !before.has(x));
  await page.goto(`${BASE}/batch-control/${kind}/${id}/`);
  const ch = page.locator('form[action$="/changeApprover"]');
  if (!(await ch.count())) { log('X1c', kind, id, 'no change-approvers form for the requester'); await page.context().close(); continue; }
  const ap = await login('approver-1');
  await ap.page.goto(`${BASE}/batch-control/${kind}/${id}/`);
  await ap.page.fill('form[action$="/reject"] textarea[name="comment"]', 'rejected while the requester edits');
  await Promise.all([ap.page.waitForLoadState('load'), ap.page.locator('form[action$="/reject"] button').click()]);
  await ch.locator('input[name="approvers"][value="approver-2"]').setChecked(false, { force: true });
  const st = [];
  page.on('response', (r) => { if (r.request().method() === 'POST') st.push(r.status()); });
  await Promise.all([page.waitForLoadState('load'), ch.locator('button[name="Submit"]').click()]);
  log('X1c', kind, id, 'HTTP', st, '|', flat(await text(page)).slice(0, 230));
  await shot(page, ['#main-panel .jenkins-alert', '#main-panel table'], `X1c-${kind}-stale-change-approvers`);
  await ap.context.close(); await page.context().close();
}
await close();
