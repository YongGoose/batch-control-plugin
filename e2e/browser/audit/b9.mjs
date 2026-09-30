// B9 self-grant guard re-audit (B9-01..08).
import { login, close, shot, api, BASE, changeRows, requestGrant, decide, sleep } from '../lib.mjs';
import { row, ev, mainText } from './rec.mjs';
import { uiRevoke } from './restsubmit.mjs';
const T = String(Date.now()).slice(-4);
const viol = async () => (await changeRows(/,GRANT_VIOLATION,/));
const reqEntries = async (path) => ((await api('admin', `${path}config.xml`, { raw: true })).text.match(/<permission>[^<]*:requester<\/permission>/g) || []);
const gidOf = (u) => u.match(/(\d{8}-\d{6}-\w+)/)[1];
const rq = await login('requester'); const p = rq.page;
const ad = await login('admin');
let g, v0;
async function addSelf(configUrl, shotName) {
  await p.goto(`${BASE}${configUrl}`); await p.waitForTimeout(1500);
  const l = p.locator('label:has-text("Enable project-based security")').first();
  if (!(await l.evaluate((x) => x.parentElement.querySelector('input[type=checkbox]').checked))) { await l.click(); await p.waitForTimeout(700); }
  let card = p.locator('.mas-card[data-sid="requester"]:visible').first();
  if (!(await card.count())) { await p.locator('button.matrix-auth-add-button:has-text("Add user"):visible').first().click(); await p.waitForTimeout(500); await p.locator('dialog[open] input').first().fill('requester'); await p.locator('dialog[open] button[data-id="ok"]').click(); await p.waitForTimeout(800); card = p.locator('.mas-card[data-sid="requester"]:visible').first(); }
  if (await card.locator('.mas-card__body--collapsed').count()) { await card.locator('.mas-card__toggle, .mas-card__header').first().click(); await p.waitForTimeout(500); }
  for (const perm of ['hudson.model.Item.Configure', 'hudson.model.Item.Delete']) if (!(await card.locator(`input[name="[${perm}]"]`).first().isChecked())) await card.locator(`label[data-permission-id="${perm}"]`).click();
  const s = await shot(p, card, shotName, { pad: 8 });
  const [r] = await Promise.all([p.waitForNavigation(), p.locator('button[name="Submit"]').click()]);
  const after = await mainText(p);
  const notice = await p.locator('#main-panel .jenkins-alert, .jenkins-notification, #notification-bar').allInnerTexts();
  return { status: r.status(), url: p.url(), notice: notice.join(' ').replace(/\s+/g, ' ').trim(), s };
}
if (!process.argv.includes('from04')) {
// B9-01
g = await requestGrant(p, { scope: 'batch-pipeline', actions: ['CONFIGURE'], minutes: 15, reason: `Audit B9-01 ${T}: guard via the form` });
await decide(g.url); const g1 = gidOf(g.url);
v0 = (await viol()).length;
const r1 = await addSelf('/job/batch-pipeline/configure', 'B9-01-1-adding-self');
await p.goto(`${BASE}/job/batch-pipeline/configure`); await p.waitForTimeout(1500);
const back = await p.locator('.mas-card[data-sid="requester"]').count();
const s1b = await shot(p, p.locator('.mas-card[data-sid="auditor"]').first().locator('xpath=ancestor::*[contains(@class,"mas-cards")][1]'), 'B9-01-2-reopened', { pad: 8 });
const v1 = await viol(); const nv = v1.slice(0, v1.length - v0);
await ad.page.goto(`${BASE}/batch-control/changes/`);
const s1c = await shot(ad.page, ad.page.locator('#main-panel tr:has-text("GRANT_VIOLATION")').first(), 'B9-01-3-record', { pad: 8 });
ev(`B9-01 save ${JSON.stringify({ ...r1, s: !!r1.s })}; reopen entry ${back}; entries ${await reqEntries('/job/batch-pipeline/')}; violations ${nv.join(' || ')}`);
row('B9-01', { roles: 'requester (CONFIGURE window), admin (records)', V: '✓ the project-matrix block is offered to the window holder (LIMITATIONS 33 / U-14)', G: `${back === 0 && (await reqEntries('/job/batch-pipeline/')).length === 0 ? '✓' : '✗'} requester added himself (Configure+Delete) and saved (${r1.status}); on reopen the entry is gone`, R: `✗ the save lands on the job page like a success ("${r1.notice.slice(0, 60) || 'no message'}"): nothing tells him that part of his change was reverted, or why (DEF-35)`, C: `${nv.length === 1 && /requester/.test(nv[0]) && nv[0].includes(g1) ? '✓' : '✗'} GRANT_VIOLATION naming requester, job and grant: "${(nv[0] || '').split(',').slice(6).join(',').slice(0, 120)}"`, E: r1.s && s1b && s1c ? '✓ B9-01-1..3' : '✗', defect: 'DEF-35 (new)' });
// B9-02 POST config.xml
const x = (await api('requester', '/job/batch-pipeline/config.xml', { raw: true })).text;
const inj = x.replace(/(<hudson\.security\.AuthorizationMatrixProperty>[\s\S]*?)(<\/hudson\.security\.AuthorizationMatrixProperty>)/, '$1<permission>USER:hudson.model.Item.Configure:requester</permission>$2');
v0 = (await viol()).length;
const pr = await api('requester', '/job/batch-pipeline/config.xml', { method: 'POST', body: inj, headers: { 'Content-Type': 'application/xml' } }); await sleep(1500);
const e2 = await reqEntries('/job/batch-pipeline/'); const nv2 = (await viol()).length - v0;
row('B9-02', { roles: 'requester (script, CONFIGURE window)', V: 'n.a.', G: `${e2.length === 0 ? '✓' : '✗'} POST config.xml with a requester Configure entry: entry removed (${e2.length} left)`, R: `✗ the script gets HTTP ${pr.status} "${pr.text.slice(0, 40)}" as for a clean save: nothing says the entry was dropped (DEF-35)`, C: `${nv2 === 1 ? '✓' : '✗'} one GRANT_VIOLATION`, E: '✓ text', defect: 'DEF-35 (new)' });
await uiRevoke(g1);
// B9-03 inherited folder window on team/app-1
g = await requestGrant(p, { type: 'FOLDER', scope: 'team', actions: ['CONFIGURE'], minutes: 15, reason: `Audit B9-03 ${T}: folder window` });
await decide(g.url); const g3 = gidOf(g.url);
v0 = (await viol()).length;
const r3 = await addSelf('/job/team/job/app-1/configure', 'B9-03-1-adding-self');
await sleep(1000); const e3 = await reqEntries('/job/team/job/app-1/'); const nv3 = (await viol()).length - v0;
row('B9-03', { roles: 'requester (FOLDER team window)', V: 'n.a.', G: `${e3.length === 0 ? '✓' : '✗'} requester added himself on team/app-1 and saved (${r3.status}); entry removed`, R: '✗ silent, as B9-01 (DEF-35)', C: `${nv3 === 1 ? '✓' : '✗'} one GRANT_VIOLATION`, E: r3.s ? '✓ B9-03-1-adding-self' : '✗', defect: 'DEF-35 (new)' });
await uiRevoke(g3);
}
// B9-04..06 under a CREATE window on team/ (plus a CONFIGURE window on the copy source for B9-06)
g = await requestGrant(p, { type: 'FOLDER', scope: 'team', actions: ['CREATE'], minutes: 1, reason: `Audit B9-04 ${T}: create under the guard` });
await decide(g.url); const g4 = gidOf(g.url);
const gs = await requestGrant(p, { scope: 'batch-daily', actions: ['CONFIGURE'], minutes: 15, reason: `Audit B9-06 ${T}: copy source` });
await decide(gs.url);
const nm = `app-guard-${T}`;
await p.goto(`${BASE}/job/team/newJob`); await p.fill('#name', nm); await p.locator('label:has-text("Freestyle project")').first().click();
await Promise.all([p.waitForNavigation(), p.locator('#ok-button').click()]); await Promise.all([p.waitForNavigation(), p.locator('button[name="Submit"]').click()]);
const e4 = await reqEntries(`/job/team/job/${nm}/`);
const c4 = (await p.goto(`${BASE}/job/team/job/${nm}/configure`)).status(); const c4b = (await p.goto(`${BASE}/job/team/job/app-1/configure`)).status();
v0 = (await viol()).length;
const payload = `<?xml version='1.1' encoding='UTF-8'?><project><properties><hudson.security.AuthorizationMatrixProperty><inheritanceStrategy class="org.jenkinsci.plugins.matrixauth.inheritance.InheritParentStrategy"/><permission>USER:hudson.model.Item.Read:requester</permission><permission>USER:hudson.model.Item.Configure:requester</permission></hudson.security.AuthorizationMatrixProperty></properties><builders/></project>`;
const r5 = await api('requester', `/job/team/createItem?name=app-payload-${T}`, { method: 'POST', body: payload, headers: { 'Content-Type': 'application/xml' } }); await sleep(1000);
const e5 = await reqEntries(`/job/team/job/app-payload-${T}/`); const v5 = (await viol()).length - v0;
await p.goto(`${BASE}/job/team/newJob`); await p.fill('#name', `app-copy-${T}`);
await p.locator('label:has-text("Duplicate an existing item")').first().click(); await p.waitForTimeout(400);
await p.locator('#from').fill('batch-daily'); await p.waitForTimeout(800);
const [r6] = await Promise.all([p.waitForNavigation().catch(() => null), p.locator('#ok-button').click()]);
const copyLanded = p.url();
if (p.url().includes('/configure')) await Promise.all([p.waitForNavigation(), p.locator('button[name="Submit"]').click()]);
await sleep(1000);
const e6 = ((await api('admin', `/job/team/job/app-copy-${T}/config.xml`, { raw: true })).text.match(/<permission>[^<]+<\/permission>/g) || []);
const vv = await viol(); const v6 = vv.slice(0, vv.length - v0 - v5);
ev(`B9-04 ${nm} entries ${e4} configure ${c4} app-1 ${c4b}; B9-05 ${r5.status} ${e5} +${v5}; B9-06 ${r6 && r6.status()} ${copyLanded} perms ${e6} ${v6.join(' || ')}`);
await sleep(65000);
const c4c = (await p.goto(`${BASE}/job/team/job/${nm}/configure`)).status();
row('B9-04', { roles: 'requester (CREATE window)', V: 'n.a.', G: `${e4.length === 0 && c4 === 200 && c4b === 403 && c4c === 403 ? '✓' : '✗'} team/${nm} created under only a CREATE window: no permission entry on the item (${e4.length}); Configure on it during the window (${c4}, D-35c), not on team/app-1 (${c4b}); after the window ${c4c}`, R: 'n.a.', C: 'n.a.', E: '✓ text' });
row('B9-05', { roles: 'requester (script)', V: 'n.a.', G: `${r5.status === 200 && e5.length === 0 ? '✓' : '✗'} createItem with a payload granting requester Read+Configure -> ${r5.status}; created without the entries (${e5.length})`, R: '✗ HTTP 200 like a clean create: nothing says the property was removed (DEF-35)', C: `${v5 === 1 ? '✓' : '✗'} one GRANT_VIOLATION`, E: '✓ text', defect: 'DEF-35 (new)' });
row('B9-06', { roles: 'requester (CREATE on team + CONFIGURE on batch-daily)', V: 'n.a.', G: `${e6.length === 0 ? '✓' : '✗'} Copy from batch-daily (carries auditor Read) into team/app-copy-${T} -> ${r6 && r6.status()}; the copy has ${e6.length} permission entries`, R: '✗ the copy lands on its configure page as usual; nothing says the authorization property was removed (DEF-35)', C: `${v6.length === 1 && /carried an authorization property/.test(v6[0]) ? '✓' : '✗'} "${(v6[0] || '').split(',').slice(6).join(',').slice(0, 120)}"`, E: '✓ text', defect: 'DEF-35 (new)' });
await uiRevoke(gidOf(gs.url));
// B9-07 delete + recreate by admin
await ad.page.goto(`${BASE}/job/team/job/${nm}/`);
ad.page.once('dialog', (d) => d.accept());
await ad.page.locator('#side-panel a:has-text("Delete Project")').click(); await ad.page.waitForTimeout(700);
{ const ok = ad.page.locator('dialog[open] button[data-id="ok"]').first(); if (await ok.count()) await Promise.all([ad.page.waitForNavigation().catch(() => null), ok.click()]); }
const g7 = await requestGrant(p, { type: 'FOLDER', scope: 'team', actions: ['CREATE'], minutes: 15, reason: `Audit B9-07 ${T}: window open during delete+recreate` });
await decide(g7.url);
await ad.page.goto(`${BASE}/job/team/newJob`); await ad.page.fill('#name', nm); await ad.page.locator('label:has-text("Freestyle project")').first().click();
await Promise.all([ad.page.waitForNavigation(), ad.page.locator('#ok-button').click()]); await Promise.all([ad.page.waitForNavigation(), ad.page.locator('button[name="Submit"]').click()]);
const c7 = (await p.goto(`${BASE}/job/team/job/${nm}/configure`)).status();
row('B9-07', { roles: 'admin (delete + recreate), requester (open CREATE window)', V: 'n.a.', G: `${c7 === 403 ? '✓' : '✗'} admin deleted and re-created team/${nm} while requester's CREATE window was open: requester configure -> ${c7}`, R: 'n.a.', C: 'n.a.', E: '✓ text' });
await uiRevoke(gidOf(g7.url));
// B9-08 admin edits the property: kept, no record
v0 = (await viol()).length;
await ad.page.goto(`${BASE}/job/batch-pipeline/configure`); await ad.page.waitForTimeout(1500);
let card = ad.page.locator('.mas-card[data-sid="approver-2"]:visible').first();
if (!(await card.count())) { await ad.page.locator('button.matrix-auth-add-button:has-text("Add user"):visible').first().click(); await ad.page.waitForTimeout(500); await ad.page.locator('dialog[open] input').first().fill('approver-2'); await ad.page.locator('dialog[open] button[data-id="ok"]').click(); await ad.page.waitForTimeout(800); card = ad.page.locator('.mas-card[data-sid="approver-2"]:visible').first(); }
if (await card.locator('.mas-card__body--collapsed').count()) { await card.locator('.mas-card__toggle, .mas-card__header').first().click(); await ad.page.waitForTimeout(500); }
await card.locator('label[data-permission-id="hudson.model.Item.Workspace"]').click();
await Promise.all([ad.page.waitForNavigation(), ad.page.locator('button[name="Submit"]').click()]); await sleep(1000);
const kept = ((await api('admin', '/job/batch-pipeline/config.xml', { raw: true })).text.match(/Item\.Workspace:approver-2/g) || []).length;
const nv8 = (await viol()).length - v0;
row('B9-08', { roles: 'admin', V: 'n.a.', G: `${kept === 1 ? '✓' : '✗'} admin adds approver-2 Job/Workspace on batch-pipeline: kept (${kept})`, R: 'n.a.', C: `${nv8 === 0 ? '✓' : '✗'} no GRANT_VIOLATION (+${nv8}); the edit is an ordinary CONFIGURE record`, E: '✓ text' });
await close();
