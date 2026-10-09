// B10 new-job lock (D-31, D-34) and its interplay with activation (SPEC 6a).
// Usage: node b10.mjs ui rest cli dsl unblock help mb off
import { login, close, shot, api, job, BASE, log, changeRows, sleep, setGlobal, groovy, waitFor, clickBuildEntry } from './lib.mjs';
import { execSync } from 'node:child_process';
const L = 'section-b.log';
const steps = {};
const lockOf = async (name) => {
  const x = (await api('admin', `/job/${name}/config.xml`, { raw: true })).text;
  const m = x.match(/<io\.jenkins\.plugins\.batchcontrol\.config\.BatchControlJobProperty[\s\S]*?<\/io\.jenkins\.plugins\.batchcontrol\.config\.BatchControlJobProperty>/);
  if (!m) return 'no property';
  const f = (t) => (m[0].match(new RegExp(`<${t}>([^<]*)</${t}>`)) || [])[1];
  return `approvalRequired=${f('approvalRequired')} blockTimer=${f('blockTimer')} blockUpstream=${f('blockUpstream')} allowed=${(m[0].match(/<allowedUpstreamJobs>[\s\S]*?<\/allowedUpstreamJobs>|<allowedUpstreamJobs\/>/) || [''])[0].replace(/\s+/g, '')} jobApprovers=${(m[0].match(/<jobApprovers>[\s\S]*?<\/jobApprovers>|<jobApprovers\/>/) || [''])[0].replace(/\s+/g, '')}`;
};
const XML = (extra = '') => `<?xml version='1.1' encoding='UTF-8'?><project><properties><io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty><approvalRequired>false</approvalRequired><blockTimer>false</blockTimer><blockUpstream>false</blockUpstream><allowedUpstreamJobs><string>batch-upstream</string></allowedUpstreamJobs><jobApprovers><string>approver-2</string></jobApprovers></io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty></properties><triggers><hudson.triggers.TimerTrigger><spec>* * * * *</spec></hudson.triggers.TimerTrigger></triggers><builders><hudson.tasks.Shell><command>echo made</command></hudson.tasks.Shell></builders>${extra}</project>`;

steps.ui = async () => {
  const { page } = await login('admin');
  if ((await api('admin', '/job/b10-ui/api/json')).status === 404) {
    await page.goto(`${BASE}/view/all/newJob`); await page.fill('#name', 'b10-ui');
    await page.locator('label:has-text("Freestyle project")').first().click();
    await Promise.all([page.waitForNavigation(), page.locator('#ok-button').click()]);
    await page.waitForTimeout(1500);
    const flags = { approvalRequired: await page.locator('[name="_.approvalRequired"]').first().isChecked(), blockTimer: await page.locator('[name="_.blockTimer"]').first().isChecked(), blockUpstream: await page.locator('[name="_.blockUpstream"]').first().isChecked() };
    await shot(page, page.locator('[name="_.approvalRequired"]').first().locator('xpath=ancestor::*[contains(@class,"jenkins-form-item")][1]/..'), 'B10-01-1-new-job-form', { pad: 8 });
    await page.locator('label:has-text("Build periodically")').first().click(); await page.waitForTimeout(500);
    await page.locator('textarea[name="_.spec"]').first().fill('* * * * *');
    await Promise.all([page.waitForNavigation(), page.locator('button[name="Submit"]').click()]);
    log(L, `B10-01 New Item b10-ui (admin): Batch Control section pre-ticked ${JSON.stringify(flags)}; saved with cron * * * * *`);
  }
  const t0 = Date.now();
  await sleep(140000);
  const j = await job('b10-ui', 'nextBuildNumber');
  const tb = (await changeRows(/TRIGGER_BLOCKED,b10-ui/))[0];
  const lg = execSync(`docker logs --since 3m batch-control-e2e 2>&1 | grep "b10-ui" | head -3`, { shell: '/bin/bash' }).toString().replace(/\n/g, ' || ');
  await page.goto(`${BASE}/job/b10-ui/`);
  const notices = (await page.locator('#main-panel .jenkins-alert').allInnerTexts()).map((t) => t.replace(/\s+/g, ' ').slice(0, 110));
  await shot(page, page.locator('#main-panel .jenkins-alert').first(), 'B10-01-2-job-page', { pad: 8 });
  log(L, `B10-01 after 140 s: b10-ui nextBuildNumber ${j.nextBuildNumber}; notices ${JSON.stringify(notices)}; TRIGGER_BLOCKED ${tb}; CREATE record ${(await changeRows(/,CREATE,b10-ui,/))[0]}; log ${lg.slice(0, 400)}`);
  await close();
};

