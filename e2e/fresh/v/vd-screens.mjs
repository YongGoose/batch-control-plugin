// Re-verification D: FD-08 (grant form position), FD-09 (user on approved runs), FD-11 (dates), DD-01.
import { login, BASE, shot, text, log, close, sleep } from '../lib.mjs';
const flat = (s) => s.replace(/\s+/g, ' ');

// FD-08
{
  const { page, context } = await login('requester');
  await page.goto(`${BASE}/job/fresh-daily/`);
  await page.locator('#tasks a', { hasText: 'Request Change Permission' }).click();
  await page.waitForLoadState('load');
  const pos = await page.evaluate(() => ({ y: window.scrollY, top: Math.round(document.querySelector('form[action$="grants/create"]').getBoundingClientRect().top), heads: [...document.querySelectorAll('#main-panel h2')].map((h) => h.innerText) }));
  log('FD-08 landing', page.url(), JSON.stringify(pos), 'scope prefilled:', await page.locator('input[name="scopeFullName"]').inputValue());
  await page.screenshot({ path: 'screenshots/verify/FD08-landing-viewport.png' });
  await Promise.all([page.waitForLoadState('load'), page.locator('form[action$="grants/create"] button[name="Submit"]').click()]);
  await sleep(500);
  const pos2 = await page.evaluate(() => ({ y: window.scrollY, err: Math.round(document.querySelector('.jenkins-alert-danger, .error')?.getBoundingClientRect().top ?? -1) }));
  log('FD-08 after invalid submit', page.url(), JSON.stringify(pos2), (await page.$$eval('.error', (es) => es.map((e) => e.innerText.trim()))).join(' | '));
  await page.screenshot({ path: 'screenshots/verify/FD08-invalid-viewport.png' });
  // neighbour: lists still present and paged
  const t = flat(await text(page));
  log('FD-08 neighbour sections', (t.match(/(Grant Requests|Active Grants|Ended Grants|New Grant Request)/g) || []).join(','), '| paging:', (t.match(/Page \d+[^.]{0,40}/g) || []).join(' ; '));
  await context.close();
}

// FD-09
for (const u of ['auditor', 'approver-1']) {
  const { page, context } = await login(u);
  await page.goto(`${BASE}/batch-control/dashboard/`);
  const row = page.locator('tbody tr', { hasText: 'fresh-token' }).filter({ hasText: 'APPROVED_REQUEST' }).first();
  log('FD-09 dashboard', u, flat(await row.innerText().catch(() => 'none')));
  if (u === 'auditor') await shot(page, [page.locator('#main-panel thead th', { hasText: 'User' }).first(), row], 'FD09-dashboard-user');
  await page.goto(`${BASE}/batch-control/history/?kind=runs&user=requester`);
  const rows = (await page.locator('tbody tr').allInnerTexts()).map(flat);
  log('FD-09 history user=requester', u, rows.length, rows.filter((r) => r.includes('APPROVED_REQUEST')).slice(0, 3));
  if (u === 'auditor') {
    const csv = await context.request.get(`${BASE}/batch-control/history/runs.csv?user=requester`);
    const lines = (await csv.text()).split('\n');
    log('FD-09 runs.csv user=requester', lines.length - 2, lines.filter((l) => l.includes('APPROVED_REQUEST')).slice(0, 3));
    // the older fresh-daily#1 (recorded before the fix) - LIMITATIONS 42
    log('FD-09 old record fresh-daily#1 found by user filter?', rows.some((r) => r.startsWith('#1 fresh-daily ')));
  }
  await context.close();
}

// FD-11
{
  const { page, context } = await login('auditor');
  await page.goto(`${BASE}/batch-control/history/?kind=runs&from=2026-13-45&to=yesterday`);
  log('FD-11 bad URL dates', (await page.$$eval('.error, .jenkins-alert-danger, .jenkins-alert-warning', (es) => es.map((e) => e.innerText.trim()))).join(' | '), '| from kept:', await page.locator('input[name="from"]').inputValue());
  await shot(page, page.locator('#main-panel form').first(), 'FD11-bad-url-dates');
  await page.goto(`${BASE}/batch-control/history/?kind=runs`);
  await page.fill('input[name="from"]', '2026-09-30');
  await page.fill('input[name="to"]', '2026-09-01');
  await page.fill('input[name="job"]', 'fresh');
  await Promise.all([page.waitForLoadState('load'), page.locator('#main-panel form button, #main-panel form input[type=submit]').first().click()]);
  const errs = await page.$$eval('.error, .jenkins-alert-danger', (es) => es.map((e) => e.innerText.trim()));
  log('FD-11 reversed', page.url().replace(/Jenkins-Crumb=[^&]*/, 'Jenkins-Crumb=…').slice(0, 200), '| errors', errs, '| kept from/to/job', await page.locator('input[name="from"]').inputValue(), await page.locator('input[name="to"]').inputValue(), await page.locator('input[name="job"]').inputValue(), '| rows', await page.locator('tbody tr').count());
  await shot(page, page.locator('#main-panel form').first(), 'FD11-reversed-dates');
  // neighbour: a valid range still works and CSV links carry it
  await page.goto(`${BASE}/batch-control/history/?kind=runs&from=2026-09-30&to=2026-09-30&job=fresh-token`);
  log('FD-11 neighbour valid range rows', await page.locator('tbody tr').count(), await page.$$eval('#main-panel a[href*="runs.csv"]', (as) => as.map((a) => a.getAttribute('href'))));
  await context.close();
}

// DD-01: header menu
for (const u of ['requester', 'nobc']) {
  const { page, context } = await login(u);
  await page.goto(`${BASE}/`);
  await page.locator('header button[aria-label="More actions"], header button[tooltip="More actions"]').first().click();
  await page.waitForTimeout(600);
  const items = await page.$$eval('.tippy-box a', (as) => as.map((a) => a.innerText.trim()));
  log('DD-01', u, 'header menu', items);
  if (u === 'requester') await page.screenshot({ path: 'screenshots/verify/DD01-header-menu.png', clip: { x: 700, y: 0, width: 580, height: 300 } });
  await context.close();
}
await close();
