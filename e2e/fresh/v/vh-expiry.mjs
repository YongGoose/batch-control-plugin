// Re-verification H: pending expiry of a run, activation and grant request (FD-04, FD-05).
import fs from 'node:fs';
import { login, BASE, shot, text, log, close, OUT } from '../lib.mjs';
const flat = (s) => s.replace(/\s+/g, ' ');
const ids = JSON.parse(fs.readFileSync(`${OUT}/verify-expiry.json`, 'utf8'));
ids.dis = '20260930-132816-95a6ah';
const path = { run: 'requests', dis: 'requests', act: 'activations', grant: 'grants' };
for (const [k, id] of Object.entries(ids)) {
  if (!path[k]) continue;
  const { page, context } = await login('requester');
  await page.goto(`${BASE}/batch-control/${path[k]}/${id}/`);
  const t = flat(await text(page));
  log('EXP', k, id, t.slice(0, 450));
  await shot(page, [page.locator('#main-panel tr', { hasText: 'Status' }).first(), page.locator('#main-panel tr', { hasText: /expired/i }).last()], `FD05-${k}-pending-expired`);
  await context.close();
  const r = await fetch(`http://localhost:8025/api/v1/search?query=${id}&limit=20`);
  const ms = (await r.json()).messages || [];
  log('   mails', ms.map((m) => `${m.To[0].Address}: ${m.Subject}`));
  const em = ms.find((m) => /expired/i.test(m.Subject) && m.To[0].Address.startsWith('requester'));
  if (em) log('   text', flat((await (await fetch(`http://localhost:8025/api/v1/message/${em.ID}`)).json()).Text).slice(0, 300));
}
await close();
