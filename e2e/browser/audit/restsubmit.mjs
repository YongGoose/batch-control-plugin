import { api, errText } from '../lib.mjs';
/** Scripted run request submission, the way a Jenkins form posts it (single json field). */
export async function restSubmit(user, jobPath, { reason, approvers, params = [] }) {
  const json = JSON.stringify({ reason, approvers, parameter: params });
  const r = await api(user, `${jobPath}batch-control/submit`, { method: 'POST', body: new URLSearchParams({ json }), headers: { 'Content-Type': 'application/x-www-form-urlencoded' } });
  return { status: r.status, location: r.location, msg: errText(r.text) };
}
/** Form POST with a crumb (for approve/reject/cancel/changeApprover), as a script would. */
export async function formPost(user, path, fields) {
  const body = new URLSearchParams();
  for (const [k, v] of Object.entries(fields)) for (const x of [].concat(v)) body.append(k, x);
  const r = await api(user, path, { method: 'POST', body, headers: { 'Content-Type': 'application/x-www-form-urlencoded' } });
  return { status: r.status, location: r.location, msg: errText(r.text) };
}
/** Cancel a request in the UI as its requester (confirmation dialog). */
export async function uiCancel(page, url) {
  await page.goto(url);
  const b = page.locator('a:has-text("Cancel Request"), button:has-text("Cancel Request")').first();
  if (!(await b.count())) return false;
  page.once('dialog', (d) => d.accept());
  await b.click(); await page.waitForTimeout(800);
  const c = page.locator('dialog[open] button[data-id="ok"], dialog[open] button:has-text("Yes")').first();
  if (await c.count()) await Promise.all([page.waitForLoadState('load'), c.click()]);
  return true;
}
/** Revoke an active grant in the UI as <user> (default manager). */
export async function uiRevoke(gid, user = 'manager') {
  const { login, BASE } = await import('../lib.mjs');
  const { context, page } = await login(user);
  await page.goto(`${BASE}/batch-control/grants/`);
  const r = page.locator('table:has(th:has-text("Expires")) tbody tr', { hasText: gid }).first();
  if (!(await r.count())) { await context.close(); return false; }
  page.once('dialog', (d) => d.accept());
  await r.locator('a:has-text("Revoke"), button:has-text("Revoke")').first().click(); await page.waitForTimeout(800);
  const d = page.locator('dialog[open] button[data-id="ok"], dialog[open] button:has-text("Yes")').first();
  if (await d.count()) await Promise.all([page.waitForLoadState('load'), d.click()]);
  await context.close(); return true;
}
