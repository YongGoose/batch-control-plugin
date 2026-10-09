// B15 notifications (the rows not already covered).
import { login, close, shot, api, BASE, MAIL, log, requestRun, mails, sleep, launch } from './lib.mjs';
import { execSync } from 'node:child_process';
const L = 'section-b.log';
const body = async (m) => (await (await fetch(`${MAIL}/api/v1/message/${m.ID}`)).json()).Text;
const cancel = async (page, url) => { await page.goto(url); page.once('dialog', (d) => d.accept()); await page.locator('a:has-text("Cancel Request")').first().click(); await page.waitForTimeout(800); const ok = page.locator('dialog[open] button[data-id="ok"]'); if (await ok.count()) await Promise.all([page.waitForLoadState('load'), ok.click()]); };
// B15-06 once per grant, B15-07 grant mails content
const all = (await (await fetch(`${MAIL}/api/v1/messages?limit=500`)).json()).messages;
const ends = all.filter((m) => /Change window ends soon/.test(m.Subject));
const per = {}; ends.forEach((m) => { const id = m.Subject.match(/\d{8}-\d{6}-\w+/)[0]; per[id] = (per[id] || 0) + 1; });
log(L, `B15-06 "Change window ends soon" mails per grant: ${JSON.stringify(per)}`);
for (const re of [/awaiting your decision: change request 20260929-195720-swkb4b/, /Request approved: change request 20260929-195720-swkb4b/]) {
  const m = all.find((x) => re.test(x.Subject));
  log(L, `B15-07 ${m ? m.Subject : 'NOT FOUND'}:\n${m ? (await body(m)).split('\n').map((l) => '      | ' + l).join('\n') : ''}`);
}
// B15-09 Jenkins URL empty
const ad = await login('admin');
await ad.page.goto(`${BASE}/manage/configure`); await ad.page.waitForTimeout(1500);
await ad.page.locator('input[name="_.url"]').first().fill('');
await Promise.all([ad.page.waitForNavigation(), ad.page.locator('button[name="Submit"]').click()]);
const rq = await login('requester');
let url = await requestRun(rq.page, '/job/batch-pipeline/', { reason: 'Jenkins URL empty (B15-09).', approvers: ['approver-1'] });
let id = url.split('/requests/')[1].replace('/', '');
await sleep(4000);
let m = (await mails(id))[0];
const t9 = m ? await body(m) : 'NO MAIL';
log(L, `B15-09 Jenkins URL empty: mail "${m && m.Subject}" has a Link line: ${/Link:/.test(t9)}; any http: ${/https?:\/\//.test(t9)}`);
await cancel(rq.page, url);
// C-17 flavour: Host header while URL empty
const hr = await fetch(`${BASE}/job/batch-pipeline/batch-control/submit`, { method: 'POST', headers: { Host: 'evil.example', 'X-Forwarded-Host': 'evil.example' } }).catch((e) => ({ status: e.message }));
await ad.page.goto(`${BASE}/manage/configure`); await ad.page.waitForTimeout(1500);
await ad.page.locator('input[name="_.url"]').first().fill('http://localhost:8080/');
await Promise.all([ad.page.waitForNavigation(), ad.page.locator('button[name="Submit"]').click()]);
// B15-11 mail sink stopped
execSync('docker stop batch-control-e2e-mail');
const t0 = Date.now();
url = await requestRun(rq.page, '/job/batch-pipeline/', { reason: 'Mail sink stopped (B15-11).', approvers: ['approver-1'] });
const dt = Date.now() - t0;
await shot(rq.page, '#main-panel table', 'B15-11', { pad: 8 });
await sleep(3000);
execSync('docker start batch-control-e2e-mail');
const lg = execSync('docker logs --since 1m batch-control-e2e 2>&1 | grep -iE "mail|notif" | head -3', { shell: '/bin/bash' }).toString().replace(/\n/g, ' || ');
log(L, `B15-11 sink stopped: Request Run form -> detail in ${dt} ms (${rq.page.url().replace(BASE, '')}); log ${lg.slice(0, 400)}`);
await cancel(rq.page, url);
// B15-13 user without an e-mail address (approver-2's address removed in the UI)
await ad.page.goto(`${BASE}/user/approver-2/account/`); await ad.page.waitForTimeout(1500);
const em = ad.page.locator('input[name="email.address"], input[name="_.address"]').first();
const oldMail = await em.inputValue().catch(() => '');
await em.fill('');
await Promise.all([ad.page.waitForNavigation(), ad.page.locator('button[name="Submit"]').click()]);
await sleep(2000);
for (let i = 0; i < 20; i++) { try { const r = await fetch(`${MAIL}/api/v1/messages?limit=1`); if (r.ok) break; } catch { /* sink starting */ } await sleep(1000); }
const t13 = Date.now();
url = await requestRun(rq.page, '/job/batch-pipeline/', { reason: 'Approver without an e-mail address (B15-13).', approvers: ['approver-2'] });
const d13 = Date.now() - t13;
id = url.split('/requests/')[1].replace('/', '');
await sleep(4000);
const m13 = await mails(id);
const lg13 = execSync('docker logs --since 1m batch-control-e2e 2>&1 | grep -iE "approver-2|e-mail|address" | head -3', { shell: '/bin/bash' }).toString().replace(/\n/g, ' || ');
log(L, `B15-13 approver-2 without address (was "${oldMail}"): request created in ${d13} ms at ${rq.page.url().replace(BASE, '')}; mails for it ${JSON.stringify(m13.map((x) => x.To.map((t) => t.Address).join(',') + ': ' + x.Subject))}; log ${lg13.slice(0, 300)}`);
await cancel(rq.page, url);
await ad.page.goto(`${BASE}/user/approver-2/account/`); await ad.page.waitForTimeout(1200);
await ad.page.locator('input[name="email.address"], input[name="_.address"]').first().fill(oldMail || 'approver-2@e2e.local');
await Promise.all([ad.page.waitForNavigation(), ad.page.locator('button[name="Submit"]').click()]);
await close();
