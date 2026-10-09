import { login, close, BASE, shot } from '../lib.mjs';
const { page } = await login('admin');
await page.goto(`${BASE}/manage/role-strategy/`); await page.waitForTimeout(3000);
console.log(await shot(page, '#main-panel', 'B8-R1-role-management', { pad: 8 }));
console.log(await page.locator('#main-panel a, #main-panel button').evaluateAll((as) => as.map((a) => `${a.tagName}:${a.innerText.trim()}:${a.getAttribute('href') || ''}`).filter((x) => x.length > 4).slice(0, 40)));
await close();
