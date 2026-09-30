import { login, close, BASE, shot } from '../lib.mjs';
const { page } = await login('approver-1');
await page.goto(`${BASE}/batch-control/incidents/20260930-003841-zojevu/`);
for (const t of await page.locator('#main-panel table').allInnerTexts()) console.log('TABLE:', t.replace(/\s+/g, ' ').slice(0, 400));
const h = page.locator('#main-panel table').filter({ hasText: 'Looking into the vendor feed' }).first();
console.log(await shot(page, h, 'B14-03-acknowledged', { pad: 8 }));
await close();
