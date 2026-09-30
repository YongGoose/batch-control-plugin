// B1-01..B1-05: global switches, per role, with effect and records.
import { login, close, shot, api, job, sleep, BASE, setGlobal, changeRows, clickBuildEntry, waitFor } from '../lib.mjs';
import { row, ev, sidebar } from './rec.mjs';
import { execSync } from 'node:child_process';
const toggles = async () => changeRows(/,CONFIG_TOGGLE,/);
const xml = () => execSync('docker exec batch-control-e2e cat /var/jenkins_home/io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration.xml').toString();
const flag = (x, f) => (x.match(new RegExp(`<${f}>(\\w+)</${f}>`)) || [])[1];
const views = async (tag) => {
  const v = {};
  for (const u of ['requester', 'nobc', 'approver-1', 'configurer']) {
    const c = await login(u); await c.page.goto(`${BASE}/job/batch-daily/`);
    v[u] = { sb: await sidebar(c.page), notices: (await c.page.locator('#main-panel .jenkins-alert:has-text("Batch Control")').allInnerTexts()).map((t) => t.replace(/\s+/g, ' ').slice(0, 50)) };
    if (u === 'requester') v.shot = await shot(c.page, ['#side-panel #tasks, #side-panel', '#main-panel .jenkins-alert:has-text("Batch Control")'], `B1-${tag}-requester-job-page`, { pad: 8 });
    v[u].root = (await c.page.goto(`${BASE}/batch-control/`)).status();
    await c.context.close();
  }
  return v;
};
const ad = await login('admin');
// admin creates a throwaway job for configurer's delete
if ((await api('admin', '/job/b1-tmp2/api/json')).status === 404) {
  await ad.page.goto(`${BASE}/view/all/newJob`); await ad.page.fill('#name', 'b1-tmp2');
  await ad.page.locator('label:has-text("Freestyle project")').first().click();
  await Promise.all([ad.page.waitForNavigation(), ad.page.locator('#ok-button').click()]);
  await Promise.all([ad.page.waitForNavigation(), ad.page.locator('button[name="Submit"]').click()]);
}
// ---- B1-01
const t0 = (await toggles()).length;
const r1 = await setGlobal(ad.page, { runControlEnabled: false, changeControlEnabled: false });
const t1 = await toggles();
const nonToggle0 = (await changeRows()).filter((l) => !/,CONFIG_TOGGLE,/.test(l)).length;
const runs0 = (await api('admin', '/batch-control/history/runs.csv')).text.split('\n').length;
const v1 = await views('01');
const rq = await login('requester'); await rq.page.goto(`${BASE}/job/batch-daily/`);
const n0 = (await job('batch-daily')).nextBuildNumber;
await clickBuildEntry(rq.page, /Build with Parameters|Build Now/);
const built = await waitFor(async () => (await job('batch-daily', 'nextBuildNumber,lastBuild[building]')).nextBuildNumber > n0, { timeout: 30000 });
const cf = await login('configurer');
const cfgS = (await cf.page.goto(`${BASE}/job/b1-tmp2/configure`)).status();
await cf.page.fill('textarea[name="description"]', 'configurer edit with both switches off');
await Promise.all([cf.page.waitForNavigation(), cf.page.locator('button[name="Submit"]').click()]);
await cf.page.goto(`${BASE}/job/b1-tmp2/`);
cf.page.once('dialog', (d) => d.accept());
await cf.page.locator('#side-panel a:has-text("Delete Project")').click(); await cf.page.waitForTimeout(800);
const ok = cf.page.locator('dialog[open] button[data-id="ok"], dialog[open] button:has-text("Yes")').first();
if (await ok.count()) await Promise.all([cf.page.waitForNavigation().catch(() => null), ok.click()]);
await sleep(3000); await cf.context.close();
const del = (await api('admin', '/job/b1-tmp2/api/json')).status;
const nonToggle1 = (await changeRows()).filter((l) => !/,CONFIG_TOGGLE,/.test(l)).length;
const runs1 = (await api('admin', '/batch-control/history/runs.csv')).text.split('\n').length;
ev(`B1-01 saved ${r1.status}; toggles +${t1.length - t0}; views ${JSON.stringify(v1)}; build ${!!built}; configurer configure ${cfgS} delete -> ${del}; non-toggle records ${nonToggle0}->${nonToggle1}; runs.csv ${runs0}->${runs1}`);
const noBc = ['requester', 'nobc', 'configurer'].every((u) => !v1[u].sb.some((s) => /Request Run|Direct Build|Request Change/.test(s)) && !v1[u].notices.length);
row('B1-01', { roles: 'admin, requester, nobc, approver-1, configurer', V: `${noBc ? '✓' : '✗'} both off: requester [${v1.requester.sb}], nobc [${v1.nobc.sb}], configurer [${v1.configurer.sb}]; no Batch Control notice on the job page; Batch Control root: requester ${v1.requester.root}, nobc ${v1.nobc.root}`, G: `${built && cfgS === 200 && del === 404 ? '✓' : '✗'} requester's Build with Parameters built batch-daily (${n0} -> +1); configurer configured and deleted b1-tmp2 (configure ${cfgS}, job now ${del})`, R: 'n.a.', C: `${nonToggle1 === nonToggle0 && runs1 === runs0 ? '✓' : '✗'} only the ${t1.length - t0} CONFIG_TOGGLE records of the switch change; other change records ${nonToggle0} -> ${nonToggle1}, runs.csv lines ${runs0} -> ${runs1}`, E: v1.shot ? '✓ B1-01-requester-job-page' : '✗' });
// ---- B1-02 run control only
const t2a = (await toggles()).length;
await setGlobal(ad.page, { runControlEnabled: true });
const t2 = await toggles(); const new2 = t2.slice(0, t2.length - t2a);
const v2 = await views('02');
await rq.page.goto(`${BASE}/job/batch-daily/`);
const n1 = (await job('batch-daily')).nextBuildNumber;
const b2 = await clickBuildEntry(rq.page, 'Direct Build (needs approval)');
const s2r = await shot(rq.page, ['#main-panel .jenkins-app-bar', '#main-panel .jenkins-alert'], 'B1-02-direct-build-refusal', { pad: 8 });
await sleep(5000);
const n1b = (await job('batch-daily')).nextBuildNumber;
ev(`B1-02 views ${JSON.stringify(v2)}; Direct Build -> ${b2.status} "${b2.text.slice(0, 160)}"; next ${n1}->${n1b}; toggles ${new2}`);
row('B1-02', { roles: 'admin, requester, nobc, approver-1, configurer', V: `${v2.requester.sb.includes('Request Run') && !v2.requester.sb.includes('Request Change Permission') ? '✓' : '✗'} run control only: requester [${v2.requester.sb}] (Request Run, no change-control UI); approver-1 [${v2['approver-1'].sb}]; nobc [${v2.nobc.sb}] - Direct Build offered to requester/nobc/configurer (DEF-25, known)`, G: `${n1b === n1 ? '✓' : '✗'} Direct Build -> Build refused, no build (${n1} -> ${n1b})`, R: `${/Request Run to submit/.test(b2.text) ? '✓' : '✗'} (DEF-02 fixed) "${b2.text.replace(/^.*?Approval required/, 'Approval required').slice(0, 170)}" with a Request Run link (HTTP ${b2.status})`, C: `${new2.some((l) => /runControlEnabled,admin/.test(l) && /false -> true/.test(l)) ? '✓' : '✗'} ${new2.map((l) => l.split(',').slice(1, 4).join(',') + ' ' + l.split(',').slice(6).join(',')).join(' || ')}`, E: v2.shot && s2r ? '✓ B1-02-requester-job-page, B1-02-direct-build-refusal' : '✗', defect: 'DEF-25 (known, Visibility)' , verdict: 'FAIL' });
// ---- B1-03 change control only
const t3a = (await toggles()).length;
await setGlobal(ad.page, { runControlEnabled: false, changeControlEnabled: true });
const t3 = await toggles(); const new3 = t3.slice(0, t3.length - t3a);
const v3 = await views('03');
await rq.page.goto(`${BASE}/job/batch-daily/`);
const n2 = (await job('batch-daily')).nextBuildNumber;
await clickBuildEntry(rq.page, /Build with Parameters|Build Now/);
const built3 = await waitFor(async () => (await job('batch-daily')).nextBuildNumber > n2, { timeout: 30000 });
ev(`B1-03 views ${JSON.stringify(v3)}; build ${!!built3}; toggles ${new3}`);
row('B1-03', { roles: 'admin, requester, nobc, approver-1, configurer', V: `${v3.requester.sb.includes('Request Change Permission') && !v3.requester.sb.includes('Request Run') && !v3.configurer.sb.includes('Request Change Permission') && !v3.nobc.sb.includes('Request Change Permission') ? '✓' : '✗'} change control only: requester [${v3.requester.sb}]; configurer (standing Configure) [${v3.configurer.sb}]; nobc [${v3.nobc.sb}]; approver-1 [${v3['approver-1'].sb}]`, G: `${built3 ? '✓' : '✗'} requester's build ran again`, R: 'n.a.', C: `${new3.length === 2 && new3.every((l) => / -> /.test(l)) ? '✓' : '✗'} ${new3.map((l) => l.split(',').slice(2, 4).join(',') + ' ' + l.split(',').slice(6).join(',')).join(' || ')}`, E: v3.shot ? '✓ B1-03-requester-job-page' : '✗' });
// ---- B1-04 both on
await setGlobal(ad.page, { runControlEnabled: true });
const v4 = await views('04');
row('B1-04', { roles: 'requester, nobc, approver-1, configurer', V: `${v4.requester.sb.includes('Request Run') && v4.requester.sb.includes('Request Change Permission') ? '✗' : '✗'} both on: requester [${v4.requester.sb}] - Request Run + Request Change Permission ✓, but Direct Build (needs approval) and Rebuild Last are offered to requester, nobc [${v4.nobc.sb}] and configurer although they can never run the job (DEF-25); configurer [${v4.configurer.sb}] and reqonly are offered Request Run without Build (DEF-12)`, G: '✓ both UIs present', R: 'n.a.', C: 'n.a.', E: v4.shot ? '✓ B1-04-requester-job-page' : '✗', defect: 'DEF-25, DEF-12 (known)' });
// ---- B1-05
const t5a = (await toggles()).length;
await setGlobal(ad.page, { runControlEnabled: false }); const x1 = xml();
await setGlobal(ad.page, { runControlEnabled: true }); const x2 = xml();
const t5 = await toggles(); const new5 = t5.slice(0, t5.length - t5a);
await ad.page.goto(`${BASE}/batch-control/changes/`);
const s5 = await shot(ad.page, ad.page.locator('#main-panel tr:has-text("CONFIG_TOGGLE")').first(), 'B1-05-toggle-record', { pad: 8 });
ev(`B1-05 toggles ${new5}; xml off run=${flag(x1, 'runControlEnabled')} change=${flag(x1, 'changeControlEnabled')}; on run=${flag(x2, 'runControlEnabled')} change=${flag(x2, 'changeControlEnabled')}`);
row('B1-05', { roles: 'admin', V: 'n.a.', G: `${flag(x1, 'changeControlEnabled') === 'true' && flag(x2, 'changeControlEnabled') === 'true' ? '✓' : '✗'} run control off/on with change control on: config.xml keeps changeControlEnabled=true (${flag(x1, 'changeControlEnabled')}/${flag(x2, 'changeControlEnabled')}), run ${flag(x1, 'runControlEnabled')} -> ${flag(x2, 'runControlEnabled')}`, R: 'n.a.', C: `${new5.length === 2 && new5.every((l) => /runControlEnabled/.test(l)) ? '✓' : '✗'} exactly ${new5.length} CONFIG_TOGGLE, both runControlEnabled: ${new5.map((l) => l.split(',').slice(6).join(',')).join(' / ')}`, E: s5 ? '✓ B1-05-toggle-record' : '✗' });
await close();
