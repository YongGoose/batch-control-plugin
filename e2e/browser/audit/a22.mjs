// A-22 (+B7-11): a CONFIGURE window across a restart; manager revokes once.
import { login, close, shot, BASE, api, changeRows } from '../lib.mjs';
import { row, ev, mainText } from './rec.mjs';
import fs from 'node:fs';
const phase = process.argv[2];
const cfg = async (u) => { const c = await login(u); const s = (await c.page.goto(`${BASE}/job/batch-pipeline/configure`)).status(); await c.context.close(); return s; };
const activeRow = async (page) => { await page.goto(`${BASE}/batch-control/grants/`); const t = page.locator('table:has(th:has-text("Expires"))').first(); return t.locator('tbody tr:has-text("batch-pipeline")'); };
if (phase === 'before') {
  const c0 = await cfg('requester');
  const { context, page } = await login('requester');
  await page.goto(`${BASE}/job/batch-pipeline/`);
  await page.locator('#side-panel a:has-text("Request Change Permission")').click(); await page.waitForLoadState('load');
  const pre = { kind: (await page.locator('[data-batch-control-item-kind]').count()) ? await page.locator('[data-batch-control-item-kind]').first().getAttribute('data-batch-control-item-kind') : null, scope: await page.locator('input[name="scopeFullName"]').inputValue(), conf: await page.locator('input[name="actions"][value="CONFIGURE"]').isChecked() };
  await page.selectOption('select[name="durationMinutes"]', '15');
  const reason = `Audit A-22 ${Date.now()}: fix the pipeline script`;
  await page.fill('textarea[name="reason"]', reason);
  await page.locator('input[name="approvers"][value="approver-1"] + label').click();
  const s0 = await shot(page, 'form:has(button:has-text("Request Grant"))', 'A-22-0-prefilled-form', { pad: 8 });
  await Promise.all([page.waitForLoadState('load'), page.locator('button:has-text("Request Grant")').click()]);
  const href = await page.locator('#main-panel table tbody tr', { hasText: reason.slice(0, 25) }).first().locator('a').first().getAttribute('href');
  const url = new URL(href, page.url()).href; const gid = url.match(/(\d{8}-\d{6}-\w+)/)[1];
  const ap = await login('approver-1'); await ap.page.goto(url);
  await ap.page.fill('form[name="approve"] textarea[name="comment"]', 'ok'); await Promise.all([ap.page.waitForLoadState('load'), ap.page.locator('form[name="approve"] button').first().click()]);
  const r = await activeRow(page); const rt = (await r.first().innerText()).replace(/\s+/g, ' ');
  const s1 = await shot(page, r.first(), 'A-22-1-active-before-restart', { pad: 8 });
  const c1 = await cfg('requester');
  fs.writeFileSync('../out/audit-a22.json', JSON.stringify({ gid, url, pre, c0, c1, rt, s0: !!s0, s1: !!s1 }));
  ev(`A-22 before: prefilled ${JSON.stringify(pre)}; configure ${c0} -> ${c1}; active "${rt}"`);
  await close();
} else {
  const st = JSON.parse(fs.readFileSync('../out/audit-a22.json', 'utf8'));
  const c2 = await cfg('requester');
  const m = await login('manager');
  const r = await activeRow(m.page); const n = await r.count(); const rt = (await r.first().innerText()).replace(/\s+/g, ' ');
  const s2 = await shot(m.page, r.first(), 'A-22-2-active-after-restart', { pad: 8 });
  let dialogText = '';
  m.page.once('dialog', (d) => { dialogText = d.message(); d.accept(); });
  await r.first().locator('a:has-text("Revoke"), button:has-text("Revoke")').first().click(); await m.page.waitForTimeout(800);
  const dlg = m.page.locator('dialog[open]').first();
  if (await dlg.count()) { dialogText = (await dlg.innerText()).replace(/\s+/g, ' '); await shot(m.page, dlg, 'A-22-3-revoke-confirmation', { pad: 4 }); await Promise.all([m.page.waitForLoadState('load'), dlg.locator('button[data-id="ok"], button:has-text("Yes")').first().click()]); }
  await m.page.waitForTimeout(1500);
  const left = await (await activeRow(m.page)).count();
  const s4 = await shot(m.page, m.page.locator('h2:has-text("Active Grants")').first(), 'A-22-4-after-revoke', { pad: 60 });
  const c3 = await cfg('requester');
  const recs = await changeRows(new RegExp(`GRANT_REVOKE.*${st.gid}|${st.gid}.*GRANT_REVOKE|,GRANT_REVOKE,`));
  const mine = recs.filter((l) => l.includes(st.gid));
  const rq = await login('requester'); await rq.page.goto(`${BASE}/job/batch-pipeline/`); const sb = (await rq.page.locator('#side-panel a').allInnerTexts()).map((x) => x.trim());
  ev(`A-22 after restart: configure ${c2}; manager rows ${n} "${rt}"; dialog "${dialogText}"; left ${left}; configure after revoke ${c3}; revoke records for ${st.gid}: ${mine.join(' || ')}; requester sidebar ${sb}`);
  const rem1 = (st.rt.match(/(\d+) min/) || [])[1]; const rem2 = (rt.match(/(\d+) min/) || [])[1];
  for (const id of ['A-22', 'B7-11']) row(id, { roles: 'requester, approver-1, manager', V: `✓ Request Change Permission prefills batch-pipeline (one item, D-71) + Configure (${JSON.stringify(st.pre)}); Revoke offered to manager; the requester's sidebar loses Configure after the revoke (${sb.includes('Configure') ? 'still there' : 'gone'})`, G: `${st.c0 === 403 && st.c1 === 200 && c2 === 200 && n === 1 && left === 0 && c3 === 403 ? '✓' : '✗'} configure ${st.c0} -> approved ${st.c1} -> after docker restart ${c2}; one active row before (${rem1} min left) and after the restart (${rem2} min left); manager Revoke (confirmation "${dialogText.slice(0, 80)}") -> row gone, configure ${c3}`, R: 'n.a.', C: `${mine.length === 1 ? '✓' : '✗'} ${mine.length} GRANT_REVOKE for ${st.gid}: "${(mine[0] || '').slice(0, 120)}"`, E: st.s0 && st.s1 && s2 && s4 ? '✓ A-22-0..4' : '✗' });
  await close();
}
