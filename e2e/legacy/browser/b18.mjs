import { login, close, shot, BASE, log, api } from './lib.mjs';
const L = 'section-b.log';
const tag = process.argv[2] || 'b18-01';
const reqId = (await api('admin', '/batch-control/history/requests.csv')).text.split('\n')[1].split(',')[0];
for (const loc of (tag === 'b18-01' ? ['ko-KR', 'en-US'] : ['en-US'])) {
  const { context, page } = await login('approver-1', { locale: loc });
  await context.setExtraHTTPHeaders({ 'Accept-Language': loc });
  const out = [];
  for (const p of ['/batch-control/requests/', `/batch-control/requests/${reqId}/`, '/batch-control/dashboard/', '/batch-control/history/', '/batch-control/changes/']) {
    const r = await page.goto(BASE + p);
    const t = (await page.locator('#main-panel').innerText()).replace(/\s+/g, ' ');
    const dates = [...new Set(t.match(/\d{4}-\d{2}-\d{2} \d{2}:\d{2}(:\d{2})? ?[A-Z]{2,4}|\d{4}\. ?\d{1,2}\. ?\d{1,2}\.?/g) || [])].slice(0, 2);
    out.push(`${p} ${r.status()} dates ${JSON.stringify(dates)}`);
  }
  await page.goto(`${BASE}/batch-control/requests/${reqId}/`);
  await shot(page, page.locator('#main-panel table').first(), `${tag.toUpperCase()}-${loc}`, { pad: 8 });
  const core = (await page.locator('header, #page-header').first().innerText().catch(() => '')).replace(/\s+/g, ' ').slice(0, 80);
  log(L, `${tag} locale ${loc}: ${out.join(' | ')}; core chrome "${core}"`);
  await context.close();
}
const csv = (await api('admin', '/batch-control/history/requests.csv')).text.split('\n')[1];
log(L, `${tag} requests.csv first row createdAt "${csv.split(',')[7]}" for ${reqId}; ids ASCII: ${/^[\x00-\x7F]+$/.test(reqId)}`);
await close();
