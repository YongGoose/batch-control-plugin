// D-05 (faked clock, :8081): November records just after the plugin-zone month boundary, made in the browser.
import { chromium } from 'playwright';
import { password, sleep } from '../lib.mjs';
const BASE = 'http://localhost:8081';
const b = await chromium.launch({ channel: 'chrome' });
const ctx = await b.newContext({ viewport: { width: 1280, height: 900 } }); const page = await ctx.newPage();
await page.goto(`${BASE}/login`); await page.fill('#j_username', 'admin'); await page.fill('input[name="j_password"]', password('admin'));
await Promise.all([page.waitForLoadState('load'), page.click('button[name="Submit"], button[type="submit"]')]);
// wait until the plugin clock (JVM zone Asia/Seoul) is in November
for (;;) { const r = await page.request.get(`${BASE}/api/json?tree=x`); const d = new Date(r.headers()['date']); if (d.getTime() >= Date.parse('2026-10-31T15:00:05Z')) break; await sleep(3000); }
await page.goto(`${BASE}/view/all/newJob`); await page.fill('#name', 'nov-job'); await page.locator('label:has-text("Freestyle project")').first().click();
await Promise.all([page.waitForNavigation(), page.locator('#ok-button').click()]);
await page.fill('textarea[name="description"]', 'created just after midnight KST on 1 November');
await Promise.all([page.waitForNavigation(), page.locator('button[name="Submit"]').click()]);
await page.goto(`${BASE}/job/batch-unstable/`);
await page.locator('#side-panel a').filter({ hasText: /Build Now/ }).first().click(); await sleep(15000);
const r = await page.request.get(`${BASE}/job/batch-unstable/api/json?tree=builds[number,timestamp,result]`);
console.log(JSON.stringify(await r.json()));
await b.close();
