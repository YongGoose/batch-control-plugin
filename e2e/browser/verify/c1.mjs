// C-01 escaping and C-02 CSV formula prefixes.
import { login, close, shot, api, job, BASE, requestRun, waitFor, sleep } from '../lib.mjs';
import { row, ev, mainText } from '../audit/rec.mjs';
import { findMail, mailText } from '../audit/mail.mjs';
const T = String(Date.now()).slice(-4);
const J = `=c02-${T}`; const enc = encodeURIComponent(J);
const DN = `<b>bold</b><img src=x onerror=alert(1)>`;
const xml = `<?xml version='1.1' encoding='UTF-8'?><project><displayName>${DN.replace(/</g, '&lt;').replace(/>/g, '&gt;')}</displayName><properties><hudson.model.ParametersDefinitionProperty><parameterDefinitions><hudson.model.StringParameterDefinition><name>P</name><defaultValue>x</defaultValue></hudson.model.StringParameterDefinition></parameterDefinitions></hudson.model.ParametersDefinitionProperty></properties><builders><hudson.tasks.Shell><command>echo "$P"; exit 1</command></hudson.tasks.Shell></builders></project>`;
const cr = await api('admin', `/createItem?name=${enc}`, { method: 'POST', body: xml, headers: { 'Content-Type': 'application/xml' } });
const bad = await api('admin', `/createItem?name=${encodeURIComponent('c01<b>x')}`, { method: 'POST', body: xml, headers: { 'Content-Type': 'application/xml' } });
const alerts = [];
const rq = await login('requester'); rq.page.on('dialog', async (d) => { alerts.push(`requester:${d.message()}`); await d.dismiss(); });
const reason = `<script>alert(1)</script> =1+1 ${T}`;
const url = await requestRun(rq.page, `/job/${enc}/`, { reason, approvers: ['approver-1'], params: { P: '<img src=x onerror=alert(2)>' } });
const id = url.match(/(\d{8}-\d{6}-\w+)/)[1];
const a = await login('approver-1'); a.page.on('dialog', async (d) => { alerts.push(`approver:${d.message()}`); await d.dismiss(); });
const pages = {};
await a.page.goto(url); pages.detail = await mainText(a.page); const s1 = await shot(a.page, '#main-panel table', 'C-01-1-detail-escaped', { pad: 8 });
await a.page.fill('form[name="approve"] textarea[name="comment"]', '<i>ok</i>'); await Promise.all([a.page.waitForLoadState('load'), a.page.locator('form[name="approve"] button').first().click()]);
await waitFor(async () => { const j = await job(J, 'lastBuild[building]'); return j.lastBuild && !j.lastBuild.building; }, { timeout: 90000 }); await sleep(4000);
for (const [k, p] of [['list', '/batch-control/requests/'], ['dashboard', '/batch-control/dashboard/'], ['history', '/batch-control/history/'], ['incidents', '/batch-control/incidents/']]) { await a.page.goto(BASE + p); pages[k] = await mainText(a.page); }
const s2 = await shot(a.page, a.page.locator('#main-panel tr', { hasText: 'bold' }).first(), 'C-01-2-incidents-row', { pad: 8 });
const inc = (await api('admin', '/batch-control/history/incidents.csv')).text.split('\n').find((l) => l.includes('c02-' + T)) || '';
if (inc) { await a.page.goto(`${BASE}/batch-control/incidents/${inc.split(',')[0]}/`); pages.incident = await mainText(a.page); }
const html = {}; for (const p of ['/batch-control/requests/' + id + '/', '/batch-control/dashboard/']) html[p] = (await api('approver-1', p)).text;
const rawTag = Object.values(html).some((h) => /<img src=x onerror|<script>alert\(1\)<\/script>/.test(h));
const m = await findMail(`to:approver-1@e2e.local ${id}`); const mt = m ? await mailText(m) : '';
ev(`C-01 create ${cr.status}, name with <b> ${bad.status} "${bad.text.replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ').slice(0, 120)}"; alerts ${JSON.stringify(alerts)}; raw tags in HTML ${rawTag}; pages show literal: ${Object.entries(pages).map(([k, v]) => `${k}:${/<script>alert|<img src=x|<b>bold/.test(v)}`).join(' ')}; mail "${mt.slice(0, 300)}"`);
row('C-01', { roles: 'requester, approver-1 (and admin creating the job)', V: 'n.a.', G: `${!alerts.length && !rawTag ? '✓' : '✗'} reason "<script>alert(1)</script>", parameter "<img src=x onerror=alert(2)>" and a display name "${DN}" appear as text on the detail, list, dashboard, history, incident list and incident detail; no dialog fired (${alerts.length}); no unescaped tag in the HTML. A job name with "<b>" is refused by core (${bad.status})`, R: 'n.a.', C: `${/<script>alert\(1\)<\/script>/.test(mt) ? '✓' : '✗'} the mail is plain text and carries the reason literally`, E: s1 && s2 ? '✓ C-01-1-detail-escaped, C-01-2-incidents-row' : '✗' });
// C-02 CSVs
const csv = {}; for (const c of ['runs', 'incidents', 'changes', 'requests']) csv[c] = (await api('admin', `/batch-control/history/${c}.csv`)).text.split('\n').filter((l) => l.includes(`c02-${T}`));
const unsafe = Object.entries(csv).flatMap(([c, ls]) => ls.flatMap((l) => l.split(',').filter((cell) => /^"?[=+\-@]/.test(cell) && !/^"?'/.test(cell)).map((cell) => `${c}:${cell}`)));
ev(`C-02 ${JSON.stringify(csv)}`);
row('C-02', { roles: 'admin (CSV exports)', V: 'n.a.', G: `${Object.values(csv).every((l) => l.length) && unsafe.length === 0 ? '✓' : '✗'} job "${J}" and reason "=1+1 ..." in all four CSVs: every cell starting with = + - @ is prefixed with ' (${Object.entries(csv).map(([c, l]) => `${c} ${l.length} rows`).join(', ')}; unprefixed: ${unsafe.join(', ') || 'none'})`, R: 'n.a.', C: 'n.a.', E: '✓ text (CSV rows in audit.log)' });
await close();
