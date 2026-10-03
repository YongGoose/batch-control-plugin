import { login, close, shot, api, BASE, log, changeRows, sleep } from './lib.mjs';
const L = 'section-b.log';
const branches = async () => ((await api('admin', '/job/team-mb/api/json?tree=jobs[name,builds[number,result,actions[causes[shortDescription]]]]')).json?.jobs || []);
const fmt = (bs) => JSON.stringify(bs.map((b) => `${b.name}: ${b.builds.map((x) => '#' + x.number + ' ' + x.result).join(',') || 'no builds'}`));
async function request(action, reason) {
  const rq = await login('requester');
  await rq.page.goto(`${BASE}/job/team-mb/batch-control-activation`);
  await rq.page.fill('textarea[name="reason"]', reason);
  await rq.page.locator('input[name="approvers"][value="approver-1"] + label').click();
  await Promise.all([rq.page.waitForNavigation(), rq.page.locator('button:has-text("Submit Request")').click()]);
  const url = rq.page.url(); await rq.context.close();
  const ap = await login('approver-1');
  await ap.page.goto(url); await ap.page.fill('form[name="approve"] textarea[name="comment"]', 'ok');
  await Promise.all([ap.page.waitForLoadState('load'), ap.page.locator('form[name="approve"] button').first().click()]);
  await ap.context.close();
  return url;
}
async function scan(page) { await page.goto(`${BASE}/job/team-mb/`); await page.locator('#side-panel a:has-text("Scan Multibranch Pipeline Now")').click(); await sleep(30000); }
const ad = await login('admin');
await sleep(20000);
log(L, `B10-06 after adding Discover branches (auto scan, team-mb activated): ${fmt(await branches())}`);
await request('HOLD', 'Hold the multibranch project (B10-06).');
const n0 = (await changeRows(/TRIGGER_BLOCKED,team-mb/)).length;
// a new branch appears while held
const { execSync } = await import('node:child_process');
execSync(`docker exec batch-control-e2e sh -c 'cd /var/jenkins_home/repos/demo-work && git checkout -q -b feature-2 && git -c user.name=e2e -c user.email=e2e@e2e.local commit -q --allow-empty -m f2 && git push -q origin feature-2'`);
await scan(ad.page);
const held = await branches();
const tb = await changeRows(/TRIGGER_BLOCKED,team-mb/);
await ad.page.goto(`${BASE}/job/team-mb/job/feature-2/`);
const notices = (await ad.page.locator('#main-panel .jenkins-alert').allInnerTexts()).map((t) => t.replace(/\s+/g, ' ').slice(0, 200));
await shot(ad.page, ['#side-panel', '#main-panel .jenkins-alert'], 'B10-06-1-branch-held', { pad: 6 });
log(L, `B10-06 held: new branch feature-2 after scan ${fmt(held)}; TRIGGER_BLOCKED +${tb.length - n0} ${tb[0] || ''}; feature-2 notices ${JSON.stringify(notices)}; feature-2 has no configure form (no lock possible): ${(await ad.page.goto(`${BASE}/job/team-mb/job/feature-2/configure`)).status()}`);
await request('ACTIVATE', 'Activate the multibranch project again (B10-06).');
await scan(ad.page);
const act = await branches();
const runs = (await api('admin', '/batch-control/history/runs.csv')).text.split('\n').filter((l) => l.includes('team-mb/'));
await ad.page.goto(`${BASE}/job/team-mb/job/feature-2/`);
await shot(ad.page, ['#side-panel', '#main-panel .jenkins-alert'], 'B10-06-2-branch-activated', { pad: 6 });
log(L, `B10-06 activated again, scan: ${fmt(act)}; runs.csv rows for team-mb/*: ${runs.length} ${runs.slice(0, 2).join(' || ')}`);
await close();
