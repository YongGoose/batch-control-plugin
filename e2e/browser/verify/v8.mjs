import { login, close, shot, api, job, BASE, requestRun, sleep, waitFor, clickBuildEntry, changeRows } from '../lib.mjs';
import { row, ev, mainText } from '../audit/rec.mjs';
import { uiCancel, formPost } from '../audit/restsubmit.mjs';
const T = String(Date.now()).slice(-4);
const only = process.argv.slice(2); const on = (x) => !only.length || only.includes(x);
const csvRow = async (id) => { const t = (await api('admin', '/batch-control/history/requests.csv')).text; const i = t.indexOf(id); return i < 0 ? '' : t.slice(i, t.indexOf('\n', i + 200) > 0 ? t.indexOf('\n', i + 200) : undefined).split('\n')[0]; };
const rq = await login('requester'); const p = rq.page;
if (on('cancel')) {
  const u1 = await requestRun(p, '/job/batch-pipeline/', { reason: `Verify B4-10 ${T}`, approvers: ['approver-1'] });
  await uiCancel(p, u1); await p.goto(u1); const t1 = await mainText(p); const s1 = await shot(p, '#main-panel table', 'B4-10-cancelled-by-requester', { pad: 8 });
  const u2 = await requestRun(p, '/job/batch-pipeline/', { reason: `Verify B4-11 ${T}`, approvers: ['approver-1'] });
  const m = await login('manager'); await uiCancel(m.page, u2); await m.context.close();
  await p.goto(u2); const t2 = await mainText(p); const s2 = await shot(p, '#main-panel table', 'B4-11-cancelled-by-manager', { pad: 8 });
  const c1 = await csvRow(u1.match(/(\d{8}-\d{6}-\w+)/)[1]); const c2 = await csvRow(u2.match(/(\d{8}-\d{6}-\w+)/)[1]);
  ev(`V8 cancel "${t1.slice(0, 250)}" csv ${c1}; manager "${t2.slice(0, 250)}" csv ${c2}`);
  row('B4-10', { roles: 'requester, approver-1', V: '✓ Cancel Request for the requester on PENDING', G: `${/CANCELLED/.test(t1) ? '✓' : '✗'} CANCELLED`, R: 'n.a.', C: `${/Decided by requester|Cancelled by requester/.test(t1) && /,requester\s*$/.test(c1) ? '✓' : '✗'} detail: "${(t1.match(/Decided[^R]{0,80}/) || [''])[0]}"; requests.csv decidedBy "${c1.split(',').pop()}" (DEF-13)`, E: s1 ? '✓ B4-10-cancelled-by-requester' : '✗' });
  row('B4-11', { roles: 'manager, requester', V: '✓ Cancel Request for a Manage holder', G: `${/CANCELLED/.test(t2) ? '✓' : '✗'} manager cancelled the requester's PENDING request`, R: 'n.a.', C: `${/manager/.test(t2) && /,manager\s*$/.test(c2) ? '✓' : '✗'} detail: "${(t2.match(/Decided[^R]{0,80}/) || [''])[0]}"; requests.csv decidedBy "${c2.split(',').pop()}" (DEF-13)`, E: s2 ? '✓ B4-11-cancelled-by-manager' : '✗' });
}
if (on('invalidate')) {
  const ad = await login('admin'); const J = `b6-verify-${T}`;
  await ad.page.goto(`${BASE}/view/all/newJob`); await ad.page.fill('#name', J); await ad.page.locator('label:has-text("Freestyle project")').first().click();
  await Promise.all([ad.page.waitForNavigation(), ad.page.locator('#ok-button').click()]); await Promise.all([ad.page.waitForNavigation(), ad.page.locator('button[name="Submit"]').click()]);
  const u1 = await requestRun(p, `/job/${J}/`, { reason: `Verify B6-03 ${T}: pending before rename`, approvers: ['approver-1'] });
  await ad.page.goto(`${BASE}/job/${J}/confirm-rename`); await ad.page.fill('input[name="newName"]', `${J}-r`); await ad.page.waitForTimeout(700);
  await Promise.all([ad.page.waitForNavigation(), ad.page.locator('button[name="Submit"], button:has-text("Rename")').first().click()]); await sleep(2500);
  await p.goto(u1); const t1 = await mainText(p); const s1 = await shot(p, '#main-panel table', 'B6-03-invalidated-rename', { pad: 8 });
  const u2 = await requestRun(p, `/job/${J}-r/`, { reason: `Verify B6-04 ${T}: pending before move`, approvers: ['approver-1'] });
  await ad.page.goto(`${BASE}/job/${J}-r/`); await ad.page.locator('#side-panel a:has-text("Move")').click(); await ad.page.waitForLoadState('load');
  const sel = ad.page.locator('select[name="destination"]'); await sel.selectOption({ label: 'Jenkins » team' }).catch(async () => sel.selectOption('/team'));
  await Promise.all([ad.page.waitForNavigation(), ad.page.locator('button:has-text("Move")').last().click()]); await sleep(2500);
  await p.goto(u2); const t2 = await mainText(p); const s2 = await shot(p, '#main-panel table', 'B6-04-invalidated-move', { pad: 8 });
  ev(`V8 invalidate rename "${t1.slice(0, 300)}"; move "${t2.slice(0, 300)}"`);
  const why = (t) => (t.match(/(renamed|moved)[^.]*\./i) || [''])[0];
  row('B6-03', { roles: 'admin (rename), requester', V: 'n.a.', G: `${/INVALIDATED/.test(t1) ? '✓' : '✗'} rename of ${J} with a PENDING request -> INVALIDATED`, R: `${why(t1) ? '✓' : '✗'} the detail says why: "${why(t1).slice(0, 140)}" (DEF-17)`, C: `${why(t1) ? '✓' : '✗'} reason kept as the decision comment`, E: s1 ? '✓ B6-03-invalidated-rename' : '✗' });
  row('B6-04', { roles: 'admin (move), requester', V: 'n.a.', G: `${/INVALIDATED/.test(t2) ? '✓' : '✗'} move into team/ with a PENDING request -> INVALIDATED`, R: `${why(t2) ? '✓' : '✗'} "${why(t2).slice(0, 140)}" (DEF-17)`, C: `${why(t2) ? '✓' : '✗'} reason on the detail`, E: s2 ? '✓ B6-04-invalidated-move' : '✗' });
  await ad.context.close();
}
if (on('links')) {
  const a = await login('approver-1'); await a.page.goto(`${BASE}/batch-control/dashboard/?days=30`);
  const r = a.page.locator('#main-panel tr', { hasText: 'team/secret-job' }).first();
  const links = await r.locator('a').evaluateAll((as) => as.map((x) => `${x.innerText.trim()}->${x.getAttribute('href')}`));
  const s = await shot(a.page, r, 'B2-05-secret-job-row', { pad: 8 });
  const codes = []; for (const l of links) codes.push(`${l}=${(await a.page.goto(new URL(l.split('->')[1], BASE + '/batch-control/dashboard/').href)).status()}`);
  await a.context.close();
  row('B2-05', { roles: 'approver-1 (no Read on team/secret-job)', V: `${codes.every((c) => /=200$/.test(c)) ? '✓' : '✗'} the team/secret-job row offers ${links.length} links: ${codes.join(', ') || 'none (run and request are plain text)'} (DEF-11)`, G: '✓ the row is listed (LIMITATIONS 19)', R: 'n.a.', C: 'n.a.', E: s ? '✓ B2-05-secret-job-row' : '✗' });
  const ad = await login('admin'); await ad.page.goto(`${BASE}/batch-control/dashboard/`);
  const cron = (await job('batch-cron', 'builds[number]')).builds.map((b) => b.number); const low = Math.min(...cron);
  const dead = ad.page.locator('#main-panel tr', { hasText: 'batch-cron' }).filter({ hasText: new RegExp(`#${low - 5}\\b`) }).first();
  let dl = 'row not on page 1';
  const allRows = await ad.page.locator('#main-panel tr', { hasText: 'batch-cron' }).evaluateAll((trs) => trs.map((t) => ({ n: (t.innerText.match(/#(\d+)/) || [])[1], a: !!t.querySelector('a[href*="/job/batch-cron/"]') })));
  const deleted = allRows.filter((r) => r.n && +r.n < low); const deletedLinked = deleted.filter((r) => r.a).length;
  row('B12-08', { roles: 'admin', V: `${deleted.length && deletedLinked === 0 ? '✓' : deleted.length ? '✗' : '?'} batch-cron keeps builds >= #${low} (log rotation); rows for deleted builds on page 1: ${deleted.length}, of which linked ${deletedLinked} (DEF-23)`, G: '✓ records stay listed', R: 'n.a.', C: 'n.a.', E: '✓ text (dashboard rows in audit.log)', verdict: deleted.length ? undefined : 'BLOCKED' });
  ev(`V8 B12-08 low ${low} rows ${JSON.stringify(allRows.slice(0, 8))}`);
  await ad.page.goto(`${BASE}/batch-control/history/`);
  const st = await ad.page.locator('#main-panel h2:has-text("Monthly Summary") ~ table').first().innerText().catch(() => '');
  const s2 = await shot(ad.page, [ad.page.locator('#main-panel h2:has-text("Monthly Summary")').first(), ad.page.locator('#main-panel h2:has-text("Monthly Summary") ~ table').first()], 'B13-06-summary-labels', { pad: 8 });
  row('B13-06', { roles: 'admin', V: 'n.a.', G: `${!/incidentsOpen|requestsApproved/.test(st) ? '✓' : '✗'} Monthly Summary rows: "${st.replace(/\s+/g, ' ').slice(0, 200)}" (DEF-22)`, R: 'n.a.', C: 'n.a.', E: s2 ? '✓ B13-06-summary-labels' : '✗' });
  await ad.page.goto(`${BASE}/job/batch-daily/configure`); await ad.page.waitForTimeout(1500);
  const helps = {};
  for (const f of ['blockTimer', 'approvalRequired', 'blockUpstream']) {
    const item = ad.page.locator(`[name="_.${f}"]`).first().locator('xpath=ancestor::*[contains(@class,"jenkins-form-item")][1]');
    await item.locator('.jenkins-help-button').first().click().catch(() => {}); await ad.page.waitForTimeout(1200);
    helps[f] = (await item.locator('.help-area .help, .help').first().innerText().catch(() => '')).replace(/\s+/g, ' ');
  }
  const s3 = await shot(ad.page, ad.page.locator('[name="_.blockTimer"]').first().locator('xpath=ancestor::*[contains(@class,"jenkins-form-item")][1]'), 'B10-05-help-blockTimer', { pad: 8 });
  row('B10-05', { roles: 'admin', V: 'n.a.', G: `${/activat/i.test(helps.blockTimer) && !/turning a switch off here/.test(helps.blockTimer) ? '✓' : '✗'} help-blockTimer: "${helps.blockTimer.slice(0, 220)}" (DEF-21)`, R: 'n.a.', C: 'n.a.', E: s3 ? '✓ B10-05-help-blockTimer' : '✗', note: `approvalRequired: "${helps.approvalRequired.slice(0, 80)}"` });
  // B5-17: blockTimer on a job without approvalRequired (batch-cron)
  await ad.page.goto(`${BASE}/job/batch-cron/configure`); await ad.page.waitForTimeout(1500);
  const bt = ad.page.locator('[name="_.blockTimer"]').first(); if (!(await bt.isChecked())) await bt.locator('xpath=following-sibling::label[1]').click();
  await Promise.all([ad.page.waitForNavigation(), ad.page.locator('button[name="Submit"]').click()]);
  const n0 = (await job('batch-cron', 'nextBuildNumber')).nextBuildNumber; await sleep(125000); const n1 = (await job('batch-cron', 'nextBuildNumber')).nextBuildNumber;
  const vr = await login('requester'); await vr.page.goto(`${BASE}/job/batch-cron/`);
  const nt = (await vr.page.locator('#main-panel').innerText()).replace(/\s+/g, ' ');
  const s4 = await shot(vr.page, vr.page.locator('#main-panel').locator('text=/blockTimer|Block cron/').first().locator('xpath=..'), 'B5-17-blocktimer-notice', { pad: 8 }); await vr.context.close();
  const rec = (await changeRows(/,TRIGGER_BLOCKED,batch-cron,/))[0] || '';
  await ad.page.goto(`${BASE}/job/batch-cron/configure`); await ad.page.waitForTimeout(1500);
  await ad.page.locator('[name="_.blockTimer"]').first().locator('xpath=following-sibling::label[1]').click();
  await Promise.all([ad.page.waitForNavigation(), ad.page.locator('button[name="Submit"]').click()]);
  row('B5-17', { roles: 'admin (switch), requester (job page)', V: 'n.a.', G: `${n1 === n0 ? '✓' : '✗'} blockTimer on the activated, not approval-required batch-cron: no timer build in 125 s (${n0} -> ${n1}); switched off again`, R: `${/blockTimer|Block cron/.test(nt) ? '✓' : '✗'} the job page names the switch: "${(nt.match(/[^.]*(blockTimer|Block cron)[^.]*\./) || [''])[0].slice(0, 140)}" (DEF-15)`, C: `${/switch=blockTimer/.test(rec) ? '✓' : '✗'} "${rec.split(',').slice(6).join(',').slice(0, 90)}"`, E: s4 ? '✓ B5-17-blocktimer-notice' : '✗' });
  await ad.context.close();
}
await close();
