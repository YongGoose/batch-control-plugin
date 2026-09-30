import { login, close, shot, BASE } from '../lib.mjs';
const { page } = await login('nobc');
await page.goto(`${BASE}/job/batch-daily/`);
await page.locator('#side-panel a:has-text("Direct Build")').click(); await page.waitForLoadState('load');
await Promise.all([page.waitForNavigation({ waitUntil: 'load' }).catch(() => null), page.locator('#main-panel button:has-text("Build")').first().click()]);
await shot(page, ['#main-panel .jenkins-app-bar', '#main-panel .jenkins-alert', '#main-panel a:has-text("Back to")'], 'A-13-nobc-direct-build-refusal', { pad: 10 });
await close();
