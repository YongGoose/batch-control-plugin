// B1-06, 07, 08, 11, 12, 16, 19 re-audit.
import { login, close, shot, api, BASE, setGlobal, globalCfg, requestRun, requestGrant, decide, mails, sleep, errText } from '../lib.mjs';
import { row, ev, mainText } from './rec.mjs';
const only = process.argv.slice(2); const on = (x) => !only.length || only.includes(x);
const ad = await login('admin');
const APPR = 'approver-1\napprover-2\napprover-disc\nadmin';
if (on('B1-06')) {
  await setGlobal(ad.page, { approversText: 'approver-1\nAPPROVER-2\napprover-disc\nadmin' });
  const rq = await login('requester'); await rq.page.goto(`${BASE}/job/batch-pipeline/batch-control/`);
  const offered = await rq.page.locator('form[name="batch-control-request"] input[name="approvers"]').evaluateAll((es) => es.map((e) => e.value));
  const s1 = await shot(rq.page, rq.page.locator('form[name="batch-control-request"] input[name="approvers"]').first().locator('xpath=ancestor::*[contains(@class,"jenkins-form-item")][1]'), 'B1-06-1-form', { pad: 8 });
  const url = await requestRun(rq.page, '/job/batch-pipeline/', { reason: `Audit B1-06 ${Date.now()}: upper-case approver id`, approvers: ['APPROVER-2'] });
  const a2 = await login('approver-2'); await a2.page.goto(url);
  const forms = await a2.page.locator('form[name="approve"]').count();
  if (forms) { await a2.page.fill('form[name="approve"] textarea[name="comment"]', 'ok'); await Promise.all([a2.page.waitForLoadState('load'), a2.page.locator('form[name="approve"] button').first().click()]); }
  const t = await mainText(a2.page); const s2 = await shot(a2.page, '#main-panel table', 'B1-06-2-decided', { pad: 8 });
  await setGlobal(ad.page, { approversText: APPR });
  ev(`B1-06 offered ${offered}; approver-2 forms ${forms}; ${t.slice(0, 200)}`);
  row('B1-06', { roles: 'admin, requester, approver-2', V: `${offered.includes('APPROVER-2') ? '✓' : '✗'} the form offers ${offered.join(', ')}`, G: `${forms && /APPROVED|EXECUTED/.test(t) ? '✓' : '✗'} approver-2 (lower-case login) got the decision form for APPROVER-2 and approved: ${(t.match(/Status \w+/) || [''])[0]}`, R: 'n.a.', C: '✓ decided by approver-2 on the detail', E: s1 && s2 ? '✓ B1-06-1..2' : '✗' });
  await rq.context.close(); await a2.context.close();
}
if (on('B1-07')) {
  await setGlobal(ad.page, { approversText: '' });
  const rq = await login('requester');
  const res = {};
  for (const [k, p] of [['run', '/job/batch-daily/batch-control/'], ['grants', '/batch-control/grants/'], ['activation', '/job/batch-daily/batch-control-activation']]) {
    await rq.page.goto(BASE + p);
    const w = (await rq.page.locator('#main-panel .jenkins-alert').allInnerTexts()).map((x) => x.replace(/\s+/g, ' ')).find((x) => /approver/i.test(x)) || '';
    const submit = await rq.page.locator('#main-panel button:has-text("Submit Request"), #main-panel button:has-text("Request Grant")').count();
    res[k] = { w, submit, s: await shot(rq.page, rq.page.locator('#main-panel .jenkins-alert', { hasText: /approver/i }).first(), `B1-07-${k}-form`, { pad: 8 }) };
  }
  const rest = await api('requester', '/job/batch-daily/batch-control/submit', { method: 'POST', body: new URLSearchParams({ reason: 'x', approvers: 'approver-1', json: '{}' }), headers: { 'Content-Type': 'application/x-www-form-urlencoded' } });
  await setGlobal(ad.page, { approversText: APPR });
  ev(`B1-07 ${JSON.stringify(res)}; REST ${rest.status} ${errText(rest.text)}`);
  row('B1-07', { roles: 'admin, requester', V: `${Object.values(res).every((r) => r.submit === 0) ? '✓' : '✗'} no submit button on the run, grant or activation form while the list is empty`, G: `${rest.status >= 400 ? '✓' : '✗'} nothing can be created: REST submit -> ${rest.status} "${errText(rest.text).slice(0, 80)}"`, R: `${Object.values(res).every((r) => /administrator|Manage Jenkins/i.test(r.w)) ? '✓' : '✗'} run: "${res.run.w.slice(0, 140)}"; grants: "${res.grants.w.slice(0, 80)}"; activation: "${res.activation.w.slice(0, 80)}"`, C: 'n.a.', E: Object.values(res).every((r) => r.s) ? '✓ B1-07-{run,grants,activation}-form' : '✗' });
  await rq.context.close();
}
if (on('B1-08')) {
  await setGlobal(ad.page, { allowAdminSelfApproval: false });
  await ad.page.goto(`${BASE}/job/batch-pipeline/batch-control/`);
  const offeredOff = await ad.page.locator('form[name="batch-control-request"] input[name="approvers"]').evaluateAll((es) => es.map((e) => e.value));
  const s1 = await shot(ad.page, ad.page.locator('form[name="batch-control-request"] input[name="approvers"]').first().locator('xpath=ancestor::*[contains(@class,"jenkins-form-item")][1]'), 'B1-08-1-self-off-offered', { pad: 8 });
  const restOff = await api('admin', '/job/batch-pipeline/batch-control/submit', { method: 'POST', body: new URLSearchParams({ reason: 'self off', approvers: 'admin', json: '{}' }), headers: { 'Content-Type': 'application/x-www-form-urlencoded' } });
  await setGlobal(ad.page, { allowAdminSelfApproval: true });
  await ad.page.goto(`${BASE}/job/batch-pipeline/batch-control/`);
  const offeredOn = await ad.page.locator('form[name="batch-control-request"] input[name="approvers"]').evaluateAll((es) => es.map((e) => e.value));
  const url = await requestRun(ad.page, '/job/batch-pipeline/', { reason: `Audit B1-08 ${Date.now()}: self approval on`, approvers: ['admin'] });
  await ad.page.fill('form[name="approve"] textarea[name="comment"]', 'self'); await Promise.all([ad.page.waitForLoadState('load'), ad.page.locator('form[name="approve"] button').first().click()]);
  const s2 = await shot(ad.page, '#main-panel table', 'B1-08-2-self-on', { pad: 8 });
  const id = url.match(/(\d{8}-\d{6}-\w+)/)[1];
  const csv = (await api('admin', '/batch-control/history/requests.csv')).text.split('\n').find((l) => l.startsWith(id)) || '';
  ev(`B1-08 off offered ${offeredOff}; REST self off ${restOff.status} ${errText(restOff.text)}; on offered ${offeredOn}; csv ${csv}`);
  row('B1-08', { roles: 'admin', V: `${!offeredOff.includes('admin') && offeredOn.includes('admin') ? '✓' : '✗'} off: admin is not offered as approver of his own request (${offeredOff.join(', ')}); on: offered (${offeredOn.join(', ')})`, G: `${restOff.status >= 400 && /,true,/.test(csv) ? '✓' : '✗'} off: scripted self-designation refused ${restOff.status} "${errText(restOff.text).slice(0, 80)}"; on: self-approved`, R: 'n.a. (off: nothing offered)', C: `${/,true,/.test(csv) && /,admin\s*$/.test(csv) ? '✓' : '✗'} requests.csv selfApproved=true, decidedBy=admin`, E: s1 && s2 ? '✓ B1-08-1..2' : '✗' });
}
if (on('B1-11') || on('B1-12')) {
  await setGlobal(ad.page, { grantDurationOptionsText: '5, 10' });
  const rq = await login('requester'); await rq.page.goto(`${BASE}/batch-control/grants/`);
  const opts = await rq.page.locator('select[name="durationMinutes"] option').allInnerTexts();
  const s1 = await shot(rq.page, rq.page.locator('select[name="durationMinutes"]'), 'B1-11-1-options', { pad: 30 });
  const g = await requestGrant(rq.page, { scope: 'batch-pipeline', actions: ['CONFIGURE'], minutes: 5, reason: `Audit B1-11 ${Date.now()}: five-minute window` });
  await decide(g.url, 'approve', 'ok');
  await rq.page.goto(`${BASE}/batch-control/grants/`);
  const r = rq.page.locator('table:has(th:has-text("Expires")) tbody tr', { hasText: 'batch-pipeline' }).first();
  const rt = (await r.innerText()).replace(/\s+/g, ' ');
  const s2 = await shot(rq.page, r, 'B1-11-2-active', { pad: 8 });
  const times = rt.match(/(\d\d:\d\d:\d\d)[^\d]+(\d\d:\d\d:\d\d)/);
  const mins = times ? ((new Date(`1970-01-01T${times[2]}Z`) - new Date(`1970-01-01T${times[1]}Z`)) / 60000) : null;
  row('B1-11', { roles: 'admin, requester, approver-1', V: 'n.a.', G: `${JSON.stringify(opts) === '["5 minutes","10 minutes"]' && mins === 5 ? '✓' : '✗'} Duration offers ${opts.join(' / ')}; approved 5-minute window "${rt.slice(0, 120)}" (${mins} min)`, R: 'n.a.', C: 'n.a.', E: s1 && s2 ? '✓ B1-11-1..2' : '✗' });
  // B1-12
  await setGlobal(ad.page, { maxGrantMinutes: 20 });
  await rq.page.goto(`${BASE}/batch-control/grants/`);
  const desc = (await rq.page.locator('.jenkins-form-item:has(input[name="customDurationMinutes"])').innerText()).replace(/\s+/g, ' ');
  const n0 = await rq.page.locator('#main-panel table').first().locator('tbody tr').count();
  await rq.page.fill('input[name="scopeFullName"]', 'batch-daily');
  await rq.page.locator('#grant-action-configure + label').click();
  await rq.page.fill('input[name="customDurationMinutes"]', '30');
  const reason = `Audit B1-12 ${Date.now()}: custom 30 over a max of 20`;
  await rq.page.fill('textarea[name="reason"]', reason);
  await rq.page.locator('input[name="approvers"][value="approver-1"] + label').click();
  const [resp] = await Promise.all([rq.page.waitForNavigation({ waitUntil: 'load' }).catch(() => null), rq.page.locator('button:has-text("Request Grant")').click()]);
  await rq.page.waitForTimeout(800);
  const t = await mainText(rq.page); const kept = (await rq.page.locator('textarea[name="reason"]').inputValue().catch(() => '')) === reason;
  const s3 = await shot(rq.page, '#main-panel', 'B1-12-refusal', { pad: 8 });
  await rq.page.goto(`${BASE}/batch-control/grants/`);
  const n1 = await rq.page.locator('#main-panel table').first().locator('tbody tr').count();
  await setGlobal(ad.page, { grantDurationOptionsText: '1, 15, 30, 60', maxGrantMinutes: 240 });
  ev(`B1-12 field "${desc}"; submit -> ${resp && resp.status()} "${t.slice(0, 200)}" input kept ${kept}; requests ${n0}->${n1}`);
  row('B1-12', { roles: 'admin, requester', V: 'n.a.', G: `${n1 === n0 && /20/.test(desc) ? '✓' : '✗'} the field states "${desc.slice(0, 60)}"; custom 30 refused, nothing stored (${n0} -> ${n1})`, R: `${kept && !/maxGrantMinutes/.test(t) ? '✓' : '✗'} HTTP ${resp && resp.status()} "${t.slice(0, 120)}"; the form and the typed reason are ${kept ? 'kept' : 'gone'}${/maxGrantMinutes/.test(t) ? '; names the internal key maxGrantMinutes' : ''} (DEF-09)`, C: 'n.a.', E: s3 ? '✓ B1-12-refusal' : '✗', defect: 'DEF-09 (known)' });
  await rq.context.close();
}
if (on('B1-16')) {
  await setGlobal(ad.page, { emailNotifications: false });
  const rq = await login('requester');
  const url = await requestRun(rq.page, '/job/batch-pipeline/', { reason: `Audit B1-16 ${Date.now()}: mail off`, approvers: ['approver-1'] });
  const id = url.match(/(\d{8}-\d{6}-\w+)/)[1];
  await sleep(8000);
  const m = await mails(id);
  await ad.page.goto(`${BASE}/manage/configure`);
  const box = ad.page.locator('[name="_.emailNotifications"]').first();
  const s = await shot(ad.page, box.locator('xpath=ancestor::*[contains(@class,"jenkins-form-item")][1]'), 'B1-16-mail-off', { pad: 8 });
  await setGlobal(ad.page, { emailNotifications: true });
  // cancel the request again (requester)
  await rq.page.goto(url); rq.page.once('dialog', (d) => d.accept());
  await rq.page.locator('a:has-text("Cancel Request"), button:has-text("Cancel Request")').first().click().catch(() => {}); await rq.page.waitForTimeout(800);
  const c = rq.page.locator('dialog[open] button[data-id="ok"]').first(); if (await c.count()) await Promise.all([rq.page.waitForLoadState('load'), c.click()]);
  ev(`B1-16 mails for ${id}: ${m.length}`);
  row('B1-16', { roles: 'admin, requester', V: 'n.a.', G: `${m.length === 0 ? '✓' : '✗'} with "Send e-mail notifications" off a new request ${id} produced ${m.length} mails; switched back on`, R: 'n.a.', C: 'n.a. (no mail is the promise)', E: s ? '✓ B1-16-mail-off' : '✗' });
  await rq.context.close();
}
if (on('B1-19')) {
  await ad.page.goto(`${BASE}/manage/configure`); await ad.page.waitForTimeout(1500);
  const sec = ad.page.locator('.jenkins-section:has([name="_.runControlEnabled"])').first();
  const helps = sec.locator('a.jenkins-help-button, .jenkins-help-button');
  const n = await helps.count(); const texts = [];
  for (let i = 0; i < n; i++) { await helps.nth(i).click(); await ad.page.waitForTimeout(700); }
  await ad.page.waitForTimeout(1500);
  const areas = (await sec.locator('.help-area .help').allInnerTexts()).map((x) => x.replace(/\s+/g, ' ').trim()).filter(Boolean);
  const bad = areas.filter((x) => /\bSPEC\b|\bD-\d+|\bT-\d+|#\d+\b|Loading/.test(x));
  const labels = await sec.locator('.jenkins-form-label').allInnerTexts();
  const noHelp = [];
  for (const l of await sec.locator('.jenkins-form-item').all()) { const lab = (await l.locator('.jenkins-form-label').first().innerText().catch(() => '')).trim(); if (lab && !(await l.locator('.jenkins-help-button').count())) noHelp.push(lab); }
  const s = await shot(ad.page, sec.locator('.help-area .help').first(), 'B1-19-help-open', { pad: 20 });
  ev(`B1-19 ${n} help buttons, ${areas.length} texts, bad ${JSON.stringify(bad)}, no help: ${JSON.stringify(noHelp)}`);
  row('B1-19', { roles: 'admin', V: 'n.a.', G: `${areas.length >= n && !bad.length ? '✓' : '✗'} ${n} help buttons in the section, ${areas.length} texts open, none with an internal reference or stuck "Loading..."; fields without help: ${noHelp.join(', ') || 'none'}`, R: 'n.a.', C: 'n.a.', E: s ? '✓ B1-19-help-open' : '✗' });
}
await close();
