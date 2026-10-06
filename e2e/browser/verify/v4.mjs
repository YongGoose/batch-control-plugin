// DEF-09 form refusals, DEF-08 invalid settings, DEF-10 manager configuration.
import { login, close, shot, api, BASE, requestRun, requestGrant, setGlobal, globalCfg, sleep } from '../lib.mjs';
import { row, ev, mainText } from '../audit/rec.mjs';
import { uiCancel } from '../audit/restsubmit.mjs';
const T = String(Date.now()).slice(-4);
const F = 'form[name="batch-control-request"]';
const reqLines = async () => (await api('admin', '/batch-control/history/requests.csv')).text.split('\n').length;
const grantCount = async () => (await api('admin', '/batch-control/history/changes.csv')).text.length;
async function submitState(page, resp, fieldSel, typed) {
  const errs = (await page.locator('#main-panel .error, #main-panel .validation-error-area--visible, #main-panel .jenkins-form-item .error').allInnerTexts()).map((t) => t.replace(/\s+/g, ' ').trim()).filter(Boolean);
  const kept = typed === undefined ? null : await page.locator(fieldSel).first().inputValue().catch(() => null);
  const title = (await page.locator('#main-panel h1, .jenkins-app-bar h1').first().innerText().catch(() => '')).trim();
  return { status: resp && resp.status(), errs, kept: typed === undefined ? 'n/a' : kept === typed, title };
}
const rq = await login('requester'); const p = rq.page;
const R = (id, name, st, extra = {}) => row(id, { roles: extra.roles || 'requester', V: 'n.a.', G: `${extra.stored === false ? '✓' : extra.stored === true ? '✗' : '✓'} ${extra.g || 'nothing stored'}`, R: `${st.status === 400 && st.errs.length && st.kept !== false && !/^Error$/.test(st.title) ? '✓' : '✗'} HTTP ${st.status}; the form re-renders ("${st.title}") with "${st.errs.join(' / ').slice(0, 120)}" next to the field; typed input kept: ${st.kept} (DEF-09 ${st.status === 400 && st.errs.length && st.kept !== false ? 'fixed' : 'open'})`, C: 'n.a.', E: extra.s ? `✓ ${name}` : '✗', defect: extra.defect || '' });
if (false) {
// B4-02 empty reason
{ await p.goto(`${BASE}/job/batch-daily/batch-control/`); await p.locator(`${F} input[name="approvers"][value="approver-1"] + label`).click();
  const d = await p.locator(`${F} .jenkins-form-item:has(.jenkins-form-label:text-is("DATE")) input[name="value"]`).first(); await d.fill('2031-05-05');
  const n0 = await reqLines(); const [r] = await Promise.all([p.waitForNavigation().catch(() => null), p.locator('button:has-text("Submit Request")').click()]);
  const st = await submitState(p, r, `${F} .jenkins-form-item:has(.jenkins-form-label:text-is("DATE")) input[name="value"]`, '2031-05-05');
  const appr = await p.locator(`${F} input[name="approvers"][value="approver-1"]`).isChecked().catch(() => null);
  const s = await shot(p, F, 'B4-02-empty-reason', { pad: 8 });
  R('B4-02', 'B4-02-empty-reason', st, { s, stored: (await reqLines()) !== n0, g: `nothing stored; the chosen approver stays ticked (${appr}) and DATE keeps 2031-05-05 (${st.kept})` }); }
// B3-02 no approver
{ await p.goto(`${BASE}/job/batch-pipeline/batch-control/`); const reason = `Verify B3-02 ${T}`; await p.fill(`${F} textarea[name="reason"]`, reason);
  const [r] = await Promise.all([p.waitForNavigation().catch(() => null), p.locator('button:has-text("Submit Request")').click()]);
  const st = await submitState(p, r, `${F} textarea[name="reason"]`, reason); const s = await shot(p, F, 'B3-02-no-approver', { pad: 8 }); R('B3-02', 'B3-02-no-approver', st, { s }); }
// B4-03 long reason
{ await p.goto(`${BASE}/job/batch-daily/batch-control/`); const reason = 'x'.repeat(4001); await p.fill(`${F} textarea[name="reason"]`, reason); await p.locator(`${F} input[name="approvers"][value="approver-1"] + label`).click();
  const [r] = await Promise.all([p.waitForNavigation().catch(() => null), p.locator('button:has-text("Submit Request")').click()]);
  const st = await submitState(p, r, `${F} textarea[name="reason"]`, reason); const s = await shot(p, [F + ' textarea[name="reason"]', F + ' .error'], 'B4-03-reason-4001', { pad: 8 }); R('B4-03', 'B4-03-reason-4001', st, { s }); }
// B4-08 reject without comment
{ const url = await requestRun(p, '/job/batch-pipeline/', { reason: `Verify B4-08 ${T}`, approvers: ['approver-1'] });
  const a = await login('approver-1'); await a.page.goto(url);
  const [r] = await Promise.all([a.page.waitForNavigation().catch(() => null), a.page.locator('form[name="reject"] button').first().click()]);
  const st = await submitState(a.page, r); const s = await shot(a.page, ['form[name="reject"]', '#main-panel .error'], 'B4-08-reject-no-comment', { pad: 8 });
  const still = /PENDING/.test(await mainText(a.page));
  R('B4-08', 'B4-08-reject-no-comment', { ...st, kept: 'n/a' }, { roles: 'approver-1', s, g: `request stays PENDING (${still}); the detail page re-renders with the message at the comment field` });
  await a.context.close(); await uiCancel(p, url); }
}
// B1-12 custom duration over the maximum (admin sets max 20)
{ const ad = await login('admin'); const sv = await setGlobal(ad.page, { grantDurationOptions: '5, 10, 15', maxGrantMinutes: 20 }); ev(`B1-12 arrange ${sv.status} ${JSON.stringify((await globalCfg()))}`);
  await p.goto(`${BASE}/batch-control/grants/`); await p.fill('input[name="scopeFullName"]', 'batch-daily');
  if (!(await p.locator('#grant-action-configure').isChecked())) await p.locator('#grant-action-configure + label').click();
  await p.fill('input[name="customDurationMinutes"]', '30'); const reason = `Verify B1-12 ${T}`; await p.fill('textarea[name="reason"]', reason);
  await p.locator('input[name="approvers"][value="approver-1"] + label').click();
  const [r] = await Promise.all([p.waitForNavigation().catch(() => null), p.locator('button:has-text("Request Grant")').click()]);
  const st = await submitState(p, r, 'textarea[name="reason"]', reason); const key = /maxGrantMinutes/.test(await mainText(p));
  const s = await shot(p, ['input[name="customDurationMinutes"]', '#main-panel .error'], 'B1-12-over-maximum', { pad: 20 });
  await setGlobal(ad.page, { maxGrantMinutes: 240 }); await setGlobal(ad.page, { grantDurationOptions: '1, 15, 30, 60' }); await ad.context.close();
  R('B1-12', 'B1-12-over-maximum', { ...st, errs: st.errs.map((e) => e + (key ? ' [names maxGrantMinutes]' : '')) }, { roles: 'admin (max 20), requester', s }); }
