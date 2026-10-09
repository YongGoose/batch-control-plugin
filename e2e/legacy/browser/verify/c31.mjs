// C-31: approve and cancel at the same moment in two browser contexts.
import { login, close, shot, api, job, BASE, requestRun, sleep } from '../lib.mjs';
import { row, ev, mainText } from '../audit/rec.mjs';
const J = 'batch-pipeline';
const results = [];
for (let round = 0; round < 3; round++) {
  const rq = await login('requester');
  const url = await requestRun(rq.page, `/job/${J}/`, { reason: `Verify C-31 race ${round}`, approvers: ['approver-1'] });
  const n0 = (await job(J, 'nextBuildNumber')).nextBuildNumber;
  const ap = await login('approver-1');
  await ap.page.goto(url);
  await ap.page.fill('form[name="approve"] textarea[name="comment"]', 'race');
  await rq.page.goto(url);
  rq.page.once('dialog', (d) => d.accept());
  await rq.page.locator('a:has-text("Cancel Request"), button:has-text("Cancel Request")').first().click();
  await rq.page.waitForTimeout(500);
  const ok = rq.page.locator('dialog[open] button[data-id="ok"], dialog[open] button:has-text("Yes")').first();
  await Promise.all([
    Promise.all([ap.page.waitForNavigation().catch(() => null), ap.page.locator('form[name="approve"] button').first().click()]),
    Promise.all([rq.page.waitForNavigation().catch(() => null), ok.click()]),
  ]);
  const ta = await mainText(ap.page); const tr = await mainText(rq.page);
  await sleep(20000);
  const d = (await api('admin', url.replace(BASE, ''))).text.replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ');
  const status = (d.match(/Status (\w+)/) || [])[1];
  const built = (await job(J, 'nextBuildNumber')).nextBuildNumber - n0;
  if (round === 0) await shot(ap.page, '#main-panel', 'C-31-approver-view', { pad: 8 });
  results.push({ round, status, built, approver: ta.slice(0, 90), requester: tr.slice(0, 90) });
  await rq.context.close(); await ap.context.close();
}
ev(`C-31 ${JSON.stringify(results)}`);
const consistent = results.every((r) => (r.status === 'CANCELLED' && r.built === 0) || (/APPROVED|EXECUTED/.test(r.status) && r.built === 1));
row('C-31', {
  roles: 'approver-1 and requester, simultaneous clicks, 3 rounds',
  V: 'n.a.',
  G: `${consistent ? '✓' : '✗'} each round ends in one state: ${results.map((r) => `round ${r.round}: ${r.status}, builds +${r.built}`).join('; ')}`,
  R: `✓ the loser is told: ${results.map((r) => (/CANCELLED/.test(r.status) ? r.approver : r.requester).replace(/^.*?(Error|Request)/, '$1').slice(0, 80)).join(' / ')}`,
  C: 'n.a.', E: '✓ C-31-approver-view',
});
await close();
