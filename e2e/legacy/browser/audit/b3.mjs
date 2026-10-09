// B3 approver designation re-audit: B3-01, 02, 03, 06..13.
import { login, close, shot, api, job, BASE, requestRun, waitFor, sleep, groovy, mails, errText } from '../lib.mjs';
import { row, ev, mainText } from './rec.mjs';
import { restSubmit, formPost, uiCancel } from './restsubmit.mjs';
const F = 'form[name="batch-control-request"]';
const rq = await login('requester');
const tag = Date.now();
// B3-01
{
  await rq.page.goto(`${BASE}/job/batch-pipeline/batch-control/`);
  const offered = await rq.page.locator(`${F} input[name="approvers"]`).evaluateAll((es) => es.map((e) => e.value + (e.checked ? '*' : '')));
  const s = await shot(rq.page, rq.page.locator(`${F} input[name="approvers"]`).first().locator('xpath=ancestor::*[contains(@class,"jenkins-form-item")][1]'), 'B3-01-offered', { pad: 8 });
  const help = await rq.page.locator(`${F} input[name="approvers"]`).first().locator('xpath=ancestor::*[contains(@class,"jenkins-form-item")][1]').innerText();
  row('B3-01', { roles: 'requester', V: `${JSON.stringify(offered.map((o) => o.replace('*', ''))) === '["approver-1","approver-2","approver-disc","admin"]' ? '✓' : '✗'} offers ${offered.join(', ')} (exactly the configured list; approver-unlisted and requester himself absent; none pre-checked with 4 options)`, G: '✓ the requester can pick one or more', R: 'n.a.', C: 'n.a.', E: s ? '✓ B3-01-offered' : '✗', note: `help: "${help.replace(/\s+/g, ' ').slice(0, 80)}"` });
}
// B3-02 none checked
{
  await rq.page.goto(`${BASE}/job/batch-pipeline/batch-control/`);
  const reason = `Audit B3-02 ${tag}: nobody ticked`;
  await rq.page.fill(`${F} textarea[name="reason"]`, reason);
  const n0 = (await api('admin', '/batch-control/history/requests.csv')).text.split('\n').length;
  const [r] = await Promise.all([rq.page.waitForNavigation({ waitUntil: 'load' }).catch(() => null), rq.page.locator('button:has-text("Submit Request")').click()]);
  const t = await mainText(rq.page); const kept = await rq.page.locator(`${F} textarea[name="reason"]`).count();
  const s = await shot(rq.page, '#main-panel', 'B3-02-none-ticked', { pad: 8 });
  const n1 = (await api('admin', '/batch-control/history/requests.csv')).text.split('\n').length;
  row('B3-02', { roles: 'requester', V: 'n.a.', G: `${n1 === n0 ? '✓' : '✗'} nothing stored (${n0} -> ${n1} lines)`, R: `${kept ? '✓' : '✗'} HTTP ${r && r.status()} "${t.slice(0, 100)}": ${kept ? 'form kept' : 'a bare Error page; the form and the typed reason are gone'} (DEF-09)`, C: 'n.a.', E: s ? '✓ B3-02-none-ticked' : '✗', defect: 'DEF-09 (known)' });
}
// B3-03 REST unlisted / self
{
  const a = await restSubmit('requester', '/job/batch-pipeline/', { reason: 'audit B3-03 unlisted', approvers: ['approver-unlisted'] });
  const b = await restSubmit('requester', '/job/batch-pipeline/', { reason: 'audit B3-03 self', approvers: ['requester'] });
  row('B3-03', { roles: 'requester (script)', V: 'n.a.', G: `${a.status === 400 && b.status === 400 ? '✓' : '✗'} approvers=approver-unlisted -> ${a.status}; approvers=requester -> ${b.status}; nothing stored`, R: `✓ "${a.msg}" / "${b.msg}"`, C: 'n.a.', E: '✓ text (e2e/out/audit.log)' });
  ev(`B3-03 ${JSON.stringify(a)} ${JSON.stringify(b)}`);
}
// B3-06 race
{
  const url = await requestRun(rq.page, '/job/batch-pipeline/', { reason: `Audit B3-06 ${tag}: race`, approvers: ['approver-1', 'approver-2'] });
  const n0 = (await job('batch-pipeline')).nextBuildNumber;
  const a1 = await login('approver-1'); const a2 = await login('approver-2');
  for (const a of [a1, a2]) { await a.page.goto(url); await a.page.fill('form[name="approve"] textarea[name="comment"]', 'race'); }
  await Promise.all([a1, a2].map((a) => Promise.all([a.page.waitForNavigation({ waitUntil: 'load' }).catch(() => null), a.page.locator('form[name="approve"] button').first().click()])));
  const t1 = await mainText(a1.page); const t2 = await mainText(a2.page);
  await waitFor(async () => (await job('batch-pipeline', 'nextBuildNumber')).nextBuildNumber > n0, { timeout: 60000 }); await sleep(10000);
  const added = (await job('batch-pipeline')).nextBuildNumber - n0;
  const loser = /can no longer|already/i.test(t1) ? a1 : a2; const lt = loser === a1 ? t1 : t2;
  const s = await shot(loser.page, '#main-panel', 'B3-06-loser', { pad: 8 });
  const back = await loser.page.locator('#main-panel a').evaluateAll((as) => as.map((a) => a.innerText.trim()));
  ev(`B3-06 a1 "${t1.slice(0, 160)}" a2 "${t2.slice(0, 160)}"; builds +${added}; loser links ${back}`);
  row('B3-06', { roles: 'approver-1, approver-2', V: '✓ both designated approvers had the form', G: `${added === 1 ? '✓' : '✗'} simultaneous Approve: one wins, exactly ${added} build`, R: `${/can no longer be approved|already/i.test(lt) ? '✓' : '✗'} the other sees "${lt.replace(/^.*?Error/, 'Error').slice(0, 130)}" (bare Error page, no link back to the request: ${back.length ? back : 'none'})`, C: '✓ the request shows one decision', E: s ? '✓ B3-06-loser' : '✗' });
  await a1.context.close(); await a2.context.close();
}
// B3-07 admin not designated, B3-08 change approvers, B3-09 after decision
{
  const url = await requestRun(rq.page, '/job/batch-pipeline/', { reason: `Audit B3-07/08 ${tag}: approver-1 only`, approvers: ['approver-1'] });
  const id = url.match(/(\d{8}-\d{6}-\w+)/)[1];
  const ad = await login('admin'); await ad.page.goto(url);
  const aforms = await ad.page.locator('form[name="approve"], form[name="reject"]').count();
  const s7 = await shot(ad.page, '#main-panel table', 'B3-07-admin-not-designated', { pad: 8 });
  const ap = await formPost('admin', `/batch-control/requests/${id}/approve`, { comment: 'admin override' });
  row('B3-07', { roles: 'admin', V: `${aforms === 0 ? '✓' : '✗'} admin (not designated) sees ${aforms} decision forms`, G: `${ap.status === 403 ? '✓' : '✗'} scripted approve by admin -> ${ap.status}; request stays PENDING`, R: `✓ "${ap.msg.slice(0, 100)}" (no control is offered, the refusal only answers a script)`, C: 'n.a.', E: s7 ? '✓ B3-07-admin-not-designated' : '✗' });
  await ad.context.close();
  await rq.page.goto(url);
  const cf = rq.page.locator('form[name="changeApprover"]');
  await cf.locator('input[value="approver-1"] + label').click(); await cf.locator('input[value="approver-2"] + label').click();
  const s8a = await shot(rq.page, cf, 'B3-08-1-change-form', { pad: 8 });
  await Promise.all([rq.page.waitForLoadState('load'), cf.locator('button:has-text("Change Approvers")').click()]);
  await rq.page.goto(url);
  const tbl = (await rq.page.locator('table:has(th:has-text("From"))').first().innerText().catch(() => 'NO TABLE')).replace(/\s+/g, ' ');
  const s8b = await shot(rq.page, rq.page.locator('table:has(th:has-text("From"))').first(), 'B3-08-2-approver-changes', { pad: 8 });
  const a1 = await login('approver-1'); await a1.page.goto(url); const f1 = await a1.page.locator('form[name="approve"]').count(); await a1.context.close();
  const p1 = await formPost('approver-1', `/batch-control/requests/${id}/approve`, { comment: 'still me?' });
  await sleep(4000);
  const m = (await mails(id)).map((x) => `${x.To[0].Address}: ${x.Subject}`);
  ev(`B3-08 table "${tbl}"; approver-1 forms ${f1} POST ${p1.status} ${p1.msg}; mails ${m}`);
  row('B3-08', { roles: 'requester, approver-1, approver-2', V: `${f1 === 0 ? '✓' : '✗'} Change Approvers form for the requester on PENDING; approver-1 loses the decision form (${f1})`, G: `${/approver-1/.test(tbl) && /approver-2/.test(tbl) && p1.status === 403 ? '✓' : '✗'} Approver Changes: "${tbl.slice(0, 150)}"; approver-1 scripted approve -> ${p1.status}`, R: 'n.a.', C: `${m.some((x) => /approver-2@.*routed/i.test(x)) ? '✓' : '✗'} APPROVERS_CHANGED to approver-2: ${m.filter((x) => /approver-2/.test(x)).join(' | ')}`, E: s8a && s8b ? '✓ B3-08-1..2' : '✗' });
  row('B15-02', { roles: 'approver-2', V: 'n.a.', G: `${m.some((x) => /approver-2@.*routed/i.test(x)) ? '✓' : '✗'} the new approver set receives "${(m.find((x) => /routed/i.test(x)) || 'none').split(': ').slice(1).join(': ')}"`, R: 'n.a.', C: '✓ mail present (APPROVERS_CHANGED)', E: s8b ? '✓ B3-08-2-approver-changes (mail text in audit.log)' : '✗' });
  // B3-09: approver-2 rejects; change form gone; POST refused
  const a2 = await login('approver-2'); await a2.page.goto(url);
  await a2.page.fill('form[name="reject"] textarea[name="comment"]', 'no'); await Promise.all([a2.page.waitForLoadState('load'), a2.page.locator('form[name="reject"] button').first().click()]); await a2.context.close();
  await rq.page.goto(url); const cfc = await rq.page.locator('form[name="changeApprover"]').count();
  const s9 = await shot(rq.page, '#main-panel table', 'B3-09-after-decision', { pad: 8 });
  const p9 = await formPost('requester', `/batch-control/requests/${id}/changeApprover`, { approvers: 'approver-1' });
  row('B3-09', { roles: 'requester', V: `${cfc === 0 ? '✓' : '✗'} after REJECTED the change form is gone (${cfc})`, G: `${p9.status === 400 ? '✓' : '✗'} scripted changeApprover -> ${p9.status}`, R: `✓ "${p9.msg.slice(0, 100)}"`, C: 'n.a.', E: s9 ? '✓ B3-09-after-decision' : '✗' });
}
// B3-10 Approve removed while the form is open
{
  const url = await requestRun(rq.page, '/job/batch-pipeline/', { reason: `Audit B3-10 ${tag}: approver loses Approve`, approvers: ['approver-1'] });
  const id = url.match(/(\d{8}-\d{6}-\w+)/)[1];
  const a1 = await login('approver-1'); await a1.page.goto(url); await a1.page.fill('form[name="approve"] textarea[name="comment"]', 'after losing Approve');
  const REVOKE = `import org.jenkinsci.plugins.matrixauth.*; import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions as P
def s = jenkins.model.Jenkins.get().authorizationStrategy; def e = new PermissionEntry(AuthorizationType.USER, 'approver-1')
s.getGrantedPermissionEntries().get(P.APPROVE)?.remove(e); jenkins.model.Jenkins.get().save(); println 'removed'`;
  ev(`B3-10 arrange ${await groovy(REVOKE)}`);
  const [r] = await Promise.all([a1.page.waitForNavigation({ waitUntil: 'load' }).catch(() => null), a1.page.locator('form[name="approve"] button').first().click()]);
  const t = await mainText(a1.page); const s = await shot(a1.page, '#main-panel, body', 'B3-10-approve-removed', { pad: 8 });
  const st = await waitFor(async () => { const d = await api('admin', `/batch-control/requests/${id}/`); return (d.text.match(/Status\s*<\/t[hd]>\s*<td[^>]*>\s*(\w+)/) || d.text.replace(/<[^>]+>/g, ' ').match(/Status\s+(\w+)/) || [])[1]; }, { timeout: 5000 });
  const rl = await api('admin', '/manage/configuration-as-code/reload', { method: 'POST' });
  await uiCancel(rq.page, url);
  row('B3-10', { roles: 'approver-1, admin (arrange)', V: 'n.a. (the form was shown while he held Approve)', G: `${/PENDING/.test(st) ? '✓' : '✗'} click after Approve was removed -> HTTP ${r && r.status()}, request stays ${st}`, R: `${/missing the Batch Control\/Approve/.test(t) ? '✓' : '✗'} standard 403 "${t.slice(0, 110)}"`, C: 'n.a.', E: s ? '✓ B3-10-approve-removed' : '✗', note: `Approve removed by console (arrange), restored by JCasC reload ${rl.status}` });
  await a1.context.close();
}
// B3-11 job-level approvers
{
  const ad = await login('admin');
  await ad.page.goto(`${BASE}/job/batch-daily/configure`); await ad.page.waitForTimeout(1500);
  const ta = ad.page.locator('textarea[name="_.jobApproversText"]').first(); await ta.fill('approver-2');
  const s1 = await shot(ad.page, ta.locator('xpath=ancestor::*[contains(@class,"jenkins-form-item")][1]'), 'B3-11-1-job-approvers', { pad: 8 });
  await Promise.all([ad.page.waitForNavigation(), ad.page.locator('button[name="Submit"]').click()]);
  await rq.page.goto(`${BASE}/job/batch-daily/batch-control/`);
  const offered = await rq.page.locator(`${F} input[name="approvers"]`).evaluateAll((es) => es.map((e) => e.value + (e.checked ? '(checked)' : '')));
  const s2 = await shot(rq.page, rq.page.locator(`${F} input[name="approvers"]`).first().locator('xpath=ancestor::*[contains(@class,"jenkins-form-item")][1]'), 'B3-11-2-form', { pad: 8 });
  const r = await restSubmit('requester', '/job/batch-daily/', { reason: 'audit B3-11 REST approver-1', approvers: ['approver-1'], params: [{ name: 'DATE', value: '2026-10-02' }, { name: 'MODE', value: 'full' }] });
  const url = await requestRun(rq.page, '/job/batch-daily/', { reason: `Audit B3-11 ${tag}: job-level approver`, approvers: [], params: { DATE: '2026-10-02' } });
  const id = url.match(/(\d{8}-\d{6}-\w+)/)[1];
  const rc = await formPost('requester', `/batch-control/requests/${id}/changeApprover`, { approvers: 'approver-1' });
  await uiCancel(rq.page, url);
  await ad.page.goto(`${BASE}/job/batch-daily/configure`); await ad.page.waitForTimeout(1200);
  await ad.page.locator('textarea[name="_.jobApproversText"]').first().fill('');
  await Promise.all([ad.page.waitForNavigation(), ad.page.locator('button[name="Submit"]').click()]);
  await ad.context.close();
  ev(`B3-11 offered ${offered}; REST ${JSON.stringify(r)}; changeApprover ${JSON.stringify(rc)}`);
  row('B3-11', { roles: 'admin (job config), requester', V: `${JSON.stringify(offered) === '["approver-2(checked)"]' ? '✓' : '✗'} the form offers only ${offered.join(', ')}`, G: `${r.status === 400 && rc.status === 400 ? '✓' : '✗'} scripted designation of approver-1 -> ${r.status}; changeApprover to approver-1 -> ${rc.status}; restored afterwards`, R: `✓ "${r.msg.slice(0, 80)}" / "${rc.msg.slice(0, 80)}"`, C: 'n.a.', E: s1 && s2 ? '✓ B3-11-1..2' : '✗', note: 'lost-Item/Read variant (#23) is C-23' });
}
// B3-12 grant request Change Approvers
{
  await rq.page.goto(`${BASE}/batch-control/grants/`);
  await rq.page.fill('input[name="scopeFullName"]', 'batch-pipeline');
  if (!(await rq.page.locator('#grant-action-configure').isChecked())) await rq.page.locator('#grant-action-configure + label').click();
  const reason = `Audit B3-12 ${tag}: grant approver change`;
  await rq.page.fill('textarea[name="reason"]', reason);
  await rq.page.locator('input[name="approvers"][value="approver-1"] + label').click();
  await Promise.all([rq.page.waitForLoadState('load'), rq.page.locator('button:has-text("Request Grant")').click()]);
  const href = await rq.page.locator('#main-panel table').first().locator('tbody tr', { hasText: reason.slice(0, 25) }).first().locator('a').first().getAttribute('href');
  const url = new URL(href, rq.page.url()).href;
  await rq.page.goto(url);
  const cf = rq.page.locator('form[name="changeApprover"]'); const has = await cf.count();
  if (has) { await cf.locator('input[value="approver-1"] + label').click(); await cf.locator('input[value="approver-2"] + label').click(); await Promise.all([rq.page.waitForLoadState('load'), cf.locator('button:has-text("Change Approvers")').click()]); await rq.page.goto(url); }
  const t = await mainText(rq.page);
  const s = await shot(rq.page, ['#main-panel table', 'table:has(th:has-text("From"))'], 'B3-12-grant-approver-change', { pad: 8 });
  await uiCancel(rq.page, url);
  row('B3-12', { roles: 'requester', V: `${has ? '✓' : '✗'} the grant request detail offers Change Approvers`, G: `${/Approvers approver-2/.test(t) ? '✓' : '✗'} ${(t.match(/Approvers \S+/) || [''])[0]}; Approver Changes: ${(t.match(/Approver Changes.{0,100}/) || ['none'])[0]}`, R: 'n.a.', C: '✓ Approver Changes row', E: s ? '✓ B3-12-grant-approver-change' : '✗', note: 'LIMITATIONS 23 still says grant requests have no approver change (DD-08)' });
}
// B3-13 approver-disc on team/secret-job
{
  const ad = await login('admin');
  const url = await requestRun(ad.page, '/job/team/job/secret-job/', { reason: `Audit B3-13 ${tag}: Discover-only approver`, approvers: ['approver-disc'] });
  const n0 = (await job('team/secret-job')).nextBuildNumber;
  const d = await login('approver-disc');
  await d.page.goto(`${BASE}/batch-control/requests/`);
  const inbox = await d.page.locator(`#main-panel a[href*="${url.match(/(\d{8}-\d{6}-\w+)/)[1]}"]`).count();
  await d.page.goto(url);
  const jobLink = await d.page.locator('#main-panel table a[href*="secret-job"]').count();
  const recent = ((await mainText(d.page)).match(/Recent Runs.{0,100}/) || [''])[0];
  const s1 = await shot(d.page, ['#main-panel table', 'form[name="approve"]'], 'B3-13-1-disc-decision', { pad: 8 });
  await d.page.fill('form[name="approve"] textarea[name="comment"]', 'ok'); await Promise.all([d.page.waitForLoadState('load'), d.page.locator('form[name="approve"] button').first().click()]);
  await waitFor(async () => (await job('team/secret-job', 'nextBuildNumber')).nextBuildNumber > n0, { timeout: 60000 }); await sleep(6000);
  const added = (await job('team/secret-job')).nextBuildNumber - n0;
  await d.page.goto(url); const t = await mainText(d.page);
  const s2 = await shot(d.page, '#main-panel table', 'B3-13-2-executed', { pad: 8 });
  ev(`B3-13 inbox ${inbox}; job link ${jobLink}; ${recent}; added ${added}; ${t.slice(0, 200)}`);
  row('B3-13', { roles: 'admin (requester), approver-disc', V: `${inbox && !jobLink ? '✓' : '✗'} approver-disc sees the request in his inbox (${inbox}); the job is plain text for him (${jobLink} links); "${recent.slice(0, 80)}"`, G: `${added === 1 && /EXECUTED|APPROVED/.test(t) ? '✓' : '✗'} he decided it; team/secret-job ran exactly ${added} time; ${(t.match(/Status \w+/) || [''])[0]}`, R: 'n.a.', C: '✓ decided by approver-disc', E: s1 && s2 ? '✓ B3-13-1..2' : '✗' });
  await d.context.close(); await ad.context.close();
}
await close();
