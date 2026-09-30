import { login, close, BASE, job, clickBuildEntry } from '../lib.mjs';
import { sidebar, mainText } from '../audit/rec.mjs';
const out = {};
for (const u of ['requester', 'nobc', 'reqonly', 'admin']) {
  const { context, page } = await login(u);
  for (const p of ['/job/batch-cbn/', '/job/batch-rebuild/2/', '/job/batch-nag/2/', '/job/batch-pipeline/8/']) {
    await page.goto(BASE + p);
    out[`${u} ${p}`] = { sb: (await sidebar(page)).filter((x) => !/^#|Status|Changes|Console|Previous|Next|Lockable|Parameters|Workspace|Delete|Edit build|Pipeline Steps|Restart|Configure|Rename|Move|Authorization|Export|Job Config/.test(x)), notices: (await page.locator('#main-panel .jenkins-alert').allInnerTexts()).map((t) => t.replace(/\s+/g, ' ').slice(0, 70)) };
  }
  await context.close();
}
console.log(JSON.stringify(out, null, 1));
const { page } = await login('requester');
for (const [p, l] of [['/job/batch-cbn/', 'Direct Build'], ['/job/batch-nag/2/', 'Retry'], ['/job/batch-lock/', 'Direct Build']]) {
  await page.goto(BASE + p);
  if (!(await page.locator('#side-panel a').filter({ hasText: l }).count())) { console.log(p, l, 'absent'); continue; }
  const r = await clickBuildEntry(page, l);
  console.log(p, l, '->', r.status, r.url.replace(BASE, ''), 'notif:', r.notif, '| text:', r.text.slice(0, 200));
}
await close();
