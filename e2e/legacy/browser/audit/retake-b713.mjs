import { login, close, shot, BASE } from '../lib.mjs';
const { page } = await login('admin');
await page.goto(`${BASE}/job/team/job/app-free-2193/configure`); await page.waitForTimeout(1500);
const boxes = ['approvalRequired', 'blockTimer', 'blockUpstream'].map((f) => page.locator(`[name="_.${f}"]`).first().locator('xpath=ancestor::*[contains(@class,"jenkins-form-item")][1]'));
console.log(await shot(page, boxes, 'B7-13-new-item-locked', { pad: 10 }));
await close();
