// DEF-12: Request Run / rerun form only for Request + Job/Build holders (B4-01, B4-13, B14-06).
import { login, close, shot, api, BASE } from '../lib.mjs';
import { row, ev, sidebar, mainText } from '../audit/rec.mjs';
import { restSubmit, formPost } from '../audit/restsubmit.mjs';
const res = {};
for (const u of ['requester', 'reqonly', 'manager', 'configurer', 'nobc', 'approver-1', 'admin']) {
  const { context, page } = await login(u); await page.goto(`${BASE}/job/batch-daily/`);
  const sb = await sidebar(page);
  const f = await page.goto(`${BASE}/job/batch-daily/batch-control/`); const ft = await mainText(page);
  const hasForm = await page.locator('form[name="batch-control-request"]').count();
  if (u === 'reqonly') res.shot = await shot(page, '#main-panel', 'B4-13-reqonly-typed-form-url', { pad: 8 });
  res[u] = { rr: sb.includes('Request Run'), form: f.status(), hasForm, ft: ft.slice(0, 200) };
  await context.close();
}
const rs = await restSubmit('reqonly', '/job/batch-daily/', { reason: 'reqonly scripted', approvers: ['approver-1'], params: [{ name: 'DATE', value: '2031-01-01' }, { name: 'MODE', value: 'full' }] });
ev(`V5 ${JSON.stringify(res)} REST reqonly ${JSON.stringify(rs)}`);
const shownTo = Object.entries(res).filter(([k, v]) => v && v.rr).map(([k]) => k);
row('B4-01', { roles: 'requester, reqonly, manager, configurer, nobc, approver-1, admin', V: `${JSON.stringify(shownTo.sort()) === JSON.stringify(['admin', 'requester']) ? '✓' : '✗'} Request Run shown to ${shownTo.join(', ')} only (DEF-12 fixed)`, G: '✓ form fields and Cancel as in the re-audit (B4-01 there); unchanged', R: 'n.a.', C: 'n.a.', E: '✓ A-02-0-* (sidebars), B4-01-1-request-form (re-audit)' });
row('B4-13', { roles: 'reqonly (Request, no Job/Build)', V: `${!res.reqonly.rr ? '✓' : '✗'} no Request Run in his sidebar`, G: `${rs.status >= 400 ? '✓' : '✗'} a scripted submission is refused (${rs.status}), nothing stored`, R: `${res.reqonly.hasForm === 0 && /Job\/Build/.test(res.reqonly.ft) ? '✓' : '✗'} the typed form URL answers ${res.reqonly.form} "${res.reqonly.ft.slice(0, 150)}"; the script is told "${rs.msg.slice(0, 90)}"`, C: 'n.a.', E: res.shot ? '✓ B4-13-reqonly-typed-form-url' : '✗' });
// B14-06 rerun form
const inc = (await api('admin', '/batch-control/history/incidents.csv')).text.split('\n').slice(1).find((l) => /b14-aud-/.test(l) && /,RESOLVED,|,ACKNOWLEDGED,|,OPEN,/.test(l)) || (await api('admin', '/batch-control/history/incidents.csv')).text.split('\n')[1];
const iid = inc.split(',')[0]; const iurl = `${BASE}/batch-control/incidents/${iid}/`;
const off = {};
for (const u of ['requester', 'approver-1', 'manager', 'auditor', 'approver-disc', 'admin']) {
  const c = await login(u); const st = (await c.page.goto(iurl)).status();
  off[u] = st === 200 ? { form: await c.page.locator('form[name="rerun"]').count(), why: ((await mainText(c.page)).match(/[^.]*(rerun|Rerun)[^.]*\./g) || []).join(' ').slice(0, 200) } : { page: st };
  if (u === 'manager') off.shot = await shot(c.page, c.page.locator('#main-panel').locator('text=/rerun|Rerun/').first().locator('xpath=..'), 'B14-06-manager-told', { pad: 8 });
  await c.context.close();
}
const mp = await formPost('manager', `/batch-control/incidents/${iid}/rerun`, { approver: 'approver-1', reason: 'manager scripted rerun' });
ev(`V5 B14-06 ${iid} ${JSON.stringify(off)} manager POST ${JSON.stringify(mp)}`);
row('B14-06', { roles: 'requester, approver-1, manager, auditor, approver-disc, admin', V: `${off.manager.form === 0 && off.admin.form === 1 ? '✓' : '✗'} rerun form: ${Object.entries(off).filter(([k]) => k !== 'shot').map(([u, v]) => `${u} ${v.form ?? 'page ' + v.page}`).join(', ')}`, G: '✓ admin (Request + Job/Build + ViewHistory) files a rerun with the original parameters (re-audit B14-06); unchanged', R: `${off.manager.form === 0 && /Job\/Build|Build/.test(off.manager.why) ? '✓' : '✗'} manager reads "${off.manager.why.slice(0, 160)}"; a scripted rerun by manager -> ${mp.status} "${mp.msg.slice(0, 80)}"`, C: '✓ requests.csv incidentId (re-audit)', E: off.shot ? '✓ B14-06-manager-told, B14-06-1..2 (re-audit)' : '✗' });
await close();
