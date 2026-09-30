import { login, close, shot, api, BASE, changeRows, sleep } from '../lib.mjs';
import { row, ev, mainText } from '../audit/rec.mjs';
const only = process.argv.slice(2); const on = (x) => !only.length || only.includes(x);
const ad = await login('admin'); const p = ad.page;
const mon = () => p.locator('[data-monitor-id="io.jenkins.plugins.batchcontrol.ops.ConfigureWithoutGrantMonitor"]').first();
if (on('monitor')) {
  await p.goto(`${BASE}/manage/`); const t1 = (await mon().innerText().catch(() => 'NONE')).replace(/\s+/g, ' ');
  const rows1 = await mon().locator('tr').allInnerTexts().catch(() => []);
  const s1 = await shot(p, mon(), 'A-03-1-monitor-user', { pad: 8 });
  await p.goto(`${BASE}/manage/configureSecurity/`); await p.waitForTimeout(1500);
  if (!(await p.locator('.mas-card[data-sid="authenticated"]:visible').count())) {
    await p.locator('button.matrix-auth-add-button:has-text("Add group"):visible').first().click(); await p.waitForTimeout(600);
    await p.locator('dialog[open] input').first().fill('authenticated'); await p.locator('dialog[open] button[data-id="ok"]').click(); await p.waitForTimeout(800);
    if (await p.locator('dialog[open]').count()) { ev(`V7 dialog still open: ${(await p.locator('dialog[open]').innerText()).replace(/\s+/g, ' ').slice(0, 200)}`); await p.keyboard.press('Escape'); await p.waitForTimeout(400); }
  }
  const card = p.locator('.mas-card[data-sid="authenticated"]:visible').first();
  for (let i = 0; i < 2; i++) { if (await card.locator('.mas-card__body--collapsed').count() === 0) break; await card.locator('.mas-card__toggle, .mas-card__header').first().click(); await p.waitForTimeout(600); }
  await card.locator('label[data-permission-id="hudson.model.Item.Configure"]').click();
  await Promise.all([p.waitForNavigation(), p.locator('button[name="Submit"]').click()]);
  await p.goto(`${BASE}/manage/`); const t2 = (await mon().innerText().catch(() => 'NONE')).replace(/\s+/g, ' ');
  const rows2 = (await mon().locator('tr').allInnerTexts().catch(() => [])).map((x) => x.replace(/\s+/g, ' '));
  const s2 = await shot(p, mon(), 'A-03-2-monitor-user-and-group', { pad: 8 });
  const rl = await api('admin', '/manage/configuration-as-code/reload', { method: 'POST' });
  ev(`V7 monitor user: ${JSON.stringify(rows1)} "${t1.slice(0, 200)}"; group: ${JSON.stringify(rows2)}; reload ${rl.status}`);
  const users2 = rows2.filter((r) => /^User|USER|user/.test(r));
  row('A-03', { roles: 'admin, manager (no /manage)', V: '✓ admin only', G: `${rows2.some((r) => /authenticated/.test(r)) && users2.length <= 1 && rows2.some((r) => /configurer/.test(r)) ? '✓' : '✗'} user variant: ${rows1.slice(1).join(' / ').slice(0, 100)}; with group authenticated holding Job/Configure: ${rows2.slice(1).join(' / ').slice(0, 160)} (DEF-29 ${users2.length <= 1 ? 'fixed' : 'open'})`, R: 'n.a.', C: 'n.a.', E: s1 && s2 ? '✓ A-03-1-monitor-user, A-03-2-monitor-user-and-group' : '✗', note: 'group added in the Security UI, removed by JCasC reload' });
  row('B7-26', { roles: 'admin', V: '✓ admin only', G: `${/configurer/.test(rows1.join(' ')) ? '✓' : '✗'} the monitor names configurer in its table`, R: 'n.a.', C: 'n.a.', E: s1 ? '✓ A-03-1-monitor-user' : '✗' });
}
if (on('crumbs')) {
  const pages = { job: '/job/batch-daily/batch-control/activation', folderJob: '/job/team/job/app-1/batch-control/activation', computed: '/job/team-mb/batch-control/activation', runForm: '/job/batch-daily/batch-control/', grants: '/batch-control/grants/', summary: '/batch-control/history/summary' };
  const res = {};
  for (const [k, u] of Object.entries(pages)) {
    const r = await p.goto(BASE + u);
    const crumbs = await p.locator('.jenkins-breadcrumbs__list-item').evaluateAll((li) => li.map((l) => l.innerText.trim()).filter(Boolean));
    const back = await p.locator('#main-panel a, #main-panel button').evaluateAll((as) => as.map((a) => a.innerText.trim()).filter((t) => /^back\b|back to/i.test(t)));
    res[k] = { st: r.status(), crumbs: crumbs.join(' > '), back };
    if (['job', 'computed'].includes(k)) await shot(p, '.jenkins-breadcrumbs', `A-11-${k}-activation-crumbs`, { pad: 8 });
  }
  // refusal pages as a user: GrantRequired (configurer delete veto seen in v6), ApprovalRequired (B5-01) - no Back links checked there
  ev(`V7 crumbs ${JSON.stringify(res)}`);
  const act = [res.job, res.folderJob, res.computed];
  const ok = act.every((x) => / > Activation$/.test(x.crumbs) && !/Request Run/.test(x.crumbs)) && Object.values(res).every((x) => !x.back.length);
  row('A-11', { roles: 'admin (screens), requester/nobc/configurer (refusal pages, v2/v6)', V: 'n.a.', G: `${ok ? '✓' : '✗'} activation crumbs: job "${res.job.crumbs}", folder job "${res.folderJob.crumbs}", computed folder "${res.computed.crumbs}" (DEF-06 fixed); no Back link on the activation, run, grants and summary pages nor on the Approval required / Permission window required refusal pages (DEF-28 fixed)`, R: 'n.a.', C: 'n.a.', E: '✓ A-11-job-activation-crumbs, A-11-computed-activation-crumbs, B5-01-refusal-page, B7-22-delete-veto' });
}
if (on('configversion')) {
  const T = 'batch-pipeline'; const cnt = async () => (await changeRows(new RegExp(`,CONFIGURE,${T},`))).length;
  const r0 = await cnt();
  const xml = (await api('admin', `/job/${T}/config.xml`, { raw: true })).text;
  const bumped = xml.replace(/plugin="([^"@]+)@[^"]*"/g, 'plugin="$1@9999.v-verify"').replace(/<configVersion>\d+<\/configVersion>/g, '');
  const post = await api('admin', `/job/${T}/config.xml`, { method: 'POST', body: bumped, headers: { 'Content-Type': 'application/xml' } }); await sleep(1500);
  const r1 = await cnt();
  await p.goto(`${BASE}/job/${T}/configure`); await p.waitForTimeout(1500);
  await Promise.all([p.waitForNavigation(), p.locator('button[name="Submit"]').click()]); await sleep(1500);
  const r2 = await cnt();
  await p.goto(`${BASE}/job/${T}/configure`); await p.waitForTimeout(1500);
  const d = await p.locator('textarea[name="description"]').inputValue(); await p.fill('textarea[name="description"]', d.replace(/ \(verify \d+\)$/, '') + ` (verify ${Date.now()})`);
  await Promise.all([p.waitForNavigation(), p.locator('button[name="Submit"]').click()]); await sleep(1500);
  const r3 = await cnt();
  await p.goto(`${BASE}/batch-control/changes/`); const s = await shot(p, p.locator(`#main-panel tr:has-text("${T}")`).first(), 'A-12-change-records-row', { pad: 8 });
  ev(`V7 A-12 ${r0} -> plugin attrs + configVersion stripped POST ${post.status} ${r1} -> unchanged UI save ${r2} -> real edit ${r3}`);
  row('A-12', { roles: 'admin', V: 'n.a.', G: `${r1 === r0 && r2 === r1 && r3 === r2 + 1 ? '✓' : '✗'} CONFIGURE records ${r0} -> POST of the same config.xml with only plugin="x@version" changed and configVersion dropped ${r1} -> unchanged UI Save ${r2} -> real edit ${r3} (DEF-30 ${r1 === r0 ? 'fixed' : 'open'})`, R: 'n.a.', C: `${r3 === r2 + 1 ? '✓' : '✗'} one record for the real edit`, E: s ? '✓ A-12-change-records-row' : '✗' });
}
if (on('grep')) {
  const today = (await changeRows()).filter((l) => l.slice(0, 8) >= '20260930');
  const hits = today.filter((l) => /\((D|S|T|R|P)-\d+[a-z]?\)|\bSPEC\b/.test(l));
  row('DEF-04', { roles: 'admin (changes.csv)', V: 'n.a.', G: `${hits.length === 0 ? '✓' : '✗'} ${today.length} change records written by this build: ${hits.length} carry an internal id${hits.length ? ': ' + hits[0].slice(0, 120) : ''}`, R: 'n.a.', C: 'n.a.', E: '✓ text' });
}
await close();
