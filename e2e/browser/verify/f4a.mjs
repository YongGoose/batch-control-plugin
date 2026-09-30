// DEF-37 / D-50a: the instance-wide SYSTEM-build warning, as each role sees it (per-project authenticator only).
import { login, close, shot, BASE, requestGrant } from '../lib.mjs';
import { ev, mainText } from '../audit/rec.mjs';
import fs from 'node:fs';
const WARN = /run as SYSTEM|account with Configure/i;
const { page } = await login('admin');
await page.goto(`${BASE}/manage/`);
const mon = page.locator('#main-panel .jenkins-alert', { hasText: WARN }).first();
const mt = (await mon.innerText().catch(() => 'NONE')).replace(/\s+/g, ' ');
await shot(page, mon, 'C-08-1-monitor-warning', { pad: 8 });
const rq = await login('requester');
const g = await requestGrant(rq.page, { scope: 'batch-upstream', actions: ['CONFIGURE'], minutes: 15, reason: 'Final C-08: pending CONFIGURE request' });
const seen = {};
for (const u of ['approver-1', 'manager', 'requester', 'admin']) {
  const c = u === 'requester' ? rq : await login(u);
  await c.page.goto(g.url);
  const t = await mainText(c.page);
  seen[u] = (t.match(/[^.]*(run as SYSTEM|account with Configure)[^.]*\.[^.]*\./i) || [''])[0];
  if (u === 'approver-1') await shot(c.page, c.page.locator('#main-panel .jenkins-alert', { hasText: WARN }).first(), 'C-08-2-approver-sees-warning', { pad: 8 });
  if (u !== 'requester') await c.context.close();
}
fs.writeFileSync('../out/final-c08.url', g.url);
ev(`F4a monitor "${mt.slice(0, 400)}"`);
ev(`F4a request ${g.url}: ${JSON.stringify(seen)}`);
await page.goto(`${BASE}/manage/configureSecurity/`); await page.waitForTimeout(1500);
const sec = page.locator('.jenkins-section', { hasText: 'Access Control for Builds' }).first();
ev(`F4a security section: ${(await sec.innerText().catch(() => 'NONE')).replace(/\s+/g, ' ').slice(0, 400)}`);
ev(`F4a buttons: ${JSON.stringify(await sec.locator('button').allInnerTexts().catch(() => []))}`);
await close();
