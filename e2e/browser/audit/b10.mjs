// B10 new-job lock re-audit: 01, 02, 03, 04, 06, 07.  Usage: node b10.mjs ui rest cli dsl unblock mb off
import { login, close, shot, api, job, BASE, changeRows, sleep, setGlobal, groovy, waitFor, clickBuildEntry } from '../lib.mjs';
import { row, ev, mainText } from './rec.mjs';
import { approveAs } from './helpers-e.mjs';
import { execSync } from 'node:child_process';
const T = String(Date.now()).slice(-4);
const lockOf = async (name) => {
  const x = (await api('admin', `/job/${name}/config.xml`, { raw: true })).text;
  const m = x.match(/<io\.jenkins\.plugins\.batchcontrol\.config\.BatchControlJobProperty[\s\S]*?<\/io\.jenkins\.plugins\.batchcontrol\.config\.BatchControlJobProperty>/);
  if (!m) return 'no property';
  const f = (t) => (m[0].match(new RegExp(`<${t}>([^<]*)</${t}>`)) || [])[1];
  return `approvalRequired=${f('approvalRequired')} blockTimer=${f('blockTimer')} blockUpstream=${f('blockUpstream')} allowed=${(m[0].match(/<allowedUpstreamJobs>[\s\S]*?<\/allowedUpstreamJobs>|<allowedUpstreamJobs\/>/) || ['none'])[0].replace(/\s+/g, '')} jobApprovers=${(m[0].match(/<jobApprovers>[\s\S]*?<\/jobApprovers>|<jobApprovers\/>/) || ['none'])[0].replace(/\s+/g, '')}`;
};
const activated = async (name) => (await groovy(`def j = jenkins.model.Jenkins.get().getItemByFullName('${name}'); println io.jenkins.plugins.batchcontrol.ui.ActivationView.isActivated(j)`)).trim();
const XML = `<?xml version='1.1' encoding='UTF-8'?><project><properties><io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty><approvalRequired>false</approvalRequired><blockTimer>false</blockTimer><blockUpstream>false</blockUpstream><allowedUpstreamJobs><string>batch-upstream</string></allowedUpstreamJobs><jobApprovers><string>approver-2</string></jobApprovers></io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty></properties><triggers><hudson.triggers.TimerTrigger><spec>* * * * *</spec></hudson.triggers.TimerTrigger></triggers><builders/></project>`;
const ad = await login('admin'); const p = ad.page;
const steps = process.argv.slice(2);
const UI = 'b10-aud-ui';
if (steps.includes('ui')) {
  await p.goto(`${BASE}/view/all/newJob`); await p.fill('#name', UI); await p.locator('label:has-text("Freestyle project")').first().click();
  await Promise.all([p.waitForNavigation(), p.locator('#ok-button').click()]); await p.waitForTimeout(1500);
  const flags = await Promise.all(['approvalRequired', 'blockTimer', 'blockUpstream'].map((f) => p.locator(`[name="_.${f}"]`).first().isChecked()));
  const s1 = await shot(p, ['approvalRequired', 'blockTimer', 'blockUpstream'].map((f) => p.locator(`[name="_.${f}"]`).first().locator('xpath=ancestor::*[contains(@class,"jenkins-form-item")][1]')), 'B10-01-1-new-job-form', { pad: 8 });
  await p.locator('label:has-text("Build periodically")').first().click(); await p.waitForTimeout(500);
  await p.locator('textarea[name="_.spec"]').first().fill('* * * * *');
  await Promise.all([p.waitForNavigation(), p.locator('button[name="Submit"]').click()]);
  await sleep(140000);
  const n = (await job(UI, 'nextBuildNumber')).nextBuildNumber;
  const tb = (await changeRows(new RegExp(`,TRIGGER_BLOCKED,${UI},`)))[0] || '';
  const cr = (await changeRows(new RegExp(`,CREATE,${UI},`)))[0] || '';
  const lg = execSync(`docker logs --since 4m batch-control-e2e 2>&1 | grep "${UI}" | head -2`, { shell: '/bin/bash' }).toString().replace(/\s+/g, ' ');
  const rq = await login('requester'); await rq.page.goto(`${BASE}/job/${UI}/`);
  const notices = (await rq.page.locator('#main-panel .jenkins-alert').allInnerTexts()).map((t) => t.replace(/\s+/g, ' ').slice(0, 90));
  const s2 = await shot(rq.page, rq.page.locator('#main-panel .jenkins-alert'), 'B10-01-2-job-page-notices', { pad: 8 });
  await rq.context.close();
  ev(`B10-01 flags ${flags}; next ${n}; ${tb}; ${cr}; log ${lg}; notices ${JSON.stringify(notices)}`);
  row('B10-01', { roles: 'admin (New Item), requester (job page)', V: 'n.a.', G: `${flags.every(Boolean) && n === 1 ? '✓' : '✗'} New Item opens with Require approval, Block cron, Block upstream ticked (${flags}); saved with cron * * * * *: no build in 140 s (nextBuildNumber ${n})`, R: `${notices.some((x) => /blockTimer|Block cron/.test(x)) && notices.some((x) => /not activated/.test(x)) ? '✓' : '✗'} job page: ${notices.map((x) => `"${x.slice(0, 60)}"`).join(' / ')}`, C: `${/switch=blockTimer|switch=activation/.test(tb) && cr ? '✓' : '✗'} TRIGGER_BLOCKED "${tb.split(',').slice(6).join(',').slice(0, 70)}"; CREATE by admin; log "${lg.slice(0, 100)}"`, E: s1 && s2 ? '✓ B10-01-1..2' : '✗' });
}
if (steps.includes('rest')) {
  const nm = `b10-aud-rest-${T}`;
  const r = await api('admin', `/createItem?name=${nm}`, { method: 'POST', body: XML, headers: { 'Content-Type': 'application/xml' } });
  const l = await lockOf(nm);
  row('B10-02', { roles: 'admin (script)', V: 'n.a.', G: `${/approvalRequired=true blockTimer=true blockUpstream=true/.test(l) && /approver-2/.test(l) && !/batch-upstream/.test(l) ? '✓' : '✗'} createItem with approvalRequired/blockTimer/blockUpstream=false, allowed=[batch-upstream], jobApprovers=[approver-2] -> ${r.status}; stored ${l}`, R: 'n.a.', C: `${(await changeRows(new RegExp(`,CREATE,${nm},`))).length ? '✓' : '✗'} CREATE record`, E: '✓ text' });
}
if (steps.includes('cli')) {
  const nm = `b10-aud-cli-${T}`; let out;
  try { out = execSync(`printf '%s' "${XML.replace(/"/g, '\\"')}" | ../scripts/cli.sh admin create-job ${nm} 2>&1; echo EXIT=$?`, { shell: '/bin/bash' }).toString(); } catch (e) { out = (e.stdout || '').toString(); }
  const l1 = await lockOf(nm); const a1 = await activated(nm);
  // copy in the UI
  const cp = `b10-aud-copy-${T}`;
  await p.goto(`${BASE}/view/all/newJob`); await p.fill('#name', cp);
  await p.locator('label:has-text("Duplicate an existing item")').first().click(); await p.waitForTimeout(400);
  await p.locator('#from').fill('batch-cron'); await p.waitForTimeout(800);
  await Promise.all([p.waitForNavigation().catch(() => null), p.locator('#ok-button').click()]);
  if (p.url().includes('/configure')) await Promise.all([p.waitForNavigation(), p.locator('button[name="Submit"]').click()]);
  const l2 = await lockOf(cp); const a2 = await activated(cp);
  // Job DSL: delete the generated job, then run the seed again
  await api('admin', '/job/b10-dsl/doDelete', { method: 'POST' });
  const n = (await job('b10-seed')).nextBuildNumber;
  await p.goto(`${BASE}/job/b10-seed/`); await clickBuildEntry(p, 'Build Now');
  await waitFor(async () => { const j = await job('b10-seed', 'nextBuildNumber,lastBuild[building,result]'); return j.nextBuildNumber > n && !j.lastBuild.building ? j : null; }, { timeout: 90000 });
  const sr = (await job('b10-seed', 'lastBuild[number,result]')).lastBuild;
  const l3 = await lockOf('b10-dsl'); const a3 = await activated('b10-dsl');
  const cr3 = (await changeRows(/,CREATE,b10-dsl,/))[0] || '';
  ev(`B10-03 CLI ${out.replace(/\s+/g, ' ').slice(-40)} ${l1} act ${a1}; copy ${l2} act ${a2}; seed #${sr.number} ${sr.result} ${l3} act ${a3} ${cr3}`);
  const locked = (l) => /approvalRequired=true blockTimer=true blockUpstream=true/.test(l);
  row('B10-03', { roles: 'admin (CLI, UI copy, seed build)', V: 'n.a.', G: `${locked(l1) && locked(l2) && locked(l3) && a1 === 'false' && a2 === 'false' && a3 === 'false' ? '✓' : '✗'} CLI create-job ${nm}: ${l1.slice(0, 60)}, activated ${a1}; UI copy of batch-cron ${cp}: ${l2.slice(0, 60)}, activated ${a2}; Job DSL seed #${sr.number} ${sr.result} re-generated b10-dsl: ${l3.slice(0, 60)}, activated ${a3}`, R: 'n.a.', C: `${/SYSTEM|admin/.test(cr3) ? '✓' : '✗'} CREATE record of b10-dsl by ${cr3.split(',')[3]}`, E: '✓ text' });
}
if (steps.includes('unblock')) {
  const r0 = (await changeRows(new RegExp(`,CONFIGURE,${UI},`))).length;
  await p.goto(`${BASE}/job/${UI}/configure`); await p.waitForTimeout(1500);
  await p.locator('[name="_.blockTimer"]').first().locator('xpath=following-sibling::label[1]').click();
  await Promise.all([p.waitForNavigation(), p.locator('button[name="Submit"]').click()]);
  await sleep(130000);
  const n = (await job(UI, 'nextBuildNumber')).nextBuildNumber; const r1 = (await changeRows(new RegExp(`,CONFIGURE,${UI},`))).length;
  await p.goto(`${BASE}/job/${UI}/`);
  const nt = (await p.locator('#main-panel .jenkins-alert', { hasText: /not activated/ }).first().innerText().catch(() => 'NONE')).replace(/\s+/g, ' ');
  const s = await shot(p, p.locator('#main-panel .jenkins-alert', { hasText: /not activated/ }).first(), 'B10-04-still-not-activated', { pad: 8 });
  row('B10-04', { roles: 'admin', V: 'n.a.', G: `${n === 1 ? '✓' : '✗'} (6a) Block cron unticked and saved: still no timer build after 130 s (the job is not activated)`, R: `${/not activated/.test(nt) ? '✓' : '✗'} the page says so: "${nt.slice(0, 150)}"`, C: `${r1 === r0 + 1 ? '✓' : '✗'} one CONFIGURE record (${r0} -> ${r1})`, E: s ? '✓ B10-04-still-not-activated' : '✗' });
}
if (steps.includes('mb')) {
  const branches = async () => ((await api('admin', '/job/team-mb/api/json?tree=jobs[name,builds[number,result]]')).json?.jobs || []);
  async function actReq(reason) {
    const rq = await login('requester'); await rq.page.goto(`${BASE}/job/team-mb/`);
    await rq.page.locator('.jenkins-alert a:has-text("request a hold"), .jenkins-alert a:has-text("Request activation")').first().click(); await rq.page.waitForLoadState('load');
    await rq.page.fill('textarea[name="reason"]', reason); await rq.page.locator('input[name="approvers"][value="approver-1"] + label').click();
    await Promise.all([rq.page.waitForNavigation(), rq.page.locator('button:has-text("Submit Request")').click()]);
    const url = rq.page.url(); await rq.context.close(); await approveAs('approver-1', url); return url;
  }
  async function scan() { await p.goto(`${BASE}/job/team-mb/`); await p.locator('#side-panel a:has-text("Scan Multibranch Pipeline Now")').click(); await sleep(30000); }
  const br = `feature-a${T}`;
  const locks = await Promise.all((await branches()).map(async (b) => `${b.name}:${await lockOf(`team-mb/job/${b.name}`)}`));
  await actReq(`Audit B10-06 ${T}: hold the multibranch project`);
  const n0 = (await changeRows(/,TRIGGER_BLOCKED,team-mb/)).length;
  execSync(`docker exec batch-control-e2e sh -c 'cd /var/jenkins_home/repos/demo-work && git checkout -q main 2>/dev/null || git checkout -q master; git checkout -q -b ${br} && git -c user.name=e2e -c user.email=e2e@e2e.local commit -q --allow-empty -m ${br} && git push -q origin ${br}'`);
  await scan();
  const held = (await branches()).find((b) => b.name === br);
  const tb = await changeRows(/,TRIGGER_BLOCKED,team-mb/);
  const rq = await login('requester'); await rq.page.goto(`${BASE}/job/team-mb/job/${br}/`);
  const nt = (await rq.page.locator('#main-panel .jenkins-alert').allInnerTexts()).map((t) => t.replace(/\s+/g, ' ')).find((t) => /held by|activation/.test(t)) || 'NONE';
  const s1 = await shot(rq.page, rq.page.locator('#main-panel .jenkins-alert').first(), 'B10-06-1-branch-held', { pad: 8 });
  await rq.context.close();
  await actReq(`Audit B10-06 ${T}: activate the multibranch project again`);
  await scan();
  const act = (await branches()).find((b) => b.name === br);
  const runs = (await api('admin', '/batch-control/history/runs.csv')).text.split('\n').filter((l) => l.includes(`team-mb/${br}`));
  ev(`B10-06 locks ${locks}; held ${JSON.stringify(held)} tb +${tb.length - n0} ${tb[0]}; notice "${nt}"; act ${JSON.stringify(act)}; runs ${runs.length}`);
  row('B10-06', { roles: 'requester (hold/activate, branch page), approver-1, admin (scan)', V: 'n.a.', G: `${held && held.builds.length === 0 && act && act.builds.length >= 1 ? '✓' : '✗'} branch jobs carry no lock (${locks.join('; ').slice(0, 80)}); new branch ${br} while team-mb is on hold: not built; after ACTIVATE and a re-scan: #${act && act.builds.map((b) => b.number + ' ' + b.result).join(',')}`, R: `${/held|on hold/.test(nt) ? '✓' : '✗'} branch page: "${nt.slice(0, 120)}"`, C: `${tb.length > n0 && runs.length ? '✓' : '✗'} TRIGGER_BLOCKED "${(tb[0] || '').split(',').slice(6).join(',').slice(0, 80)}"; branch run in runs.csv (${runs.length})`, E: s1 ? '✓ B10-06-1-branch-held' : '✗', note: 'U-23 wording "activation is held by team-mb, which is on hold"' });
}
if (steps.includes('off')) {
  const nm = `b10-aud-off-${T}`;
  await setGlobal(p, { runControlEnabled: false });
  await p.goto(`${BASE}/view/all/newJob`); await p.fill('#name', nm); await p.locator('label:has-text("Freestyle project")').first().click();
  await Promise.all([p.waitForNavigation(), p.locator('#ok-button').click()]); await p.waitForTimeout(1500);
  const flags = await Promise.all(['approvalRequired', 'blockTimer', 'blockUpstream'].map((f) => p.locator(`[name="_.${f}"]`).first().isChecked().catch(() => 'absent')));
  const s = await shot(p, ['approvalRequired', 'blockTimer', 'blockUpstream'].map((f) => p.locator(`[name="_.${f}"]`).first().locator('xpath=ancestor::*[contains(@class,"jenkins-form-item")][1]')), 'B10-07-new-job-run-control-off', { pad: 8 });
  await Promise.all([p.waitForNavigation(), p.locator('button[name="Submit"]').click()]);
  await setGlobal(p, { runControlEnabled: true });
  const a = await activated(nm);
  const rec = (await changeRows(new RegExp(`,ACTIVATED,${nm},`)))[0] || '';
  ev(`B10-07 flags ${flags} activated ${a} rec ${rec}`);
  row('B10-07', { roles: 'admin', V: 'n.a.', G: `${flags.every((f) => f === false) && a === 'true' ? '✓' : '✗'} run control off: New Item ${nm} opens with the three switches unticked (${flags}); after run control is back on it counts as activated (${a}, D-45)`, R: 'n.a.', C: `${rec && !/\(D-\d+\)/.test(rec) ? '✓' : '✗'} ACTIVATED record "${rec.split(',').slice(6).join(',').slice(0, 120)}" (no internal id, DEF-04 fix)`, E: s ? '✓ B10-07-new-job-run-control-off' : '✗' });
}
await close();
