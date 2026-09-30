import { login, api, job, waitFor, BASE } from '../lib.mjs';
export async function openConfig(page, jobName) {
  await page.goto(`${BASE}/job/${jobName}/configure`);
  await page.waitForTimeout(1500);
}
export async function saveConfig(page) {
  await Promise.all([page.waitForNavigation({ waitUntil: 'load' }), page.locator('button[name="Submit"]').click()]);
}
/** Ticks a checkbox / optional block by its label text if not ticked yet. */
export async function tick(page, label, on = true) {
  const l = page.locator('label').filter({ hasText: new RegExp(`^\\s*${label.replace(/[()?.*+]/g, '\\$&')}\\s*$`) }).first();
  await l.scrollIntoViewIfNeeded();
  const cur = await l.evaluate((x) => { const i = x.parentElement.querySelector('input[type=checkbox]') || x.previousElementSibling; return !!(i && i.checked); });
  if (cur !== on) { await l.click(); await page.waitForTimeout(500); }
}
export async function fieldAfter(page, label) {
  return page.locator('.jenkins-form-item').filter({ has: page.locator('.jenkins-form-label', { hasText: label }) }).last().locator('input, textarea, select').first();
}
export async function approveAs(user, url, comment = 'ok') {
  const { context, page } = await login(user);
  await page.goto(url);
  const form = page.locator('form[name="approve"]');
  await form.locator('textarea[name="comment"]').fill(comment);
  await Promise.all([page.waitForLoadState('load'), form.locator('button').first().click()]);
  const txt = (await page.locator('#main-panel').innerText()).replace(/\s+/g, ' ');
  await context.close();
  return txt;
}
export async function activate(jobName, reason) {
  const rq = await login('requester');
  await rq.page.goto(`${BASE}/job/${jobName}/batch-control/activation`);
  await rq.page.fill('textarea[name="reason"]', reason);
  await rq.page.locator('input[name="approvers"][value="approver-1"] + label').click();
  await Promise.all([rq.page.waitForNavigation({ waitUntil: 'load' }), rq.page.locator('button:has-text("Submit Request")').click()]);
  const url = rq.page.url();
  await rq.context.close();
  await approveAs('approver-1', url, 'activate for section E');
  return url;
}
export async function changes(filter) {
  const r = await api('admin', '/batch-control/history/changes.csv');
  return r.text.split('\n').filter((l) => filter.test(l));
}
export async function builds(name) {
  const j = await job(name, 'nextBuildNumber,inQueue,builds[number,result,actions[causes[shortDescription]]]');
  return { next: j.nextBuildNumber, inQueue: j.inQueue, list: j.builds.map((b) => `#${b.number} ${b.result} [${(b.actions.find((a) => a && a.causes) || { causes: [] }).causes.map((c) => c.shortDescription).join('; ')}]`) };
}
export async function waitBuilt(name, next) {
  return waitFor(async () => { const j = await job(name, 'nextBuildNumber,lastBuild[number,building,result]'); return j.nextBuildNumber > next && j.lastBuild && !j.lastBuild.building ? j : null; }, { timeout: 120000 });
}
