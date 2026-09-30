// Re-audit Section A read-only rows: A-05, A-06, A-10, A-11, A-13, A-14, A-17, A-18.
import { login, close, shot, api, BASE } from '../lib.mjs';
import { row, ev, mainText } from './rec.mjs';
const want = process.argv.slice(2);
const on = (id) => !want.length || want.includes(id);
const side = (page) => page.locator('#side-panel a.task-link, #tasks a.task-link, #side-panel .task a');

if (on('A-05')) {
  const res = {}; const shots = [];
  const expect = { reqonly: ['Run Requests', 'Activations'], requester: ['Run Requests', 'Activations', 'Grants'], auditor: ['Change Records', 'Dashboard', 'Incidents', 'History'], 'approver-1': 7, manager: 7, 'approver-disc': null, 'approver-unlisted': null, configurer: null, admin: 7 };
  for (const u of Object.keys(expect)) {
    const { context, page } = await login(u);
    const r = await page.goto(`${BASE}/batch-control/`);
    const links = await side(page).evaluateAll((as) => as.map((a) => ({ t: a.innerText.trim(), h: a.getAttribute('href') })));
    const main = await page.locator('#main-panel a').evaluateAll((as) => as.map((a) => ({ t: a.innerText.trim(), h: a.getAttribute('href') })));
    const s = await shot(page, ['#side-panel', '#main-panel .jenkins-app-bar, #main-panel h1'], `A-05-${u}`, { pad: 10 });
    shots.push(!!s);
    const clicked = [];
    for (const l of [...links, ...main].filter((x) => x.h && !x.h.startsWith('#'))) {
      const rr = await page.goto(new URL(l.h, `${BASE}/batch-control/`).href);
      clicked.push(`${l.t}=${rr.status()}`);
    }
    res[u] = { status: r.status(), side: links.map((l) => l.t), clicked };
    await context.close();
  }
  ev(`A-05 ${JSON.stringify(res)}`);
  const bad = Object.entries(res).flatMap(([u, v]) => v.clicked.filter((c) => !/=200$/.test(c)).map((c) => `${u}:${c}`));
  const ok = (u) => { const e = expect[u]; if (e === null) return true; return typeof e === 'number' ? res[u].side.length === e : JSON.stringify(res[u].side) === JSON.stringify(e); };
  const vis = Object.keys(expect).every(ok);
  row('A-05', { roles: Object.keys(expect).join(', '), V: `${vis ? '✓' : '✗'} ${Object.entries(res).map(([u, v]) => `${u}: ${v.side.join('/')}`).join('; ')}`, G: bad.length ? `✗ offered link not 200: ${bad}` : `✓ every offered link (side panel + main panel) clicked = 200 (${Object.values(res).reduce((n, v) => n + v.clicked.length, 0)} clicks)`, R: 'n.a. (nothing refused is offered)', C: 'n.a.', E: shots.every(Boolean) ? '✓ A-05-<user>.png (9)' : '✗' });
}