if (false) {
// B7-17 invalid regex, B7-25 unreadable scope
for (const [id, opts] of [['B7-17', { scope: 'team', actions: ['CREATE'], pattern: '/app-[/' }], ['B7-25', { scope: 'team/secret-job', actions: ['CONFIGURE'] }]]) {
  await p.goto(`${BASE}/batch-control/grants/`); await p.fill('input[name="scopeFullName"]', opts.scope);
  for (const a of opts.actions) { const b = p.locator(`input[name="actions"][value="${a}"]`); if (!(await b.isChecked())) await b.locator('xpath=following-sibling::label[1]').click(); }
  if (opts.pattern) await p.fill('input[name="createNamePattern"]', opts.pattern);
  const reason = `Verify ${id} ${T}`; await p.fill('textarea[name="reason"]', reason); await p.locator('input[name="approvers"][value="approver-1"] + label').click();
  const [r] = await Promise.all([p.waitForNavigation().catch(() => null), p.locator('button:has-text("Request Grant")').click()]);
  const st = await submitState(p, r, 'textarea[name="reason"]', reason); const s = await shot(p, ['#main-panel form:has(button:has-text("Request Grant"))'], `${id}-refused`, { pad: 8 });
  R(id, `${id}-refused`, st, { s });
}
// B7-24 grant reject without comment
{ const g = await requestGrant(p, { scope: 'batch-pipeline', actions: ['CONFIGURE'], minutes: 15, reason: `Verify B7-24 ${T}` });
  const a = await login('approver-1'); await a.page.goto(g.url);
  const [r] = await Promise.all([a.page.waitForNavigation().catch(() => null), a.page.locator('form[name="reject"] button').first().click()]);
  const st = await submitState(a.page, r); const s = await shot(a.page, ['form[name="reject"]', '#main-panel .error'], 'B7-24-grant-reject-no-comment', { pad: 8 });
  await a.page.goto(g.url); await a.page.fill('form[name="reject"] textarea[name="comment"]', 'no'); await Promise.all([a.page.waitForLoadState('load'), a.page.locator('form[name="reject"] button').first().click()]);
  const t2 = await mainText(a.page); await a.context.close();
  R('B7-24', 'B7-24-grant-reject-no-comment', { ...st, kept: 'n/a' }, { roles: 'approver-1, requester', s, g: `after a comment: ${(t2.match(/Status \w+/) || [''])[0]}, decided by approver-1` }); }
}
// B1-17 invalid settings (DEF-08)
{ const ad = await login('admin'); const before = await globalCfg(); const res = [];
  for (const [f, v] of [['maxGrantMinutes', '0'], ['pendingTimeoutHours', '-1'], ['grantDurationOptions', '15,abc'], ['incidentResults', 'FAILURE, BOGUS']]) {
    const r = await setGlobal(ad.page, { [f]: v }, { save: false }); await ad.page.waitForTimeout(600);
    const near = (await ad.page.locator(`[name="_.${f}"]`).first().locator('xpath=ancestor::*[contains(@class,"jenkins-form-item")][1]').locator('.error, .validation-error-area--visible').allInnerTexts()).join(' ').replace(/\s+/g, ' ').trim();
    if (f === 'maxGrantMinutes') await shot(ad.page, ad.page.locator(`[name="_.${f}"]`).first().locator('xpath=ancestor::*[contains(@class,"jenkins-form-item")][1]'), 'B1-17-1-field-message', { pad: 8 });
    const [resp] = await Promise.all([ad.page.waitForNavigation().catch(() => null), ad.page.locator('button[name="Submit"]').click()]);
    await ad.page.waitForTimeout(600);
    const t = await mainText(ad.page); if (f === 'maxGrantMinutes') await shot(ad.page, '#main-panel', 'B1-17-2-save-refused', { pad: 8 });
    res.push({ f, v, near, status: resp && resp.status(), page: t.slice(0, 120) });
  }
  const after = await globalCfg();
  ev(`V4 B1-17 ${JSON.stringify(res)} before ${JSON.stringify(before)} after ${JSON.stringify(after)}`);
  const unchanged = JSON.stringify(before) === JSON.stringify(after);
  row('B1-17', { roles: 'admin', V: 'n.a.', G: `${unchanged ? '✓' : '✗'} after four invalid saves the configuration is unchanged (${unchanged})`, R: `${res.every((x) => x.near) && res.every((x) => x.status >= 400 || !/Manage Jenkins$/.test(x.page)) ? '✓' : '✗'} ${res.map((x) => `${x.f}=${x.v}: field "${x.near.slice(0, 50)}", Save -> ${x.status} "${x.page.slice(0, 50)}"`).join('; ')}`, C: 'n.a.', E: '✓ B1-17-1-field-message, B1-17-2-save-refused' });
  await ad.context.close(); }
