// B2 permissions and visibility, B3-13 (#26) is set up here too (secret-job run).
import { login, close, shot, api, job, waitFor, sleep, BASE, log, requestRun } from './lib.mjs';
const L = 'section-b.log';
const side = async (page) => (await page.locator('#side-panel a').allInnerTexts()).map((t) => t.trim()).filter(Boolean);
const SECTIONS = ['requests/', 'activations/', 'grants/', 'changes/', 'dashboard/', 'incidents/', 'history/'];

// B2-01 matrix group and tooltips
{
  const { page } = await login('admin');
  await page.goto(`${BASE}/manage/configureSecurity/`);
  const card = page.locator('.mas-card[data-sid="auditor"]');
  await card.locator('.mas-card__header').click(); await page.waitForTimeout(800);
  const grp = card.locator('fieldset:has(legend:text-is("Batch Control"))');
  const perms = await grp.locator('.mas-card__permission-name').allInnerTexts();
  const tips = await grp.locator('.mas-card__permission-info').evaluateAll((es) => es.map((e) => e.getAttribute('data-html-tooltip')));
  await grp.locator('.mas-card__permission-info').nth(perms.indexOf('ViewHistory')).hover(); await page.waitForTimeout(800);
  const tip = page.locator('.tippy-box').first();
  await shot(page, [grp, tip], 'B2-01', { pad: 10 });
  log(L, `B2-01 group "Batch Control": ${JSON.stringify(perms)}; tooltips: ${JSON.stringify(tips)}`);
  await page.context().close();
}
// B2-02 landing links per account + hidden section URLs answer 403
for (const u of ['requester', 'reqonly', 'approver-1', 'auditor', 'manager', 'approver-unlisted']) {
  const { page } = await login(u);
  await page.goto(`${BASE}/batch-control/`);
  const links = await side(page);
  const codes = [];
  for (const s of SECTIONS) codes.push(`${s}=${(await page.goto(`${BASE}/batch-control/${s}`)).status()}`);
  log(L, `B2-02 ${u}: links ${JSON.stringify(links)}; ${codes.join(' ')}`);
  if (u === 'approver-unlisted') { await page.goto(`${BASE}/batch-control/`); await shot(page, '#side-panel', 'B2-02-approver-unlisted', { pad: 8 }); }
  await page.context().close();
}
// B2-03 nobc: remaining URLs incl. a real request id and the summary
{
  const reqs = (await api('admin', '/batch-control/history/requests.csv')).text.split('\n')[1].split(',')[0];
  const out = [];
  for (const p of [`/batch-control/requests/${reqs}/`, '/batch-control/grants/create', '/batch-control/history/summary', '/batch-control/history/runs.csv', `/batch-control/requests/${reqs}/approve`]) {
    const g = await api('nobc', p); const q = await api('nobc', p, { method: 'POST', body: '' });
    out.push(`${p} GET ${g.status} POST ${q.status}`);
  }
  log(L, `B2-03 nobc: ${out.join(' | ')}`);
}
// B3-13 + B2-05: admin requests a run of team/secret-job for approver-disc (Discover only)
{
  const ad = await login('admin');
  const url = await requestRun(ad.page, '/job/team/job/secret-job/', { reason: 'Secret job, approver without read access (B3-13, #26).', approvers: ['approver-disc'] });
  const n0 = (await job('team/secret-job')).nextBuildNumber;
  const d = await login('approver-disc');
  const inbox = await d.page.goto(`${BASE}/batch-control/requests/`);
  const listed = await d.page.locator(`a[href*="${url.split('/requests/')[1]}"]`).count();
  await d.page.goto(url);
  const detail = (await d.page.locator('#main-panel').innerText()).replace(/\s+/g, ' ').slice(0, 300);
  await shot(d.page, '#main-panel', 'B3-13-1-approver-disc-decision', { pad: 8 });
  await d.page.fill('form[name="approve"] textarea[name="comment"]', 'ok without read');
  await Promise.all([d.page.waitForLoadState('load'), d.page.locator('form[name="approve"] button').first().click()]);
  const built = await waitFor(async () => { const j = await job('team/secret-job', 'nextBuildNumber,lastBuild[building]'); return j.nextBuildNumber > n0 && !j.lastBuild.building; }, { timeout: 60000 });
  await d.page.goto(url);
  const after = (await d.page.locator('#main-panel table').first().innerText()).replace(/\s+/g, ' ');
  const jobLink = await d.page.locator('#main-panel table a').evaluateAll((as) => as.map((a) => a.innerText + '->' + a.getAttribute('href')));
  await shot(d.page, '#main-panel table', 'B3-13-2-executed', { pad: 8 });
  log(L, `B3-13 approver-disc: inbox lists it=${listed}; detail "${detail}"; after approve built=${!!built}; "${after}"; links in summary ${JSON.stringify(jobLink)}`);
  // B2-05 approver-1 (no Read on secret-job) on the dashboard / history
  const a1 = await login('approver-1');
  await a1.page.goto(`${BASE}/batch-control/dashboard/`);
  const row = a1.page.locator('#main-panel tr:has-text("secret-job")').first();
  const rowText = (await row.innerText().catch(() => 'NO ROW')).replace(/\s+/g, ' ');
  const rowLinks = await row.locator('a').evaluateAll((as) => as.map((a) => a.innerText + '->' + a.getAttribute('href'))).catch(() => []);
  if (await row.count()) await shot(a1.page, row, 'B2-05-dashboard-secret-job', { pad: 8 });
  await a1.page.goto(`${BASE}/batch-control/history/`);
  const hrow = a1.page.locator('#main-panel tr:has-text("secret-job")').first();
  const hLinks = await hrow.locator('a').evaluateAll((as) => as.map((a) => a.innerText + '->' + a.getAttribute('href'))).catch(() => []);
  log(L, `B2-05 approver-1 dashboard row "${rowText}" links ${JSON.stringify(rowLinks)}; history row links ${JSON.stringify(hLinks)}`);
  for (const c of [ad, d, a1]) await c.context.close();
}
// B2-06 another requester (reqonly) must not see requester's request; B2-07 approver-unlisted
{
  const rows = (await api('admin', '/batch-control/history/requests.csv')).text.split('\n').slice(1);
  const mine = rows.find((l) => l.split(',')[4] === 'requester' && l.includes(',PENDING,'));
  const id = (mine || rows.find((l) => l.split(',')[4] === 'requester')).split(',')[0];
  for (const u of ['reqonly', 'approver-unlisted']) {
    const { page } = await login(u);
    await page.goto(`${BASE}/batch-control/requests/`);
    const ids = await page.locator('#main-panel table a').allInnerTexts();
    const body = (await page.locator('#main-panel').innerText()).replace(/\s+/g, ' ').slice(0, 200);
    const d = await page.goto(`${BASE}/batch-control/requests/${id}/`);
    log(L, `B2-0${u === 'reqonly' ? 6 : 7} ${u}: list shows ${ids.length} requests (${ids.includes(id) ? 'INCLUDES' : 'not'} ${id}); list text "${body}"; detail -> ${d.status()}`);
    await page.goto(`${BASE}/batch-control/requests/`);
    await shot(page, '#main-panel', `B2-0${u === 'reqonly' ? 6 : 7}-${u}-list`, { pad: 8 });
    await page.context().close();
  }
}
// B2-08 auditor sees rows of jobs he cannot read
{
  const { page } = await login('auditor');
  await page.goto(`${BASE}/batch-control/history/`);
  const jobs = [...new Set(await page.locator('#main-panel table tbody tr td:nth-child(2)').allInnerTexts())].map((t) => t.trim());
  const cronLink = await page.locator('#main-panel tr:has-text("batch-cron") a').count();
  log(L, `B2-08 auditor history jobs ${JSON.stringify(jobs.slice(0, 20))}; links on batch-cron rows ${cronLink}; auditor /job/batch-cron/ -> ${(await page.goto(`${BASE}/job/batch-cron/`)).status()}`);
  await page.goto(`${BASE}/batch-control/history/`);
  await shot(page, page.locator('#main-panel table tbody tr').first(), 'B2-08-auditor-history', { pad: 8 });
  await page.context().close();
}
// B2-09 Request Change Permission entry
for (const u of ['requester', 'configurer', 'admin', 'nobc']) {
  const { page } = await login(u);
  await page.goto(`${BASE}/job/batch-daily/`);
  const s = await side(page);
  log(L, `B2-09 ${u} on batch-daily: Request Change Permission ${s.includes('Request Change Permission') ? 'SHOWN' : 'absent'}`);
  if (u === 'requester') {
    await page.locator('#side-panel a:has-text("Request Change Permission")').click(); await page.waitForLoadState('load');
    const pre = await page.locator('#main-panel p.jenkins-description:has-text("Prefilled")').innerText().catch(() => '');
    log(L, `B2-09 click -> ${page.url()} kind=${(await page.locator('[data-batch-control-item-kind]').count()) ? await page.locator('[data-batch-control-item-kind]').first().getAttribute('data-batch-control-item-kind') : 'none'} scope=${await page.inputValue('input[name="scopeFullName"]')} configure=${await page.locator('input[name="actions"][value="CONFIGURE"]').isChecked()} "${pre}"`);
    await shot(page, ['#main-panel p.jenkins-description:has-text("Prefilled")', '[data-batch-control-item-kind]', 'input[name="scopeFullName"]', 'input[name="actions"][value="CONFIGURE"]'], 'B2-09', { pad: 10 });
  }
  await page.context().close();
}
await close();