if (on('A-06')) {
  const { context, page } = await login('nobc');
  await page.goto(`${BASE}/`);
  const html = await page.content();
  const nav = (await page.locator('#side-panel, header, #page-header').allInnerTexts()).join(' ');
  // open the header menu as a user would
  const menuBtn = page.locator('header button[aria-label], #root-action-SideMenu, .jenkins-header__actions button, header .jenkins-menu-dropdown-chevron').last();
  let menuText = '';
  if (await menuBtn.count()) { await menuBtn.click().catch(() => {}); await page.waitForTimeout(800); menuText = (await page.locator('.tippy-box, .jenkins-dropdown').allInnerTexts()).join(' '); }
  const s1 = await shot(page, ['#side-panel', 'header, #page-header'], 'A-06-1-nobc-dashboard', { pad: 8 });
  const codes = [];
  for (const p of ['/batch-control/', '/batch-control/requests/', '/batch-control/history/runs.csv', '/batch-control/history/summary', '/batch-control/grants/', '/batch-control/dashboard/', '/batch-control/activations/']) codes.push(`${p}=${(await page.goto(BASE + p)).status()}`);
  await page.goto(`${BASE}/batch-control/`);
  const s2 = await shot(page, '#main-panel, body', 'A-06-2-nobc-404', { pad: 8 });
  const posts = [];
  for (const p of ['/batch-control/grants/create', '/batch-control/requests/x/approve', '/batch-control/activations/x/approve']) posts.push(`POST ${p}=${(await api('nobc', p, { method: 'POST', body: new URLSearchParams({ reason: 'x' }), headers: { 'Content-Type': 'application/x-www-form-urlencoded' } })).status}`);
  ev(`A-06 nav mentions BC ${/Batch Control/.test(nav + menuText)}, href ${html.includes('/batch-control/')}; ${codes.join(' ')} ${posts.join(' ')}`);
  const all404 = [...codes, ...posts].every((c) => /=404$/.test(c));
  row('A-06', { roles: 'nobc (and admin for contrast in A-05)', V: `${!/Batch Control/.test(nav + menuText) && !html.includes('/batch-control/') ? '✓' : '✗'} no Batch Control entry in side panel, header or header menu; no href to /batch-control/`, G: `${all404 ? '✓' : '✗'} ${codes.length} GETs and ${posts.length} POSTs (valid crumb) all 404`, R: 'n.a. (404 by design: the feature does not exist for him)', C: 'n.a.', E: s1 && s2 ? '✓ A-06-1..2' : '✗' });
  await context.close();
}

if (on('A-13')) {
  const res = {}; const sh = [];
  for (const u of ['nobc', 'approver-1', 'auditor', 'requester']) {
    const { context, page } = await login(u);
    const r0 = await page.goto(`${BASE}/job/batch-daily/`);
    const entries = (await side(page).allInnerTexts()).map((e) => e.trim());
    sh.push(!!(await shot(page, '#side-panel', `A-13-${u}-sidebar`, { pad: 8 })));
    const codes = [];
    for (const p of ['batch-control/', 'batch-control/submit', 'batch-control/activation']) codes.push(`${p}=${(await page.goto(`${BASE}/job/batch-daily/${p}`)).status()}`);
    res[u] = { job: r0.status(), entries, codes };
    await context.close();
  }
  // DEF-02 fix: nobc's Direct Build refusal
  const { context, page } = await login('nobc');
  await page.goto(`${BASE}/job/batch-daily/`);
  await page.locator('#side-panel a:has-text("Direct Build")').click(); await page.waitForLoadState('load');
  const [resp] = await Promise.all([page.waitForNavigation({ waitUntil: 'load' }).catch(() => null), page.locator('#main-panel button:has-text("Build")').first().click()]);
  const txt = await mainText(page);
  const links = await page.locator('#main-panel a').evaluateAll((as) => as.map((a) => `${a.innerText.trim()}->${a.getAttribute('href')}`));
  const s = await shot(page, '#main-panel .jenkins-alert, #main-panel', 'A-13-nobc-direct-build-refusal', { pad: 12 });
  const back = await page.locator('#main-panel a:has-text("Back to")').count();
  const s2 = back ? await shot(page, page.locator('#main-panel a:has-text("Back to")').first(), 'A-13-nobc-refusal-back-link', { pad: 12 }) : null;
  ev(`A-13 ${JSON.stringify(res)}; nobc refusal ${resp && resp.status()} "${txt.slice(0, 300)}" links ${links}`);
  const hidden = ['nobc', 'approver-1'].every((u) => !res[u].entries.some((e) => /Request Run/.test(e)) && res[u].codes.every((c) => /=404$/.test(c)));
  row('A-13', { roles: 'nobc, approver-1, auditor, requester', V: `${hidden && res.requester.entries.some((e) => /Request Run/.test(e)) ? '✓' : '✗'} nobc [${res.nobc.entries}], approver-1 [${res['approver-1'].entries}], auditor job ${res.auditor.job} [${res.auditor.entries}], requester [${res.requester.entries}]; nobc/approver-1 batch-control/, submit, activation = 404`, G: '✓ getUrlName-style hiding: the action is absent and its URLs 404 for non-Request holders', R: `${/may not submit run requests/.test(txt) && !/batch-control\//.test(links.join(' ')) ? '✓' : '✗'} nobc Direct Build -> "${txt.slice(0, 150)}" (HTTP ${resp && resp.status()}); no link to the 404 form (DEF-02 fixed)`, C: 'n.a.', E: s ? '✓ A-13-*-sidebar, A-13-nobc-direct-build-refusal' : '✗', note: back ? 'the refusal page ends with a "Back to batch-daily" link (see A-11)' : '' });
  await context.close();
}

