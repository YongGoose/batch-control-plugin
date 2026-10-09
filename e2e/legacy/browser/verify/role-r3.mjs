import { login, close, BASE, groovy, shot, api, sleep, changeRows } from '../lib.mjs';
import { row, ev, mainText } from '../audit/rec.mjs';
const T = String(Date.now()).slice(-4);
const ad = await login('admin'); const p = ad.page;
async function applySource(src) {
  await p.goto(`${BASE}/manage/configuration-as-code/`);
  await p.locator('button:has-text("Apply configuration")').first().click(); await p.waitForTimeout(800);
  await p.locator('dialog[open] input[name="_.newSource"]').fill(src);
  await Promise.all([p.waitForNavigation({ waitUntil: 'load' }).catch(() => null), p.locator('dialog[open] button[name="replace"], dialog[open] button:has-text("Apply configuration")').last().click()]);
  await sleep(3000);
}
await applySource('/var/jenkins_casc/profile-role-r3.yaml');
const strat = (await groovy('println jenkins.model.Jenkins.get().authorizationStrategy.class.simpleName + " " + jenkins.model.Jenkins.get().projectNamingStrategy.class.simpleName')).trim();
const rq = await login('requester'); const q = rq.page;
await q.goto(`${BASE}/job/team/`); const hasNew = await q.locator('#side-panel a:has-text("New Item")').count();
await q.goto(`${BASE}/job/team/newJob`); await q.locator('#name').fill(`app-z${T}`); await q.waitForTimeout(1500);
await q.locator('label:has-text("Freestyle project")').first().click(); await q.waitForTimeout(500);
const vz = (await q.locator('#itemname-invalid, .input-validation-message').allInnerTexts()).join(' ').trim(); const okZ = !(await q.locator('#ok-button').isDisabled());
let postZ = 'OK disabled';
if (okZ) { const [r] = await Promise.all([q.waitForNavigation().catch(() => null), q.locator('#ok-button').click()]); postZ = `${r && r.status()} "${(await mainText(q)).slice(0, 150)}"`; }
const s1 = await shot(q, '#main-panel, body', 'B8-R3-1-non-matching-name', { pad: 8 });
await q.goto(`${BASE}/job/team/newJob`); await q.locator('#name').fill(`app-r${T}`); await q.waitForTimeout(1500); await q.locator('label:has-text("Freestyle project")').first().click();
const [r2] = await Promise.all([q.waitForNavigation().catch(() => null), q.locator('#ok-button').click()]);
if (q.url().includes('/configure')) await Promise.all([q.waitForNavigation(), q.locator('button[name="Submit"]').click()]);
const made = (await api('admin', `/job/team/job/app-r${T}/api/json`)).status; const madeZ = (await api('admin', `/job/team/job/app-z${T}/api/json`)).status;
const cr = (await changeRows(new RegExp(`,CREATE,team/app-r${T},`)))[0] || '';
const lock = ((await api('admin', `/job/team/job/app-r${T}/config.xml`, { raw: true })).text.match(/<approvalRequired>(\w+)/) || [])[1];
await p.goto(`${BASE}/manage/`); const mon = (await p.locator('[data-monitor-id="io.jenkins.plugins.batchcontrol.ops.ConfigureWithoutGrantMonitor"]').innerText().catch(() => 'NONE')).replace(/\s+/g, ' ');
await applySource('/var/jenkins_casc/jenkins.yaml');
const back = (await groovy('println jenkins.model.Jenkins.get().authorizationStrategy.class.simpleName + " " + jenkins.model.Jenkins.get().projectNamingStrategy.class.simpleName')).trim();
ev(`R3 ${strat}; New Item ${hasNew}; app-z typed "${vz}" okEnabled ${okZ} -> ${postZ}; app-r ${r2 && r2.status()} made ${made} z ${madeZ}; CREATE ${cr}; approvalRequired ${lock}; monitor "${mon.slice(0, 250)}"; restored ${back}`);
row('B8-R3', { roles: 'requester (item role team-create: Create on team/app-r.*), admin (profile)', V: `${hasNew ? '✓' : '✗'} New Item offered in team/ through the item role`, G: `${made === 200 && madeZ === 404 ? '✓' : '✗'} role-based naming: app-r${T} created (${made}), app-z${T} not (${madeZ}); the new job starts locked (approvalRequired ${lock}); ${strat}`, R: `${vz || /app-r|pattern|name/i.test(postZ) ? '✓' : '✗'} typing app-z${T}: "${vz.slice(0, 120)}"${okZ ? `; OK -> ${postZ.slice(0, 120)}` : ' (OK disabled)'} (role-strategy's own message)`, C: `${cr ? '✓' : '✗'} CREATE record by requester "${cr.split(',').slice(1, 4).join(',')}"; the standing Create of the role is reported by the monitor: ${/requester|E2E Requester/.test(mon) ? 'yes' : 'no'}`, E: s1 ? '✓ B8-R3-1-non-matching-name' : '✗', note: `roles and naming strategy arranged by the JCasC profile casc/profile-role-r3.yaml; restored to jenkins.yaml (${back})` });
await close();
