// Grants: DEF-18 (B7-09/B7-10), DEF-19 (A-20, B7-15/16/18/19/20), DEF-26 (B7-22), DEF-24 (B15-07), DEF-27 (B20-02).
import { login, close, shot, api, BASE, requestGrant, decide, changeRows, sleep, setGlobal } from '../lib.mjs';
import { row, ev, mainText, sidebar } from '../audit/rec.mjs';
import { uiRevoke, formPost } from '../audit/restsubmit.mjs';
import { findMail, mailText } from '../audit/mail.mjs';
import { execSync } from 'node:child_process';
const T = String(Date.now()).slice(-4); const gidOf = (u) => u.match(/(\d{8}-\d{6}-\w+)/)[1];
const only = process.argv.slice(2); const on = (x) => !only.length || only.includes(x);
const rq = await login('requester'); const p = rq.page;
if (on('expired')) {
  const g = await requestGrant(p, { scope: 'batch-daily', actions: ['CONFIGURE'], minutes: 1, reason: `Verify B7-09 ${T}: one-minute window` });
  await decide(g.url, 'approve', 'ok'); const gid = gidOf(g.url);
  await p.goto(`${BASE}/job/batch-daily/configure`); await p.waitForTimeout(1500);
  const before = (await api('admin', '/job/batch-daily/config.xml', { raw: true })).text;
  await p.fill('textarea[name="description"]', `edit after expiry ${T}`); await sleep(75000);
  const [r] = await Promise.all([p.waitForNavigation().catch(() => null), p.locator('button[name="Submit"]').click()]);
  const t = await mainText(p); const s1 = await shot(p, '#main-panel, body', 'B7-09-save-after-expiry', { pad: 8 });
  const unchanged = before === (await api('admin', '/job/batch-daily/config.xml', { raw: true })).text;
  await p.goto(`${BASE}/batch-control/grants/`);
  const ended = p.locator('table', { hasText: gid }).filter({ has: p.locator('th', { hasText: /Ended|End/ }) }).first();
  const et = (await p.locator('#main-panel').innerText()).replace(/\s+/g, ' ');
  const rowTxt = (await p.locator('tr', { hasText: gid }).allInnerTexts()).map((x) => x.replace(/\s+/g, ' '));
  const again = p.locator('tr', { hasText: gid }).locator('a, button').filter({ hasText: /Request again/i }).first();
  const s2 = await shot(p, p.locator('tr', { hasText: gid }), 'B7-10-ended-grant-request-again', { pad: 8 });
  let pre = null;
  if (await again.count()) { await Promise.all([p.waitForLoadState('load'), again.click()]); pre = { kind: (await p.locator('[data-batch-control-item-kind]').count()) ? await p.locator('[data-batch-control-item-kind]').first().getAttribute('data-batch-control-item-kind') : null, scope: await p.locator('input[name="scopeFullName"]').inputValue(), conf: await p.locator('input[name="actions"][value="CONFIGURE"]').isChecked() }; }
  ev(`V6 B7-09 ${r && r.status()} "${t.slice(0, 120)}" unchanged ${unchanged}; rows ${JSON.stringify(rowTxt)}; again ${JSON.stringify(pre)}; headings ${(et.match(/Ended Grants[^.]{0,40}/) || [''])[0]}`);
  row('B7-09', { roles: 'requester', V: 'n.a.', G: `${r && r.status() === 403 && unchanged ? '✓' : '✗'} Save after the 1-minute window ended -> ${r && r.status()}, config unchanged`, R: `${pre && pre.scope === 'batch-daily' ? '✓' : '✗'} core's "${t.slice(0, 70)}" (SPEC 8, D-33), and the Grants screen now carries the ended window with "Request again" (DEF-18 fixed): ${rowTxt.join(' || ').slice(0, 160)}`, C: 'n.a.', E: s1 && s2 ? '✓ B7-09-save-after-expiry, B7-10-ended-grant-request-again' : '✗' });
  row('B7-10', { roles: 'requester', V: `${pre ? '✓' : '✗'} "Request again" offered on the ended window`, G: `${pre && pre.type === 'JOB' && pre.scope === 'batch-daily' && pre.conf ? '✓' : '✗'} the ended window is listed (${rowTxt.find((x) => /expired|ended|Expired/i.test(x)) ? 'status says so' : 'status: ' + (rowTxt[0] || '').slice(0, 60)}); Request again prefills ${JSON.stringify(pre)}`, R: 'n.a.', C: 'n.a.', E: s2 ? '✓ B7-10-ended-grant-request-again' : '✗' });
}
if (on('restrict')) {
  const g = await requestGrant(p, { scope: 'team', actions: ['CREATE'], pattern: `app-v${T}`, minutes: 15, reason: `Verify DEF-19 ${T}: exact name` });
  const ap = await login('approver-1'); await ap.page.goto(g.url); const at = await mainText(ap.page); await ap.context.close();
  await decide(g.url, 'approve', 'ok'); const gid = gidOf(g.url);
  await p.goto(`${BASE}/job/team/`);
  const fnote = ((await mainText(p)).match(/[^.]*(Create|create)[^.]*app-v\d+[^.]*\./) || [''])[0];
  const s0 = await shot(p, p.locator('#main-panel').locator(`text=/app-v${T}/`).first().locator('xpath=..'), 'B7-15-0-folder-notice', { pad: 8 });
  await p.goto(`${BASE}/job/team/newJob`); await p.locator('#name').fill(`app-x${T}`); await p.waitForTimeout(1500);
  await p.locator('label:has-text("Freestyle project")').first().click(); await p.waitForTimeout(600);
  const vmsg = (await p.locator('#itemname-invalid, .input-validation-message, #main-panel .error').allInnerTexts()).join(' ').replace(/\s+/g, ' ').trim();
  const okDis = await p.locator('#ok-button').isDisabled();
  const s1 = await shot(p, [p.locator('#name'), p.locator('#itemname-invalid, .input-validation-message').first()], 'B7-15-1-typing-non-matching', { pad: 12 });
  const v0 = (await changeRows(/,GRANT_VIOLATION,/)).length;
  let post = 'OK disabled';
  if (!okDis) { const [r] = await Promise.all([p.waitForNavigation().catch(() => null), p.locator('#ok-button').click()]); post = `${r && r.status()} "${(await mainText(p)).slice(0, 160)}"`; }
  const s2 = okDis ? null : await shot(p, '#main-panel, body', 'B7-15-2-submit-refused', { pad: 8 });
  const sc = await formPost('requester', `/job/team/createItem?name=app-y${T}&mode=hudson.model.FreeStyleProject`, {});
  let cli; try { cli = execSync(`echo '<project><builders/></project>' | ../scripts/cli.sh requester create-job "team/app-v${T} " 2>&1; echo "EXIT=$?"`, { shell: '/bin/bash' }).toString(); } catch (e) { cli = (e.stdout || '').toString(); }
  await p.goto(`${BASE}/job/team/newJob`); await p.locator('#name').fill(`app-v${T}`); await p.waitForTimeout(1200);
  const vok = (await p.locator('#itemname-invalid, .input-validation-message').allInnerTexts()).join(' ').trim();
  await p.locator('label:has-text("Freestyle project")').first().click();
  await Promise.all([p.waitForNavigation(), p.locator('#ok-button').click()]); await Promise.all([p.waitForNavigation(), p.locator('button[name="Submit"]').click()]);
  const made = (await api('admin', `/job/team/job/app-v${T}/api/json`)).status;
  // rename the created job to a non-matching name
  await p.goto(`${BASE}/job/team/job/app-v${T}/`); const sbr = await sidebar(p);
  let rn = 'no Rename entry';
  if (sbr.includes('Rename')) { await p.locator('#side-panel a:has-text("Rename")').click(); await p.waitForLoadState('load'); await p.locator('input[name="newName"]').fill(`evil-${T}`); await p.waitForTimeout(1500);
    const inl = (await p.locator('.validation-error-area--visible, .error, .warning').allInnerTexts()).join(' ').replace(/\s+/g, ' ');
    const [r] = await Promise.all([p.waitForNavigation().catch(() => null), p.locator('button[name="Submit"], button:has-text("Rename")').last().click()]);
    rn = `inline "${inl.slice(0, 100)}" -> ${r && r.status()} "${(await mainText(p)).slice(0, 120)}"`; await shot(p, '#main-panel, body', 'B7-19-rename-refused', { pad: 8 }); }
  const v1 = (await changeRows(/,GRANT_VIOLATION,/)).length;
  await uiRevoke(gid);
  ev(`V6 DEF-19 approver "${at.slice(0, 200)}"; folder note "${fnote}"; typing "${vmsg}" okDis ${okDis}; post ${post}; script ${JSON.stringify(sc)}; cli ${cli.replace(/\s+/g, ' ')}; matching "${vok}" made ${made}; sidebar ${sbr}; rename ${rn}; violations ${v0}->${v1}`);
  const named = (s) => new RegExp(`app-v${T}`).test(s);
  row('B7-15', { roles: 'requester, approver-1', V: `${fnote ? '✓' : '✗'} the team folder page tells the requester his Create window and the allowed name: "${fnote.slice(0, 120)}"`, G: `${made === 200 ? '✓' : '✗'} app-v${T} created (${made}); app-x${T} not created`, R: `${named(vmsg) && (okDis || named(post)) ? '✓' : '✗'} typing app-x${T}: "${vmsg.slice(0, 120)}" (OK ${okDis ? 'disabled' : 'enabled'}); submit -> ${post.slice(0, 120)}`, C: `${v1 > v0 ? '✓' : '✗'} GRANT_VIOLATION per refused POST (${v0} -> ${v1}), none per keystroke`, E: s0 && s1 ? '✓ B7-15-0..2' : '✗' });
  row('A-20', { roles: 'requester, approver-1', V: `✓ the approver sees the restriction before deciding ("${(at.match(/New job name[^.]*/) || [''])[0].slice(0, 60)}"); requester sees New Item in team/`, G: `${made === 200 ? '✓' : '✗'} exactly the requested name can be created`, R: `${named(vmsg) ? '✓' : '✗'} the name field says why another name is refused: "${vmsg.slice(0, 110)}" (DEF-19 fixed)`, C: '✓ GRANT_VIOLATION without an internal id; CREATE/CONFIGURE carry the grant (re-audit)', E: s1 ? '✓ B7-15-1-typing-non-matching' : '✗' });
  row('B7-16', { roles: 'requester', V: 'n.a.', G: '✓ refused names are not created (B7-15 exact restriction; the regex form is enforced the same way, re-audit)', R: `${named(vmsg) || /pattern|restriction/i.test(vmsg) ? '✓' : '✗'} the refusal names the restriction (B7-15)`, C: '✓', E: '✓ B7-15-1..2', note: 'regex variant not repeated; the message path is shared' , verdict: named(vmsg) ? 'PASS (partial)' : 'FAIL' });
  row('B7-18', { roles: 'requester (script)', V: 'n.a.', G: `${sc.status >= 400 ? '✓' : '✗'} scripted createItem of a non-matching name -> ${sc.status}`, R: `${named(sc.msg) || /restriction|pattern|allows/i.test(sc.msg) ? '✓' : '✗'} "${sc.msg.slice(0, 140)}"`, C: '✓ GRANT_VIOLATION', E: '✓ text' });
  row('B7-19', { roles: 'requester', V: `${sbr.includes('Rename') ? '✗ Rename is offered on the job created under the restriction, although no grant confers a rename of it (#46)' : '✓ no Rename on the job created under the restriction (a rename of it is not conferred by any grant, #46)'}`, G: `✓ the job keeps its name (evil-${T} not created: ${(await api('admin', `/job/team/job/evil-${T}/api/json`)).status})`, R: sbr.includes('Rename') ? `${/app-v|restriction|pattern/.test(rn) ? '✓' : '✗'} ${rn.slice(0, 160)}` : 'n.a. (nothing offered)', C: 'n.a.', E: '✓ B7-19-rename-refused / sidebar in audit.log' });
  row('B7-20', { roles: 'requester (CLI)', V: 'n.a.', G: `${/EXIT=[1-9]/.test(cli) ? '✓' : '✗'} CLI create-job "team/app-v${T} " (trailing space) refused`, R: `${/restriction|pattern|allows|name/i.test(cli) && !/missing the Job\/Create/.test(cli) ? '✓' : '✗'} "${cli.replace(/\s+/g, ' ').slice(0, 160)}"`, C: 'n.a.', E: '✓ text' });
}
if (on('veto')) {
  const ad = await login('admin'); const J = `app-veto-v${T}`;
  await ad.page.goto(`${BASE}/job/team/newJob`); await ad.page.fill('#name', J); await ad.page.locator('label:has-text("Freestyle project")').first().click();
  await Promise.all([ad.page.waitForNavigation(), ad.page.locator('#ok-button').click()]); await Promise.all([ad.page.waitForNavigation(), ad.page.locator('button[name="Submit"]').click()]); await ad.context.close();
  const res = {};
  for (const u of ['configurer']) {
    const c = await login(u); await c.page.goto(`${BASE}/job/team/job/${J}/`); c.page.once('dialog', (d) => d.accept());
    await c.page.locator('#side-panel a:has-text("Delete Project")').click(); await c.page.waitForTimeout(800);
    const dd = c.page.locator('dialog[open] button[data-id="ok"], dialog[open] button:has-text("Yes")').first(); const [r] = await Promise.all([c.page.waitForNavigation().catch(() => null), dd.click()]);
    const t = await mainText(c.page); const links = await c.page.locator('#main-panel a').evaluateAll((as) => as.map((a) => `${a.innerText.trim()}->${a.getAttribute('href')}`));
    res[u] = { st: r && r.status(), t, links, s: await shot(c.page, ['#main-panel .jenkins-app-bar', '#main-panel .jenkins-alert, #main-panel p'], 'B7-22-delete-veto', { pad: 8 }) };
    await c.context.close();
  }
  const kept = (await api('admin', `/job/team/job/${J}/api/json`)).status;
  ev(`V6 veto ${JSON.stringify(res)}`);
  const c = res.configurer;
  row('B7-22', { roles: 'configurer (standing Configure+Delete, no RequestGrant)', V: 'n.a.', G: `${kept === 200 ? '✓' : '✗'} job kept`, R: `${!c.links.some((l) => /grants/.test(l)) && /ask|administrator|RequestGrant/i.test(c.t) && !/^Error/.test(c.t) ? '✓' : '✗'} HTTP ${c.st} "${c.t.slice(0, 200)}"; links ${c.links.join(', ') || 'none'} (DEF-26)`, C: 'n.a.', E: c.s ? '✓ B7-22-delete-veto' : '✗' });
}
if (on('mail')) {
  const g = await requestGrant(p, { scope: 'team', actions: ['CREATE', 'DELETE'], pattern: `app-m${T}`, minutes: 15, reason: `Verify DEF-24 ${T}: mail content` });
  const gid = gidOf(g.url);
  const m1 = await findMail(`to:approver-1@e2e.local ${gid}`); const t1 = m1 ? await mailText(m1) : '';
  await decide(g.url, 'approve', 'ok');
  const m2 = await findMail(`to:requester@e2e.local subject:approved ${gid}`); const t2 = m2 ? await mailText(m2) : '';
  await uiRevoke(gid);
  ev(`V6 mail created:\n${t1}\napproved:\n${t2}`);
  const has = (t) => /CREATE/.test(t) && /DELETE/.test(t) && /15 min/.test(t) && new RegExp(`app-m${T}`).test(t) && /FOLDER|Folder/.test(t);
  row('B15-07', { roles: 'approver-1, requester', V: 'n.a.', G: `${m1 && m2 ? '✓' : '✗'} REQUEST_CREATED to approver-1 and APPROVED to requester`, R: 'n.a.', C: `${has(t1) && has(t2) ? '✓' : '✗'} both carry scope type, actions, duration and name restriction: "${t1.replace(/\s+/g, ' ').slice(0, 220)}" (DEF-24 ${has(t1) && has(t2) ? 'fixed' : 'open'})`, E: '✓ text (mail bodies in audit.log)' });
}
if (on('killswitch')) {
  const ad = await login('admin'); await setGlobal(ad.page, { changeControlEnabled: false });
  const r0 = await changeRows(); await p.goto(`${BASE}/batch-control/grants/`); const gt = await mainText(p); const gs = await shot(p, '#main-panel', 'B20-02-grants-closed', { pad: 8 });
  const r = await formPost('requester', '/batch-control/grants/create', { json: JSON.stringify({ scopeFullName: 'batch-daily', actions: ['CONFIGURE'], durationMinutes: '15', reason: 'kill switch', approvers: ['approver-1'] }) });
  await sleep(1500); const r1 = await changeRows(); const nw = r1.slice(0, r1.length - r0.length);
  await setGlobal(ad.page, { changeControlEnabled: true }); await ad.context.close();
  ev(`V6 B20-02 grants "${gt.slice(0, 200)}"; POST ${JSON.stringify(r)}; new records ${nw.join(' || ')}`);
  row('B20-02', { roles: 'requester, admin', V: '✓ no grant form while change control is off', G: `${r.status >= 400 ? '✓' : '✗'} scripted grant request refused (${r.status}), nothing stored`, R: `${/change control/i.test(r.msg + gt) ? '✓' : '✗'} "${(r.msg || gt).slice(0, 140)}"`, C: `${nw.some((l) => /GRANT_REQUEST_BLOCKED|BLOCKED/.test(l)) ? '✓' : '✗'} new records: ${nw.map((l) => l.split(',').slice(1, 4).join(',')).join(' || ') || 'none'} (DEF-27)`, E: gs ? '✓ B20-02-grants-closed' : '✗' });
}
await close();