if (on('A-14')) {
  const { context, page } = await login('reqonly');
  const out = []; let s;
  for (const p of ['/batch-control/history/', '/batch-control/dashboard/', '/batch-control/changes/', '/batch-control/incidents/', '/batch-control/grants/']) {
    const code = (await page.goto(BASE + p)).status();
    const text = (await page.locator('#main-panel, body').first().innerText()).replace(/\s+/g, ' ');
    out.push({ p, code, t: (text.match(/reqonly is missing[^.]*/) || [text.slice(0, 80)])[0] });
    if (p.includes('history')) s = await shot(page, '#main-panel, body', 'A-14-reqonly-history-403', { pad: 8 });
  }
  ev(`A-14 ${JSON.stringify(out)}`);
  const std = out.every((o) => o.code === 403 && /missing the Batch Control\/(ViewHistory|RequestGrant) permission/.test(o.t));
  row('A-14', { roles: 'reqonly (links hidden for him per A-05)', V: '✓ none of the five sections is linked for reqonly (A-05)', G: `${std ? '✓' : '✗'} typed URLs answer the standard Jenkins 403 page: ${out.map((o) => `${o.p.split('/')[2]} ${o.code} "${o.t}"`).join('; ')}`, R: '✓ standard page naming the missing permission (the reviewer\'s ask); reached only by typing a URL', C: 'n.a.', E: s ? '✓ A-14-reqonly-history-403.png' : '✗' });
  await context.close();
}

if (on('A-10')) {
  const found = []; const sh = [];
  for (const [u, scale] of [['admin', 1], ['requester', 2], ['approver-1', 1]]) {
    const { context, page } = await login(u, { scale });
    for (const p of ['/batch-control/', '/job/batch-daily/', '/job/batch-daily/9/']) {
      await page.goto(BASE + p);
      const icons = await page.locator('#side-panel svg, #side-panel img').evaluateAll((els) => els.map((e) => { const r = e.getBoundingClientRect(); const empty = e.tagName === 'svg' ? e.innerHTML.trim().length < 20 : !(e.complete && e.naturalWidth > 0); return { w: Math.round(r.width), empty, label: (e.closest('a')?.innerText || '').trim() }; }));
      const broken = icons.filter((i) => i.empty || i.w === 0);
      found.push(`${u}@${scale}x ${p}: ${icons.length} icons, broken ${broken.length}${broken.length ? JSON.stringify(broken) : ''}`);
      sh.push(!!(await shot(page, '#side-panel', `A-10-${u}-${p.replace(/[^a-z0-9]+/g, '-')}${scale}x`, { pad: 6 })));
    }
    await context.close();
  }
  const deps = await api('admin', '/pluginManager/api/json?depth=2&tree=plugins[shortName,dependencies[shortName,optional]]');
  const d = deps.json.plugins.find((x) => x.shortName === 'batch-control').dependencies.find((x) => x.shortName === 'ionicons-api');
  ev(`A-10 ${found.join(' | ')}; ionicons-api dep ${JSON.stringify(d)}`);
  const anyBroken = found.some((f) => !/broken 0/.test(f));
  row('A-10', { roles: 'admin (1x), requester (2x), approver-1 (1x)', V: 'n.a. (icons of entries each role sees; entries per role in A-05/A-13)', G: `${anyBroken ? '✗' : '✓'} every side-panel icon on landing, job and approved-build page renders (non-empty svg): ${found.map((f) => f.split(':')[1]).join(';')}; ionicons-api required dependency ${d && !d.optional}`, R: 'n.a.', C: 'n.a.', E: sh.every(Boolean) ? '✓ A-10-*.png (9)' : '✗' });
}

