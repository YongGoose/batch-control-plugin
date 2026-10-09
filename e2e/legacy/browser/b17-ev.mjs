import { login, close, shot, BASE, log, api } from './lib.mjs';
import fs from 'node:fs';
const L = 'section-b.log';
const st = JSON.parse(fs.readFileSync('../out/b17-state.json', 'utf8'));
const vis = [];
for (const u of ['approver-1', 'auditor', 'requester', 'nobc']) {
  const { context, page } = await login(u);
  const r = await page.goto(`${BASE}/batch-control/changes/`);
  const n = r.status() === 200 ? await page.locator('#main-panel tr:has-text("RETENTION")').count() : 0;
  vis.push(`${u}: Change Records ${r.status()}, RETENTION rows ${n}`);
  if (u === 'approver-1') await shot(page, page.locator('#main-panel tr:has-text("RETENTION")'), 'B16-01', { pad: 8 });
  await context.close();
}
log(L, `B16 visibility of RETENTION records: ${vis.join(' | ')}`);
const a1 = await login('approver-1');
await a1.page.goto(st.pending);
await shot(a1.page, ['#main-panel table', 'form[name="approve"]'], 'B17-01', { pad: 8 });
await a1.page.goto(st.approved);
await shot(a1.page, '#main-panel table', 'B17-02-1-request', { pad: 8 });
await a1.page.goto(`${BASE}/job/batch-daily/9/`);
await shot(a1.page, '#main-panel', 'B17-02-2-build', { pad: 8 });
await a1.page.goto(`${BASE}/batch-control/dashboard/`);
await shot(a1.page, [a1.page.locator('#main-panel').getByText(/Page 1 \(/).first(), a1.page.locator('#main-panel tr:has-text("batch-cron")').first()], 'B16-03-B17-06-dashboard', { pad: 8 });
const ad = await login('admin');
await ad.page.goto(`${BASE}/manage/configure`); await ad.page.waitForTimeout(1500);
await shot(ad.page, '.jenkins-section:has(.jenkins-section__title:text-is("Batch Control"))', 'B17-05', { pad: 6 });
await close();
