// Re-verification E: FD-07 evidence, FD-10/DD-04 (incident rerun), FD-12/FD-13/DD-03 (build links vs the documents),
// and the re-run links of LIMITATIONS 41 (neighbours).
import { login, BASE, shot, text, api, log, close, sleep } from '../lib.mjs';
const flat = (s) => s.replace(/\s+/g, ' ');
const INC = process.argv[2];
const nb = async (j) => (await api('admin', `/job/${j}/api/json?tree=builds%5Bnumber%5D`)).json().builds.length;
const changes = async (job) => {
  const { page, context } = await login('admin');
  await page.goto(`${BASE}/batch-control/history/?kind=changes&job=${job}`);
  const r = (await page.locator('tbody tr').allInnerTexts()).map(flat).filter((x) => /TRIGGER|MARKER|HELD|ACTIV/.test(x)).slice(0, 4);
  await context.close();
  return r;
};

// FD-07 evidence
{
  const { page, context } = await login('auditor');
  await page.goto(`${BASE}/batch-control/history/?kind=changes&job=fresh-cron`);
  const rows = page.locator('tbody tr');
  await shot(page, [rows.nth(0), rows.nth(2)], 'FD07-record-after-hold');
  await context.close();
}

// FD-10 / DD-04
for (const u of ['approver-1', 'requester', 'admin']) {
  const { page, context } = await login(u);
  const r = await page.goto(`${BASE}/batch-control/incidents/${INC}/`);
  const t = flat(await text(page));
  const i = t.indexOf('Request Rerun');
  log('FD-10', u, r.status(), '|', i >= 0 ? t.slice(i, i + 450) : t.slice(0, 250));
  const sec = page.locator('#main-panel h2, #main-panel h3', { hasText: 'Request Rerun' });
  await shot(page, (await sec.count()) ? [sec, page.locator('#main-panel').locator('p, div', { hasText: /rerun|Request Run/i }).last()] : '#main-panel', `FD10-${u}-rerun-section`);
  const links = await page.$$eval('#main-panel a', (as) => as.filter((a) => /Request Run|request/i.test(a.innerText)).map((a) => `${a.innerText.trim()} -> ${a.getAttribute('href')}`));
  log('   links', links);
  for (const l of links) { const h = l.split(' -> ')[1]; const s = await context.request.get(new URL(h, page.url()).href); log('   link status for', u, h, s.status()); }
  if (u === 'admin') {
    log('   rerun form fields', await page.$$eval('form[action$="/rerun"] [name]', (es) => es.map((e) => `${e.name}:${e.type}`)));
  }
  await context.close();
}
// requester reruns through Request Run on the job page, as the incident page says
{
  const { page, context } = await login('requester');
  await page.goto(`${BASE}/job/fresh-fail-renamed/batch-control/`);
  log('FD-10 requester Request Run form fields', await page.$$eval('form[action="submit"] [name]', (es) => es.map((e) => e.name)));
  await context.close();
}

// FD-12 / FD-13 / DD-03: observe the documented behaviour again
for (const [u, job] of [['requester', 'fresh-token'], ['admin', 'fresh-token'], ['nobc', 'fresh-token']]) {
  const { page, context } = await login(u);
  await page.goto(`${BASE}/job/${job}/`);
  const b = page.locator('#tasks a', { hasText: /Direct Build/ }).first();
  const before = await nb(job);
  log('FD-13', u, 'Direct Build visible', await b.count());
  if (await b.count()) {
    await b.click(); await sleep(1500);
    const toast = await page.locator('.jenkins-notification').allInnerTexts();
    log('FD-12', u, 'toast', toast, '| builds', before, '->', await nb(job));
    if (u === 'requester') await shot(page, page.locator('.jenkins-notification').first(), 'FD12-toast');
    const notice = page.locator('#main-panel .jenkins-alert', { hasText: 'manual runs' });
    log('   notice', flat(await notice.innerText().catch(() => 'none')).slice(0, 200));
  }
  await context.close();
}
log('FD-12 records for fresh-token', await changes('fresh-token'));

// LIMITATIONS 41 neighbours: which re-run links are drawn on build pages now
for (const [u, p] of [['requester', '/job/fresh-daily/1/'], ['requester', '/job/fresh-pipe/1/'], ['requester', '/job/fresh-fail-renamed/3/'], ['admin', '/job/fresh-pipe/1/'], ['requester', '/job/fresh-daily/']]) {
  const { page, context } = await login(u);
  await page.goto(BASE + p);
  log('L41', u, p, '| sidebar:', flat(await page.locator('#tasks').innerText()));
  await shot(page, page.locator('#tasks').first(), `L41-${u}-${p.replace(/\W+/g, '_')}sidebar`);
  await context.close();
}
// Pipeline "Rebuild" (workflow-cps replay/rebuild) by the requester: refused? recorded per attempt (D-51)?
{
  const { page, context } = await login('requester');
  await page.goto(`${BASE}/job/fresh-pipe/1/`);
  const rb = page.locator('#tasks a', { hasText: /^Rebuild$/ });
  if (await rb.count()) {
    await rb.click(); await page.waitForLoadState('load');
    await Promise.all([page.waitForLoadState('load'), page.locator('#main-panel button[name="Submit"], #main-panel button.jenkins-button--primary').first().click()]);
    await sleep(1500);
    log('D-51 pipeline Rebuild by requester ->', page.url(), flat(await text(page)).slice(0, 250));
    await shot(page, '#main-panel', 'L41-pipeline-rebuild-refused');
  }
  await context.close();
  log('D-51 records fresh-pipe', await changes('fresh-pipe'));
}
// naginator Retry by the requester (person): per-attempt record (D-51)
{
  const { page, context } = await login('requester');
  await page.goto(`${BASE}/job/fresh-fail-renamed/3/`);
  const rt = page.locator('#tasks a', { hasText: /Retry/ });
  if (await rt.count()) { await rt.click(); await page.waitForLoadState('load'); await sleep(1500); }
  await context.close();
  log('D-51 records fresh-fail-renamed', await changes('fresh-fail-renamed'));
}
await close();