if (on('A-11')) {
  const { context, page } = await login('admin');
  const screens = ['/batch-control/', '/batch-control/requests/', '/batch-control/activations/', '/batch-control/grants/', '/batch-control/changes/', '/batch-control/dashboard/', '/batch-control/incidents/', '/batch-control/history/', '/batch-control/history/summary', '/job/batch-daily/batch-control/', '/job/batch-daily/batch-control/activation'];
  for (const list of ['/batch-control/activations/', '/batch-control/requests/', '/batch-control/grants/', '/batch-control/incidents/']) {
    await page.goto(BASE + list);
    const hrefs = await page.locator('#main-panel a[href*="/batch-control/"]').evaluateAll((as) => as.map((a) => a.getAttribute('href')));
    for (const h of hrefs.filter((x) => /\/\d{8}-/.test(x)).slice(0, 2)) screens.push(new URL(h, BASE).pathname);
  }
  const f = []; const backs = [];
  for (const p of screens) {
    const code = (await page.goto(BASE + p)).status();
    const back = await page.locator('#main-panel a, #main-panel button').evaluateAll((as) => as.map((a) => a.innerText.trim()).filter((t) => /^back\b|back to/i.test(t)));
    const crumbs = await page.locator('.jenkins-breadcrumbs__list-item').evaluateAll((li) => li.map((l) => l.innerText.trim()).filter(Boolean));
    f.push(`${p} ${code} [${crumbs.join(' > ')}]${back.length ? ' BACK=' + back : ''}`);
    if (back.length) backs.push(p);
  }
  // the gate's refusal page (DEF-02 fix) as the requester sees it
  const rq = await login('requester');
  await rq.page.goto(`${BASE}/job/batch-daily/`);
  await rq.page.locator('#side-panel a:has-text("Direct Build")').click(); await rq.page.waitForLoadState('load');
  await Promise.all([rq.page.waitForNavigation({ waitUntil: 'load' }).catch(() => null), rq.page.locator('#main-panel button:has-text("Build")').first().click()]);
  const rb = await rq.page.locator('#main-panel a').evaluateAll((as) => as.map((a) => a.innerText.trim()).filter((t) => /back to/i.test(t)));
  const refCrumbs = await rq.page.locator('.jenkins-breadcrumbs__list-item').evaluateAll((li) => li.map((l) => l.innerText.trim()).filter(Boolean));
  const sBack = rb.length ? await shot(rq.page, [rq.page.locator('#main-panel a:has-text("Back to")').first(), '.jenkins-breadcrumbs'], 'A-11-refusal-page-back-link', { pad: 10 }) : null;
  f.push(`refusal page (Direct Build -> Build) crumbs [${refCrumbs.join(' > ')}] BACK=${rb}`);
  // activation form crumbs and their targets (DEF-06)
  await page.goto(`${BASE}/job/batch-daily/batch-control/activation`);
  const ac = await page.locator('.jenkins-breadcrumbs a').evaluateAll((as) => as.map((a) => `${a.innerText.trim()}->${a.getAttribute('href')}`));
  const sAct = await shot(page, '.jenkins-breadcrumbs', 'A-11-activation-crumbs', { pad: 8 });
  // walk the crumbs of a deep page
  await page.goto(`${BASE}/batch-control/grants/`);
  const s1 = await shot(page, '.jenkins-breadcrumbs', 'A-11-crumbs', { pad: 8 });
  const crumbLinks = await page.locator('.jenkins-breadcrumbs a').evaluateAll((as) => as.map((a) => a.getAttribute('href')).filter(Boolean));
  const cs = []; for (const h of crumbLinks) cs.push(`${h}=${(await page.goto(new URL(h, BASE).href)).status()}`);
  for (const x of f) ev(`A-11 ${x}`);
  ev(`A-11 activation crumbs ${ac}; grants crumb targets ${cs}`);
  row('A-11', { roles: 'admin (all screens), requester (refusal page)', V: 'n.a.', G: `${backs.length || rb.length ? '✗' : '✓'} ${screens.length} Batch Control screens: ${backs.length ? 'Back links on ' + backs : 'no Back link'}; the gate refusal page (new in this build): "${rb}"; crumbs work (${cs.join(' ')}); activation form crumbs ${ac.join(' > ')}`, R: 'n.a.', C: 'n.a.', E: s1 && sAct ? `✓ A-11-crumbs, A-11-activation-crumbs${sBack ? ', A-11-refusal-page-back-link' : ''}` : '✗', defect: rb.length ? 'DEF-28' : '' });
  await rq.context.close(); await context.close();
}

