// A-22: a grant behaves identically across a restart; one revoke, one record.
import { login, close, BASE, shot, log, api, waitFor } from './lib.mjs';
import { execSync } from 'node:child_process';
const L = 'section-a.log';
const phase = process.argv[2];
const cfg = async (user) => { const { context, page } = await login(user); const r = await page.goto(BASE + '/job/batch-pipeline/configure'); const s = r.status(); await context.close(); return s; };
if (phase === 'before') {
  // The request was submitted from the job sidebar (prefilled JOB batch-pipeline + CONFIGURE);
  // an accidental duplicate is cancelled by the requester through the detail page.
  const [keep, dup] = process.argv.slice(3);
  const rq = await login('requester');
  await rq.page.goto(`${BASE}/batch-control/grants/${dup}/`);
  rq.page.once('dialog', (d) => d.accept());
  await rq.page.locator('a:has-text("Cancel Request"), button:has-text("Cancel Request")').first().click();
  await rq.page.waitForTimeout(800);
  const c = rq.page.locator('dialog[open] button[data-id="ok"], dialog[open] button:has-text("Yes")').first();
  if (await c.count()) await Promise.all([rq.page.waitForLoadState('load'), c.click()]);
  await rq.page.waitForTimeout(1000);
  await rq.page.goto(`${BASE}/batch-control/grants/${dup}/`);
  log(L, `A-22 duplicate ${dup} cancelled by requester: ${(await rq.page.locator('#main-panel table').first().innerText()).replace(/\s+/g, ' ').slice(0, 120)}`);
  const ap = await login('approver-1');
  await ap.page.goto(`${BASE}/batch-control/grants/${keep}/`);
  await ap.page.fill('form[name="approve"] textarea[name="comment"]', 'ok');
  await Promise.all([ap.page.waitForLoadState('load'), ap.page.locator('form[name="approve"] button:has-text("Approve")').click()]);
  log(L, `A-22 grant ${keep} approved; requester GET configure = ${await cfg('requester')}`);
  await close();
} else if (phase === 'after') {
  log(L, `A-22 after restart: requester GET configure = ${await cfg('requester')}`);
  const m = await login('manager');
  await m.page.goto(BASE + '/batch-control/grants/');
  const active = m.page.locator('table:has(th:has-text("Expires"))').first();
  const rows = await active.locator('tbody tr').allInnerTexts();
  log(L, `A-22 manager Active Grants rows: ${rows.length} ${JSON.stringify(rows.map((r) => r.replace(/\s+/g, ' ')))}`);
  await shot(m.page, active, 'A-22-1-active-after-restart', { pad: 10 });
  const row = active.locator('tbody tr:has-text("batch-pipeline")').first();
  m.page.once('dialog', (d) => d.accept());
  await row.locator('a:has-text("Revoke"), button:has-text("Revoke")').first().click();
  await m.page.waitForTimeout(800);
  const confirm = m.page.locator('dialog[open] button:has-text("Yes"), dialog[open] button[data-id="ok"]').first();
  if (await confirm.count()) await Promise.all([m.page.waitForLoadState('load'), confirm.click()]);
  await m.page.waitForTimeout(1500);
  await m.page.goto(BASE + '/batch-control/grants/');
  log(L, `A-22 after revoke Active Grants rows mentioning batch-pipeline: ${await m.page.locator('table:has(th:has-text("Expires")) tbody tr:has-text("batch-pipeline")').count()}`);
  await shot(m.page, '#main-panel h2:has-text("Active Grants") ~ *, #main-panel', 'A-22-2-after-revoke', { pad: 10 });
  log(L, `A-22 requester GET configure after revoke = ${await cfg('requester')}`);
  const csv = (await api('admin', '/batch-control/history/changes.csv')).text.split('\n').filter((l) => l.includes('GRANT_REVOKE'));
  log(L, `A-22 GRANT_REVOKE records: ${csv.length} ${csv.join(' || ')}`);
  await close();
}