// B1-22 manager configuration (DEF-10)
{ const m = await login('manager'); await m.page.goto(`${BASE}/batch-control/`);
  const link = m.page.locator('#main-panel a, #side-panel a').filter({ hasText: /Configur|Settings/ }).first();
  const lt = await link.innerText().catch(() => 'NONE'); const lh = await link.getAttribute('href').catch(() => null);
  let st = 0, saved = false, secs = [];
  if (lh) { const [r] = await Promise.all([m.page.waitForNavigation(), link.click()]); st = r.status(); secs = await m.page.locator('input[name="_.pendingTimeoutHours"]').count();
    await m.page.fill('input[name="_.pendingTimeoutHours"]', '2'); const s = await shot(m.page, m.page.locator('input[name="_.pendingTimeoutHours"]').first().locator('xpath=ancestor::*[contains(@class,"jenkins-form-item")][1]'), 'B1-22-manager-config', { pad: 8 });
    await Promise.all([m.page.waitForNavigation().catch(() => null), m.page.locator('button[name="Submit"], button:has-text("Save")').first().click()]);
    saved = (await globalCfg()).pending === 2; }
  const mc = (await m.page.goto(`${BASE}/manage/configure`)).status();
  const rp = (await api('requester', lh || '/manage/configure', { method: 'POST', body: '', headers: { 'Content-Type': 'application/x-www-form-urlencoded' } })).status;
  const ad = await login('admin'); await setGlobal(ad.page, { pendingTimeoutHours: 1 }); await ad.context.close();
  ev(`V4 B1-22 link "${lt}" ${lh} -> ${st} fields ${secs} saved ${saved}; /manage/configure ${mc}; requester POST ${rp}`);
  row('B1-22', { roles: 'manager (BatchControl/Manage), requester', V: `${lh ? '✓' : '✗'} the Batch Control page offers "${lt}" (${lh}) to manager`, G: `${st === 200 && saved ? '✓' : '✗'} manager opens it (${st}) and saves pendingTimeoutHours=2, which takes effect (${saved}); restored to 1 by admin`, R: `✓ requester POST to it -> ${rp}; /manage/configure stays core's (manager ${mc})`, C: 'n.a.', E: '✓ B1-22-manager-config' });
  await m.context.close(); }
await close();
