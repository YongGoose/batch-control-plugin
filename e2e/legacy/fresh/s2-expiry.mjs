// Scenario 2: pending-request expiry (pendingTimeoutHours=1). Run after the hour has passed.
import { login, BASE, shot, text, log, close, mails } from './lib.mjs';
const flat = (s) => s.replace(/\s+/g, ' ');
for (const id of ['20260930-081053-30kg3q', '20260930-083056-cuim56']) {
  const { page, context } = await login('requester');
  await page.goto(`${BASE}/batch-control/requests/${id}/`);
  log('S2-11', id, flat(await text(page)).slice(0, 500));
  await shot(page, [page.locator('tr', { hasText: 'Status' }).first(), page.locator('tr', { hasText: /expired|Why/i }).first()], `S2-11-${id}-pending-expired`);
  await context.close();
  log('mails', (await mails('to:requester@e2e.local')).filter((m) => m.Subject.includes(id)).map((m) => `${m.Created} ${m.Subject}`));
}
const { page } = await login('admin');
await page.goto(`${BASE}/batch-control/history/?kind=changes&job=fresh-cron`);
log('fresh-cron changes', (await page.locator('tbody tr').allInnerTexts()).slice(0, 3).map(flat));
await close();
