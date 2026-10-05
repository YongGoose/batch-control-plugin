// DEF-35 / D-48: the self-grant guard tells the saving user (B9-01, 02, 03, 05, 06).
import { login, close, shot, api, BASE, changeRows, requestGrant, decide, sleep } from '../lib.mjs';
import { row, ev, mainText } from '../audit/rec.mjs';
import { uiRevoke } from '../audit/restsubmit.mjs';
const T = String(Date.now()).slice(-4); const gidOf = (u) => u.match(/(\d{8}-\d{6}-\w+)/)[1];
const viol = async () => (await changeRows(/,GRANT_VIOLATION,/));
const reqEntries = async (path) => ((await api('admin', `${path}config.xml`, { raw: true })).text.match(/<permission>[^<]*:requester<\/permission>/g) || []);
const desc = async (path) => ((await api('admin', `${path}config.xml`, { raw: true })).text.match(/<description>([^<]*)<\/description>/) || [])[1];
const rq = await login('requester'); const p = rq.page;
const d48 = (t, item) => new RegExp(item.replace(/[/-]/g, '.')).test(t) && /temporary|permission window|grant/i.test(t) && /(other changes|rest of|saved)/i.test(t) && /administrator/i.test(t);
async function addSelf(configUrl, newDesc, shotName) {
  await p.goto(`${BASE}${configUrl}`); await p.waitForTimeout(1500);
  await p.fill('textarea[name="description"]', newDesc);
  const l = p.locator('label:has-text("Enable project-based security")').first();
  if (!(await l.evaluate((x) => x.parentElement.querySelector('input[type=checkbox]').checked))) { await l.click(); await p.waitForTimeout(700); }
  let card = p.locator('.mas-card[data-sid="requester"]:visible').first();
  if (!(await card.count())) { await p.locator('button.matrix-auth-add-button:has-text("Add user"):visible').first().click(); await p.waitForTimeout(500); await p.locator('dialog[open] input').first().fill('requester'); await p.locator('dialog[open] button[data-id="ok"]').click(); await p.waitForTimeout(800); card = p.locator('.mas-card[data-sid="requester"]:visible').first(); }
  if (await card.locator('.mas-card__body--collapsed').count()) { await card.locator('.mas-card__toggle, .mas-card__header').first().click(); await p.waitForTimeout(500); }
  for (const perm of ['hudson.model.Item.Configure', 'hudson.model.Item.Delete']) if (!(await card.locator(`input[name="[${perm}]"]`).first().isChecked())) await card.locator(`label[data-permission-id="${perm}"]`).click();
  const [r] = await Promise.all([p.waitForNavigation(), p.locator('button[name="Submit"]').click()]);
  const t = await mainText(p); const title = (await p.locator('#main-panel h1, .jenkins-app-bar h1').first().innerText().catch(() => '')).trim();
  const s = await shot(p, ['#main-panel .jenkins-app-bar', '#main-panel .jenkins-alert, #main-panel p'], shotName, { pad: 8 });
  return { status: r.status(), t, title, s };
}
// B9-01
let g = await requestGrant(p, { scope: 'batch-pipeline', actions: ['CONFIGURE'], minutes: 15, reason: `Verify B9-01 ${T}` }); await decide(g.url); const g1 = gidOf(g.url);
let v0 = (await viol()).length; const nd = `edited with a self-grant attempt ${T}`;
const r1 = await addSelf('/job/batch-pipeline/configure', nd, 'B9-01-1-refusal-page');
const e1 = await reqEntries('/job/batch-pipeline/'); const d1 = await desc('/job/batch-pipeline/'); const nv = (await viol()).slice(0, (await viol()).length - v0);
const ad = await login('admin'); await ad.page.goto(`${BASE}/batch-control/changes/`);
const s3 = await shot(ad.page, ad.page.locator('#main-panel tr:has-text("GRANT_VIOLATION")').first(), 'B9-01-2-record', { pad: 8 });
ev(`V9 B9-01 ${r1.status} "${r1.title}" "${r1.t.slice(0, 400)}"; entries ${e1.length}; desc ${d1}; viol ${nv.join(' || ')}`);
row('B9-01', { roles: 'requester (CONFIGURE window), admin (records)', V: '✓ the project-matrix block is offered to the window holder (LIMITATIONS 33)', G: `${e1.length === 0 && d1 === nd ? '✓' : '✗'} the self-added entry is gone and the description change is kept ("${d1}")`, R: `${r1.status === 403 && d48(r1.t, 'batch-pipeline') ? '✓' : '✗'} HTTP ${r1.status} "${r1.title}": "${r1.t.replace(/^.*?(The |Your )/, '$1').slice(0, 220)}" (DEF-35 / D-48)`, C: `${nv.length === 1 && nv[0].includes(g1) ? '✓' : '✗'} GRANT_VIOLATION naming requester, job and grant`, E: r1.s && s3 ? '✓ B9-01-1-refusal-page, B9-01-2-record' : '✗' });
// B9-02 POST config.xml
const x = (await api('requester', '/job/batch-pipeline/config.xml', { raw: true })).text;
const inj = x.replace(/(<hudson\.security\.AuthorizationMatrixProperty>[\s\S]*?)(<\/hudson\.security\.AuthorizationMatrixProperty>)/, '$1<permission>USER:hudson.model.Item.Configure:requester</permission>$2').replace(/<description>[^<]*<\/description>/, `<description>posted ${T}</description>`);
v0 = (await viol()).length;
const pr = await api('requester', '/job/batch-pipeline/config.xml', { method: 'POST', body: inj, headers: { 'Content-Type': 'application/xml' } }); await sleep(1500);
const e2 = await reqEntries('/job/batch-pipeline/'); const d2 = await desc('/job/batch-pipeline/'); const nv2 = (await viol()).length - v0;
ev(`V9 B9-02 ${pr.status} "${pr.text.slice(0, 300)}" entries ${e2.length} desc ${d2} viol +${nv2}`);
row('B9-02', { roles: 'requester (script, CONFIGURE window)', V: 'n.a.', G: `${e2.length === 0 && d2 === `posted ${T}` ? '✓' : '✗'} entry removed, the description change kept`, R: `${pr.status === 403 && d48(pr.text, 'batch-pipeline') ? '✓' : '✗'} HTTP ${pr.status}: "${pr.text.replace(/\s+/g, ' ').slice(0, 200)}"`, C: `${nv2 === 1 ? '✓' : '✗'} one GRANT_VIOLATION`, E: '✓ text' });
await uiRevoke(g1);
// B9-03 folder window, team/app-1
g = await requestGrant(p, { scope: 'team', actions: ['CONFIGURE'], minutes: 15, reason: `Verify B9-03 ${T}` }); await decide(g.url); const g3 = gidOf(g.url);
v0 = (await viol()).length; const nd3 = `folder window edit ${T}`;
const r3 = await addSelf('/job/team/job/app-1/configure', nd3, 'B9-03-1-refusal-page');
const e3 = await reqEntries('/job/team/job/app-1/'); const d3 = await desc('/job/team/job/app-1/'); const nv3 = (await viol()).length - v0;
row('B9-03', { roles: 'requester (FOLDER team window)', V: 'n.a.', G: `${e3.length === 0 && d3 === nd3 ? '✓' : '✗'} entry removed on team/app-1, description kept`, R: `${r3.status === 403 && d48(r3.t, 'app-1') ? '✓' : '✗'} HTTP ${r3.status} "${r3.t.replace(/^.*?(The |Your )/, '$1').slice(0, 160)}"`, C: `${nv3 === 1 ? '✓' : '✗'} one GRANT_VIOLATION`, E: r3.s ? '✓ B9-03-1-refusal-page' : '✗' });
await uiRevoke(g3);
// B9-05 createItem payload, B9-06 copy
g = await requestGrant(p, { scope: 'team', actions: ['CREATE'], minutes: 15, reason: `Verify B9-05 ${T}` }); await decide(g.url);
const gs = await requestGrant(p, { scope: 'batch-daily', actions: ['CONFIGURE'], minutes: 15, reason: `Verify B9-06 ${T}: copy source` }); await decide(gs.url);
v0 = (await viol()).length;
const payload = `<?xml version='1.1' encoding='UTF-8'?><project><description>payload ${T}</description><properties><hudson.security.AuthorizationMatrixProperty><inheritanceStrategy class="org.jenkinsci.plugins.matrixauth.inheritance.InheritParentStrategy"/><permission>USER:hudson.model.Item.Read:requester</permission><permission>USER:hudson.model.Item.Configure:requester</permission></hudson.security.AuthorizationMatrixProperty></properties><builders/></project>`;
const r5 = await api('requester', `/job/team/createItem?name=app-pl-${T}`, { method: 'POST', body: payload, headers: { 'Content-Type': 'application/xml' } }); await sleep(1000);
const e5 = await reqEntries(`/job/team/job/app-pl-${T}/`); const made5 = (await api('admin', `/job/team/job/app-pl-${T}/api/json`)).status; const v5 = (await viol()).length - v0;
row('B9-05', { roles: 'requester (script, CREATE window)', V: 'n.a.', G: `${made5 === 200 && e5.length === 0 ? '✓' : '✗'} createItem with a payload granting requester Read+Configure: item created (${made5}) without the entries`, R: `${r5.status === 403 && d48(r5.text, `app-pl-${T}`) ? '✓' : '✗'} HTTP ${r5.status}: "${r5.text.replace(/\s+/g, ' ').slice(0, 200)}"`, C: `${v5 === 1 ? '✓' : '✗'} one GRANT_VIOLATION`, E: '✓ text' });
v0 = (await viol()).length;
await p.goto(`${BASE}/job/team/newJob`); await p.fill('#name', `app-cp-${T}`);
await p.locator('label:has-text("Duplicate an existing item")').first().click(); await p.waitForTimeout(400);
await p.locator('#from').fill('batch-daily'); await p.waitForTimeout(800);
const [r6] = await Promise.all([p.waitForNavigation().catch(() => null), p.locator('#ok-button').click()]);
const t6 = await mainText(p); const s6 = await shot(p, ['#main-panel .jenkins-app-bar', '#main-panel .jenkins-alert, #main-panel p'], 'B9-06-copy-refusal', { pad: 8 });
const e6 = ((await api('admin', `/job/team/job/app-cp-${T}/config.xml`, { raw: true })).text.match(/<permission>[^<]+<\/permission>/g) || []); const made6 = (await api('admin', `/job/team/job/app-cp-${T}/api/json`)).status;
const v6 = (await viol()).length - v0;
ev(`V9 B9-05 ${r5.status} "${r5.text.slice(0, 200)}"; B9-06 ${r6 && r6.status()} "${t6.slice(0, 300)}" perms ${e6.length} made ${made6}`);
row('B9-06', { roles: 'requester (CREATE on team + CONFIGURE on batch-daily)', V: 'n.a.', G: `${made6 === 200 && e6.length === 0 ? '✓' : '✗'} Copy from batch-daily into team/app-cp-${T}: created (${made6}), the copied authorization property removed (${e6.length} entries)`, R: `${r6 && r6.status() === 403 && d48(t6, `app-cp-${T}`) ? '✓' : '✗'} HTTP ${r6 && r6.status()} "${t6.replace(/^.*?(The |Your )/, '$1').slice(0, 180)}"`, C: `${v6 === 1 ? '✓' : '✗'} one GRANT_VIOLATION`, E: s6 ? '✓ B9-06-copy-refusal' : '✗' });
await uiRevoke(gidOf(g.url)); await uiRevoke(gidOf(gs.url));
await close();
