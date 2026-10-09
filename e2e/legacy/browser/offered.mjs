// "Offered but always refused" sweep: what each account is shown, then click it.
import { login, close, shot, api, BASE, log, clickBuildEntry, errText, job } from './lib.mjs';
const L = 'section-b.log';
const inc = (await api('admin', '/batch-control/history/incidents.csv')).text.split('\n').find((l) => l.includes(',batch-failing#1,')).split(',')[0];
const pages = { job: '/job/batch-daily/', lockjob: '/job/batch-lock/', build: '/job/batch-daily/5/', grants: '/batch-control/grants/', incident: `/batch-control/incidents/${inc}/` };
const users = ['requester', 'reqonly', 'approver-1', 'approver-unlisted', 'auditor', 'manager', 'configurer'];
for (const u of users) {
  const { context, page } = await login(u);
  const out = [];
  for (const [k, p] of Object.entries(pages)) {
    const r = await page.goto(BASE + p);
    if (r.status() >= 400) { out.push(`${k}:${r.status()}`); continue; }
    const side = (await page.locator('#side-panel .task a, #tasks a').allInnerTexts()).map((t) => t.trim()).filter(Boolean);
    const forms = await page.locator('#main-panel form[name]').evaluateAll((fs) => fs.map((f) => f.getAttribute('name')));
    const btns = (await page.locator('#main-panel a:has-text("Request"), #main-panel a:has-text("Cancel Request")').allInnerTexts()).map((t) => t.trim());
    out.push(`${k}: side=${JSON.stringify(side.filter((s) => !/^(Status|Changes|Console Output|Parameters|Builds)$/.test(s)))} forms=${JSON.stringify(forms)} links=${JSON.stringify(btns)}`);
  }
  log(L, `OFFERED ${u}: ${out.join(' | ')}`);
  await context.close();
}
await close();
