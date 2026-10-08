import { login, close, shot, api, BASE, log } from './lib.mjs';
const L = 'section-b.log';
const F = 'form[name="batch-control-request"]';
const errText = (t) => (t.replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ').match(/(?:Error|Access Denied) (.{0,220}?) REST API/) || [null, t.slice(0, 120)])[1];
const r = await api('requester', '/job/batch-daily/batch-control/submit', { method: 'POST', body: new URLSearchParams({ reason: 'Raw fields only (B4-16).', approvers: 'approver-1', DATE: '2031-01-01', MODE: 'partial' }), headers: { 'Content-Type': 'application/x-www-form-urlencoded' } });
const { page } = await login('requester');
await page.goto(new URL(r.location, BASE).href);
const pv = (await page.locator('table:has(th:text-is("Name"))').first().innerText()).replace(/\s+/g, ' ');
log(L, `B4-16 raw fields DATE=2031-01-01 MODE=partial -> ${r.status} ${r.location}; stored "${pv}"`);
page.once('dialog', (d) => d.accept()); await page.locator('a:has-text("Cancel Request")').first().click(); await page.waitForTimeout(800);
{ const ok = page.locator('dialog[open] button[data-id="ok"]'); if (await ok.count()) await Promise.all([page.waitForLoadState('load'), ok.click()]); }
// B4-03 param 10,001 chars in the form
await page.goto(`${BASE}/job/batch-pipeline/batch-control/`);
await page.fill(`${F} textarea[name="reason"]`, 'Long parameter (B4-03).');
await page.locator(`${F} input[name="approvers"][value="approver-1"] + label`).click();
await page.locator(`${F} .jenkins-form-item:has(.jenkins-form-label:text-is("DATE")) input[name="value"]`).fill('y'.repeat(10001));
await Promise.all([page.waitForNavigation().catch(() => null), page.locator('button:has-text("Submit Request")').click()]);
log(L, `B4-03 DATE of 10,001 chars in the form -> "${errText(await page.content())}"`);
await shot(page, '#main-panel, body', 'B4-03-2-param', { pad: 8 });
const csv = (await api('admin', '/batch-control/history/requests.csv')).text.split('\n').filter((l) => l.includes('B4-11'));
log(L, `B4-11 requests.csv row ${csv[0]}`);
const ch = (await api('admin', '/batch-control/history/changes.csv')).text.split('\n').filter((l) => /manager/.test(l)).slice(0, 3);
log(L, `B4-11 change records by manager: ${ch.join(' || ') || 'none'}`);
await close();
