// e2e-05 check 1: open a change-record diff (collapsed <details>) and capture it, boxed. node diff.mjs <theme-label>
import { login, BASE, shot, close } from './lib.mjs';
const { page } = await login('admin');
await page.goto(`${BASE}/batch-control/changes/`);
const d = page.locator('details', { hasText: 'Diff (+1 / -1 lines)' }).first();
await d.locator('summary').click();
const pre = d.locator('pre');
const c = await pre.evaluate((e) => { const s = getComputedStyle(e); return [s.color, s.backgroundColor, getComputedStyle(document.body).backgroundColor]; });
console.log(process.argv[2], 'pre color/bg/body', c);
await shot(page, d, `T-${process.argv[2]}-admin-changes-diff-open`);
await close();
