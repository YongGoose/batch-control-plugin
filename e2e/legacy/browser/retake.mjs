import { login, close, shot, BASE } from './lib.mjs';
const { page } = await login('admin');
await page.goto(`${BASE}/manage/configure`); await page.waitForTimeout(1500);
const sec = page.locator('.jenkins-section:has(.jenkins-section__title:text-is("Administrative monitors"))').first();
await sec.scrollIntoViewIfNeeded();
const btns = await sec.locator('button').allInnerTexts();
console.log('buttons', btns);
await sec.locator("button:has-text(\"Administrative monitors\")").first().click(); await page.waitForTimeout(800);
for (const b of ["Advanced", "Edit", "Show"]) { const x = sec.locator(`button:has-text("${b}")`).first(); if (await x.count()) { await x.click(); await page.waitForTimeout(800); } }
const labels = sec.locator('label').filter({ hasText: /Batch Control/ });
console.log('visible', await labels.first().isVisible().catch(() => false));
await shot(page, [labels.nth(0), labels.nth(1)], 'A-04-monitor-toggles', { pad: 12 });
await close();
