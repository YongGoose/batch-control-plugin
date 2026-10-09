import { login, close, BASE, shot } from './lib.mjs';
const { page } = await login('requester');
await page.goto(BASE + '/job/batch-daily/batch-control-activation');
const crumbs = page.locator('.jenkins-breadcrumbs, #breadcrumbBar').first();
const title = page.locator('#main-panel .jenkins-app-bar, #main-panel h1').first();
await shot(page, [crumbs, title], 'A-11-activation-crumbs', { pad: 8 });
const hrefs = await page.locator('.jenkins-breadcrumbs a').evaluateAll(as => as.map(a => a.innerText.trim() + ' -> ' + a.getAttribute('href')));
console.log(hrefs);
await close();
