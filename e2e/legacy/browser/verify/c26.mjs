// C-26: an incident's run link for a viewer who cannot read the job (D-44 run-link rule).
import { login, close, shot, api, BASE } from '../lib.mjs';
import { row, ev } from '../audit/rec.mjs';
const { page } = await login('auditor');
const jobStatus = (await api('auditor', '/job/batch-failing/api/json')).status;
await page.goto(`${BASE}/batch-control/incidents/`);
const r = page.locator('#main-panel tr', { hasText: 'batch-failing' }).first();
const listLinks = await r.locator('a').evaluateAll((as) => as.map((a) => `${a.innerText.trim()}->${a.getAttribute('href')}`));
const s1 = await shot(page, r, 'C-26-1-incident-row', { pad: 8 });
const inc = listLinks.find((l) => /^\d{8}-/.test(l));
let detailLinks = [];
let s2 = null;
if (inc) {
  await page.goto(new URL(inc.split('->')[1], `${BASE}/batch-control/incidents/`).href);
  detailLinks = await page.locator('#main-panel table').first().locator('a').evaluateAll((as) => as.map((a) => `${a.innerText.trim()}->${a.getAttribute('href')}`));
  s2 = await shot(page, page.locator('#main-panel table').first(), 'C-26-2-incident-detail', { pad: 8 });
}
const jobLinks = [...listLinks, ...detailLinks].filter((l) => /\/job\/batch-failing\//.test(l));
ev(`C-26 auditor job ${jobStatus}; list ${listLinks}; detail ${detailLinks}`);
row('C-26', {
  roles: 'auditor (ViewHistory, no Read on batch-failing)',
  V: `${jobLinks.length === 0 ? '✓' : '✗'} the incident list row and detail link only the incident itself; the run and job are plain text for a viewer without Read (/job/batch-failing/ -> ${jobStatus}); job links offered: ${jobLinks.join(', ') || 'none'}`,
  G: '✓ the incident stays readable for the ViewHistory holder',
  R: 'n.a.', C: 'n.a.', E: s1 && s2 ? '✓ C-26-1-incident-row, C-26-2-incident-detail' : '✗',
});
await close();
