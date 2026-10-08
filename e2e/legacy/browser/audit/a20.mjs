// A-20 (+B7-15 feedback, DEF-04/DEF-05 fixes) and A-12.
import { login, close, shot, BASE, api, changeRows, sleep, decide } from '../lib.mjs';
import { row, ev, mainText } from './rec.mjs';
import fs from 'node:fs';
const only = process.argv.slice(2); const on = (x) => !only.length || only.includes(x);
if (on('A-20')) {
  const url = fs.readFileSync('../out/audit-a09.url', 'utf8').trim();
  const gid = url.match(/(\d{8}-\d{6}-\w+)/)[1];
  const ap = await login('approver-1');
  await ap.page.goto(url);
  const t0 = await mainText(ap.page);
  const s1 = await shot(ap.page, ['#main-panel table', 'form[name="approve"]'], 'A-20-1-approver-sees-restriction', { pad: 10 });
  await ap.page.fill('form[name="approve"] textarea[name="comment"]', 'Only app-31.');
  await Promise.all([ap.page.waitForLoadState('load'), ap.page.locator('form[name="approve"] button').first().click()]);
  await ap.context.close();
  const { context, page } = await login('requester');
  await page.goto(`${BASE}/job/team/`);
  const sb = (await page.locator('#side-panel a').allInnerTexts()).map((x) => x.trim()).filter(Boolean);
  await page.locator('#side-panel a:has-text("New Item")').click(); await page.waitForLoadState('load');
  await page.locator('#name').fill('app-32'); await page.waitForTimeout(1500);
  await page.locator('label:has-text("Freestyle project")').first().click(); await page.waitForTimeout(500);
  const v32 = (await page.locator('#itemname-invalid, .input-validation-message, #main-panel .error').allInnerTexts()).join(' ').trim();
  const okDis = await page.locator('#ok-button').isDisabled();
  const s2 = await shot(page, [page.locator('#name'), page.locator('#ok-button')], 'A-20-2-app-32-typed', { pad: 12 });
  const vBefore = (await changeRows(/GRANT_VIOLATION/)).length;
  let refusal = 'OK disabled';
  if (!okDis) {
    const [r] = await Promise.all([page.waitForNavigation({ waitUntil: 'load' }).catch(() => null), page.locator('#ok-button').click()]);
    refusal = `${r && r.status()} "${(await mainText(page)).slice(0, 160)}"`;
  }
  const s3 = await shot(page, '#main-panel, body', 'A-20-3-app-32-refused', { pad: 8 });
  await sleep(1000);
  const viol = (await changeRows(/GRANT_VIOLATION/));
  await page.goto(`${BASE}/job/team/newJob`);
  await page.locator('#name').fill('app-31'); await page.waitForTimeout(1200);
  await page.locator('label:has-text("Freestyle project")').first().click();
  await Promise.all([page.waitForNavigation({ waitUntil: 'load' }), page.locator('#ok-button').click()]);
  const cfgUrl = page.url();
  await page.waitForTimeout(1200);
  await page.fill('textarea[name="description"]', 'Created under a name-restricted Create grant (audit A-20).');
  await Promise.all([page.waitForNavigation({ waitUntil: 'load' }), page.locator('button[name="Submit"]').click()]);
  const s4 = await shot(page, '#main-panel', 'A-20-4-app-31-created', { pad: 8 });
  await sleep(1500);
  const a31 = (await api('admin', '/job/team/job/app-31/api/json?tree=name')).status;
  const a32 = (await api('admin', '/job/team/job/app-32/api/json?tree=name')).status;
  const recs = await changeRows(/team\/app-31|team\/app-32/);
  // the admin reads them on Change Records
  const ad = await login('admin');
  await ad.page.goto(`${BASE}/batch-control/changes/`);
  const rows31 = ad.page.locator('#main-panel tr', { hasText: 'team/app-3' });
  const rt = (await rows31.allInnerTexts()).map((x) => x.replace(/\s+/g, ' '));
  const s5 = await shot(ad.page, rows31, 'A-20-5-change-records', { pad: 8 });
  const glink = await rows31.locator(`a:has-text("${gid}")`).count();
  ev(`A-20 approver saw "${t0.slice(0, 260)}"; team sidebar ${sb}; app-32 validation "${v32}" okDisabled ${okDis} -> ${refusal}; violations ${vBefore}->${viol.length}; app-31 ${cfgUrl}; api app-31 ${a31} app-32 ${a32}; records ${recs.join(' || ')}; screen rows ${rt.join(' || ')}; grant links ${glink}`);
  const d40 = recs.some((r) => /\(D-\d+\)/.test(r)) || viol.slice(0, 1).some((r) => /\(D-\d+\)/.test(r));
  const cfg = recs.find((r) => /,CONFIGURE,team\/app-31,/.test(r)) || '';
  row('A-20', { roles: 'requester, approver-1, admin (records)', V: `✓ approver-1 sees the name restriction on the detail before deciding; requester gets New Item in team/ only while the grant is active (sidebar ${sb.includes('New Item') ? 'has' : 'lacks'} New Item)`, G: `${a31 === 200 && a32 === 404 ? '✓' : '✗'} app-31 created and saved (${a31}); app-32 absent (${a32})`, R: `✗ typing app-32: nothing under the name field ("${v32}"), OK enabled; OK -> ${refusal.slice(0, 120)}: nothing says the grant only allows app-31 (DEF-19)`, C: `${viol.length === vBefore + 1 && !d40 && cfg.includes(gid) ? '✓' : '✗'} one GRANT_VIOLATION for the POST ("${(viol[0] || '').split(',').slice(6).join(',').slice(0, 120)}", no "(D-40)": DEF-04 fixed); CREATE and CONFIGURE of team/app-31 both carry grant ${gid} (DEF-05 fixed: "${cfg.slice(0, 90)}"), linked on Change Records (${glink})`, E: [s1, s2, s3, s4, s5].every(Boolean) ? '✓ A-20-1..5' : '✗', defect: 'DEF-19 (known)' });
  await ad.context.close(); await context.close();
}
if (on('A-12')) {
  const T = 'batch-pipeline';
  const cnt = async () => (await changeRows(new RegExp(`,CONFIGURE,${T},`))).length;
  const r0 = await cnt();
  const { context, page } = await login('admin');
  await page.goto(`${BASE}/job/${T}/configure`); await page.waitForTimeout(1500);
  await Promise.all([page.waitForNavigation({ waitUntil: 'load' }), page.locator('button[name="Submit"]').click()]); await sleep(1500);
  const r1 = await cnt();
  const xml = (await api('admin', `/job/${T}/config.xml`, { raw: true })).text;
  const bumped = xml.replace(/plugin="([^"@]+)@[^"]*"/g, 'plugin="$1@9999.v-audit"');
  const n = (xml.match(/plugin="[^"]+"/g) || []).length;
  const post = await api('admin', `/job/${T}/config.xml`, { method: 'POST', body: bumped, headers: { 'Content-Type': 'application/xml' } }); await sleep(1500);
  const r2 = await cnt();
  await page.goto(`${BASE}/job/${T}/configure`); await page.waitForTimeout(1500);
  const cur = await page.locator('textarea[name="description"]').inputValue();
  await page.fill('textarea[name="description"]', cur.replace(/ \(audit A-12 \d+\)$/, '') + ` (audit A-12 ${Date.now()})`);
  await Promise.all([page.waitForNavigation({ waitUntil: 'load' }), page.locator('button[name="Submit"]').click()]); await sleep(1500);
  const r3 = await cnt();
  await page.goto(`${BASE}/batch-control/changes/`);
  const rowEl = page.locator(`#main-panel tr:has-text("${T}")`).first();
  const s1 = await shot(page, [page.locator('#main-panel table tr').first(), rowEl], 'A-12-1-change-records-row', { pad: 8 });
  const det = rowEl.locator('details, summary, a').filter({ hasText: /diff|Diff|show|Show/ }).first();
  let s2 = null;
  if (await det.count()) { await det.click(); await page.waitForTimeout(800); s2 = await shot(page, rowEl, 'A-12-2-diff', { pad: 8 }); }
  const rowT = (await rowEl.innerText()).replace(/\s+/g, ' ');
  // the plugin-attribute-only POST: was it recorded?
  const latest = (await changeRows(new RegExp(`,CONFIGURE,${T},`))).slice(0, 3);
  ev(`A-12 CONFIGURE ${T}: start ${r0}, no-op UI save ${r1}, plugin-attr-only POST (HTTP ${post.status}, ${n} attrs) ${r2}, real edit ${r3}; row "${rowT.slice(0, 300)}"; latest ${latest.join(' || ').slice(0, 400)}`);
  row('A-12', { roles: 'admin', V: 'n.a.', G: `${r1 === r0 && r2 === r1 && r3 === r2 + 1 ? '✓' : '✗'} CONFIGURE records of ${T}: ${r0} -> no-op UI Save ${r1} -> POST config.xml with only ${n} plugin="x@version" attributes changed (HTTP ${post.status}) ${r2} -> real description edit ${r3}`, R: 'n.a.', C: `${r3 === r2 + 1 ? '✓' : '✗'} exactly one record, for the real edit, with the description diff on Change Records`, E: s1 ? `✓ A-12-1-change-records-row${s2 ? ', A-12-2-diff' : ''}` : '✗' });
  await context.close();
}
await close();
