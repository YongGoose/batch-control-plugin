// B21-01/02: README "Installation" followed literally on a clean Jenkins (port 8081, no plugins, no security).
import { chromium } from 'playwright';
import path from 'node:path';
import { shot, log, sleep } from './lib.mjs';
const L = 'section-b.log';
const C = 'http://localhost:8081';
const hpi = path.resolve('../../target/batch-control.hpi');
const b = await chromium.launch({ channel: 'chrome' });
const page = await (await b.newContext({ viewport: { width: 1280, height: 900 } })).newPage();
page.setDefaultTimeout(30000);
await page.goto(`${C}/manage/pluginManager/advanced`);
const deploy = page.locator('input[type="file"]').first();
await deploy.setInputFiles(hpi);
await shot(page, deploy.locator('xpath=ancestor::*[contains(@class,"jenkins-section") or self::form][1]'), 'B21-02-1-deploy-form', { pad: 8 });
await Promise.all([page.waitForNavigation({ waitUntil: 'load' }).catch(() => null), page.locator('button:has-text("Deploy"), input[type="submit"][value*="Deploy"], button:has-text("Upload")').first().click()]);
await sleep(8000);
for (let i = 0; i < 30; i++) {
  const t = await page.locator('#main-panel').innerText().catch(() => '');
  if (/Success|Failure|failed/i.test(t) && !/Pending|Installing|Downloading/.test(t)) break;
  await sleep(4000); await page.reload().catch(() => {});
}
const status = (await page.locator('#main-panel').innerText()).replace(/\s+/g, ' ');
await shot(page, '#main-panel', 'B21-02-2-install-status', { pad: 6 });
log(L, `B21-02 Deploy Plugin (upload target/batch-control.hpi) on a clean 2.568.3 -> ${page.url().replace(C, '')}: "${status.slice(0, 700)}"`);
const pl = await (await fetch(`${C}/pluginManager/api/json?depth=1`)).json();
log(L, `B21-01 plugins on the clean instance after the upload: ${pl.plugins.map((p) => `${p.shortName}${p.active ? '' : '(inactive)'}`).join(', ')}`);
// does the plugin show up without a restart? what does the System page offer?
await page.goto(`${C}/manage/configure`); await page.waitForTimeout(2000);
const bcSec = await page.locator('.jenkins-section__title:text-is("Batch Control")').count();
await page.goto(`${C}/manage/configureSecurity/`); await page.waitForTimeout(1500);
const opts = await page.locator('select').evaluateAll((ss) => ss.flatMap((s) => [...s.options].map((o) => o.text)).filter((t) => /Batch|Matrix|Role|Logged|Anyone|Legacy/.test(t)));
log(L, `B21-01 without a restart: System has a Batch Control section ${bcSec}; Authorization options ${JSON.stringify(opts)}`);
await b.close();
