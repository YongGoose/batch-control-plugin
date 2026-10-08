import { login, close, BASE, shot } from '../lib.mjs';
const { page } = await login('admin');
await page.goto(`${BASE}/manage/`);
const all = await page.locator('#main-panel a').evaluateAll((as) => as.map((a) => `${a.innerText.replace(/\s+/g, ' ').trim().slice(0, 60)} -> ${a.getAttribute('href')}`).filter((x) => /batch|Batch/.test(x)));
console.log(all);
const e = page.locator('#main-panel a[href*="batch-control-configuration"]').first();
if (await e.count()) console.log(await shot(page, e, 'DD-14-2-manage-jenkins-entry', { pad: 12 }));
await close();
