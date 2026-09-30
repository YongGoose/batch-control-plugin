// B11 change recording.
import { login, close, shot, api, BASE, log, changeRows, sleep, setGlobal, clickBuildEntry, job, waitFor } from './lib.mjs';
import { execSync } from 'node:child_process';
const L = 'section-b.log';
const since = async (n) => (await changeRows()).slice(0, (await changeRows()).length - n);
const count = async () => (await changeRows()).length;
const ad = await login('admin'); const p = ad.page;
// B11-03 password parameter default
let c = await count();
await p.goto(`${BASE}/job/batch-daily/configure`); await p.waitForTimeout(1500);
const pwBtn = p.locator('button:has-text("Change Password")').first();
await pwBtn.scrollIntoViewIfNeeded(); await pwBtn.click(); await p.waitForTimeout(500);
await p.locator('input[type="password"]:visible').first().fill('new-default-s3cr3t-B11');
await Promise.all([p.waitForNavigation(), p.locator('button[name="Submit"]').click()]);
await sleep(1500);
const rec3 = (await since(c)).find((l) => l.includes(',CONFIGURE,batch-daily,'));
await p.goto(`${BASE}/batch-control/changes/`);
const row3 = p.locator('#main-panel tr:has-text("batch-daily"):has-text("CONFIGURE")').first();
const d3 = row3.locator('summary, a:has-text("Diff")').first(); if (await d3.count()) { await d3.click(); await p.waitForTimeout(400); }
const diff3 = (await row3.innerText()).split('\n').filter((l) => /^\s*[+-]\s*</.test(l)).join(' | ');
await shot(p, row3, 'B11-03', { pad: 8 });
const leak = execSync(`docker exec batch-control-e2e sh -c "grep -rl 'new-default-s3cr3t-B11' /var/jenkins_home/batch-control/ | head -3; echo done"`).toString().replace(/\n/g, ' ');
log(L, `B11-03 password default changed: record ${rec3 ? rec3.split(',').slice(1, 4).join('/') : 'NONE'}; diff lines "${diff3.slice(0, 300)}"; store files with the plaintext: ${leak}`);
// B11-05 multibranch re-index writes no CONFIGURE for team-mb
const mbRows = (await changeRows(/,CONFIGURE,team-mb,/));
log(L, `B11-05 CONFIGURE records for team-mb after several scans: ${mbRows.length} (${mbRows.map((l) => l.split(',')[3] + '@' + l.split(',')[4]).join(', ')}) - one expected for the admin's Discover-branches edit`);
// B11-07 rename a job and a folder
c = await count();
await p.goto(`${BASE}/job/b10-rest/confirm-rename`); await p.fill('input[name="newName"]', 'b10-rest-renamed'); await p.waitForTimeout(700);
await Promise.all([p.waitForNavigation(), p.locator('button[name="Submit"], button:has-text("Rename")').first().click()]);
if ((await api('admin', '/job/b11-f/api/json')).status === 404) {
  await api('admin', '/createItem?name=b11-f&mode=com.cloudbees.hudson.plugins.folder.Folder&from=&json=%7B%7D', { method: 'POST', body: new URLSearchParams({ name: 'b11-f', mode: 'com.cloudbees.hudson.plugins.folder.Folder' }), headers: { 'Content-Type': 'application/x-www-form-urlencoded' } });
  for (const j of ['j1', 'j2']) await api('admin', `/job/b11-f/createItem?name=${j}`, { method: 'POST', body: `<project><builders/></project>`, headers: { 'Content-Type': 'application/xml' } });
}
const c7 = await count();
await p.goto(`${BASE}/job/b11-f/confirm-rename`); await p.fill('input[name="newName"]', 'b11-g'); await p.waitForTimeout(700);
await Promise.all([p.waitForNavigation(), p.locator('button[name="Submit"], button:has-text("Rename")').first().click()]);
await sleep(1500);
log(L, `B11-07 job rename -> ${(await since(c)).slice(-1).map((l) => l.split(',').slice(1, 4).join('/') + ' ' + l.split(',').slice(6).join(',').slice(0, 80)).join('')}; folder b11-f (2 jobs) -> b11-g: ${(await since(c7)).map((l) => l.split(',').slice(1, 3).join('/') + ' ' + l.split(',').slice(6).join(',').slice(0, 60)).join(' || ')}`);
await p.goto(`${BASE}/batch-control/changes/`);
await shot(p, p.locator('#main-panel tr:has-text("b11-")'), 'B11-07', { pad: 8 });
// B11-09 change control off, run control on: recording continues
await setGlobal(p, { changeControlEnabled: false });
c = await count();
await p.goto(`${BASE}/job/b10-cli/configure`); await p.waitForTimeout(1200);
await p.fill('textarea[name="description"]', 'edited with change control off (B11-09)');
await Promise.all([p.waitForNavigation(), p.locator('button[name="Submit"]').click()]);
await sleep(1500);
const r9 = (await since(c)).map((l) => l.split(',').slice(1, 4).join('/'));
await setGlobal(p, { changeControlEnabled: true });
log(L, `B11-09 change control off: edit of b10-cli -> records ${JSON.stringify(r9)}`);
// B11-10 month navigation, paging, empty month
await p.goto(`${BASE}/batch-control/changes/`);
const ctrl = await p.locator('#main-panel a.jenkins-button, #main-panel button, #main-panel input').evaluateAll((es) => es.filter((e) => e.offsetParent !== null).map((e) => `${e.tagName}:${e.getAttribute('type') || ''}:${e.name || ''}:${(e.innerText || e.value || '').trim().slice(0, 25)}`));
const footer = (await p.locator('#main-panel').innerText()).match(/Page \d+[^\n]*/g);
await shot(p, p.locator('#main-panel .jenkins-app-bar, #main-panel form, #main-panel .jenkins-buttons-row').first(), 'B11-10-1-controls', { pad: 8 });
const prev = p.locator('#main-panel a:has-text("Previous"), #main-panel a:has-text("Prev"), #main-panel a:has-text("Older month"), #main-panel a:has-text("◀")').first();
let prevRes = 'no previous-month control';
if (await prev.count()) { await prev.click(); await p.waitForLoadState('load'); prevRes = `${p.url().replace(BASE, '')} "${(await p.locator('#main-panel').innerText()).replace(/\s+/g, ' ').slice(0, 200)}"`; await shot(p, '#main-panel', 'B11-10-2-previous-month', { pad: 6 }); }
const older = p.locator('#main-panel a:has-text("Older")').first();
log(L, `B11-10 Change Records controls ${JSON.stringify(ctrl)}; footer ${JSON.stringify(footer)}; previous month -> ${prevRes}`);
await p.goto(`${BASE}/batch-control/changes/`);
if (await older.count()) { await older.click(); await p.waitForLoadState('load'); log(L, `B11-10 Older -> ${p.url().replace(BASE, '')} footer ${JSON.stringify((await p.locator('#main-panel').innerText()).match(/Page \d+[^\n]*/g))}`); }
// B11-12 POST config.xml to a multibranch child
c = await count();
const mbx = (await api('admin', '/job/team-mb/job/main/config.xml', { raw: true }));
const r12 = await api('admin', '/job/team-mb/job/main/config.xml', { method: 'POST', body: mbx.text.replace('<description/>', '<description>posted (B11-12)</description>'), headers: { 'Content-Type': 'application/xml' } });
await sleep(1500);
log(L, `B11-12 POST config.xml to team-mb/main -> ${r12.status}; new records ${JSON.stringify((await since(c)).map((l) => l.split(',').slice(1, 4).join('/')))}`);
await close();
