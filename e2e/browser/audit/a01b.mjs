import { login, close, BASE, shot, changeRows, sleep } from '../lib.mjs';
import { row, ev } from './rec.mjs';
const { page } = await login('admin');
const recs = async () => (await changeRows(/,CONFIGURE,batch-pipeline,/));
const r0 = await recs();
await page.goto(`${BASE}/job/batch-pipeline/configure`); await page.waitForTimeout(1500);
let card = page.locator('.mas-card[data-sid="auditor"]:visible').first();
if (!(await card.count())) { await page.locator('button.matrix-auth-add-button:has-text("Add user"):visible').first().click(); await page.waitForTimeout(600); await page.locator('dialog[open] input').first().fill('auditor'); await page.locator('dialog[open] button[data-id="ok"]').click(); await page.waitForTimeout(800); card = page.locator('.mas-card[data-sid="auditor"]:visible').first(); }
for (let i = 0; i < 2; i++) { if (await card.locator('.mas-card__body--collapsed').count() === 0) break; await card.locator('.mas-card__toggle, .mas-card__header').first().click(); await page.waitForTimeout(600); }
if (!(await card.locator('input[name="[hudson.model.Item.Read]"]').isChecked())) await card.locator('label[data-permission-id="hudson.model.Item.Read"]').click();
await Promise.all([page.waitForNavigation({ waitUntil: 'load' }), page.locator('button[name="Submit"]').click()]); await sleep(1500);
const r1 = await recs();
const a = await login('auditor'); const s = (await a.page.goto(`${BASE}/job/batch-pipeline/`)).status(); await a.context.close();
await page.goto(`${BASE}/batch-control/changes/`);
const rw = page.locator('#main-panel tr:has-text("batch-pipeline")').first();
const d = rw.locator('summary, a').filter({ hasText: /Diff/ }).first(); if (await d.count()) { await d.click(); await page.waitForTimeout(600); }
const sh = await shot(page, rw, 'A-01-5-authorization-edit-record', { pad: 8 });
ev(`A-01b records before re-add ${r0.length} (newest ${r0[0].slice(0, 60)}), after re-add ${r1.length}; auditor batch-pipeline ${s}; row "${(await rw.innerText()).replace(/\s+/g, ' ').slice(0, 300)}"`);
await close();
