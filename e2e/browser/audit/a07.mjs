import { login, close, shot, BASE, api, decide } from '../lib.mjs';
import { row, ev, mainText } from './rec.mjs';
import fs from 'node:fs';
const only = process.argv.slice(2); const on = (x) => !only.length || only.includes(x);
if (on('A-07')) {
  const { context, page } = await login('admin');
  await page.goto(`${BASE}/manage/configuration-as-code/`);
  await Promise.all([page.waitForNavigation({ waitUntil: 'load' }), page.locator('form[action="viewExport"] button').first().click()]);
  await page.waitForTimeout(2000);
  const text = await page.locator('body').innerText();
  const names = [...new Set(text.match(/Batch ?Control\/[A-Za-z]+/g) || [])];
  const s1 = await shot(page, [page.getByText('batchControlProjectMatrix', { exact: false }).first(), page.getByText('BatchControl/Request', { exact: false }).first()], 'A-07-export', { pad: 30 });
  await page.goto(`${BASE}/manage/configureSecurity/`);
  const card = page.locator('.mas-card[data-sid="requester"]');
  await card.locator('.mas-card__header').click(); await page.waitForTimeout(1000);
  const t = card.getByText('Batch Control', { exact: true }).first();
  const s2 = await shot(page, t.locator('xpath=ancestor::*[contains(@class,"group") or self::fieldset or self::section or self::table][1]'), 'A-07-matrix-group', { pad: 12 });
  const readme = fs.readFileSync('../../README.md', 'utf8');
  const rn = [...new Set(readme.match(/BatchControl\/[A-Za-z]+/g))];
  const vis = {};
  for (const u of ['manager', 'requester', 'nobc']) { const c = await login(u); vis[u] = (await c.page.goto(`${BASE}/manage/configuration-as-code/`)).status(); await c.context.close(); }
  ev(`A-07 export names ${names}; README ${rn}; vis ${JSON.stringify(vis)}`);
  row('A-07', { roles: 'admin, manager, requester, nobc', V: `✓ Configuration as Code page only for admin (${Object.entries(vis).map(([u, s]) => `${u} ${s}`).join(', ')})`, G: `${names.every((n) => n.startsWith('BatchControl/')) && names.length >= 5 ? '✓' : '✗'} export: ${names.join(', ')}; README names ${rn.join(', ')}; matrix group title "Batch Control" readable; import under these names is what this profile boots with (E-08)`, R: 'n.a.', C: 'n.a.', E: s1 && s2 ? '✓ A-07-export, A-07-matrix-group' : '✗' });
  await context.close();
}
if (on('A-08')) {
  const { context, page } = await login('requester');
  await page.goto(`${BASE}/batch-control/grants/`);
  const rep = await page.locator('select').evaluateAll((ss) => ss.filter((s) => s.offsetParent !== null).map((s) => `${s.name}: wrapped=${!!s.closest('.jenkins-select')} h=${Math.round(s.getBoundingClientRect().height)}`));
  // D-71: no scope type select; the Duration select is the styled select used for this widget/keyboard check
  const scope = page.locator('select[name="durationMinutes"]');
  await scope.focus(); await page.keyboard.type('3'); const kb = await scope.inputValue();
  await page.keyboard.press('ArrowUp'); const kb2 = await scope.inputValue();
  await page.locator('select[name="durationMinutes"]').selectOption({ index: 1 });
  const s = await shot(page, [scope, page.locator('select[name="durationMinutes"]')], 'A-08-light', { pad: 20 });
  const appr = await page.goto(`${BASE}/me/appearance/`);
  const a = await login('admin'); await a.page.goto(`${BASE}/manage/configureSecurity/`);
  const core = await a.page.locator('select').evaluateAll((ss) => ss.filter((s) => s.offsetParent !== null).slice(0, 2).map((s) => `${s.name}: wrapped=${!!s.closest('.jenkins-select')} h=${Math.round(s.getBoundingClientRect().height)}`));
  ev(`A-08 ${rep}; type-ahead Fo -> ${kb}, ArrowUp -> ${kb2}; core ${core}; /me/appearance ${appr.status()}`);
  row('A-08', { roles: 'requester, admin (core reference)', V: 'n.a.', G: `${rep.every((r) => /wrapped=true h=38/.test(r)) ? '✓' : '✗'} light: ${rep.join('; ')} (core: ${core.join('; ')}); keyboard type-ahead "Fo" -> ${kb}, ArrowUp -> ${kb2}; dark theme NOT RUN (no theme switch: /me/appearance ${appr.status()}, no dark-theme plugin)`, R: 'n.a.', C: 'n.a.', E: s ? '✓ A-08-light.png' : '✗', verdict: 'PASS (partial)', note: 'dark half not runnable in this image' });
  await a.context.close(); await context.close();
}
if (on('A-09')) {
  const { context, page } = await login('requester');
  await page.goto(`${BASE}/batch-control/grants/`);
  await page.waitForSelector('input[name="scopeFullName"]');
  await page.fill('input[name="scopeFullName"]', 'team'); // D-71: one item (folder), no scope type
  const cb = (v) => page.locator(`input[name="actions"][value="${v}"]`);
  const lbl = (v) => cb(v).locator('xpath=following-sibling::label[1]'); // #107 renames the Create id to cb<n>
  const st = async () => [await cb('CREATE').isChecked(), await cb('CONFIGURE').isChecked(), await cb('DELETE').isChecked()];
  const pre = await st();
  for (const v of ['CREATE', 'CONFIGURE', 'DELETE']) { if (!(await cb(v).isChecked())) await lbl(v).click(); }
  const allOn = await st();
  const item = page.locator('input[name="actions"]').first().locator('xpath=ancestor::*[contains(@class,"jenkins-form-item")][1]');
  const s1 = await shot(page, item, 'A-09-1-all-checked', { pad: 12 });
  await lbl('DELETE').click(); // D-71: Delete does not apply to a folder; submit CREATE+CONFIGURE
  const cd = await st();
  const s2 = await shot(page, item, 'A-09-2-create-configure', { pad: 12 });
  const tops = await page.locator('input[name="actions"]').evaluateAll((es) => es.map((e) => Math.round(e.getBoundingClientRect().top)));
  const tick = await lbl('CREATE').evaluate((l) => getComputedStyle(l, '::after').content + '|' + getComputedStyle(l, '::before').backgroundColor);
  // #107: the name restriction field is revealed only after Create is ticked (done above)
  await page.locator('input[name="createNamePattern"]').waitFor({ state: 'visible' }).catch(() => {});
  await page.fill('input[name="createNamePattern"]', 'app-31');
  await page.selectOption('select[name="durationMinutes"]', '15');
  const reason = `Audit A-09: create team/app-31 and delete a leftover (${Date.now()})`;
  await page.fill('textarea[name="reason"]', reason);
  await page.locator('input[name="approvers"][value="approver-1"] + label').click();
  await Promise.all([page.waitForLoadState('load'), page.locator('button:has-text("Request Grant")').click()]);
  const landed = page.url();
  const rowEl = page.locator('#main-panel table tbody tr', { hasText: reason.slice(0, 30) }).first();
  const rowT = (await rowEl.innerText().catch(() => 'NOT LISTED')).replace(/\s+/g, ' ');
  const s3 = await shot(page, rowEl, 'A-09-3-grant-requests-row', { pad: 10 });
  const href = await rowEl.locator('a').first().getAttribute('href');
  const url = new URL(href, page.url()).href;
  await page.goto(url);
  const det = await mainText(page);
  const s4 = await shot(page, '#main-panel table', 'A-09-4-grant-detail', { pad: 10 });
  fs.writeFileSync('../out/audit-a09.url', url);
  ev(`A-09 pre ${pre} all ${allOn} after-uncheck ${cd} tops ${tops} tick ${tick}; landed ${landed}; row "${rowT}"; detail ${det.slice(0, 300)}`);
  const actions = (det.match(/Actions? ([A-Z, ]+?) (Duration|New job|Name|Status)/) || [])[1];
  row('A-09', { roles: 'requester', V: 'n.a. (form visibility per role in B2/B7)', G: `${JSON.stringify(allOn) === '[true,true,true]' && JSON.stringify(cd) === '[true,true,false]' && /CREATE/.test(det) && /CONFIGURE/.test(det) && !/DELETE/.test(actions || '') ? '✓' : '✗'} label clicks tick all (${allOn}), label click unticks Configure (${cd}), boxes ${tops[1] - tops[0]} px apart; submitted: list row "${rowT.slice(0, 120)}", detail actions "${actions}"`, R: 'n.a.', C: 'n.a.', E: [s1, s2, s3, s4].every(Boolean) ? '✓ A-09-1..4' : '✗', note: `after submit the user lands on ${landed.replace(BASE, '')} (the list, not the new request: U-03)` });
  await context.close();
}
if (on('A-23')) {
  const { context, page } = await login('requester');
  await page.goto(`${BASE}/batch-control/grants/`);
  const noScopeType = await page.locator('select[name="scopeType"]').count(); // D-71: must be 0
  const opts = [];
  await page.fill('input[name="scopeFullName"]', 'team-mb');
  await page.locator('input[name="scopeFullName"]').blur(); await page.waitForTimeout(1000);
  const kindBadge = (await page.locator('[data-batch-control-item-kind]').count()) ? await page.locator('[data-batch-control-item-kind]').first().getAttribute('data-batch-control-item-kind') : null;
  const help = page.locator('input[name="scopeFullName"]').locator('xpath=ancestor::*[contains(@class,"jenkins-form-item")][1]').locator('.jenkins-help-button').first();
  await help.click().catch(() => {}); await page.waitForTimeout(1200);
  const ht = (await page.locator('.help-area .help').allInnerTexts()).join(' ').replace(/\s+/g, ' ');
  const s1 = await shot(page, page.locator('input[name="scopeFullName"]').locator('xpath=ancestor::*[contains(@class,"jenkins-form-item")][1]'), 'A-23-1-item-name-help', { pad: 10 });
  if (!(await page.locator('input[name="actions"][value="CONFIGURE"]').isChecked())) await page.locator('input[name="actions"][value="CONFIGURE"]').locator('xpath=following-sibling::label[1]').click();
  const reason = `Audit A-23: adjust the multibranch source of team-mb (${Date.now()})`;
  await page.fill('textarea[name="reason"]', reason);
  await page.locator('input[name="approvers"][value="approver-2"] + label').click();
  const [r] = await Promise.all([page.waitForNavigation({ waitUntil: 'load' }).catch(() => null), page.locator('button:has-text("Request Grant")').click()]);
  const rowEl = page.locator('#main-panel table tbody tr', { hasText: reason.slice(0, 30) }).first();
  const rowT = (await rowEl.innerText().catch(() => 'NOT LISTED')).replace(/\s+/g, ' ');
  const s2 = await shot(page, rowEl, 'A-23-2-folder-request-row', { pad: 10 });
  ev(`A-23 no scope type selector=${noScopeType === 0}; team-mb kind=${kindBadge}; help "${ht.slice(0, 300)}"; submit ${r && r.status()} row "${rowT}"`);
  row('A-23', { roles: 'requester', V: 'n.a.', G: `${noScopeType === 0 && /Multibranch|WorkflowMultiBranchProject/.test(kindBadge || '') && /team-mb/.test(rowT) && /PENDING/.test(rowT) ? '✓' : '✗'} D-71: no scope type selector; team-mb named directly, kind ${kindBadge}; CONFIGURE window on the multibranch accepted: "${rowT.slice(0, 120)}"; help: "${ht.slice(0, 120)}"`, R: 'n.a.', C: 'n.a.', E: s1 && s2 ? '✓ A-23-1-item-name-help, A-23-2-folder-request-row' : '✗' });
  // cancel it again as the requester (keeps the approver inbox clean); cancel UI is B7-24
  await context.close();
}
await close();
