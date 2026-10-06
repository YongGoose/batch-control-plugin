// B1-13 incident results (ABORTED) and B1-15 / B15-06 notify-before-expiry.
import { login, close, shot, api, job, BASE, setGlobal, sleep, waitFor, requestGrant, decide } from '../lib.mjs';
import { row, ev, mainText } from './rec.mjs';
import { findMail, mailText, mailShot } from './mail.mjs';
const only = process.argv.slice(2); const on = (x) => !only.length || only.includes(x);
const ad = await login('admin');
const incCount = async (j) => (await api('admin', '/batch-control/history/incidents.csv')).text.split('\n').filter((l) => l.includes(`,${j}`) || l.includes(`${j}#`)).length;
async function abortOne(J) {
  const n = (await job(J, 'nextBuildNumber')).nextBuildNumber;
  await ad.page.goto(`${BASE}/job/${J}/`);
  await ad.page.locator('#side-panel a').filter({ hasText: /Build Now|Direct Build/ }).first().click();
  await waitFor(async () => { const j = await job(J, 'lastBuild[number,building]'); return j.lastBuild && j.lastBuild.number === n && j.lastBuild.building; }, { timeout: 60000, every: 1000 });
  await ad.page.goto(`${BASE}/job/${J}/${n}/`); await ad.page.waitForTimeout(1500);
  const stop = ad.page.locator('a.stop-button-link, .stop-button-link, a[href$="/stop"], button[tooltip*="Abort"], .app-progress-bar ~ a').first();
  ad.page.once('dialog', (d) => d.accept());
  await stop.click(); await ad.page.waitForTimeout(800);
  const dlg = ad.page.locator('dialog[open] button[data-id="ok"], dialog[open] button:has-text("Yes")').first(); if (await dlg.count()) await dlg.click();
  await waitFor(async () => { const j = await job(J, 'lastBuild[number,building,result]'); return j.lastBuild.number === n && !j.lastBuild.building; }, { timeout: 60000, every: 1000 });
  return n;
}
if (on('B1-13')) {
  const J = 'b1-sleep';
  await setGlobal(ad.page, { incidentResultsText: 'FAILURE, UNSTABLE, ABORTED' });
  const i0 = await incCount(J);
  const n1 = await abortOne(J); await sleep(3000);
  const i1 = await incCount(J);
  await ad.page.goto(`${BASE}/batch-control/incidents/`);
  const ir = ad.page.locator('#main-panel tr', { hasText: `${J}` }).first();
  const irt = (await ir.innerText().catch(() => '')).replace(/\s+/g, ' ');
  const s1 = await shot(ad.page, ir, 'B1-13-1-incident-aborted', { pad: 8 });
  await setGlobal(ad.page, { incidentResultsText: 'FAILURE, UNSTABLE' });
  const n2 = await abortOne(J); await sleep(3000);
  const i2 = await incCount(J);
  await ad.page.goto(`${BASE}/batch-control/dashboard/`);
  const dr = ad.page.locator('#main-panel tr', { hasText: J }).filter({ hasText: 'ABORTED' });
  const drt = (await dr.allInnerTexts()).slice(0, 2).map((x) => x.replace(/\s+/g, ' '));
  const s2 = await shot(ad.page, dr, 'B1-13-2-dashboard-aborted-by', { pad: 8 });
  const res = (await job(J, 'builds[number,result]')).builds.slice(0, 2);
  ev(`B1-13 #${n1}/#${n2} ${JSON.stringify(res)}; incidents ${i0}->${i1}->${i2}; incident row "${irt}"; dashboard ${JSON.stringify(drt)}`);
  row('B1-13', { roles: 'admin (setting, abort), approver-1 would see the same lists', V: 'n.a.', G: `${i1 === i0 + 1 && i2 === i1 ? '✓' : '✗'} ABORTED listed: aborting ${J} #${n1} opened an incident (${i0} -> ${i1}: "${irt.slice(0, 90)}"); ABORTED removed: aborting #${n2} opened none (${i2})`, R: 'n.a.', C: `${drt.length && /admin/.test(drt.join(' ')) ? '✓' : '✗'} dashboard rows ABORTED with "aborted by admin": ${drt.join(' || ').slice(0, 200)}`, E: s1 && s2 ? '✓ B1-13-1..2' : '✗' });
}
if (on('B1-15')) {
  const rq = await login('requester');
  const g = await requestGrant(rq.page, { scope: 'team/app-1', actions: ['CONFIGURE'], minutes: 1, reason: `Audit B1-15 ${Date.now()}: one-minute window for the expiry notice` });
  await decide(g.url, 'approve', 'ok');
  const gid = g.url.match(/(\d{8}-\d{6}-\w+)/)[1]; const t0 = Date.now();
  const m = await findMail(`to:requester@e2e.local subject:"ends soon" ${gid}`, { timeout: 90000 });
  const tMail = Date.now();
  await sleep(90000);
  const all = await (await fetch(`http://localhost:8025/api/v1/search?query=${encodeURIComponent(gid)}&limit=20`)).json();
  const subs = (all.messages || []).map((x) => x.Subject);
  const text = m ? await mailText(m) : '';
  const s = m ? await mailShot(m, 'B15-06-mail-grant-expiring') : null;
  ev(`B1-15 grant ${gid}: expiring mail after ${m ? Math.round((tMail - t0) / 1000) : 'none'} s; all mails for it ${JSON.stringify(subs)}; text ${text}`);
  const once = subs.filter((x) => /ends soon/.test(x)).length === 1;
  for (const id of ['B1-15', 'B15-06']) row(id, { roles: 'requester (holder), approver-1', V: 'n.a.', G: `${m && once ? '✓' : '✗'} 1-minute window ${gid} with notifyBeforeExpiryMinutes=1: "${m && m.Subject}" ${m ? Math.round((tMail - t0) / 1000) : '-'} s after approval, i.e. within the last minute`, R: 'n.a.', C: `${once ? '✓' : '✗'} exactly one GRANT_EXPIRING mail to the holder (mails for the grant: ${subs.join(' | ')})`, E: s ? '✓ B15-06-mail-grant-expiring' : '✗', note: `the mail does not say when the window ends (U-04): "${text.replace(/\s+/g, ' ').slice(0, 120)}"` });
  await rq.context.close();
}
await close();
