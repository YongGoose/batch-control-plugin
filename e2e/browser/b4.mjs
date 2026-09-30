// B4 run requests and approval.
import { login, close, shot, api, job, waitFor, sleep, BASE, log, requestRun, mails } from './lib.mjs';
import { execSync } from 'node:child_process';
const L = 'section-b.log';
const F = 'form[name="batch-control-request"]';
const post = (user, p, fields) => api(user, p, { method: 'POST', body: new URLSearchParams(fields), headers: { 'Content-Type': 'application/x-www-form-urlencoded' } });
const errText = (t) => (t.replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ').match(/(?:Error|Access Denied) (.{0,220}?) REST API/) || [null, t.replace(/\s+/g, ' ').slice(0, 160)])[1];
const reqCount = async () => (await api('admin', '/batch-control/history/requests.csv')).text.split('\n').filter(Boolean).length;
const mainText = async (p) => (await p.locator('#main-panel, body').first().innerText()).replace(/\s+/g, ' ');

const rq = await login('requester');
const p = rq.page;
// B4-01 the form and Cancel
await p.goto(`${BASE}/job/batch-daily/`);
await p.locator('#side-panel a:has-text("Request Run")').click(); await p.waitForLoadState('load');
const items = await p.locator(`${F} .jenkins-form-label`).allInnerTexts();
const defaults = { DATE: await p.locator(`${F} .jenkins-form-item:has(.jenkins-form-label:text-is("DATE")) input[name="value"]`).inputValue(), MODE: await p.locator(`${F} select`).first().inputValue() };
await shot(p, '#main-panel', 'B4-01-1-form', { pad: 6 });
const c0 = await reqCount();
await Promise.all([p.waitForNavigation(), p.locator(`${F} a:has-text("Cancel"), ${F} button:has-text("Cancel")`).first().click()]);
log(L, `B4-01 form labels ${JSON.stringify(items)} defaults ${JSON.stringify(defaults)}; Cancel -> ${p.url()}; requests ${c0}->${await reqCount()}`);
// B4-02 empty reason
await p.goto(`${BASE}/job/batch-daily/batch-control/`);
await p.locator(`${F} input[name="approvers"][value="approver-1"] + label`).click();
await Promise.all([p.waitForNavigation().catch(() => null), p.locator('button:has-text("Submit Request")').click()]);
log(L, `B4-02 empty reason -> "${errText(await p.content())}"; requests ${c0}->${await reqCount()}`);
await shot(p, '#main-panel, body', 'B4-02', { pad: 8 });
// B4-03 limits (REST, same fields as the form)
const r3a = await post('requester', '/job/batch-pipeline/batch-control/submit', { reason: 'x'.repeat(4001), approvers: 'approver-1' });
const r3b = await post('requester', '/job/batch-pipeline/batch-control/submit', { reason: 'long param (B4-03)', approvers: 'approver-1', json: JSON.stringify({ parameter: [{ name: 'DATE', value: 'y'.repeat(10001) }] }), name: 'DATE', value: 'y'.repeat(10001) });
// and in the browser: a 4,001-character reason typed into the form
await p.goto(`${BASE}/job/batch-pipeline/batch-control/`);
await p.fill(`${F} textarea[name="reason"]`, 'r'.repeat(4001));
await p.locator(`${F} input[name="approvers"][value="approver-1"] + label`).click();
await Promise.all([p.waitForNavigation().catch(() => null), p.locator('button:has-text("Submit Request")').click()]);
const ui3 = errText(await p.content());
await shot(p, '#main-panel, body', 'B4-03', { pad: 8 });
log(L, `B4-03 reason 4001 REST -> ${r3a.status} "${errText(r3a.text)}"; param 10001 REST -> ${r3b.status} "${errText(r3b.text)}"; reason 4001 in the form -> "${ui3}"; requests ${c0}->${await reqCount()}`);
// B4-04 valid request with a secret
await p.goto(`${BASE}/job/batch-daily/batch-control/`);
await p.fill(`${F} textarea[name="reason"]`, 'Reload the 30th partially with the rotated credential (B4-04).');
await p.locator(`${F} input[name="approvers"][value="approver-1"] + label`).click();
await p.locator(`${F} .jenkins-form-item:has(.jenkins-form-label:text-is("DATE")) input[name="value"]`).fill('2026-09-30');
await p.locator(`${F} select`).first().selectOption('partial');
const sec = p.locator(`${F} .jenkins-form-item:has(.jenkins-form-label:text-is("SECRET"))`);
await sec.locator('button:has-text("Change Password")').click();
await sec.locator('input[type="password"]').first().fill('s3cr3t');
await Promise.all([p.waitForNavigation(), p.locator('button:has-text("Submit Request")').click()]);
const url = p.url(); const id = url.split('/requests/')[1].replace('/', '');
const params = (await p.locator('h2:has-text("Parameters") + * table, table:has(th:text-is("Name"))').first().innerText()).replace(/\s+/g, ' ');
await shot(p, ['#main-panel table', 'h2:has-text("Parameters")'], 'B4-04', { pad: 8 });
const grep = execSync(`docker exec batch-control-e2e sh -c "grep -rl 's3cr3t' /var/jenkins_home/batch-control/ | head -3; echo done"`).toString().trim();
log(L, `B4-04 ${url} status "${(await mainText(p)).match(/Status \w+/)[0]}"; params "${params}"; store files containing s3cr3t: ${grep.replace(/\n/g, ' ')}`);
// B4-05 approver list
const a1 = await login('approver-1');
await a1.page.goto(`${BASE}/batch-control/requests/`);
const first = (await a1.page.locator('#main-panel table tbody tr').first().innerText()).replace(/\s+/g, ' ');
const created = await a1.page.locator('#main-panel table tbody tr td:last-child').allInnerTexts();
const sorted = created.every((c, i) => i === 0 || c <= created[i - 1]);
await shot(a1.page, a1.page.locator('#main-panel table tbody tr').first(), 'B4-05', { pad: 8 });
log(L, `B4-05 approver-1 list first row "${first}"; newest first: ${sorted} (${created.length} rows)`);
// B4-06 decision screen + ?runs= links
await a1.page.goto(url);
const sections = await a1.page.locator('#main-panel h2').allInnerTexts();
const runsLinks = await a1.page.locator('#main-panel a[href*="?runs="]').allInnerTexts();
const runRows = await a1.page.locator('h2:has-text("Recent Runs") ~ table tbody tr, table:has(th:text-is("Build")) tbody tr').count();
const runHref = await a1.page.locator('table:has(th:text-is("Build")) tbody tr a').first().getAttribute('href').catch(() => null);
let runsNav = '';
if (runsLinks.length) { await a1.page.locator('#main-panel a[href*="?runs="]').last().click(); await a1.page.waitForLoadState('load'); runsNav = `${a1.page.url().replace(BASE, '')} rows=${await a1.page.locator('table:has(th:text-is("Build")) tbody tr').count()}`; }
const bl = runHref ? (await a1.page.goto(new URL(runHref, url).href)).status() : 'no link';
await a1.page.goto(url);
await shot(a1.page, '#main-panel', 'B4-06', { pad: 6 });
log(L, `B4-06 sections ${JSON.stringify(sections)}; recent-run rows ${runRows}, size links ${JSON.stringify(runsLinks)} -> ${runsNav}; first run link -> ${bl}; edit controls for parameters: ${await a1.page.locator('#main-panel input[name="value"], #main-panel a:has-text("Edit")').count()}`);
// B4-08 reject with empty comment
await a1.page.goto(url);
const [r8] = await Promise.all([a1.page.waitForNavigation().catch(() => null), a1.page.locator('form[name="reject"] button').first().click()]);
log(L, `B4-08 reject with empty comment -> ${r8 && r8.status()} "${errText(await a1.page.content())}"; status ${(await api('admin', '/batch-control/history/requests.csv')).text.split('\n').find((l) => l.startsWith(id)).split(',').slice(-9, -8)}`);
await shot(a1.page, '#main-panel, body', 'B4-08', { pad: 8 });
// B4-07 approve with comment
const n0 = (await job('batch-daily')).nextBuildNumber;
await a1.page.goto(url);
await a1.page.fill('form[name="approve"] textarea[name="comment"]', 'Approved for the 30th.');
await Promise.all([a1.page.waitForLoadState('load'), a1.page.locator('form[name="approve"] button').first().click()]);
const notice = (await a1.page.locator('#main-panel .jenkins-alert, #notification-bar').allInnerTexts()).join(' ').replace(/\s+/g, ' ');
await shot(a1.page, '#main-panel', 'B4-07-1-after-approve', { pad: 6 });
const j = await waitFor(async () => { const x = await job('batch-daily', 'nextBuildNumber,lastBuild[number,building]'); return x.nextBuildNumber > n0 && !x.lastBuild.building ? x : null; }, { timeout: 60000 });
await a1.page.goto(url);
const exLink = a1.page.locator('#main-panel table a:has-text("batch-daily#")').first();
await shot(a1.page, '#main-panel table', 'B4-07-2-executed', { pad: 8 });
const exHref = await exLink.getAttribute('href').catch(() => null);
const b = await api('admin', `/job/batch-daily/${j.lastBuild.number}/api/json?tree=actions[parameters[name,value],causes[shortDescription]]`);
const bp = b.json.actions.find((x) => x.parameters).parameters.map((x) => `${x.name}=${x.value === undefined ? '(password)' : x.value}`);
await a1.page.goto(`${BASE}/job/batch-daily/${j.lastBuild.number}/`);
await shot(a1.page, '#main-panel', 'B4-07-3-build-page', { pad: 6 });
const pw = execSync(`docker exec batch-control-e2e sh -c "grep -c 's3cr3t' /var/jenkins_home/jobs/batch-daily/builds/${j.lastBuild.number}/build.xml; true"`).toString().trim();
log(L, `B4-07 after approve notice "${notice}"; executed link ${exHref}; build #${j.lastBuild.number} params ${JSON.stringify(bp)} (build.xml s3cr3t count ${pw}); causes ${JSON.stringify(b.json.actions.find((x) => x.causes).causes.map((c) => c.shortDescription))}`);
// B4-09 reject with comment -> requester view + mail
const url9 = await requestRun(p, '/job/batch-pipeline/', { reason: 'To be rejected (B4-09).', approvers: ['approver-1'] });
await a1.page.goto(url9);
await a1.page.fill('form[name="reject"] textarea[name="comment"]', 'Wrong day: wait for the settlement window.');
await Promise.all([a1.page.waitForLoadState('load'), a1.page.locator('form[name="reject"] button').first().click()]);
await p.goto(url9);
const v9 = await mainText(p);
await shot(p, '#main-panel', 'B4-09', { pad: 6 });
await sleep(3000);
const m9 = await mails(url9.split('/requests/')[1].replace('/', ''));
log(L, `B4-09 requester view: "${v9.slice(v9.indexOf('Status'), v9.indexOf('Status') + 320)}"; resubmit button ${await p.locator('#main-panel button, #main-panel a.jenkins-button').allInnerTexts()}; mails ${JSON.stringify(m9.map((m) => m.To[0].Address + ': ' + m.Subject))}`);
// B4-10 requester cancels with confirmation
const url10 = await requestRun(p, '/job/batch-pipeline/', { reason: 'To be cancelled by me (B4-10).', approvers: ['approver-1'] });
await p.goto(url10);
let dlg = '';
p.once('dialog', (d) => { dlg = d.message(); d.accept(); });
await p.locator('a:has-text("Cancel Request")').first().click(); await p.waitForTimeout(800);
const modal = p.locator('dialog[open]').first();
if (await modal.count()) { dlg = (await modal.innerText()).replace(/\s+/g, ' '); await shot(p, modal, 'B4-10-1-confirm', { pad: 8 }); await Promise.all([p.waitForLoadState('load'), modal.locator('button[data-id="ok"]').click()]); }
await p.goto(url10);
const s10 = (await mainText(p)).match(/Status \w+/)[0];
await a1.page.goto(url10);
const f10 = await a1.page.locator('form[name="approve"]').count();
const r10 = await post('approver-1', url10.replace(BASE, '') + 'approve', { comment: 'late' });
log(L, `B4-10 confirmation "${dlg}"; ${s10}; approver-1 forms ${f10}, POST approve -> ${r10.status} "${errText(r10.text)}"`);
// B4-11 manager cancels requester's pending request
const url11 = await requestRun(p, '/job/batch-pipeline/', { reason: 'To be cancelled by the manager (B4-11).', approvers: ['approver-1'] });
const m = await login('manager');
await m.page.goto(url11);
const mc = await m.page.locator('a:has-text("Cancel Request")').count();
if (mc) { m.page.once('dialog', (d) => d.accept()); await m.page.locator('a:has-text("Cancel Request")').first().click(); await m.page.waitForTimeout(800); const ok = m.page.locator('dialog[open] button[data-id="ok"]'); if (await ok.count()) await Promise.all([m.page.waitForLoadState('load'), ok.click()]); }
await m.page.goto(url11);
const s11 = await mainText(m.page);
await shot(m.page, '#main-panel table', 'B4-11', { pad: 8 });
log(L, `B4-11 manager cancel control ${mc}; "${s11.slice(s11.indexOf('Status'), s11.indexOf('Status') + 200)}"`);
// B4-12 approver-1 cannot cancel
const url12 = await requestRun(p, '/job/batch-pipeline/', { reason: 'Approver may not cancel (B4-12).', approvers: ['approver-1'] });
await a1.page.goto(url12);
const cc = await a1.page.locator('a:has-text("Cancel Request")').count();
const r12 = await post('approver-1', url12.replace(BASE, '') + 'cancel', {});
log(L, `B4-12 approver-1 cancel control ${cc}; POST cancel -> ${r12.status}; status ${(await api('admin', '/batch-control/history/requests.csv')).text.split('\n').find((l) => l.startsWith(url12.split('/requests/')[1].replace('/', ''))).split(',')[6]}`);
await p.goto(url12); p.once('dialog', (d) => d.accept()); await p.locator('a:has-text("Cancel Request")').first().click(); await p.waitForTimeout(800); { const ok = p.locator('dialog[open] button[data-id="ok"]'); if (await ok.count()) await Promise.all([p.waitForLoadState('load'), ok.click()]); }
// B4-13 reqonly (no Build)
const ro = await login('reqonly');
await ro.page.goto(`${BASE}/job/batch-pipeline/`);
const side13 = (await ro.page.locator('#side-panel a').allInnerTexts()).map((t) => t.trim()).filter(Boolean);
let t13 = 'no Request Run entry';
if (side13.includes('Request Run')) {
  await ro.page.locator('#side-panel a:has-text("Request Run")').click(); await ro.page.waitForLoadState('load');
  await ro.page.fill(`${F} textarea[name="reason"]`, 'No Build permission (B4-13).');
  await ro.page.locator(`${F} input[name="approvers"][value="approver-1"] + label`).click();
  const [r] = await Promise.all([ro.page.waitForNavigation().catch(() => null), ro.page.locator('button:has-text("Submit Request")').click()]);
  t13 = `${r && r.status()} "${errText(await ro.page.content())}"`;
  await shot(ro.page, '#main-panel, body', 'B4-13', { pad: 8 });
}
log(L, `B4-13 reqonly sidebar ${JSON.stringify(side13)}; submit -> ${t13}; requests stored by reqonly: ${(await api('admin', '/batch-control/history/requests.csv')).text.split('\n').filter((l) => l.split(',')[4] === 'reqonly').length}`);
// B4-15 pipeline approved -> dashboard APPROVED_REQUEST
const ad = await login('admin');
await ad.page.goto(`${BASE}/batch-control/dashboard/`);
const pr = (await ad.page.locator('#main-panel tr:has-text("batch-pipeline"):has-text("APPROVED_REQUEST")').first().innerText()).replace(/\s+/g, ' ');
log(L, `B4-15 dashboard batch-pipeline row "${pr}"`);
// B4-16 scripted submission without json on a parameterised job
const n16 = (await job('batch-daily')).nextBuildNumber;
const r16 = await post('requester', '/job/batch-daily/batch-control/submit', { reason: 'Raw fields only (B4-16).', approvers: 'approver-1', name: 'DATE', value: '2031-01-01' });
const loc = r16.location || '';
let res16 = `${r16.status} ${loc} "${errText(r16.text)}"`;
if (loc.includes('/requests/')) {
  await a1.page.goto(new URL(loc, BASE).href);
  const pv = (await a1.page.locator('table:has(th:text-is("Name"))').first().innerText().catch(() => '')).replace(/\s+/g, ' ');
  res16 += ` stored params "${pv}"`;
  await p.goto(new URL(loc, BASE).href); p.once('dialog', (d) => d.accept()); await p.locator('a:has-text("Cancel Request")').first().click(); await p.waitForTimeout(800); const ok = p.locator('dialog[open] button[data-id="ok"]'); if (await ok.count()) await Promise.all([p.waitForLoadState('load'), ok.click()]);
}
log(L, `B4-16 raw-field submission -> ${res16}`);
await close();
