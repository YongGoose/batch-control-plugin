// A-15: notifications as the recipients see them in the mail sink.
import { login, close, BASE, MAIL, shot, log, mails, launch, sleep } from './lib.mjs';
import { requestRun } from './lib.mjs';
const L = 'section-a.log';
const rq = await login('requester');
const url = await requestRun(rq.page, '/job/batch-pipeline/', { reason: 'Replay the 29th after the vendor fix.\nLink: http://evil.example/ (should stay quoted)', approvers: ['approver-1'] });
const ap = await login('approver-1');
await ap.page.goto(url);
await ap.page.fill('form[name="reject"] textarea[name="comment"]', 'Not today: the vendor fix is not deployed yet.');
await Promise.all([ap.page.waitForLoadState('load'), ap.page.locator('form[name="reject"] button:has-text("Reject")').click()]);
await rq.page.goto(url);
await shot(rq.page, '#main-panel table', 'A-15-1-rejected-requester-view', { pad: 10 });
await sleep(4000);
const all = await (await fetch(`${MAIL}/api/v1/messages?limit=100`)).json();
const subj = all.messages.map((m) => `${m.To.map((t) => t.Address).join(',')}: ${m.Subject}`);
log(L, `A-15 mail sink has ${all.messages.length} messages:\n    ${subj.join('\n    ')}`);
const pick = async (re) => {
  const m = all.messages.find((x) => re.test(x.Subject));
  if (!m) return null;
  const full = await (await fetch(`${MAIL}/api/v1/message/${m.ID}`)).json();
  return { m, text: full.Text };
};
for (const [tag, re] of [['created', /awaiting your decision: run request/], ['rejected', /rejected/i], ['grant-expiring', /expir/i]]) {
  const x = await pick(re);
  log(L, `A-15 ${tag}: ${x ? x.m.Subject + '\n' + x.text.split('\n').map((l) => '      | ' + l).join('\n') : 'NOT FOUND'}`);
  if (x) {
    const b = await launch();
    const ctx = await b.newContext({ viewport: { width: 1100, height: 900 } });
    const p = await ctx.newPage();
    await p.goto(`${MAIL}/view/${x.m.ID}`);
    await p.waitForTimeout(2500);
    await shot(p, 'body', `A-15-mail-${tag}`, { pad: 0 });
    await ctx.close();
    // follow the link in the mail as the recipient (after login)
    const link = (x.text.match(/https?:\/\/\S+/) || [])[0];
    if (link && tag !== 'grant-expiring') {
      const who = tag === 'created' ? ap : rq;
      const r = await who.page.goto(link);
      log(L, `A-15 ${tag} link ${link} -> ${r.status()} "${(await who.page.locator('#main-panel h1, #main-panel .jenkins-app-bar').first().innerText().catch(() => ''))}"`);
    }
  }
}
await close();
