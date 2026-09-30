// FD-16: "Rebuild Last" appears in the sidebar of the refused run-request page.
import { login, BASE, shot, api, log, close, sleep } from '../lib.mjs';
const { page } = await login('requester');
await page.goto(`${BASE}/job/fresh-daily/batch-control/`);
await Promise.all([page.waitForLoadState('load'), page.click('button[name="Submit"]')]);
const rl = page.locator('#tasks a', { hasText: 'Rebuild Last' });
log('href', await rl.getAttribute('href'));
await shot(page, page.locator('#tasks').first(), 'FD16-rebuild-last-sidebar');
const before = (await api('admin', '/job/fresh-daily/api/json?tree=builds%5Bnumber%5D')).json().builds.length;
await rl.click(); await page.waitForLoadState('load'); await sleep(1500);
log('after click', page.url(), (await page.locator('#main-panel').innerText()).replace(/\s+/g, ' ').slice(0, 300), await page.locator('.jenkins-notification').allInnerTexts());
await shot(page, '#main-panel', 'FD16-rebuild-last-after-click');
const b = page.locator('#main-panel button.jenkins-button--primary, #main-panel button[name="Submit"]').first();
if (await b.count()) { await Promise.all([page.waitForLoadState('load'), b.click()]); await sleep(1500); log('after submit', page.url(), (await page.locator('#main-panel').innerText()).replace(/\s+/g, ' ').slice(0, 300)); await shot(page, '#main-panel', 'FD16-rebuild-last-after-submit'); }
log('builds', before, (await api('admin', '/job/fresh-daily/api/json?tree=builds%5Bnumber%5D')).json().builds.length);
await close();