steps.rest = async () => {
  const r = await api('admin', '/createItem?name=b10-rest', { method: 'POST', body: XML(), headers: { 'Content-Type': 'application/xml' } });
  log(L, `B10-02 createItem with approvalRequired/blockTimer/blockUpstream=false, allowed=[batch-upstream], jobApprovers=[approver-2] -> ${r.status}; stored ${await lockOf('b10-rest')}`);
};

steps.cli = async () => {
  let out;
  try { out = execSync(`printf '%s' "${XML().replace(/"/g, '\\"')}" | ../scripts/cli.sh admin create-job b10-cli 2>&1; echo EXIT=$?`, { shell: '/bin/bash' }).toString(); } catch (e) { out = e.stdout.toString(); }
  log(L, `B10-03 CLI create-job b10-cli -> ${out.replace(/\s+/g, ' ').slice(0, 120)}; stored ${await lockOf('b10-cli')}`);
  // copy
  const { page } = await login('admin');
  await page.goto(`${BASE}/view/all/newJob`); await page.fill('#name', 'b10-copy');
  await page.locator('#duplicate-job').click(); await page.waitForTimeout(400); await page.locator('#from').fill('batch-cron'); await page.waitForTimeout(800);
  await Promise.all([page.waitForNavigation(), page.locator('#ok-button').click()]);
  if (page.url().includes('/configure')) await Promise.all([page.waitForNavigation(), page.locator('button[name="Submit"]').click()]);
  log(L, `B10-03 copy of batch-cron (blockTimer=false, activated) -> b10-copy: ${await lockOf('b10-copy')}; activation: ${(await groovy(`def j = jenkins.model.Jenkins.get().getItemByFullName('b10-copy'); println io.jenkins.plugins.batchcontrol.ui.ActivationView.isActivated(j)`)).trim()}`);
  await close();
};

steps.dsl = async () => {
  const { page } = await login('admin');
  if ((await api('admin', '/job/b10-seed/api/json')).status === 404) {
    await page.goto(`${BASE}/view/all/newJob`); await page.fill('#name', 'b10-seed');
    await page.locator('label:has-text("Freestyle project")').first().click();
    await Promise.all([page.waitForNavigation(), page.locator('#ok-button').click()]);
    await page.waitForTimeout(1500);
    // the seed itself starts locked: clear "Require approval to run" so the admin can start it by hand
    const ar = page.locator('[name="_.approvalRequired"]').first(); if (await ar.isChecked()) await ar.locator('xpath=following-sibling::label[1]').click();
    await page.locator('button.hetero-list-add:has-text("Add build step")').click(); await page.waitForTimeout(500);
    await page.locator('.jenkins-dropdown button, .jenkins-dropdown__item').filter({ hasText: 'Process Job DSLs' }).first().click(); await page.waitForTimeout(1200);
    await page.locator('label:has-text("Use the provided DSL script")').first().click().catch(() => {});
    const ta = page.locator('textarea[name="_.scriptText"], textarea[name="scriptText"]').first();
    await ta.evaluate((t) => { t.value = "job('b10-dsl') { triggers { cron('* * * * *') }\n  steps { shell('echo dsl') } }"; const cm = t.nextElementSibling && t.nextElementSibling.CodeMirror; if (cm) cm.setValue(t.value); });
    await Promise.all([page.waitForNavigation(), page.locator('button[name="Submit"]').click()]);
  }
  const n = (await job('b10-seed')).nextBuildNumber;
  await page.goto(`${BASE}/job/b10-seed/`);
  await clickBuildEntry(page, /Build Now/);
  await waitFor(async () => { const j = await job('b10-seed', 'nextBuildNumber,lastBuild[building,result]'); return j.nextBuildNumber > n && !j.lastBuild.building ? j : null; }, { timeout: 90000 });
  const res = (await job('b10-seed', 'lastBuild[number,result]')).lastBuild;
  const con = (await api('admin', `/job/b10-seed/${res.number}/consoleText`, { raw: true })).text.trim().split('\n').slice(-4).join(' | ');
  log(L, `B10-03 Job DSL seed #${res.number} ${res.result} (${con.slice(0, 220)}); b10-dsl: ${await lockOf('b10-dsl')}; CREATE record ${(await changeRows(/,CREATE,b10-dsl,/))[0]}`);
  await close();
};

