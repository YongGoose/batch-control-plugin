// B7 change-control grants re-audit.
import { login, close, shot, api, BASE, requestGrant, decide, changeRows, sleep, mails, clickBuildEntry, job, waitFor } from '../lib.mjs';
import { row, ev, mainText, sidebar } from './rec.mjs';
import { formPost, uiCancel, uiRevoke } from './restsubmit.mjs';
import { execSync } from 'node:child_process';
const only = process.argv.slice(2); const on = (x) => !only.length || only.includes(x);
const tag = Date.now(); const T = String(tag).slice(-4);
const gidOf = (u) => u.match(/(\d{8}-\d{6}-\w+)/)[1];
const cfgStatus = async (u, p) => { const c = await login(u); const s = (await c.page.goto(BASE + p)).status(); await c.context.close(); return s; };
const rq = await login('requester'); const p = rq.page;
if (on('job') || on('JOB')) {
  // B7-02..B7-08
  const c0 = await cfgStatus('requester', '/job/batch-daily/configure');
  const g = await requestGrant(p, { scope: 'batch-daily', actions: ['CONFIGURE'], minutes: 15, reason: `Audit B7-02 ${tag}: edit the batch-daily description` });
  const gid = gidOf(g.url);
  await p.goto(`${BASE}/batch-control/grants/`);
  const lr = p.locator('#main-panel table').first().locator('tbody tr', { hasText: gid }).first();
  const lrt = (await lr.innerText()).replace(/\s+/g, ' '); const hasLink = await lr.locator(`a:has-text("${gid}")`).count();
  const s2 = await shot(p, lr, 'B7-02-grant-request-row', { pad: 8 });
  await sleep(4000); const m = (await mails(gid)).map((x) => `${x.To[0].Address}: ${x.Subject}`);
  row('B7-02', { roles: 'requester, approver-1', V: '✓ Request Grant form offered to requester (RequestGrant)', G: `${/PENDING/.test(lrt) && hasLink ? '✓' : '✗'} Grant Requests row "${lrt.slice(0, 120)}" with the id as a link`, R: 'n.a.', C: `${m.some((x) => /approver-1@/.test(x)) ? '✓' : '✗'} ${m.join(' | ')}`, E: s2 ? '✓ B7-02-grant-request-row' : '✗', note: 'after Request Grant the user lands on the list, not the new request (U-03)' });
  const ap = await login('approver-1'); await ap.page.goto(g.url);
  const at = await mainText(ap.page);
  const s3 = await shot(ap.page, ['#main-panel table', 'form[name="approve"]'], 'B7-03-approver-detail', { pad: 8 });
  row('B7-03', { roles: 'approver-1', V: `${await ap.page.locator('form[name="approve"]').count() ? '✓' : '✗'} decision form for the designated approver`, G: `${/Scope Freestyle project batch-daily/.test(at) && /Actions CONFIGURE/.test(at) && /Duration 15 minutes/.test(at) && /Requester requester/.test(at) ? '✓' : '✗'} one screen: scope, actions, duration, status, requester, approvers, reason, and what approving grants ("${((at.match(/Approving[^.]*\./) || at.match(/[^.]*grants [^.]*permission[^.]*\./i) || [''])[0]).slice(0, 110)}")`, R: 'n.a.', C: 'n.a.', E: s3 ? '✓ B7-03-approver-detail' : '✗' });
  await ap.page.fill('form[name="approve"] textarea[name="comment"]', 'ok'); await Promise.all([ap.page.waitForLoadState('load'), ap.page.locator('form[name="approve"] button').first().click()]); await ap.context.close();
  await p.goto(`${BASE}/batch-control/grants/`);
  const ar = p.locator('table:has(th:has-text("Expires")) tbody tr', { hasText: gid }).first(); const art = (await ar.innerText()).replace(/\s+/g, ' ');
  const s4 = await shot(p, ar, 'B7-04-active-row', { pad: 8 });
  row('B7-04', { roles: 'requester', V: 'n.a.', G: `${/\d+ min/.test(art) ? '✓' : '✗'} Active Grants row "${art.slice(0, 140)}" with remaining time`, R: 'n.a.', C: 'n.a.', E: s4 ? '✓ B7-04-active-row' : '✗' });
  await p.goto(`${BASE}/job/batch-daily/`); const sb = await sidebar(p);
  const [cr] = await Promise.all([p.waitForNavigation({ waitUntil: 'load' }), p.locator('#side-panel a:has-text("Configure")').click()]);
  await p.waitForTimeout(1500); const formOk = await p.locator('form[name="config"] textarea[name="description"]').count();
  const s5 = await shot(p, ['#side-panel #tasks, #side-panel', 'form[name="config"] textarea[name="description"]'], 'B7-05-configure-renders', { pad: 8 });
  row('B7-05', { roles: 'requester', V: `${sb.includes('Configure') ? '✓' : '✗'} sidebar gains Configure under the window (${sb.filter((x) => /Configure|Authorization|Rename|Config History|Export/.test(x))}); before the window configure was ${c0}`, G: `${cr.status() === 200 && formOk ? '✓' : '✗'} Configure -> ${cr.status()}, the real form renders (E2E-D1 closed)`, R: 'n.a.', C: 'n.a.', E: s5 ? '✓ B7-05-configure-renders' : '✗' });
  const desc = `Edited under grant ${gid} (audit B7-06)`;
  await p.fill('textarea[name="description"]', desc);
  await Promise.all([p.waitForNavigation({ waitUntil: 'load' }), p.locator('button[name="Submit"]').click()]);
  const shown = (await mainText(p)).includes(desc); await sleep(1500);
  const rec = (await changeRows(/,CONFIGURE,batch-daily,requester,/))[0] || '';
  const ad = await login('admin'); await ad.page.goto(`${BASE}/batch-control/changes/`);
  const rr = ad.page.locator('#main-panel tr', { hasText: 'batch-daily' }).filter({ hasText: gid }).first();
  const rlink = await rr.locator(`a:has-text("${gid}")`).count();
  const d = rr.locator('summary, a').filter({ hasText: /Diff/ }).first(); if (await d.count()) { await d.click(); await ad.page.waitForTimeout(600); }
  const s6 = await shot(ad.page, rr, 'B7-06-configure-record', { pad: 8 });
  row('B7-06', { roles: 'requester, admin (records)', V: 'n.a.', G: `${shown ? '✓' : '✗'} description saved and shown on the job page`, R: 'n.a.', C: `${rec.includes(gid) && rlink ? '✓' : '✗'} CONFIGURE by requester with grantId ${gid} as a link and the description diff`, E: s6 ? '✓ B7-06-configure-record' : '✗' });
  const x = await api('requester', '/job/batch-daily/config.xml', { raw: true });
  row('B7-07', { roles: 'requester', V: 'n.a.', G: `${x.status === 200 && x.text.includes('<description>') ? '✓' : '✗'} config.xml ${x.status}, readable XML`, R: 'n.a.', C: 'n.a.', E: '✓ text' });
  const o = await cfgStatus('requester', '/job/batch-pipeline/configure');
  row('B7-08', { roles: 'requester', V: `✓ batch-pipeline sidebar has no Configure`, G: `${o === 403 ? '✓' : '✗'} batch-pipeline/configure -> ${o} (scope respected)`, R: '✓ core 403 on a typed URL (nothing offered)', C: 'n.a.', E: '✓ text' });
  await uiRevoke(gid); await ad.context.close();
}
if (on('B7-09')) {
  const g = await requestGrant(p, { scope: 'batch-daily', actions: ['CONFIGURE'], minutes: 1, reason: `Audit B7-09 ${tag}: one-minute window` });
  await decide(g.url, 'approve', 'ok');
  await p.goto(`${BASE}/job/batch-daily/configure`); await p.waitForTimeout(1500);
  const before = (await api('admin', '/job/batch-daily/config.xml', { raw: true })).text;
  await p.fill('textarea[name="description"]', `edit after expiry ${tag}`);
  await sleep(75000);
  const [r] = await Promise.all([p.waitForNavigation({ waitUntil: 'load' }).catch(() => null), p.locator('button[name="Submit"]').click()]);
  const t = await mainText(p); const s = await shot(p, '#main-panel, body', 'B7-09-save-after-expiry', { pad: 8 });
  const after = (await api('admin', '/job/batch-daily/config.xml', { raw: true })).text;
  await p.goto(`${BASE}/batch-control/grants/`);
  const gt = (await p.locator('#main-panel table').first().locator('tbody tr', { hasText: gidOf(g.url) }).first().innerText()).replace(/\s+/g, ' ');
  const s2 = await shot(p, p.locator('#main-panel table').first().locator('tbody tr', { hasText: gidOf(g.url) }).first(), 'B7-10-grants-after-expiry', { pad: 8 });
  const reReq = await p.locator('#main-panel a', { hasText: /request again|re-request/i }).count();
  ev(`B7-09 save -> ${r && r.status()} "${t.slice(0, 120)}"; unchanged ${before === after}; grants row "${gt}" re-request links ${reReq}`);
  row('B7-09', { roles: 'requester', V: 'n.a.', G: `${r && r.status() === 403 && before === after ? '✓' : '✗'} 1-minute window, configure open, Save after 75 s -> ${r && r.status()}, config unchanged`, R: `✗ core "${t.slice(0, 80)}" is the documented refusal (SPEC 8, D-33), but the compensating guidance SPEC 8 puts on the Grants screen is missing: the request still reads "${gt.slice(0, 90)}", no expired-window history, no re-request link (${reReq}) (DEF-18)`, C: 'n.a.', E: s && s2 ? '✓ B7-09-save-after-expiry, B7-10-grants-after-expiry' : '✗', defect: 'DEF-18 (known)' });
}
let folderGid;
if (on('folder') || on('FOLDER')) {
  // B7-12 folder team CONFIGURE
  const g = await requestGrant(p, { scope: 'team', actions: ['CONFIGURE'], minutes: 15, reason: `Audit B7-12 ${tag}: folder window` });
  await decide(g.url, 'approve', 'ok');
  const a = await cfgStatus('requester', '/job/team/job/app-1/configure'), b = await cfgStatus('requester', '/job/team/configure'), c = await cfgStatus('requester', '/job/batch-cron/configure');
  row('B7-12', { roles: 'requester', V: 'n.a.', G: `${a === 200 && b === 200 && c === 403 ? '✓' : '✗'} folder team CONFIGURE: team/app-1 ${a}, team ${b}, batch-cron ${c}`, R: 'n.a.', C: 'n.a.', E: '✓ text' });
  await uiRevoke(gidOf(g.url));
  // B7-13 / B7-14 folder team CREATE, no restriction
  const g2 = await requestGrant(p, { scope: 'team', actions: ['CREATE'], minutes: 15, reason: `Audit B7-13 ${tag}: create without restriction` });
  await decide(g2.url, 'approve', 'ok'); const gid2 = gidOf(g2.url);
  await p.goto(`${BASE}/job/team/`); await p.locator('#side-panel a:has-text("New Item")').click(); await p.waitForLoadState('load');
  const name = `app-free-${T}`; await p.locator('#name').fill(name); await p.waitForTimeout(1000);
  await p.locator('label:has-text("Freestyle project")').first().click();
  await Promise.all([p.waitForNavigation(), p.locator('#ok-button').click()]); await p.waitForTimeout(1500);
  const locks = await Promise.all(['approvalRequired', 'blockTimer', 'blockUpstream'].map((f) => p.locator(`[name="_.${f}"]`).first().isChecked().catch(() => null)));
  const s1 = await shot(p, p.locator('[name="_.approvalRequired"]').first().locator('xpath=ancestor::*[contains(@class,"jenkins-section") or contains(@class,"optionalBlock-container")][1]'), 'B7-13-new-item-locked', { pad: 8 });
  await Promise.all([p.waitForNavigation(), p.locator('button[name="Submit"]').click()]);
  await sleep(1500); const cr = (await changeRows(new RegExp(`,CREATE,team/${name},`)))[0] || '';
  row('B7-13', { roles: 'requester', V: '✓ New Item appears in team/ only under the CREATE window', G: `${locks.every(Boolean) ? '✓' : '✗'} team/${name} created; its configure opens with Require approval, Block cron, Block upstream ticked (${locks})`, R: 'n.a.', C: `${cr.includes(gid2) ? '✓' : '✗'} CREATE record with grant ${gid2}`, E: s1 ? '✓ B7-13-new-item-locked' : '✗' });
  await p.goto(`${BASE}/`); const rootSb = await sidebar(p);
  const nj = await cfgStatus('requester', '/view/all/newJob');
  const ci = await formPost('requester', '/createItem?name=b7-root-' + T + '&mode=hudson.model.FreeStyleProject', {});
  const exists = (await api('admin', `/job/b7-root-${T}/api/json`)).status;
  row('B7-14', { roles: 'requester', V: `${!rootSb.includes('New Item') ? '✓' : '✗'} root sidebar without New Item [${rootSb.join(', ').slice(0, 80)}]`, G: `${nj === 403 && ci.status === 403 && exists === 404 ? '✓' : '✗'} /view/all/newJob ${nj}, POST /createItem ${ci.status}, nothing created (${exists})`, R: '✓ nothing offered; typed URLs get core 403', C: 'n.a.', E: '✓ text' });
  await uiRevoke(gid2);
  // B7-16..B7-20 regex restriction
  const g3 = await requestGrant(p, { scope: 'team', actions: ['CREATE', 'DELETE'], pattern: '/app-[0-9]+/', minutes: 15, reason: `Audit B7-16 ${tag}: regex restriction` });
  await decide(g3.url, 'approve', 'ok'); folderGid = gidOf(g3.url);
  const ok = `app-${T}`; const bad = `app-x${T}`;
  const v0 = (await changeRows(/,GRANT_VIOLATION,/)).length;
  await p.goto(`${BASE}/job/team/newJob`); await p.locator('#name').fill(ok); await p.waitForTimeout(800); await p.locator('label:has-text("Freestyle project")').first().click();
  await Promise.all([p.waitForNavigation(), p.locator('#ok-button').click()]); await Promise.all([p.waitForNavigation(), p.locator('button[name="Submit"]').click()]);
  await p.goto(`${BASE}/job/team/newJob`); await p.locator('#name').fill(bad); await p.waitForTimeout(1200); await p.locator('label:has-text("Freestyle project")').first().click();
  const vmsg = (await p.locator('#itemname-invalid, .input-validation-message').allInnerTexts()).join(' ').trim(); const okEnabled = !(await p.locator('#ok-button').isDisabled());
  const [br] = await Promise.all([p.waitForNavigation().catch(() => null), p.locator('#ok-button').click()]);
  const bt = await mainText(p); const s16 = await shot(p, '#main-panel, body', 'B7-16-regex-refused', { pad: 8 });
  const v1 = (await changeRows(/,GRANT_VIOLATION,/)).length;
  const e1 = (await api('admin', `/job/team/job/${ok}/api/json`)).status, e2 = (await api('admin', `/job/team/job/${bad}/api/json`)).status;
  row('B7-16', { roles: 'requester', V: 'n.a.', G: `${e1 === 200 && e2 === 404 ? '✓' : '✗'} /app-[0-9]+/: ${ok} created (${e1}), ${bad} refused (${br && br.status()}), absent (${e2})`, R: `✗ typing ${bad}: "${vmsg}" under the name, OK enabled (${okEnabled}); OK -> "${bt.slice(0, 80)}": nothing names the restriction (DEF-19)`, C: `${v1 === v0 + 1 ? '✓' : '✗'} one GRANT_VIOLATION (${v0} -> ${v1})`, E: s16 ? '✓ B7-16-regex-refused' : '✗', defect: 'DEF-19 (known)' });
  const long = 'app-' + '1'.repeat(252);
  const lr = await formPost('requester', `/job/team/createItem?name=${long}&mode=hudson.model.FreeStyleProject`, {});
  const v2 = (await changeRows(/,GRANT_VIOLATION,/)).length;
  row('B7-18', { roles: 'requester (script)', V: 'n.a.', G: `${lr.status >= 400 ? '✓' : '✗'} 256-char name matching the pattern -> ${lr.status}, not created`, R: `✓ "${lr.msg.slice(0, 90)}"`, C: `${v2 === v1 + 1 ? '✓' : '✗'} GRANT_VIOLATION (${v1} -> ${v2})`, E: '✓ text' });
  // B7-19 rename app-N -> evil
  await p.goto(`${BASE}/job/team/job/${ok}/`); const sbr = await sidebar(p);
  let rt = 'no Rename'; let rs = null;
  if (sbr.includes('Rename')) {
    await p.locator('#side-panel a:has-text("Rename")').click(); await p.waitForLoadState('load');
    await p.locator('input[name="newName"]').fill(`evil-${T}`); await p.waitForTimeout(1200);
    const inline = (await p.locator('.validation-error-area--visible, .error, .warning').allInnerTexts()).join(' ').replace(/\s+/g, ' ');
    const [r] = await Promise.all([p.waitForNavigation().catch(() => null), p.locator('button[name="Submit"], button:has-text("Rename")').last().click()]);
    rt = `inline "${inline.slice(0, 80)}" -> ${r && r.status()} "${(await mainText(p)).slice(0, 90)}"`; rs = await shot(p, '#main-panel, body', 'B7-19-rename-refused', { pad: 8 });
  }
  const v3 = (await changeRows(/,GRANT_VIOLATION,/)).length; const ev1 = (await api('admin', `/job/team/job/evil-${T}/api/json`)).status;
  row('B7-19', { roles: 'requester', V: `✓ Rename offered (sidebar ${sbr.filter((x) => /Rename|Delete/.test(x))})`, G: `${ev1 === 404 ? '✓' : '✗'} rename ${ok} -> evil-${T} refused before the change (evil absent: ${ev1})`, R: `✗ ${rt}: nothing names the restriction (DEF-19)`, C: `${v3 === v2 + 1 ? '✓' : '✗'} one GRANT_VIOLATION (${v2} -> ${v3})`, E: rs ? '✓ B7-19-rename-refused' : '✗', defect: 'DEF-19 (known)' });
  // B7-21 DELETE under the same window
  await p.goto(`${BASE}/job/team/job/${ok}/`);
  p.once('dialog', (d) => d.accept());
  await p.locator('#side-panel a:has-text("Delete Project")').click(); await p.waitForTimeout(800);
  const dd = p.locator('dialog[open] button[data-id="ok"], dialog[open] button:has-text("Yes")').first(); if (await dd.count()) await Promise.all([p.waitForNavigation().catch(() => null), dd.click()]);
  await sleep(2500); const gone = (await api('admin', `/job/team/job/${ok}/api/json`)).status;
  const dr = (await changeRows(new RegExp(`,DELETE,team/${ok},`)))[0] || '';
  row('B7-21', { roles: 'requester', V: '✓ Delete Project offered under the DELETE window', G: `${gone === 404 ? '✓' : '✗'} team/${ok} deleted (${gone})`, R: 'n.a.', C: `${dr.includes(folderGid) ? '✓' : '✗'} DELETE record with grant ${folderGid}`, E: '✓ text (the record is on Change Records)' });
  await uiRevoke(folderGid);
  // B7-20 CLI trailing space under an exact restriction
  const g4 = await requestGrant(p, { scope: 'team', actions: ['CREATE'], pattern: `app-c${T}`, minutes: 15, reason: `Audit B7-20 ${tag}: exact name` });
  await decide(g4.url, 'approve', 'ok');
  let cli; try { cli = execSync(`echo '<project><builders/></project>' | ../scripts/cli.sh requester create-job "team/app-c${T} " 2>&1; echo "EXIT=$?"`, { shell: '/bin/bash' }).toString(); } catch (e) { cli = (e.stdout || '').toString() + ' EXIT=' + e.status; }
  const ex = (await api('admin', `/job/team/job/app-c${T}%20/api/json`)).status, ex2 = (await api('admin', `/job/team/job/app-c${T}/api/json`)).status;
  row('B7-20', { roles: 'requester (CLI)', V: 'n.a.', G: `${ex === 404 && ex2 === 404 && /EXIT=[1-9]/.test(cli) ? '✓' : '✗'} CLI create-job "team/app-c${T} " refused (${cli.replace(/\s+/g, ' ').trim().slice(-12)}), nothing created`, R: `✗ "${cli.replace(/\s+/g, ' ').slice(0, 90)}": nothing says the name does not match the restriction (DEF-19)`, C: 'n.a.', E: '✓ text', defect: 'DEF-19 (known)' });
  await uiRevoke(gidOf(g4.url));
}
if (on('B7-17')) {
  const g = await requestGrant(p, { scope: 'team', actions: ['CREATE'], pattern: '/app-[/', minutes: 15, reason: `Audit B7-17 ${tag}: invalid regex` });
  const s = await shot(p, '#main-panel', 'B7-17-invalid-regex', { pad: 8 });
  const kept = await p.locator('input[name="createNamePattern"]').count();
  row('B7-17', { roles: 'requester', V: 'n.a.', G: `${g.error ? '✓' : '✗'} pattern "/app-[/" refused at submission, nothing stored`, R: `✗ "${(g.error || '').slice(0, 110)}" on a bare Error page; the form is ${kept ? 'kept' : 'gone with everything typed'} (DEF-09)`, C: 'n.a.', E: s ? '✓ B7-17-invalid-regex' : '✗', defect: 'DEF-09 (known)' });
}
if (on('B7-22')) {
  const ad = await login('admin'); const J = `app-veto-${T}`;
  await ad.page.goto(`${BASE}/job/team/newJob`); await ad.page.fill('#name', J); await ad.page.locator('label:has-text("Freestyle project")').first().click();
  await Promise.all([ad.page.waitForNavigation(), ad.page.locator('#ok-button').click()]); await Promise.all([ad.page.waitForNavigation(), ad.page.locator('button[name="Submit"]').click()]); await ad.context.close();
  const cf = await login('configurer'); await cf.page.goto(`${BASE}/job/team/job/${J}/`);
  cf.page.once('dialog', (d) => d.accept());
  await cf.page.locator('#side-panel a:has-text("Delete Project")').click(); await cf.page.waitForTimeout(800);
  const dd = cf.page.locator('dialog[open] button[data-id="ok"], dialog[open] button:has-text("Yes")').first(); if (await dd.count()) await Promise.all([cf.page.waitForNavigation().catch(() => null), dd.click()]);
  await cf.page.waitForTimeout(1500);
  const t = await mainText(cf.page); const s = await shot(cf.page, '#main-panel, body', 'B7-22-delete-veto', { pad: 8 });
  const links = await cf.page.locator('#main-panel a').evaluateAll((as) => as.map((a) => a.innerText.trim()));
  const gs = (await cf.page.goto(`${BASE}/batch-control/grants/`)).status();
  const still = (await api('admin', `/job/team/job/${J}/api/json`)).status;
  row('B7-22', { roles: 'configurer (standing Configure+Delete)', V: 'n.a.', G: `${still === 200 ? '✓' : '✗'} job kept (${still})`, R: `✗ "${t.slice(0, 150)}" - the advice points to Batch Control > Grants, which configurer cannot open (${gs}) and which is not linked (links: ${links.join(', ') || 'none'}) (DEF-26)`, C: 'n.a.', E: s ? '✓ B7-22-delete-veto' : '✗', defect: 'DEF-26 (known)' });
  await cf.context.close();
}
if (on('B7-24')) {
  const g = await requestGrant(p, { scope: 'batch-pipeline', actions: ['CONFIGURE'], minutes: 15, reason: `Audit B7-24 ${tag}: to be rejected` });
  const a = await login('approver-1'); await a.page.goto(g.url);
  const [r] = await Promise.all([a.page.waitForNavigation().catch(() => null), a.page.locator('form[name="reject"] button').first().click()]);
  const t = await mainText(a.page);
  await a.page.goto(g.url); await a.page.fill('form[name="reject"] textarea[name="comment"]', 'Not this week.'); await Promise.all([a.page.waitForLoadState('load'), a.page.locator('form[name="reject"] button').first().click()]);
  await p.goto(g.url); const t2 = await mainText(p); const s = await shot(p, '#main-panel table', 'B7-24-grant-rejected', { pad: 8 });
  const g2 = await requestGrant(p, { scope: 'batch-pipeline', actions: ['CONFIGURE'], minutes: 15, reason: `Audit B7-24 ${tag}: to be cancelled` });
  await uiCancel(p, g2.url); await p.goto(g2.url); const t3 = await mainText(p);
  row('B7-24', { roles: 'approver-1, requester', V: '✓ Reject for the designated approver, Cancel Request for the requester', G: `${/REJECTED/.test(t2) && /CANCELLED/.test(t3) ? '✓' : '✗'} reject with comment -> REJECTED, decided by approver-1, comment shown; requester cancel -> CANCELLED`, R: `✗ reject without comment -> ${r && r.status()} "${t.slice(0, 70)}": bare Error page (DEF-09)`, C: `${/Decided by approver-1/.test(t2) ? '✓' : '✗'} decision on the detail`, E: s ? '✓ B7-24-grant-rejected' : '✗', defect: 'DEF-09 (known)' });
  await a.context.close();
}
if (on('B7-25')) {
  const g = await requestGrant(p, { scope: 'team/secret-job', actions: ['CONFIGURE'], minutes: 15, reason: `Audit B7-25 ${tag}: unreadable job` });
  const s = await shot(p, '#main-panel', 'B7-25-unreadable-scope', { pad: 8 });
  row('B7-25', { roles: 'requester', V: 'n.a.', G: `${g.error ? '✓' : '✗'} CONFIGURE on team/secret-job (not readable for him) refused, nothing stored`, R: `${g.error && /No such job/.test(g.error) ? '✗' : '✗'} "${(g.error || '').slice(0, 80)}" (the right answer: it does not reveal the job) on a bare Error page that drops the form (DEF-09)`, C: 'n.a.', E: s ? '✓ B7-25-unreadable-scope' : '✗', defect: 'DEF-09 (known)' });
}
if (on('B7-27')) {
  const J = 'batch-upstream';
  const last = (await job(J, 'lastSuccessfulBuild[number]')).lastSuccessfulBuild.number;
  await p.goto(`${BASE}/job/${J}/${last}/`); const before = (await sidebar(p)).includes('Replay');
  const g = await requestGrant(p, { scope: J, actions: ['CONFIGURE'], minutes: 15, reason: `Audit B7-27 ${tag}: Replay under a window` });
  await decide(g.url, 'approve', 'ok');
  await p.goto(`${BASE}/job/${J}/${last}/`); const sb = await sidebar(p);
  const s = await shot(p, '#side-panel #tasks, #side-panel', 'B7-27-replay-under-window', { pad: 8 });
  const n0 = (await job(J, 'nextBuildNumber')).nextBuildNumber;
  await p.locator('#side-panel a:has-text("Replay")').click(); await p.waitForLoadState('load');
  await Promise.all([p.waitForNavigation().catch(() => null), p.locator('button[name="Submit"], button:has-text("Run")').first().click()]);
  const ran = await waitFor(async () => (await job(J, 'nextBuildNumber')).nextBuildNumber > n0, { timeout: 60000 });
  await uiRevoke(gidOf(g.url));
  row('B7-27', { roles: 'requester', V: `${!before && sb.includes('Replay') ? '✓' : '✗'} Replay absent before (${before}), present under the CONFIGURE window`, G: `${ran ? '✓' : '✗'} Replay ran #${n0} (uncontrolled job), as documented (LIMITATIONS 33)`, R: 'n.a.', C: 'n.a.', E: s ? '✓ B7-27-replay-under-window' : '✗' });
}
await close();
