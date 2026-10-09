// Re-audit recorder: one JSON line per checklist row with the five rubric criteria
// recorded separately (V=Visibility, G=Goal, R=Refusal UX, C=Record, E=Evidence).
// Cell values: '✓', '✗', 'n.a.' (+ text). Verdict is derived: any ✗ -> FAIL.
import fs from 'node:fs';
import path from 'node:path';
import { OUT } from '../lib.mjs';
const F = path.join(OUT, 'audit.jsonl');
export function row(id, { roles = '-', V, G, R, C, E, note = '', defect = '', verdict } = {}) {
  const cells = [V, G, R, C, E];
  const auto = cells.some((c) => String(c || '').startsWith('✗')) ? 'FAIL' : (cells.some((c) => String(c || '').startsWith('?')) ? 'BLOCKED' : 'PASS');
  const r = { id, roles, V, G, R, C, E, note, defect, verdict: verdict || auto, round: process.env.BC_ROUND || 'reaudit', at: new Date().toISOString() };
  fs.appendFileSync(F, JSON.stringify(r) + '\n');
  console.log(`ROW ${id} ${r.verdict} | V ${V} | G ${G} | R ${R} | C ${C} | E ${E} | ${note} ${defect}`);
  return r;
}
export function ev(line) { fs.appendFileSync(path.join(OUT, 'audit.log'), line + '\n'); console.log(line); }
/** sidebar/task labels of the current page */
export async function sidebar(page) {
  return (await page.locator('#side-panel #tasks a, #side-panel .task a, #tasks .task-link').allInnerTexts()).map((t) => t.replace(/\s+/g, ' ').trim()).filter(Boolean);
}
export async function mainText(page) {
  return (await page.locator('#main-panel').first().innerText().catch(async () => page.locator('body').innerText())).replace(/\s+/g, ' ').trim();
}