steps.unblock = async () => {
  const { page } = await login('admin');
  await page.goto(`${BASE}/job/b10-ui/configure`); await page.waitForTimeout(1500);
  const bt = page.locator('[name="_.blockTimer"]').first(); if (await bt.isChecked()) await bt.locator('xpath=following-sibling::label[1]').click();
  await Promise.all([page.waitForNavigation(), page.locator('button[name="Submit"]').click()]);
  const n = (await job('b10-ui')).nextBuildNumber;
  await sleep(130000);
  await page.goto(`${BASE}/job/b10-ui/`);
  const notices = (await page.locator('#main-panel .jenkins-alert').allInnerTexts()).map((t) => t.replace(/\s+/g, ' ').slice(0, 120));
  await shot(page, page.locator('#main-panel .jenkins-alert').first(), 'B10-04', { pad: 8 });
  const rec = (await changeRows(/,CONFIGURE,b10-ui,admin,/))[0];
  log(L, `B10-04 blockTimer unticked on b10-ui: after 130 s next ${n}->${(await job('b10-ui')).nextBuildNumber} (6a: still not activated); notices ${JSON.stringify(notices)}; CONFIGURE record ${rec}`);
  await close();
};

steps.help = async () => {
  const { page } = await login('admin');
  await page.goto(`${BASE}/job/b10-ui/configure`); await page.waitForTimeout(1500);
  const item = page.locator('[name="_.blockTimer"]').first().locator('xpath=ancestor::*[contains(@class,"jenkins-form-item")][1]');
  await item.locator('.jenkins-help-button').first().click(); await page.waitForTimeout(1200);
  const help = (await item.locator('.help').first().innerText()).replace(/\s+/g, ' ');
  await shot(page, item, 'B10-05', { pad: 8 });
  log(L, `B10-05 help-blockTimer: "${help.slice(0, 600)}"`);
  await close();
};

