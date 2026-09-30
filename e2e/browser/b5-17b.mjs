import { login, close, shot, BASE, log, api } from './lib.mjs';
const L = 'section-b.log';
const ad = await login('admin');
const set = async (v) => {
  await ad.page.goto(`${BASE}/job/batch-cron/configure`); await ad.page.waitForTimeout(1500);
  const el = ad.page.locator('[name="_.blockTimer"]').first();
  if ((await el.isChecked()) !== v) await el.locator('xpath=following-sibling::label[1]').click();
  await Promise.all([ad.page.waitForNavigation(), ad.page.locator('button[name="Submit"]').click()]);
};
await set(true);
const x = (await api('admin', '/job/batch-cron/config.xml', { raw: true })).text;
const props = (x.match(/<io\.jenkins\.plugins\.batchcontrol\.config\.BatchControlJobProperty[\s\S]*?<\/io\.jenkins\.plugins\.batchcontrol\.config\.BatchControlJobProperty>/g) || []).map((p) => p.replace(/\s+/g, ' '));
for (const u of ['requester', 'admin']) {
  const c = u === 'admin' ? ad : await login(u);
  await c.page.goto(`${BASE}/job/batch-cron/`);
  const n = (await c.page.locator('#main-panel .jenkins-alert').allInnerTexts()).map((t) => t.replace(/\s+/g, ' ').slice(0, 90));
  log(L, `B5-17 batch-cron blockTimer=true, ${u} sees notices ${JSON.stringify(n)}`);
  if (u === 'requester') { await shot(c.page, '#main-panel', 'B5-17-2-job-page', { pad: 6 }); await c.context.close(); }
}
log(L, `B5-17 batch-cron config.xml Batch Control property: ${JSON.stringify(props)}`);
// same check on a job created with the property from the start
await ad.page.goto(`${BASE}/job/batch-pipeline/configure`); await ad.page.waitForTimeout(1500);
{ const el = ad.page.locator('[name="_.blockTimer"]').first(); if (!(await el.isChecked())) await el.locator('xpath=following-sibling::label[1]').click(); }
await Promise.all([ad.page.waitForNavigation(), ad.page.locator('button[name="Submit"]').click()]);
await ad.page.goto(`${BASE}/job/batch-pipeline/`);
log(L, `B5-17 batch-pipeline blockTimer=true: notices ${JSON.stringify((await ad.page.locator('#main-panel .jenkins-alert').allInnerTexts()).map((t) => t.replace(/\s+/g, ' ').slice(0, 90)))}`);
await ad.page.goto(`${BASE}/job/batch-pipeline/configure`); await ad.page.waitForTimeout(1500);
{ const el = ad.page.locator('[name="_.blockTimer"]').first(); if (await el.isChecked()) await el.locator('xpath=following-sibling::label[1]').click(); }
await Promise.all([ad.page.waitForNavigation(), ad.page.locator('button[name="Submit"]').click()]);
await set(false);
await close();
