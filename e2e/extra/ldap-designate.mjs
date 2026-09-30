// e2e-05 check 2: lrequester designates an approver-list id that holds no Approve (lnobody) or is not in the directory (lnever-seen-2).
import { login, BASE, shot, text, log, close } from './lib.mjs';
const flat = (s) => s.replace(/\s+/g, ' ').trim();
const { page } = await login('lrequester');
for (const who of ['lnobody', 'lnever-seen-2']) {
  await page.goto(`${BASE}/job/fresh-daily/batch-control/`);
  await page.fill('textarea[name="reason"]', `e2e-05 L designate ${who}`);
  await page.locator(`input[name="approvers"][value="${who}"]`).check({ force: true });
  const st = [];
  const h = (r) => { if (r.request().method() === 'POST') st.push(r.status()); };
  page.on('response', h);
  await Promise.all([page.waitForLoadState('load'), page.click('button[name="Submit"]')]);
  page.off('response', h);
  const err = page.locator('#main-panel .error, #main-panel .jenkins-alert').first();
  await shot(page, (await err.count()) ? err : '#main-panel table', `L-32-designate-${who}`);
  log(`designate ${who}: HTTP ${st} -> ${page.url().replace(BASE, '')} | ${flat(await text(page)).slice(0, 260)}`);
}
await close();