steps.mb = async () => {
  const { page } = await login('admin');
  await page.goto(`${BASE}/job/team-mb/`);
  const notice0 = (await page.locator('#main-panel .jenkins-alert').allInnerTexts()).map((t) => t.replace(/\s+/g, ' ').slice(0, 140));
  await page.locator('#side-panel a:has-text("Scan Multibranch Pipeline Now")').click();
  await sleep(25000);
  const branches = (await api('admin', '/job/team-mb/api/json?tree=jobs[name,nextBuildNumber,builds[number,result]]')).json?.jobs || [];
  const tb = (await changeRows(/TRIGGER_BLOCKED,team-mb/)).slice(0, 2);
  log(L, `B10-06 team-mb (created under run control): notices before scan ${JSON.stringify(notice0)}; after scan branches ${JSON.stringify(branches.map((b) => `${b.name} next=${b.nextBuildNumber} builds=${b.builds.length}`))}; TRIGGER_BLOCKED ${tb.join(' || ')}`);
  if (branches[0]) {
    await page.goto(`${BASE}/job/team-mb/job/${branches[0].name}/`);
    await shot(page, ['#side-panel', '#main-panel .jenkins-alert'], 'B10-06-1-branch-not-activated', { pad: 6 });
    log(L, `B10-06 branch ${branches[0].name} config: ${await lockOf(`team-mb/job/${branches[0].name}`)}; notices ${JSON.stringify((await page.locator('#main-panel .jenkins-alert').allInnerTexts()).map((t) => t.replace(/\s+/g, ' ').slice(0, 160)))}`);
  }
  // activate the multibranch project through its own activation request
  const rq = await login('requester');
  await rq.page.goto(`${BASE}/job/team-mb/`);
  const link = rq.page.locator('#main-panel a:has-text("Request activation"), #side-panel a:has-text("Activation")').first();
  const hasLink = await link.count();
  let url = `${BASE}/job/team-mb/batch-control-activation`;
  if (hasLink) { await link.click(); await rq.page.waitForLoadState('load'); url = rq.page.url(); } else await rq.page.goto(url);
  await rq.page.fill('textarea[name="reason"]', 'Put the multibranch project into service (B10-06, D-46c).');
  await rq.page.locator('input[name="approvers"][value="approver-1"] + label').click();
  await Promise.all([rq.page.waitForNavigation(), rq.page.locator('button:has-text("Submit Request")').click()]);
  const aurl = rq.page.url();
  const ap = await login('approver-1');
  await ap.page.goto(aurl); await ap.page.fill('form[name="approve"] textarea[name="comment"]', 'ok');
  await Promise.all([ap.page.waitForLoadState('load'), ap.page.locator('form[name="approve"] button').first().click()]);
  await page.goto(`${BASE}/job/team-mb/`);
  await page.locator('#side-panel a:has-text("Scan Multibranch Pipeline Now")').click();
  await sleep(30000);
  const after = (await api('admin', '/job/team-mb/api/json?tree=jobs[name,builds[number,result]]')).json.jobs;
  const runs = (await api('admin', '/batch-control/history/runs.csv')).text.split('\n').filter((l) => l.includes('team-mb/'));
  log(L, `B10-06 activation link on team-mb page: ${hasLink} (${url.replace(BASE, '')}); activation ${aurl.replace(BASE, '')} approved; after re-scan ${JSON.stringify(after.map((b) => `${b.name}: ${b.builds.map((x) => '#' + x.number + ' ' + x.result).join(',')}`))}; runs.csv rows ${runs.length}: ${runs.slice(0, 2).join(' || ')}`);
  await page.goto(`${BASE}/job/team-mb/job/${after[0].name}/`);
  await shot(page, ['#side-panel', '#main-panel .jenkins-alert'], 'B10-06-2-branch-after-activation', { pad: 6 });
  await close();
};

steps.off = async () => {
  const { page } = await login('admin');
  await setGlobal(page, { runControlEnabled: false });
  await page.goto(`${BASE}/view/all/newJob`); await page.fill('#name', 'b10-off');
  await page.locator('label:has-text("Freestyle project")').first().click();
  await Promise.all([page.waitForNavigation(), page.locator('#ok-button').click()]);
  await page.waitForTimeout(1500);
  const flags = await page.locator('[name="_.approvalRequired"]').count() ? { approvalRequired: await page.locator('[name="_.approvalRequired"]').first().isChecked(), blockTimer: await page.locator('[name="_.blockTimer"]').first().isChecked() } : 'no Batch Control section';
  await Promise.all([page.waitForNavigation(), page.locator('button[name="Submit"]').click()]);
  await setGlobal(page, { runControlEnabled: true });
  const act = (await groovy(`println io.jenkins.plugins.batchcontrol.ui.ActivationView.isActivated(jenkins.model.Jenkins.get().getItemByFullName('b10-off'))`)).trim();
  await page.goto(`${BASE}/job/b10-off/`);
  log(L, `B10-07 created with run control off: form ${JSON.stringify(flags)}; stored ${await lockOf('b10-off')}; activated after run control back on: ${act}; notice ${JSON.stringify((await page.locator('#main-panel .jenkins-alert').allInnerTexts()).map((t) => t.replace(/\s+/g, ' ').slice(0, 90)))}`);
  await close();
};

const wanted = process.argv.slice(2);
try { for (const s of wanted) { console.log(`== ${s}`); await steps[s](); } } finally { await close(); }
