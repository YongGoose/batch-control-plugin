// B20 kill switch: change control off.
import { login, close, shot, api, BASE, log, requestGrant, decide, setGlobal, changeRows, errText, sleep } from './lib.mjs';
const L = 'section-b.log';
const rq = await login('requester');
const g1 = await requestGrant(rq.page, { scope: 'batch-daily', actions: ['CONFIGURE'], minutes: 30, reason: 'Window 1 open when change control goes off (B20-01).' }); await decide(g1.url);
const g2 = await requestGrant(rq.page, { scope: 'team', actions: ['CONFIGURE'], minutes: 30, reason: 'Window 2 open when change control goes off (B20-01).' }); await decide(g2.url);
const cBefore = [(await rq.page.goto(`${BASE}/job/batch-daily/configure`)).status(), (await rq.page.goto(`${BASE}/job/team/job/app-1/configure`)).status()];
const snap = { changes: (await api('admin', '/batch-control/history/changes.csv')).text, requests: (await api('admin', '/batch-control/history/requests.csv')).text };
const rev0 = (await changeRows(/,GRANT_REVOKE,/)).length;
const ad = await login('admin');
await setGlobal(ad.page, { changeControlEnabled: false });
await sleep(1500);
const revs = (await changeRows(/,GRANT_REVOKE,/));
const newRevs = revs.slice(0, revs.length - rev0);
const cAfter = [(await rq.page.goto(`${BASE}/job/batch-daily/configure`)).status(), (await rq.page.goto(`${BASE}/job/team/job/app-1/configure`)).status()];
log(L, `B20-01 two open windows; change control off in the UI: GRANT_REVOKE +${newRevs.length}: ${newRevs.map((l) => l.split(',').slice(2, 4).join(' by ')).join(' || ')}; requester configure batch-daily/team/app-1 before ${cBefore} after ${cAfter}`);
// what each role sees now
const vis = [];
for (const u of ['requester', 'approver-1', 'manager']) {
  const c = u === 'requester' ? rq : await login(u);
  await c.page.goto(`${BASE}/batch-control/`);
  const links = (await c.page.locator('#side-panel a').allInnerTexts()).map((x) => x.trim()).filter(Boolean);
  const r = await c.page.goto(`${BASE}/batch-control/grants/`);
  const t = (await c.page.locator('#main-panel').innerText()).replace(/\s+/g, ' ');
  const form = await c.page.locator('form[name="createGrantRequest"]').count();
  await c.page.goto(`${BASE}/job/batch-daily/`);
  const side = (await c.page.locator('#side-panel a').allInnerTexts()).map((x) => x.trim()).includes('Request Change Permission');
  vis.push(`${u}: landing ${JSON.stringify(links)}; /grants/ ${r.status()} form=${form} "${t.slice(0, 220)}"; job sidebar Request Change Permission ${side}`);
  if (u === 'requester') { await c.page.goto(`${BASE}/batch-control/grants/`); await shot(c.page, '#main-panel', 'B20-01-grants-closed', { pad: 6 }); await c.page.goto(`${BASE}/batch-control/`); await shot(c.page, '#side-panel', 'B20-01-landing', { pad: 6 }); }
}
for (const v of vis) log(L, `B20-01 ${v}`);
await ad.page.goto(`${BASE}/batch-control/changes/`);
await shot(ad.page, ad.page.locator('#main-panel tr:has-text("GRANT_REVOKE")').first().locator('xpath=..').locator('tr:has-text("GRANT_REVOKE")'), 'B20-01-revoke-records', { pad: 8 });
// B20-02 grant request while off (REST) and approval of a pending one
const b0 = (await changeRows(/GRANT_REQUEST_BLOCKED/)).length;
const r2 = await api('requester', '/batch-control/grants/create', { method: 'POST', body: new URLSearchParams({ scopeFullName: 'batch-daily', actions: 'CONFIGURE', durationMinutes: '15', reason: 'while off (B20-02)', approvers: 'approver-1' }), headers: { 'Content-Type': 'application/x-www-form-urlencoded' } });
const bl = await changeRows(/GRANT_REQUEST_BLOCKED/);
log(L, `B20-02 REST grant request while change control is off -> ${r2.status} "${errText(r2.text)}"; GRANT_REQUEST_BLOCKED +${bl.length - b0}: ${bl[0] || ''}`);
// B20-03 back on: windows stay revoked
await setGlobal(ad.page, { changeControlEnabled: true });
const c3 = [(await rq.page.goto(`${BASE}/job/batch-daily/configure`)).status(), (await rq.page.goto(`${BASE}/job/team/job/app-1/configure`)).status()];
await rq.page.goto(`${BASE}/batch-control/grants/`);
const act = (await rq.page.locator('table:has(th:has-text("Expires")) tbody tr').allInnerTexts()).filter((t) => /B20|batch-daily|FOLDER: team/.test(t));
log(L, `B20-03 change control back on: requester configure ${c3}; active windows for the two ${JSON.stringify(act)}`);
// B20-04 history content unchanged (only appended)
const now = { changes: (await api('admin', '/batch-control/history/changes.csv')).text, requests: (await api('admin', '/batch-control/history/requests.csv')).text };
const keptC = snap.changes.split('\n').filter(Boolean).every((l) => now.changes.includes(l));
const keptR = snap.requests.split('\n').slice(1).filter(Boolean).every((l) => { const id = l.split(',')[0]; return now.requests.includes(id); });
log(L, `B20-04 every change record present before the switch still present: ${keptC}; every request still listed: ${keptR}`);
await close();
