// A-19: no internal reference visible on any screen, help text or mail.
import { login, close, BASE, MAIL, log, shot } from '../lib.mjs';
import fs from 'node:fs';
const L = 'section-a.log';
const RE = /\bSPEC\b|\bD-\d+[a-z]?\b|\bT-\d+|\bR-\d+\b|\bP-\d+\b|\bS-\d+|#\d{1,3}\b|note \d+|io\.jenkins\.plugins|grantId=null|\bsecurity-\d+/g;
const out = [];
const hits = [];
const seen = new Set();
const { page } = await login('admin');
const queue = ['/batch-control/', '/batch-control/requests/', '/batch-control/activations/', '/batch-control/grants/', '/batch-control/changes/', '/batch-control/dashboard/', '/batch-control/incidents/', '/batch-control/history/', '/batch-control/history/summary',
  '/job/batch-daily/', '/job/batch-daily/batch-control/', '/job/batch-daily/batch-control/activation', '/job/batch-daily/configure', '/job/batch-cron/', '/job/team-mb/', '/manage/', '/manage/configure', '/job/batch-daily/1/', '/job/batch-cbn/', '/job/b10-ui/', '/job/team-mb/job/main/', '/manage/configureSecurity/', '/job/batch-daily/10/'];
for (const t of ['runs', 'incidents', 'changes', 'requests']) queue.push(`/batch-control/history/?tab=${t}`);
while (queue.length) {
  const p = queue.shift();
  if (seen.has(p) || seen.size > 80) continue;
  seen.add(p);
  const r = await page.goto(BASE + p).catch(() => null);
  if (!r) continue;
  // open every help popup of Batch Control fields on configure pages
  const text = await page.locator('#main-panel, body').first().innerText().catch(() => '');
  for (const m of text.matchAll(RE)) {
    const ctx = text.slice(Math.max(0, m.index - 70), m.index + 50).replace(/\s+/g, ' ');
    if (/Jenkins 2\.568|#\d+ \(|batch-daily#\d|#\d+$/.test(ctx) && m[0].startsWith('#')) continue; // build numbers
    hits.push(`${p}: "${m[0]}" in "…${ctx}…"`);
  }
  out.push(`${r.status()} ${p}`);
  const links = await page.locator('#main-panel a[href]').evaluateAll((as) => as.map((a) => a.getAttribute('href')));
  for (const h of links) {
    const u = new URL(h, BASE + p);
    if (u.origin === BASE && /\/batch-control\/(requests|activations|grants|incidents|changes)\/[0-9]{8}-/.test(u.pathname)) queue.push(u.pathname);
  }
}
// help files
const helps = fs.readdirSync('../../../src/main/webapp/help').map((f) => `/plugin/batch-control/help/${f}`);
const res = '../../../src/main/resources/io/jenkins/plugins/batchcontrol/config';
for (const d of fs.readdirSync(res)) for (const f of fs.readdirSync(`${res}/${d}`).filter((x) => x.startsWith('help-'))) helps.push(`/descriptor/io.jenkins.plugins.batchcontrol.config.${d}/help/${f.replace(/^help-|\.html$/g, '')}`);
for (const h of helps) {
  const r = await page.goto(BASE + h);
  const text = await page.locator('body').innerText().catch(() => '');
  for (const m of text.matchAll(RE)) hits.push(`${h}: "${m[0]}" in "…${text.slice(Math.max(0, m.index - 60), m.index + 40).replace(/\s+/g, ' ')}…"`);
  out.push(`${r.status()} ${h}`);
}
// mails
const all = await (await fetch(`${MAIL}/api/v1/messages?limit=200`)).json();
for (const m of all.messages) {
  const f = await (await fetch(`${MAIL}/api/v1/message/${m.ID}`)).json();
  for (const x of (m.Subject + '\n' + f.Text).matchAll(RE)) hits.push(`mail "${m.Subject}": "${x[0]}"`);
}
// CSV exports
for (const c of ['runs', 'incidents', 'changes', 'requests']) {
  const r = await fetch(`${BASE}/batch-control/history/${c}.csv`, { headers: { Authorization: 'Basic ' + Buffer.from('admin:' + (await import('../lib.mjs')).password('admin')).toString('base64') } });
  const t = await r.text();
  for (const x of t.matchAll(RE)) hits.push(`${c}.csv: "${x[0]}" in "…${t.slice(Math.max(0, x.index - 80), x.index + 20).replace(/\s+/g, ' ')}…"`);
}
fs.writeFileSync('../../out/A-19-grep-audit.txt', `pages/help checked:\n${out.join('\n')}\n\nmails: ${all.messages.length}\n\nHITS (${hits.length}):\n${[...new Set(hits)].join('\n')}\n`);
fs.copyFileSync('../../out/A-19-grep-audit.txt', '../../screenshots/run-3-audit/A-19-grep.txt');
log(L, `A-19 checked ${out.length} pages/help files, ${all.messages.length} mails, 4 CSVs; hits ${new Set(hits).size}`);
for (const h of [...new Set(hits)].slice(0, 40)) log(L, `A-19 HIT ${h}`);
// screenshot one visible hit: Change Records row with the GRANT_VIOLATION detail
await page.goto(BASE + '/batch-control/changes/');
const row = page.locator('#main-panel tr:has-text("GRANT_VIOLATION")').first();
if (await row.count()) await shot(page, row, 'A-19-1-change-records-hit', { pad: 8 });
await close();
