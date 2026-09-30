// Re-verification: job-page build paths on approval-required jobs (A-02, PR-01/06/07/08, B5-11/18/19, B5-22).
// Ruling (test commits 1d4d742/45bbf5f): core draws its build link for every Item/Build holder, so "Direct Build (needs
// approval)" may stay; its click must be refused in plain words; Rebuild is hidden (RebuildValidator); Request Run and the
// form link need Request + Job/Build (D-38, DEF-12).
import { login, close, shot, job, queue, BASE, requestRun, waitFor, changeRows, sleep } from '../lib.mjs';
import { row, ev, sidebar } from '../audit/rec.mjs';
const [J, idList] = process.argv.slice(2); const IDS = idList.split(',');
const NOTICE = 'text=Batch Control: manual runs of this job need';
const vis = {}; const sh = [];
for (const u of ['requester', 'nobc', 'reqonly', 'manager', 'approver-1', 'admin']) {
  const { context, page } = await login(u);
  await page.goto(`${BASE}/job/${J}/`);
  const sb = await sidebar(page);
  const n = page.locator('#main-panel').locator(NOTICE).first();
  const nt = (await n.locator('xpath=..').innerText().catch(() => '')).replace(/\s+/g, ' ');
  const nl = await page.locator('#main-panel a:has-text("Open the run request form")').count();
  vis[u] = { sb: sb.filter((x) => /Build|Rebuild|Request Run|Run it now/.test(x)), nt: nt.slice(0, 220), nl };
  if (u !== 'admin') sh.push(!!(await shot(page, ['#side-panel #tasks, #side-panel', n.locator('xpath=..')], `${IDS[0]}-0-${u}`, { pad: 8 })));
  await context.close();
}
const n0 = (await job(J, 'nextBuildNumber')).nextBuildNumber;
const { context, page } = await login('requester');
await page.goto(`${BASE}/job/${J}/`);
const entry = page.locator('#side-panel a').filter({ hasText: /Direct Build|Run it now|Build Now/ }).first();
const label = (await entry.innerText()).trim();
await entry.click(); await page.waitForTimeout(1500);
const toast = (await page.locator('#notification-bar, .jenkins-notification').first().innerText().catch(() => '')).replace(/\s+/g, ' ');
const s1 = await shot(page, [entry, '#notification-bar, .jenkins-notification', page.locator('#main-panel').locator(NOTICE).first().locator('xpath=..')], `${IDS[0]}-1-click`, { pad: 10 });
await sleep(15000);
const n1 = (await job(J, 'nextBuildNumber')).nextBuildNumber; const q = (await queue()).filter((i) => i.task.name === J).length;
const url = await requestRun(page, `/job/${J}/`, { reason: `Verify ${IDS[0]}: approved run of ${J}`, approvers: ['approver-1'] });
const a = await login('approver-1'); await a.page.goto(url); await a.page.fill('form[name="approve"] textarea[name="comment"]', 'ok');
await Promise.all([a.page.waitForLoadState('load'), a.page.locator('form[name="approve"] button').first().click()]); await a.context.close();
await waitFor(async () => { const j = await job(J, 'nextBuildNumber,lastBuild[building]'); return j.nextBuildNumber > n1 && !j.lastBuild.building; }, { timeout: 90000 }); await sleep(12000);
const fin = await job(J, 'nextBuildNumber,lastBuild[number,result]');
await page.goto(url); const s2 = await shot(page, '#main-panel table', `${IDS[0]}-2-executed`, { pad: 8 });
ev(`V1 ${J} ${JSON.stringify(vis)} click "${label}" toast "${toast}" next ${n0}->${n1} q ${q}; approved +${fin.nextBuildNumber - n1} #${fin.lastBuild.number} ${fin.lastBuild.result}`);
const reqOK = vis.requester.sb.includes('Request Run') && vis.admin.sb.includes('Request Run') && !vis.reqonly.sb.includes('Request Run') && !vis.manager.sb.includes('Request Run') && !vis['approver-1'].sb.includes('Request Run');
const linkOK = vis.requester.nl && !vis.reqonly.nl && !vis.manager.nl && !vis.nobc.nl;
const rebuildHidden = !Object.values(vis).some((v) => v.sb.some((x) => /Rebuild/.test(x)));
const direct = Object.entries(vis).filter(([, v]) => v.sb.some((x) => /Direct Build/.test(x))).map(([k]) => k);
for (const id of IDS) row(id, {
  roles: 'requester, nobc, reqonly, manager, approver-1, admin',
  V: `${reqOK && linkOK && rebuildHidden ? '✓' : '✗'} Request Run and "Open the run request form" only for Request + Job/Build holders (requester, admin; not reqonly, manager, approver-1, nobc: ${reqOK && linkOK}); Rebuild Last gone (${rebuildHidden}); "Direct Build (needs approval)" still offered to ${direct.join(', ')} - accepted per the ruling that core draws its build link (not in SPEC/DECISIONS)`,
  G: `${n1 === n0 && q === 0 && fin.nextBuildNumber - n1 === 1 ? '✓' : '✗'} click "${label}": no build (${n0} -> ${n1}, queue ${q}); Request Run -> approver-1 -> exactly one build #${fin.lastBuild.number} ${fin.lastBuild.result}`,
  R: `${/manual runs of this job need an approved run request/.test(vis.requester.nt) ? '✓' : '✗'} toast "${toast}" (core), next to the job-page notice; reqonly/manager are told: "${(vis.reqonly.nt.match(/(You|Ask)[^.]*\./) || [''])[0].slice(0, 110)}"`,
  C: 'n.a. (a first manual click is not a re-run)',
  E: [s1, s2, ...sh].every(Boolean) ? `✓ ${IDS[0]}-0-*, -1-click, -2-executed` : '✗',
});
await close();
