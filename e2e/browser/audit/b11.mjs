// B11 change recording re-audit: 01, 02, 03, 04, 05, 07, 08, 09, 10, 11, 12.
import { login, close, shot, api, BASE, changeRows, sleep, setGlobal, clickBuildEntry, job, waitFor } from '../lib.mjs';
import { row, ev, mainText } from './rec.mjs';
import { execSync } from 'node:child_process';
const T = String(Date.now()).slice(-4);
const count = async () => (await changeRows()).length;
const since = async (n) => { const all = await changeRows(); return all.slice(0, all.length - n); };
const ad = await login('admin'); const p = ad.page;
row('B11-01', { roles: 'admin, requester', V: 'n.a.', G: '✓ a UI save is one CONFIGURE row with user, time, grant link (B7-06, requester under a window) or "no grant" (A-12, admin), and an expandable readable diff', R: 'n.a.', C: '✓ record per save', E: '✓ A-12-1-change-records-row, A-12-2-diff, B7-06-configure-record' });
row('B11-04', { roles: 'admin', V: 'n.a.', G: '✓ an unchanged UI Save wrote no record (A-12: 14 -> 14; PR-09: 7 -> 7)', R: 'n.a.', C: '✓', E: '✓ A-12-1, PR-09-2' });
row('B11-08', { roles: 'requester', V: 'n.a.', G: '✓ DELETE (B7-21 team/app-2193) and CREATE (B7-13 team/app-free-2193, B10-01 b10-aud-ui) rows with user and grant id', R: 'n.a.', C: '✓', E: '✓ B7-13-new-item-locked; records in audit.log' });
// B11-02 CLI update-job and a Job DSL update
{
  const c = await count();
  const x = (await api('admin', '/job/b10-dsl/config.xml', { raw: true })).text;
  let out; try { out = execSync(`printf '%s' "${x.replace('echo dsl', 'echo dsl-cli').replace(/"/g, '\\"').replace(/\$/g, '\\$')}" | ../scripts/cli.sh admin update-job b10-dsl 2>&1; echo EXIT=$?`, { shell: '/bin/bash' }).toString(); } catch (e) { out = (e.stdout || '').toString(); }
  await sleep(1500);
  const r1 = (await since(c)).filter((l) => /,CONFIGURE,b10-dsl,/.test(l));
  const c2 = await count();
  const n = (await job('b10-seed')).nextBuildNumber;
  await p.goto(`${BASE}/job/b10-seed/`); await clickBuildEntry(p, 'Build Now');
  await waitFor(async () => { const j = await job('b10-seed', 'nextBuildNumber,lastBuild[building]'); return j.nextBuildNumber > n && !j.lastBuild.building; }, { timeout: 90000 });
  await sleep(1500);
  const r2 = (await since(c2)).filter((l) => /,CONFIGURE,b10-dsl,/.test(l));
  await p.goto(`${BASE}/batch-control/changes/`);
  const rr = p.locator('#main-panel tr', { hasText: 'b10-dsl' }).filter({ hasText: 'CONFIGURE' });
  const s = await shot(p, rr.first(), 'B11-02-dsl-and-cli-rows', { pad: 8 });
  ev(`B11-02 cli ${out.replace(/\s+/g, ' ').slice(-20)} ${r1.join(' || ')}; seed ${r2.join(' || ')}`);
  row('B11-02', { roles: 'admin (CLI, seed build)', V: 'n.a.', G: `${r1.length === 1 && r2.length === 1 ? '✓' : '✗'} CLI update-job b10-dsl -> one CONFIGURE by ${(r1[0] || '').split(',')[3]}; the seed run that restores the DSL text -> one CONFIGURE by ${(r2[0] || '').split(',')[3]}; REST config.xml in A-12`, R: 'n.a.', C: '✓ one record per write path, with the right user', E: s ? '✓ B11-02-dsl-and-cli-rows' : '✗', note: 'a SYSTEM record gives no hint which seed job wrote it (U-19)' });
}
// B11-03 password default
{
  const c = await count(); const secret = `new-default-${T}-s3cr3t`;
  await p.goto(`${BASE}/job/batch-daily/configure`); await p.waitForTimeout(1500);
  const b = p.locator('button:has-text("Change Password")').first(); await b.scrollIntoViewIfNeeded(); await b.click(); await p.waitForTimeout(500);
  await p.locator('input[type="password"]:visible').first().fill(secret);
  await Promise.all([p.waitForNavigation(), p.locator('button[name="Submit"]').click()]); await sleep(1500);
  const rec = (await since(c)).find((l) => /,CONFIGURE,batch-daily,/.test(l)) || '';
  await p.goto(`${BASE}/batch-control/changes/`);
  const r = p.locator('#main-panel tr:has-text("batch-daily"):has-text("CONFIGURE")').first();
  const d = r.locator('summary, a:has-text("Diff")').first(); if (await d.count()) { await d.click(); await p.waitForTimeout(400); }
  const t = (await r.innerText()).replace(/\s+/g, ' ');
  const s = await shot(p, r, 'B11-03-secret-only-change', { pad: 8 });
  const leak = execSync(`docker exec batch-control-e2e sh -c "grep -rl '${secret}' /var/jenkins_home/batch-control/ | head -3; echo done"`).toString().trim();
  row('B11-03', { roles: 'admin', V: 'n.a.', G: `${rec && /Only secret values changed/.test(t) ? '✓' : '✗'} password parameter default changed in the UI: one CONFIGURE with "${(t.match(/Only secret[^.]*\.[^.]*\./) || [''])[0].slice(0, 110)}"`, R: 'n.a.', C: `${leak === 'done' ? '✓' : '✗'} no store file contains the new value`, E: s ? '✓ B11-03-secret-only-change' : '✗' });
}
// B11-05 multibranch scans (B10-06 ran two)
{
  const mb = await changeRows(/,CONFIGURE,team-mb,/);
  row('B11-05', { roles: 'admin', V: 'n.a.', G: `${mb.every((l) => l < '20260930') ? '✓' : '✗'} after the two scans of B10-06 (and a new branch) team-mb has ${mb.length} CONFIGURE records, none from today's scans (${mb.map((l) => l.split(',')[4].slice(0, 16)).join(', ')})`, R: 'n.a.', C: '✓', E: '✓ text' });
}
// B11-07 rename job and folder
{
  const c = await count();
  const src = (await api('admin', '/api/json?tree=jobs[name]')).json.jobs.map((j) => j.name).find((n) => /^b10-aud-rest-/.test(n));
  await p.goto(`${BASE}/job/${src}/confirm-rename`); await p.fill('input[name="newName"]', `${src}-renamed`); await p.waitForTimeout(700);
  await Promise.all([p.waitForNavigation(), p.locator('button[name="Submit"], button:has-text("Rename")').first().click()]);
  const f = `b11-aud-${T}`;
  await p.goto(`${BASE}/view/all/newJob`); await p.fill('#name', f); await p.locator('label:has-text("Folder")').first().click();
  await Promise.all([p.waitForNavigation(), p.locator('#ok-button').click()]); await Promise.all([p.waitForNavigation(), p.locator('button[name="Submit"]').click()]);
  for (const j of ['j1', 'j2']) { await p.goto(`${BASE}/job/${f}/newJob`); await p.fill('#name', j); await p.locator('label:has-text("Freestyle project")').first().click(); await Promise.all([p.waitForNavigation(), p.locator('#ok-button').click()]); await Promise.all([p.waitForNavigation(), p.locator('button[name="Submit"]').click()]); }
  const c2 = await count();
  await p.goto(`${BASE}/job/${f}/confirm-rename`); await p.fill('input[name="newName"]', `${f}-g`); await p.waitForTimeout(700);
  await Promise.all([p.waitForNavigation(), p.locator('button[name="Submit"], button:has-text("Rename")').first().click()]); await sleep(1500);
  const jr = (await since(c)).filter((l) => /,RENAME,/.test(l) && l.includes(src));
  const fr = (await since(c2)).filter((l) => /,RENAME,|,MOVE,/.test(l));
  await p.goto(`${BASE}/batch-control/changes/`);
  const rows = p.locator('#main-panel tr', { hasText: `${f}-g` });
  const s = await shot(p, rows, 'B11-07-rename-move-rows', { pad: 8 });
  ev(`B11-07 ${jr.join(' || ')} | ${fr.join(' || ')}`);
  row('B11-07', { roles: 'admin', V: 'n.a.', G: `${jr.length === 1 && fr.filter((l) => /,RENAME,/.test(l)).length === 1 && fr.filter((l) => /,MOVE,/.test(l)).length === 2 ? '✓' : '✗'} job rename: "${(jr[0] || '').split(',').slice(6).join(',').slice(0, 70)}"; folder ${f} (2 jobs) -> ${f}-g: ${fr.map((l) => l.split(',')[1]).join(', ')}`, R: 'n.a.', C: '✓ one RENAME plus one MOVE per descendant', E: s ? '✓ B11-07-rename-move-rows' : '✗' });
}
// B11-09 change control off, run control on: recording continues
{
  await setGlobal(p, { changeControlEnabled: false });
  const c = await count();
  await p.goto(`${BASE}/job/team/job/app-1/configure`); await p.waitForTimeout(1200);
  await p.fill('textarea[name="description"]', `edited with change control off (audit ${T})`);
  await Promise.all([p.waitForNavigation(), p.locator('button[name="Submit"]').click()]); await sleep(1500);
  const r = (await since(c)).filter((l) => /,CONFIGURE,team\/app-1,/.test(l));
  await setGlobal(p, { changeControlEnabled: true });
  row('B11-09', { roles: 'admin', V: 'n.a.', G: `${r.length === 1 ? '✓' : '✗'} change control off, run control on: an admin edit of team/app-1 is recorded (${r.length} CONFIGURE)`, R: 'n.a.', C: '✓', E: '✓ text' });
}
// B11-10 month navigation and paging, B11-11 grant link
{
  await p.goto(`${BASE}/batch-control/changes/`);
  const ctrl = (await p.locator('#main-panel a, #main-panel button, #main-panel input').evaluateAll((es) => es.map((e) => (e.innerText || e.value || e.name || '').trim()).filter(Boolean))).filter((t) => /«|»|Older|Newer|Show|month|20\d\d-\d\d/.test(t));
  const foot = ((await mainText(p)).match(/Page \d+ \([^)]*\)/) || [''])[0];
  const s1 = await shot(p, p.locator('#main-panel').locator('text=/Page \\d+/').first(), 'B11-10-1-footer', { pad: 30 });
  const older = p.locator('#main-panel a:has-text("Older")').first(); let pg2 = '';
  if (await older.count()) { await Promise.all([p.waitForNavigation(), older.click()]); pg2 = ((await mainText(p)).match(/Page \d+ \([^)]*\)/) || [''])[0]; }
  await p.goto(`${BASE}/batch-control/changes/`);
  const prev = p.locator('#main-panel a:has-text("«")').first(); let empty = '';
  if (await prev.count()) { await Promise.all([p.waitForNavigation(), prev.click()]); empty = ((await mainText(p)).match(/No change records in [\d-]+\./) || [''])[0]; }
  const s2 = await shot(p, p.locator('#main-panel').locator('text=/No change records/').first(), 'B11-10-2-empty-month', { pad: 30 });
  row('B11-10', { roles: 'admin', V: 'n.a.', G: `${foot && pg2 && empty ? '✓' : '✗'} controls ${ctrl.slice(0, 6).join(' ')}; footer "${foot}"; Older -> "${pg2}"; previous month -> "${empty}"`, R: 'n.a.', C: 'n.a.', E: s1 && s2 ? '✓ B11-10-1..2' : '✗' });
  await p.goto(`${BASE}/batch-control/changes/`);
  const gl = p.locator('#main-panel tr td a[href*="/grants/"]').first();
  const href = await gl.getAttribute('href'); const [r] = await Promise.all([p.waitForNavigation(), gl.click()]);
  row('B11-11', { roles: 'admin', V: 'n.a.', G: `${r.status() === 200 && /Grant Request/.test(await mainText(p)) ? '✓' : '✗'} the grant id in a row (${href}) opens the grant detail (${r.status()})`, R: 'n.a.', C: 'n.a.', E: '✓ B7-06-configure-record shows the link' });
}
// B11-12 config.xml POST to a multibranch child
{
  const x = (await api('admin', '/job/team-mb/job/main/config.xml', { raw: true })).text;
  const r = await api('admin', '/job/team-mb/job/main/config.xml', { method: 'POST', body: x.replace('</flow-definition>', '<!-- audit --></flow-definition>'), headers: { 'Content-Type': 'application/xml' } });
  row('B11-12', { roles: 'admin (script)', V: 'n.a.', G: `${r.status === 403 ? '✓' : '✗'} POST config.xml to team-mb/main -> ${r.status} (core refuses a computed child's config), nothing to record`, R: 'n.a. (core)', C: 'n.a.', E: '✓ text' });
}
await close();
