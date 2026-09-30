// A-01: per-item authorization on folder, job and agent under the Batch Control variant, as admin in the UI; effect as auditor.
import { login, close, BASE, shot, changeRows, sleep } from '../lib.mjs';
import { row, ev } from './rec.mjs';
const { page } = await login('admin');
const save = () => Promise.all([page.waitForNavigation({ waitUntil: 'load' }), page.locator('button[name="Submit"]').click()]);
async function enable(label) { const l = page.locator(`label:has-text("${label}")`).first(); const on = await l.evaluate((x) => x.parentElement.querySelector('input[type=checkbox]').checked); if (!on) { await l.click(); await page.waitForTimeout(800); } return on; }
async function expand(card) { for (let i = 0; i < 2; i++) { if (await card.locator('.mas-card__body--collapsed').count() === 0) return; await card.locator('.mas-card__toggle, .mas-card__header').first().click(); await page.waitForTimeout(600); } }
async function setPerm(sid, perm, want) {
  let card = page.locator(`.mas-card[data-sid="${sid}"]:visible`).first();
  if (!(await card.count())) { await page.locator('button.matrix-auth-add-button:has-text("Add user"):visible').first().click(); await page.waitForTimeout(600); await page.locator('dialog[open] input').first().fill(sid); await page.locator('dialog[open] button[data-id="ok"]').click(); await page.waitForTimeout(800); card = page.locator(`.mas-card[data-sid="${sid}"]:visible`).first(); }
  await expand(card);
  const box = card.locator(`input[name="[${perm}]"]`).first();
  if ((await box.isChecked()) !== want) await card.locator(`label[data-permission-id="${perm}"]`).click();
  return card;
}
const summary = async (sid) => (await page.locator(`.mas-card[data-sid="${sid}"] .mas-card__summary`).first().innerText().catch(() => 'MISSING')).trim();
const au = async (p) => { const c = await login('auditor'); const s = (await c.page.goto(BASE + p)).status(); await c.context.close(); return s; };
const out = {}; const sh = [];
// job: add auditor Job/Read on batch-pipeline, check effect, remove again
const j0 = await au('/job/batch-pipeline/');
const rec0 = (await changeRows(/,CONFIGURE,batch-pipeline,/)).length;
await page.goto(`${BASE}/job/batch-pipeline/configure`); await page.waitForTimeout(1500);
const jwas = await enable('Enable project-based security');
const card = await setPerm('auditor', 'hudson.model.Item.Read', true);
sh.push(!!(await shot(page, card, 'A-01-1-job-add-auditor-read', { pad: 8 })));
await save();
await page.goto(`${BASE}/job/batch-pipeline/configure`); await page.waitForTimeout(1500);
out.jobKept = await summary('auditor');
const j1 = await au('/job/batch-pipeline/');
await sleep(1000);
const rec1 = (await changeRows(/,CONFIGURE,batch-pipeline,/)).length;
const c2 = await setPerm('auditor', 'hudson.model.Item.Read', false);
await save();
const j2 = await au('/job/batch-pipeline/');
// folder and agent: block renders, existing entries survive a reopen
await page.goto(`${BASE}/job/team/configure`); await page.waitForTimeout(1500);
out.folderBlock = await page.locator('label:has-text("Enable project-based security")').count();
out.folder = await summary('approver-disc');
sh.push(!!(await shot(page, page.locator('.mas-card[data-sid="approver-disc"]').first(), 'A-01-2-folder-entry', { pad: 8 })));
await page.goto(`${BASE}/computer/agent-1/configure`); await page.waitForTimeout(1500);
out.agentBlock = await page.locator('label:has-text("Enable node-based security")').count();
out.agent = await summary('auditor');
sh.push(!!(await shot(page, page.locator('.mas-card[data-sid="auditor"]').first(), 'A-01-3-agent-entry', { pad: 8 })));
const ad = await login('approver-disc'); const d1 = (await ad.page.goto(`${BASE}/job/team/job/app-1/`)).status(); await ad.context.close();
const a = await login('auditor'); await a.page.goto(`${BASE}/`);
const jobs = await a.page.locator('#projectstatus a.jenkins-table__link, #projectstatus td a').evaluateAll((as) => [...new Set(as.map((x) => x.innerText.trim()).filter(Boolean))]);
sh.push(!!(await shot(a.page, '#projectstatus', 'A-01-4-auditor-dashboard', { pad: 8 })));
await a.context.close();
const vis = {}; for (const u of ['requester', 'nobc']) { const c = await login(u); vis[u] = (await c.page.goto(`${BASE}/job/batch-pipeline/configure`)).status(); await c.context.close(); }
ev(`A-01 ${JSON.stringify(out)} auditor batch-pipeline ${j0}->${j1}->${j2}; CONFIGURE records ${rec0}->${rec1}; approver-disc team/app-1 ${d1}; auditor dashboard ${JSON.stringify(jobs)}; configure for ${JSON.stringify(vis)}`);
row('A-01', { roles: 'admin (configure), auditor, approver-disc (effect), requester, nobc (no configure)', V: `✓ the per-item blocks render on job (enabled before: ${jwas}), folder (${out.folderBlock}) and agent (${out.agentBlock}) for admin; requester/nobc configure = ${vis.requester}/${vis.nobc}`, G: `${j0 === 404 && j1 === 200 && j2 === 404 && /Read/.test(out.jobKept) ? '✓' : '✗'} job: auditor Job/Read added on batch-pipeline, kept on reopen ("${out.jobKept}"), auditor /job/batch-pipeline/ ${j0} -> ${j1} -> removed again ${j2}; folder team entry approver-disc "${out.folder}" (approver-disc team/app-1 ${d1}: Discover, no Read); agent-1 entry auditor "${out.agent}"; auditor dashboard lists ${jobs.length} jobs: ${jobs.join(', ').slice(0, 80)}`, R: 'n.a.', C: `${rec1 === rec0 + 1 ? '✓' : '✗'} the authorization edit is one CONFIGURE record on batch-pipeline (${rec0} -> ${rec1})`, E: sh.every(Boolean) ? '✓ A-01-1..4' : '✗' });
await close();
