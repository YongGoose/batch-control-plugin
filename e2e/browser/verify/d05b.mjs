// D-05 (faked clock, :8081): a November CONFIGURE and a November run, in the browser.
import { chromium } from 'playwright';
import { password, sleep } from '../lib.mjs';
const BASE = 'http://localhost:8081';
const b = await chromium.launch({ channel: 'chrome' });
const page = await (await b.newContext({ viewport: { width: 1280, height: 900 }, locale: 'en-US' })).newPage();
page.setDefaultTimeout(30000);
await page.goto(`${BASE}/login`); await page.fill('#j_username', 'admin'); await page.fill('input[name="j_password"]', password('admin'));
await Promise.all([page.waitForLoadState('load'), page.click('button[name="Submit"], button[type="submit"]')]);
await page.goto(`${BASE}/job/batch-unstable/`);
await page.locator('#side-panel a').filter({ hasText: /Build Now/ }).first().click();
await sleep(15000);
const r = await page.request.get(`${BASE}/job/batch-unstable/api/json?tree=builds[number,timestamp,result]`);
console.log(JSON.stringify(await r.json()));
await b.close();
