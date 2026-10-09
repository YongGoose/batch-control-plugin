// A-01: per-item authorization on a folder, a job and an agent under
// "Batch Control: Matrix-based security", configured in the browser.
import { login, close, BASE, shot, log } from './lib.mjs';
const L = 'section-a.log';
const { page } = await login('admin');
async function save() { await Promise.all([page.waitForNavigation({ waitUntil: 'load' }), page.locator('button[name="Submit"]').click()]); }
async function enable(label) {
  const l = page.locator(`label:has-text("${label}")`).first();
  const on = await l.evaluate((x) => x.parentElement.querySelector('input[type=checkbox]').checked);
  if (!on) { await l.click(); await page.waitForTimeout(800); }
  return on;
}
async function expand(card) {
  for (let i = 0; i < 2; i++) {
    if (await card.locator('.mas-card__body--collapsed').count() === 0) return;
    await card.locator('.mas-card__toggle, .mas-card__header').first().click();
    await page.waitForTimeout(600);
  }
}
async function grant(sid, permId) {
  let card = page.locator(`.mas-card[data-sid="${sid}"]:visible`).first();
  if (!(await card.count())) {
    await page.locator('button.matrix-auth-add-button:has-text("Add user"):visible').first().click();
    await page.waitForTimeout(600);
    await page.locator('dialog[open] input').first().fill(sid);
    await page.locator('dialog[open] button[data-id="ok"]').click();
    await page.waitForTimeout(800);
    card = page.locator(`.mas-card[data-sid="${sid}"]:visible`).first();
  }
  await expand(card);
  const box = card.locator(`input[name="[${permId}]"]`).first();
  if (!(await box.isChecked())) await card.locator(`label[data-permission-id="${permId}"]`).click();
  return box.isChecked();
}
const summary = async (sid) => (await page.locator(`.mas-card[data-sid="${sid}"] .mas-card__summary`).first().innerText().catch(() => 'MISSING')).trim();

const targets = [
  ['folder', '/job/team/configure', 'Enable project-based security', 'approver-disc', 'hudson.model.Item.Discover'],
  ['job', '/job/batch-daily/configure', 'Enable project-based security', 'auditor', 'hudson.model.Item.Read'],
  ['agent', '/computer/agent-1/configure', 'Enable node-based security', 'auditor', 'hudson.model.Computer.Build'],
];
for (const [kind, url, label, sid, perm] of targets) {
  await page.goto(BASE + url); await page.waitForTimeout(1500);
  const was = await enable(label);
  const ok = await grant(sid, perm);
  await save();
  await page.goto(BASE + url); await page.waitForTimeout(1500);
  log(L, `A-01 ${kind} ${url}: block "${label}" rendered, enabled before=${was}; ${sid} ${perm} checked=${ok}; after save+reopen ${sid} summary "${await summary(sid)}"`);
  await shot(page, page.locator(`.mas-card[data-sid="${sid}"]`).first().locator('xpath=ancestor::*[contains(@class,"mas-cards")][1]'), `A-01-${kind}`, { pad: 10 });
}
await page.context().close();

const au = await login('auditor');
await au.page.goto(BASE + '/');
const jobs = await au.page.locator('#projectstatus a').evaluateAll((as) => [...new Set(as.map((a) => a.innerText.trim()).filter(Boolean))]);
log(L, `A-01 auditor dashboard links: ${JSON.stringify(jobs)}`);
await shot(au.page, '#projectstatus, #main-panel', 'A-01-auditor', { pad: 8 });
const r1 = await au.page.goto(BASE + '/job/batch-daily/'); const r2 = await au.page.goto(BASE + '/job/batch-pipeline/');
log(L, `A-01 auditor /job/batch-daily/=${r1.status()} /job/batch-pipeline/=${r2.status()}`);
const ad = await login('approver-disc');
const r3 = await ad.page.goto(BASE + '/job/team/job/app-1/');
log(L, `A-01 approver-disc (Discover on team/) /job/team/job/app-1/=${r3.status()} url=${ad.page.url()}`);
await close();
