// Re-audit A-02 / PR-01 / B5-11 (DEF-01 fix): customize-build-now on batch-cbn, as each role.
// Usage: node a02.mjs <job> <rowIds comma> [label regex]
import { login, close, shot, job, queue, BASE, requestRun, waitFor, changeRows, sleep } from '../lib.mjs';
import { row, ev, mainText, sidebar } from './rec.mjs';
const J = process.argv[2] || 'batch-cbn';
const IDS = (process.argv[3] || 'A-02').split(',');
const vis = {}; const sh = [];
for (const u of ['requester', 'nobc', 'approver-1', 'reqonly', 'admin']) {
  const { context, page } = await login(u);
  await page.goto(`${BASE}/job/${J}/`);
  const sb = await sidebar(page);
  const notice = page.locator('.jenkins-alert:has-text("manual runs of this job need")').first();
  const nt = (await notice.innerText().catch(() => '')).replace(/\s+/g, ' ');
  const nlink = await notice.locator('a').evaluateAll((as) => as.map((a) => `${a.innerText.trim()}->${a.getAttribute('href')}`)).catch(() => []);
  vis[u] = { sb, nt: nt.slice(0, 200), nlink };
  if (u !== 'admin') sh.push(!!(await shot(page, ['#side-panel #tasks, #side-panel', notice], `${IDS[0]}-0-${u}-sidebar-notice`, { pad: 8 })));
  await context.close();
}
ev(`${J} visibility ${JSON.stringify(vis)}`);
const before = (await job(J, 'nextBuildNumber')).nextBuildNumber;
const recBefore = (await changeRows(new RegExp(`,${J},`))).length;
// requester clicks the build entry
const { context, page } = await login('requester');
await page.goto(`${BASE}/job/${J}/`);
const entry = page.locator('#side-panel a').filter({ hasText: /Direct Build|Run it now|Build Now/ }).first();
const label = (await entry.innerText()).trim();
await entry.click();
await page.waitForTimeout(1500);
const toast = page.locator('#notification-bar, .jenkins-notification').first();
const tt = (await toast.innerText().catch(() => '')).replace(/\s+/g, ' ');
const notice = page.locator('.jenkins-alert:has-text("manual runs of this job need")').first();
const s1 = await shot(page, [entry, toast, notice], `${IDS[0]}-1-requester-click-toast-and-notice`, { pad: 12 });
await sleep(20000);
const after = (await job(J, 'nextBuildNumber')).nextBuildNumber;
const q = (await queue()).filter((i) => i.task.name === J).length;
const recAfter = (await changeRows(new RegExp(`,${J},`))).length;
// follow the notice link
await notice.locator('a').first().click(); await page.waitForLoadState('load');
const formUrl = page.url(); const formOk = await page.locator('form[name="batch-control-request"]').count();
const s2 = await shot(page, 'form[name="batch-control-request"]', `${IDS[0]}-2-notice-link-opens-form`, { pad: 8 });
// approved path, exactly once
const url = await requestRun(page, `/job/${J}/`, { reason: `Audit ${IDS[0]}: approved run of ${J}`, approvers: ['approver-1'] });
const ap = await login('approver-1');
await ap.page.goto(url);
await ap.page.fill('form[name="approve"] textarea[name="comment"]', 'ok');
await Promise.all([ap.page.waitForLoadState('load'), ap.page.locator('form[name="approve"] button').first().click()]);
const done = await waitFor(async () => { const j = await job(J, 'nextBuildNumber,lastBuild[number,building,result]'); return j.nextBuildNumber > after && j.lastBuild && !j.lastBuild.building ? j : null; }, { timeout: 90000 });
await sleep(15000);
const fin = await job(J, 'nextBuildNumber,lastBuild[number,result,actions[causes[shortDescription]]]');
await ap.page.goto(url);
const det = await mainText(ap.page);
const s3 = await shot(ap.page, '#main-panel table', `${IDS[0]}-3-request-executed`, { pad: 8 });
const cause = fin.lastBuild.actions.flatMap((x) => (x.causes || []).map((c) => c.shortDescription)).join('; ');
ev(`${J} clicked "${label}" toast "${tt}" next ${before}->${after} queue ${q} records ${recBefore}->${recAfter}; notice link -> ${formUrl} form=${formOk}; approved: builds added ${fin.nextBuildNumber - after}, #${fin.lastBuild.number} ${fin.lastBuild.result} "${cause}"; detail ${det.slice(0, 200)}`);
for (const id of IDS) row(id, {
  roles: 'requester, nobc, approver-1, reqonly, admin',
  V: `${!vis.requester.sb.some((s) => /Run it now|Build Now/.test(s)) && vis.requester.sb.includes('Request Run') && !vis['approver-1'].sb.some((s) => /Direct Build/.test(s)) && !vis.nobc.sb.some((s) => /Direct Build|Rebuild/.test(s)) && !vis.reqonly.sb.includes('Request Run') ? '✓' : '✗ (DEF-25: Direct Build/Rebuild Last offered to Build holders who can never use them here; DEF-12: Request Run offered to reqonly who cannot submit)'} requester [${vis.requester.sb}]; nobc [${vis.nobc.sb}]; approver-1 [${vis['approver-1'].sb}]; reqonly [${vis.reqonly.sb}]; notice link only for Request holders (requester ${vis.requester.nlink.length}, reqonly ${vis.reqonly.nlink.length}, nobc ${vis.nobc.nlink.length}, approver-1 ${vis['approver-1'].nlink.length})`,
  G: `${after === before && q === 0 && fin.nextBuildNumber - after === 1 ? '✓' : '✗'} click "${label}": no build (next ${before}->${after}, queue ${q}); Request Run -> approver-1 -> exactly one build #${fin.lastBuild.number} ${fin.lastBuild.result} "${cause.slice(0, 90)}"`,
  R: `${/manual runs of this job need an approved run request/.test(vis.requester.nt) && vis.requester.nlink.length ? '✓' : '✗'} the click still only raises core's toast "${tt}", but the same page now states "${vis.requester.nt.slice(0, 120)}..." with "${vis.requester.nlink.map((l) => l.split('->')[0])}" (opens the form: ${formOk ? 'yes' : 'no'}); nobc/approver-1 are told whom to ask`,
  C: `n.a. (a first manual click is not a re-run; SPEC 6 asks for no record; change records for ${J} ${recBefore}->${recAfter})`,
  E: [s1, s2, s3, ...sh].every(Boolean) ? `✓ ${IDS[0]}-0-*, -1-requester-click-toast-and-notice, -2-notice-link-opens-form, -3-request-executed` : '✗',
  note: 'DEF-01 fixed by a permanent notice on the job page; the toast itself is unchanged', defect: 'DEF-25, DEF-12 (known)',
});
await close();
