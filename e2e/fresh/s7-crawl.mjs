// Scenario 5/7: for each role, crawl the Batch Control screens and the fresh job pages,
// follow every link and form-free GET target shown, and report any link that answers 4xx/5xx.
import fs from 'node:fs';
import { login, BASE, log, close, OUT } from './lib.mjs';

const roles = process.argv.slice(2).length ? process.argv.slice(2) : ['admin', 'manager', 'approver-1', 'requester', 'auditor', 'nobc', 'reqonly', 'configurer'];
const seeds = ['/batch-control/', '/job/fresh-daily/', '/job/fresh-fail-renamed/', '/batch-control/incidents/20260930-133056-nmnlps/', '/batch-control/grants/', '/job/fresh-cron/', '/job/fresh-folder/', '/job/fresh-pipe/1/', '/job/fresh-daily/1/'];
const inScope = (h) => /^\/(batch-control|batch-control-configuration|job\/fresh-)/.test(h) && !/\/(ws|lastBuild|lastSuccessfulBuild|console|consoleText|consoleFull|changes|buildTimeTrend|rssAll|rssFailed|pipeline-syntax|flowGraphTable|configure|confirmDelete|doDelete|replay|rebuild|retry|rename|move|jobConfigHistory|locked-resources|parameters|Authorization|authorization|api|cc\.xml|workflow-stage)\b/.test(h);
const result = {};
for (const role of roles) {
  const { page, context } = await login(role);
  const seen = new Set(); const queue = [...seeds]; const bad = []; const checked = new Map();
  let pages = 0;
  while (queue.length && pages < 70) {
    const p = queue.shift();
    if (seen.has(p)) continue; seen.add(p);
    const r = await page.goto(BASE + p).catch(() => null);
    pages++;
    if (!r || r.status() >= 400) continue;
    const links = await page.$$eval('#main-panel a[href], #side-panel a[href], #tasks a[href], .app-page-body__sidebar a[href]', (as) => as.map((a) => ({ h: a.getAttribute('href'), t: a.innerText.trim().slice(0, 40) })));
    for (const { h, t } of links) {
      if (!h || h.startsWith('#') || h.startsWith('javascript') || h.startsWith('mailto')) continue;
      const abs = new URL(h, page.url());
      if (abs.origin !== BASE) continue;
      const path = abs.pathname + abs.search;
      if (!checked.has(path)) {
        const res = await context.request.get(BASE + path, { maxRedirects: 5 }).catch(() => null);
        checked.set(path, res ? res.status() : 0);
      }
      const st = checked.get(path);
      if (st >= 400 || st === 0) bad.push(`${st} ${path} ("${t}") on ${p}`);
      if (inScope(abs.pathname) && !seen.has(path) && queue.length < 400 && /(month=|page=|\?$|\/$)/.test(path) === false ? false : inScope(abs.pathname)) queue.push(path);
    }
  }
  const uniq = [...new Set(bad)];
  result[role] = { pages, links: checked.size, bad: uniq };
  log('ROLE', role, 'pages', pages, 'links', checked.size, 'bad', uniq.length);
  for (const b of uniq.slice(0, 40)) log('   ', b);
  await context.close();
}
fs.writeFileSync(`${OUT}/crawl.json`, JSON.stringify(result, null, 1));
await close();