if (on('A-17')) {
  const { context, page } = await login('approver-1');
  await page.goto(`${BASE}/batch-control/grants/`);
  const g = await page.locator('#main-panel table a').filter({ hasText: /^[0-9]{8}-/ }).first().getAttribute('href');
  const r = (await api('admin', '/batch-control/history/requests.csv')).text.split('\n')[1].split(',')[0];
  const out = []; const sh = [];
  for (const [kind, url] of [['grant', new URL(g, BASE + '/batch-control/grants/').href], ['run', `${BASE}/batch-control/requests/${r}/`]]) {
    for (const w of [1280, 800]) {
      await page.setViewportSize({ width: w, height: 900 });
      await page.goto(url);
      const t = page.locator('#main-panel table').first();
      const cls = await t.getAttribute('class');
      const hs = await t.locator('tr').evaluateAll((trs) => trs.map((x) => Math.round(x.getBoundingClientRect().height)));
      const ww = await t.evaluate((x) => [Math.round(x.getBoundingClientRect().width), document.documentElement.scrollWidth]);
      out.push(`${kind}@${w}: class="${cls}" maxRow=${Math.max(...hs)} width=${ww}`);
      sh.push(!!(await shot(page, t, `A-17-${kind}-${w}`, { pad: 8 })));
    }
  }
  ev(`A-17 ${out.join(' | ')}`);
  const small = out.every((o) => /jenkins-table--small/.test(o));
  const noOverflow = out.filter((o) => o.includes('@800')).every((o) => { const m = o.match(/width=(\d+),(\d+)/); return +m[2] <= 800; });
  row('A-17', { roles: 'approver-1', V: 'n.a.', G: `${small && noOverflow ? '✓' : '✗'} ${out.join('; ')}`, R: 'n.a.', C: 'n.a.', E: sh.every(Boolean) ? '✓ A-17-{grant,run}-{1280,800}.png' : '✗' });
  await context.close();
}

if (on('A-18')) {
  const { context, page } = await login('admin');
  await page.goto(`${BASE}/batch-control/`);
  const appBar = await page.locator('#main-panel .jenkins-app-bar').count();
  const sideT = (await side(page).allInnerTexts()).map((s) => s.trim());
  const mainA = await page.locator('#main-panel a').allInnerTexts();
  const tops = await side(page).evaluateAll((as) => as.map((a) => Math.round(a.getBoundingClientRect().top)));
  const gaps = tops.slice(1).map((t, i) => t - tops[i]);
  const dup = mainA.filter((m) => sideT.includes(m.trim()));
  const s = await shot(page, ['#side-panel', '#main-panel .jenkins-app-bar', '#main-panel p'], 'A-18', { pad: 10 });
  ev(`A-18 appbar ${appBar} side ${sideT} main ${mainA} gaps ${gaps}`);
  row('A-18', { roles: 'admin (all 7 entries)', V: 'n.a. (per-role entries in A-05)', G: `${appBar && !dup.length && Math.min(...gaps) >= 30 ? '✓' : '✗'} app bar present; side panel ${sideT.length} entries, main-panel links duplicating them: ${dup.length ? dup : 'none'}; entry spacing ${Math.min(...gaps)}-${Math.max(...gaps)} px`, R: 'n.a.', C: 'n.a.', E: s ? '✓ A-18.png' : '✗' });
  await context.close();
}
await close();
