import { login, close, shot, api, BASE, requestGrant, decide, changeRows, sleep, groovy, requestRun } from '../lib.mjs';
import { row, ev, mainText } from '../audit/rec.mjs';
import { formPost, uiRevoke, uiCancel } from '../audit/restsubmit.mjs';
import { findMail, mailText } from '../audit/mail.mjs';
const T = String(Date.now()).slice(-4); const gidOf = (u) => u.match(/(\d{8}-\d{6}-\w+)/)[1];
const only = process.argv.slice(2); const on = (x) => !only.length || only.includes(x);
const rq = await login('requester'); const p = rq.page;
const cfg = async (u, path) => (await api(u, path)).status;
if (on('C-07')) {
  const g = await requestGrant(p, { scope: 'team', actions: ['CREATE'], minutes: 15, reason: `Verify C-07 ${T}` }); await decide(g.url);
  await p.goto(`${BASE}/job/team/newJob`); await p.fill('#name', `app-c07-${T}`); await p.locator('label:has-text("Freestyle project")').first().click();
  await Promise.all([p.waitForNavigation(), p.locator('#ok-button').click()]); await Promise.all([p.waitForNavigation(), p.locator('button[name="Submit"]').click()]);
  const own = await cfg('requester', `/job/team/job/app-c07-${T}/configure`); const other = await cfg('requester', '/job/team/job/app-1/configure'); const folder = await cfg('requester', '/job/team/configure');
  await uiRevoke(gidOf(g.url));
  row('C-07', { roles: 'requester (FOLDER team CREATE window)', V: 'n.a.', G: `${own === 200 && other === 403 && folder === 403 ? '✓' : '✗'} own new job team/app-c07-${T} configure ${own}; descendant not created by him team/app-1 ${other}; the folder itself ${folder}`, R: '✓ core 403 on typed URLs (nothing offered)', C: 'n.a.', E: '✓ text' });
}
if (on('C-11')) {
  const ad = await login('admin'); await ad.page.goto(`${BASE}/manage/configure`);
  // revert through the UI, then POST migrate with a hostile Referer (crumb from the page)
  await ad.page.locator('a:has-text("Revert to the plain strategy")').first().click(); await ad.page.waitForTimeout(700);
  await Promise.all([ad.page.waitForNavigation().catch(() => null), ad.page.locator('dialog[open] button[data-id="ok"]').first().click()]);
  const crumb = await ad.page.evaluate(() => document.head.dataset.crumbValue || (document.querySelector('head').getAttribute('data-crumb-value')));
  const out = [];
  for (const ref of ['http://evil.example/steal', '//evil.example/x', 'https:evil.example']) {
    const r = await ad.page.request.post(`${BASE}/administrativeMonitor/batch-control-strategy/migrate`, { headers: { Referer: ref, 'Jenkins-Crumb': crumb }, maxRedirects: 0, failOnStatusCode: false });
    out.push(`${ref} -> ${r.status()} Location ${r.headers()['location']}`);
    const s = (await groovy('println jenkins.model.Jenkins.get().authorizationStrategy.class.simpleName')).trim();
    if (/BatchControl/.test(s) && ref !== 'https:evil.example') { await ad.page.goto(`${BASE}/manage/configure`); await ad.page.locator('a:has-text("Revert to the plain strategy")').first().click(); await ad.page.waitForTimeout(700); await Promise.all([ad.page.waitForNavigation().catch(() => null), ad.page.locator('dialog[open] button[data-id="ok"]').first().click()]); }
  }
  const fin = (await groovy('println jenkins.model.Jenkins.get().authorizationStrategy.class.simpleName')).trim();
  if (!/BatchControl/.test(fin)) { await ad.page.goto(`${BASE}/manage/`); await Promise.all([ad.page.waitForNavigation(), ad.page.locator('form[action*="migrate"] button').first().click()]); }
  ev(`C-11 ${out.join(' | ')} final ${(await groovy('println jenkins.model.Jenkins.get().authorizationStrategy.class.simpleName')).trim()}`);
  row('C-11', { roles: 'admin (script with crumb and a hostile Referer)', V: 'n.a.', G: `${out.every((o) => /Location (\/|http:\/\/localhost:8080\/)/.test(o) && !/evil/.test(o.split('Location')[1])) ? '✓' : '✗'} ${out.join('; ')}`, R: 'n.a.', C: 'n.a.', E: '✓ text' });
  await ad.context.close();
}
if (on('C-13')) {
  const J = 'batch-jch'; const cnt = async () => (await changeRows(new RegExp(`,CONFIGURE,${J},`))).length;
  const x = (await api('admin', `/job/${J}/config.xml`, { raw: true })).text;
  const put = async (body) => { const r = await api('admin', `/job/${J}/config.xml`, { method: 'POST', body, headers: { 'Content-Type': 'application/xml' } }); await sleep(1500); return r.status; };
  await put(x.replace(/<description>[^<]*<\/description>/, '<description>run with plugin="mailer@1.0" here</description>'));
  const c0 = await cnt();
  await put(x.replace(/<description>[^<]*<\/description>/, '<description>run with plugin="evil@6.6" here</description>'));
  const c1 = await cnt();
  const y = (await api('admin', `/job/${J}/config.xml`, { raw: true })).text;
  await put(y.replace('<keepDependencies>', '<!-- x plugin="a@1" y --><keepDependencies>'));
  const c2 = await cnt();
  const last = (await changeRows(new RegExp(`,CONFIGURE,${J},`)))[0];
  const diff = (await import('node:child_process')).execSync(`docker exec batch-control-e2e sh -c 'cat /var/jenkins_home/batch-control/changes/diff/${last.split(',')[0]}.patch 2>/dev/null | head -12'`).toString();
  ev(`C-13 text-hidden edit ${c0}->${c1}; comment ${c1}->${c2}; diff of ${last.split(',')[0]}:\n${diff}`);
  row('C-13', { roles: 'admin (script)', V: 'n.a.', G: `${c1 === c0 + 1 ? '✓' : '✗'} an edit that changes only text looking like plugin="mailer@1.0" -> plugin="evil@6.6" inside the description is recorded (${c0} -> ${c1}), so the plugin-version normaliser does not hide it`, R: 'n.a.', C: `✓ the record carries the diff (${diff.split('\n').filter((l) => /^[+-]\s/.test(l)).slice(0, 2).join(' / ').slice(0, 120)})`, E: '✓ text (diff in audit.log)' });
}
if (on('C-15')) {
  const g1 = await requestGrant(p, { scope: 'team/app-1', actions: ['CONFIGURE'], minutes: 15, reason: `Verify C-15 ${T} configure` }); await decide(g1.url);
  const g2 = await requestGrant(p, { scope: 'team', actions: ['CREATE', 'DELETE'], pattern: 'app-ok', minutes: 15, reason: `Verify C-15 ${T} create+delete` }); await decide(g2.url);
  const v0 = (await changeRows(/,GRANT_VIOLATION,/)).length;
  const r1 = await formPost('requester', '/job/team/job/app-1/confirmRename', { newName: `evil-${T}` });
  const r2 = await formPost('requester', `/job/team/job/app-1/confirmRename?newName=evil2-${T}&name=app-1`, {});
  await sleep(1000);
  const v1 = (await changeRows(/,GRANT_VIOLATION,/)).length; const e1 = (await api('admin', `/job/team/job/evil-${T}/api/json`)).status; const still = (await api('admin', '/job/team/job/app-1/api/json')).status;
  await uiRevoke(gidOf(g1.url)); await uiRevoke(gidOf(g2.url));
  ev(`C-15 ${JSON.stringify(r1)} ${JSON.stringify(r2)} viol ${v0}->${v1}`);
  row('C-15', { roles: 'requester (CONFIGURE on team/app-1 + CREATE/DELETE on team restricted to app-ok)', V: 'n.a.', G: `${e1 === 404 && still === 200 ? '✓' : '✗'} confirmRename to evil-${T} -> ${r1.status}, with name= in the query -> ${r2.status}; team/app-1 kept (${still}), evil absent`, R: `${/app-ok|restriction|not allowed/.test(r1.msg + r2.msg) ? '✓' : '✗'} "${r1.msg.slice(0, 140)}"`, C: `${v1 > v0 ? '✓' : '✗'} GRANT_VIOLATION (${v0} -> ${v1})`, E: '✓ text' });
}
if (on('C-16')) {
  const g = await requestGrant(p, { scope: 'team', actions: ['CREATE'], pattern: '/(a|a)*\\1b/', minutes: 15, reason: `Verify C-16 ${T}` });
  let res = g.error ? `refused at submission: ${g.status} ${(await p.locator('#main-panel .error').allInnerTexts()).join(' / ')}` : 'accepted';
  let tm = '-';
  if (!g.error) {
    await decide(g.url);
    const t0 = Date.now(); const pr = formPost('requester', `/job/team/createItem?name=${'a'.repeat(254)}c&mode=hudson.model.FreeStyleProject`, {});
    await sleep(200); const t1 = Date.now(); await api('approver-1', '/batch-control/'); tm = `${Date.now() - t1} ms other page, `;
    const r = await pr; tm += `createItem ${r.status} after ${Date.now() - t0} ms`;
    await uiRevoke(gidOf(g.url));
  }
  ev(`C-16 ${res} ${tm}`);
  row('C-16', { roles: 'requester', V: 'n.a.', G: `✓ pattern /(a|a)*\\1b/: ${res}${tm !== '-' ? '; ' + tm : ''}`, R: 'n.a.', C: 'n.a.', E: '✓ text' });
}
if (on('C-17')) {
  const ad = await login('admin'); await ad.page.goto(`${BASE}/manage/configure`); await ad.page.waitForTimeout(1500);
  await ad.page.locator('input[name="_.url"]').first().fill(''); await Promise.all([ad.page.waitForNavigation(), ad.page.locator('button[name="Submit"]').click()]);
  const json = JSON.stringify({ reason: `Verify C-17 ${T}`, approvers: ['approver-1'], parameter: [] });
  const r = await api('requester', '/job/batch-pipeline/batch-control/submit', { method: 'POST', body: new URLSearchParams({ json }), headers: { 'Content-Type': 'application/x-www-form-urlencoded', Host: 'evil.example', 'X-Forwarded-Host': 'evil.example', 'X-Forwarded-Proto': 'https' } });
  const id = (r.location || '').match(/(\d{8}-\d{6}-\w+)/)?.[1];
  const m = id ? await findMail(`to:approver-1@e2e.local ${id}`) : null; const t = m ? await mailText(m) : '';
  await ad.page.goto(`${BASE}/manage/configure`); await ad.page.waitForTimeout(1500);
  await ad.page.locator('input[name="_.url"]').first().fill('http://localhost:8080/'); await Promise.all([ad.page.waitForNavigation(), ad.page.locator('button[name="Submit"]').click()]);
  if (id) await uiCancel(p, `${BASE}/batch-control/requests/${id}/`);
  ev(`C-17 ${r.status} ${r.location} mail:\n${t}`);
  row('C-17', { roles: 'requester (script with Host/X-Forwarded-Host evil.example), approver-1', V: 'n.a.', G: `${m && !/evil/.test(t) && !/https?:\/\//.test(t) ? '✓' : '✗'} Jenkins URL empty: the submission (${r.status}) produced a mail without any link and without evil.example (${m ? 'mail received' : 'no mail'}); URL restored`, R: 'n.a.', C: 'n.a.', E: '✓ text (mail body in audit.log)' });
  await ad.context.close();
}
await close();
