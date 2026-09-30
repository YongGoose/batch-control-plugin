// Discovery helper: first detail links on each Batch Control list page, with the row text.
import { login, BASE, close } from './lib.mjs';
const { page } = await login(process.argv[2] || 'admin');
for (const u of ['requests', 'activations', 'grants', 'incidents', 'changes', 'dashboard', 'history']) {
  await page.goto(`${BASE}/batch-control/${u}/`);
  const rows = await page.$$eval('#main-panel a[href]', (as) => as.map((a) => {
    const tr = a.closest('tr'); return [a.href.replace(location.origin, ""), (tr ? tr.innerText : a.innerText).replace(/\s+/g, ' ').slice(0, 110)];
  }).filter(([h]) => /\/(requests|grants|incidents|changes|activations)\/[^/?]+\/?$/.test(h)));
  const seen = new Set(); let n = 0;
  console.log('==', u);
  for (const [h, t] of rows) { if (seen.has(h) || n > 7) continue; seen.add(h); n++; console.log('  ', h, '|', t); }
}
await close();
