// Scenario 7: what each role sees, and what each Batch Control URL answers to them.
import { login, BASE, shot, text, log, close } from './lib.mjs';

const flat = (s) => s.replace(/\s+/g, ' ');
const roles = ['admin', 'manager', 'approver-1', 'requester', 'auditor', 'nobc', 'reqonly', 'configurer', 'approver-disc'];
const urls = ['/batch-control/', '/batch-control/requests/', '/batch-control/activations/', '/batch-control/grants/', '/batch-control/changes/', '/batch-control/dashboard/', '/batch-control/incidents/', '/batch-control/history/', '/batch-control/history/runs.csv', '/batch-control-configuration/', '/job/fresh-daily/batch-control/', '/job/fresh-daily/batch-control-activation', '/batch-control/requests/20260930-080845-fvazja/'];
for (const role of roles) {
  const { page, context } = await login(role);
  await page.goto(`${BASE}/`);
  await page.locator('header button[tooltip="More actions"], header button[aria-label="More actions"]').first().click().catch(() => {});
  await page.waitForTimeout(500);
  const menu = await page.$$eval('.tippy-box a', (as) => as.map((a) => a.innerText.trim()));
  await page.keyboard.press('Escape');
  const st = [];
  for (const u of urls) {
    const r = await context.request.get(BASE + u, { maxRedirects: 0 });
    st.push(`${r.status()} ${u}`);
  }
  await page.goto(`${BASE}/batch-control/`);
  const landing = flat(await page.locator('#tasks, #side-panel').first().innerText().catch(() => '(none)'));
  await page.goto(`${BASE}/job/fresh-daily/`);
  const jobSide = flat(await page.locator('#tasks').innerText().catch(() => ''));
  const notices = await page.locator('#main-panel .jenkins-alert').allInnerTexts().catch(() => []);
  const noticeLinks = await page.$$eval('#main-panel .jenkins-alert a', (as) => as.map((a) => a.innerText.trim()));
  log('ROLE', role, '| header menu:', menu.join(', '), '| landing sidebar:', landing, '| job sidebar:', jobSide, '| notice links:', noticeLinks.join(', '), '| notices:', notices.length);
  log('   status:', st.join(' ; '));
  await shot(page, page.locator('#tasks').first(), `S7-${role}-job-sidebar`);
  await context.close();
}
await close();
