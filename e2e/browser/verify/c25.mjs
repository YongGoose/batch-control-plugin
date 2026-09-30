// C-25: a request on a job some viewers cannot read must not break their Requests list.
import { login, close, shot, BASE, requestRun } from '../lib.mjs';
import { row, ev, mainText } from '../audit/rec.mjs';
import { formPost } from '../audit/restsubmit.mjs';
const T = String(Date.now()).slice(-4);
const ad = await login('admin');
const url = await requestRun(ad.page, '/job/team/job/secret-job/', { reason: `Verify C-25 ${T}`, approvers: ['approver-disc'] });
const id = url.match(/(\d{8}-\d{6}-\w+)/)[1];
const res = {};
let s = null;
for (const u of ['requester', 'approver-disc', 'approver-1']) {
  const c = await login(u);
  const r = await c.page.goto(`${BASE}/batch-control/requests/`);
  const t = await mainText(c.page);
  res[u] = { st: r.status(), rows: await c.page.locator('#main-panel table tbody tr').count(), total: (t.match(/Page \d+ \([^)]*\)/) || ['none'])[0], listed: t.includes(id) };
  if (u === 'requester') s = await shot(c.page, [c.page.locator('#main-panel table tbody tr').first(), c.page.locator('#main-panel').locator('text=/Page \\d+/').first()], 'C-25-requester-list', { pad: 8 });
  await c.context.close();
}
await formPost('admin', `/batch-control/requests/${id}/cancel`, {});
ev(`C-25 ${JSON.stringify(res)}`);
const ok = Object.values(res).every((v) => v.st === 200 && v.rows > 0);
row('C-25', {
  roles: 'requester (no Read on team/secret-job), approver-disc (Discover only, designated), approver-1',
  V: `${!res.requester.listed && res['approver-disc'].listed ? '✓' : '✗'} the request on team/secret-job is listed for approver-disc (designated) and not for requester`,
  G: `${ok ? '✓' : '✗'} each list renders its own rows and totals: ${Object.entries(res).map(([u, v]) => `${u} ${v.rows} rows "${v.total}"`).join('; ')}`,
  R: 'n.a.', C: 'n.a.', E: s ? '✓ C-25-requester-list' : '✗',
});
await close();
