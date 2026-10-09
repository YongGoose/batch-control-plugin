import { close, login, BASE, shot, clickBuildEntry, job } from './lib.mjs';
const before = await job('batch-daily');
const { page } = await login('nobc');
await page.goto(BASE + '/job/batch-daily/');
const r = await clickBuildEntry(page, 'Direct Build (needs approval)');
console.log(r);
const links = await page.locator('#main-panel a').evaluateAll(as => as.map(a => a.innerText + ' -> ' + a.getAttribute('href')));
console.log(links);
await shot(page, '#main-panel', 'A-13-nobc-direct-build-refusal', { pad: 8 });
for (const l of links) { const h = l.split(' -> ')[1]; if (h && h.includes('batch-control')) { const x = await page.goto(new URL(h, page.url()).href); console.log('follow', h, x.status()); } }
await new Promise(r => setTimeout(r, 3000));
console.log('nextBuildNumber', before.nextBuildNumber, '->', (await job('batch-daily')).nextBuildNumber);
await close();
