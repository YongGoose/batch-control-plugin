// Section A of CHECKLIST.md: the hosting reviewer's 23 items, clicked through
// as the checklist accounts. Usage: node section-a.mjs <A-xx> [<A-yy> ...]
import { login, close, shot, api, job, queue, groovy, mails, waitFor, sleep, BASE, MAIL, log, requestRun } from './lib.mjs';
import fs from 'node:fs';
import path from 'node:path';
import { OUT } from './lib.mjs';

const L = 'section-a.log';
const rows = {};

/** Click a link and return the HTTP status of the navigation it caused. */
async function clickStatus(page, locator) {
  const [resp] = await Promise.all([page.waitForNavigation({ waitUntil: 'load' }).catch(() => null), locator.click()]);
  return resp ? resp.status() : 'no-nav';
}

async function status(page, p) {
  const r = await page.goto(BASE + p);
  return r.status();
}

const sidePanelLinks = (page) => page.locator('#side-panel a.task-link, #tasks a.task-link, #side-panel .task a');

// ---------------------------------------------------------------- A-05
rows['A-05'] = async () => {
  for (const user of ['reqonly', 'requester', 'approver-1', 'auditor', 'manager']) {
    const { context, page } = await login(user);
    await page.goto(`${BASE}/batch-control/`);
    const links = await sidePanelLinks(page).evaluateAll((as) => as.map((a) => ({ t: a.innerText.trim(), h: a.getAttribute('href') })));
    const mainLinks = await page.locator('#main-panel a').evaluateAll((as) => as.map((a) => ({ t: a.innerText.trim(), h: a.getAttribute('href') })));
    await shot(page, ['#side-panel', '#main-panel .jenkins-app-bar, #main-panel h1'], `A-05-${user}`, { pad: 10 });
    const results = [];
    for (const l of [...links, ...mainLinks].filter((x) => x.h && !x.h.startsWith('#'))) {
      const url = l.h.startsWith('http') ? l.h : new URL(l.h, `${BASE}/batch-control/`).href;
      const r = await page.goto(url);
      results.push(`${l.t}=${r.status()}`);
    }
    log(L, `A-05 ${user}: sidebar ${links.map((l) => l.t).join(', ')} | main ${mainLinks.map((l) => l.t).join(', ') || '-'} | clicked ${results.join(' ')}`);
    await context.close();
  }
};

// ---------------------------------------------------------------- A-06
rows['A-06'] = async () => {
  const { context, page } = await login('nobc');
  await page.goto(`${BASE}/`);
  const html = await page.content();
  const navText = (await page.locator('#side-panel, header, #page-header').allInnerTexts()).join(' ');
  log(L, `A-06 nobc side panel/header mention "Batch Control": ${/Batch Control/.test(navText)}, any href to /batch-control/: ${html.includes('/batch-control/')}`);
  await shot(page, ['#side-panel', 'header, #page-header'], 'A-06-1-nobc-dashboard', { pad: 8 });
  // header "hamburger" menu, where core lists root actions on this version
  const menu = page.locator('button[aria-label*="menu" i], #root-action-SideMenu, .jenkins-header__actions button').last();
  const codes = [];
  for (const p of ['/batch-control/', '/batch-control/requests/', '/batch-control/history/runs.csv', '/batch-control/grants/', '/batch-control/dashboard/', '/batch-control/activations/']) {
    codes.push(`${p}=${await status(page, p)}`);
  }
  await page.goto(`${BASE}/batch-control/`);
  await shot(page, '#main-panel, body', 'A-06-2-nobc-404', { pad: 8 });
  const posts = [];
  for (const p of ['/batch-control/grants/create', '/batch-control/requests/x/approve', '/batch-control/activations/x/approve']) {
    posts.push(`POST ${p}=${(await api('nobc', p, { method: 'POST', body: new URLSearchParams({ reason: 'x' }), headers: { 'Content-Type': 'application/x-www-form-urlencoded' } })).status}`);
  }
  log(L, `A-06 nobc codes: ${codes.join(' ')} | ${posts.join(' ')} (valid crumb)`);
  await context.close();
};

// ---------------------------------------------------------------- A-13
rows['A-13'] = async () => {
  for (const user of ['nobc', 'approver-1']) {
    const { context, page } = await login(user);
    await page.goto(`${BASE}/job/batch-daily/`);
    const entries = await sidePanelLinks(page).allInnerTexts();
    await shot(page, '#side-panel', `A-13-${user}-sidebar`, { pad: 8 });
    const c1 = await status(page, '/job/batch-daily/batch-control/');
    const c2 = await status(page, '/job/batch-daily/batch-control/submit');
    const c3 = await status(page, '/job/batch-daily/batch-control-activation');
    log(L, `A-13 ${user}: sidebar [${entries.map((e) => e.trim()).join(', ')}] batch-control/=${c1} submit=${c2} activation=${c3}`);
    await context.close();
  }
};

// ---------------------------------------------------------------- A-14
rows['A-14'] = async () => {
  const { context, page } = await login('reqonly');
  const out = [];
  for (const p of ['/batch-control/history/', '/batch-control/dashboard/', '/batch-control/changes/', '/batch-control/incidents/', '/batch-control/grants/']) {
    const code = await status(page, p);
    const text = (await page.locator('#main-panel, body').first().innerText()).replace(/\s+/g, ' ').slice(0, 160);
    out.push(`${p}=${code} "${text}"`);
    if (p.includes('history')) await shot(page, '#main-panel, body', 'A-14-reqonly-history-403', { pad: 8 });
  }
  log(L, `A-14 reqonly: ${out.join(' || ')}`);
  await context.close();
};

// ---------------------------------------------------------------- A-10 icons
rows['A-10'] = async () => {
  const { context, page } = await login('admin');
  const pages = ['/batch-control/', '/job/batch-daily/'];
  for (const zoom of [1, 2]) {
    const { context: c2, page: p2 } = zoom === 1 ? { context, page } : await login('requester', { scale: 2 });
    for (const p of zoom === 1 ? pages : ['/job/batch-daily/', '/batch-control/']) {
      await p2.goto(BASE + p);
      const icons = await p2.locator('#side-panel svg, #side-panel img').evaluateAll((els) => els.map((e) => {
        const r = e.getBoundingClientRect();
        const empty = e.tagName === 'svg' ? e.innerHTML.trim().length < 20 : !(e.complete && e.naturalWidth > 0);
        return { w: Math.round(r.width), h: Math.round(r.height), empty, label: (e.closest('a')?.innerText || '').trim() };
      }));
      const broken = icons.filter((i) => i.empty || i.w === 0);
      log(L, `A-10 zoom ${zoom} ${p}: ${icons.length} icons, broken/empty: ${JSON.stringify(broken)}`);
      await shot(p2, '#side-panel', `A-10-${p.replace(/[^a-z]+/g, '-')}zoom${zoom}`.replace('--', '-'), { pad: 6 });
    }
    if (zoom === 2) await c2.close();
  }
  // the header root-action icon and an approved build's action are captured in A-16
  const header = page.locator('a[href$="/batch-control/"]').first();
  await page.goto(`${BASE}/`);
  log(L, `A-10 header/root entry present for admin on dashboard: ${await header.count()}`);
  if (await header.count()) await shot(page, header, 'A-10-root-entry', { pad: 12 });
  await page.goto(`${BASE}/manage/pluginManager/installed`);
  await page.fill('input[type="search"], #filter-box', 'Batch Control').catch(() => {});
  await page.waitForTimeout(800);
  const row = page.locator('tr:has-text("Batch Control")').first();
  await shot(page, row, 'A-10-plugin-manager-row', { pad: 8 });
  const deps = await api('admin', '/pluginManager/api/json?depth=2&tree=plugins[shortName,dependencies[shortName,optional]]');
  const bc = deps.json.plugins.find((x) => x.shortName === 'batch-control');
  log(L, `A-10 batch-control dependencies: ${bc.dependencies.map((d) => d.shortName + (d.optional ? '(opt)' : '')).join(', ')}`);
  await context.close();
};

// ---------------------------------------------------------------- A-11 breadcrumbs / no back links
rows['A-11'] = async () => {
  const { context, page } = await login('admin');
  const screens = ['/batch-control/', '/batch-control/requests/', '/batch-control/activations/', '/batch-control/grants/',
    '/batch-control/changes/', '/batch-control/dashboard/', '/batch-control/incidents/', '/batch-control/history/',
    '/job/batch-daily/batch-control/', '/job/batch-daily/batch-control-activation'];
  // plus every request / activation detail linked from the lists
  for (const list of ['/batch-control/activations/', '/batch-control/requests/', '/batch-control/grants/']) {
    await page.goto(BASE + list);
    const hrefs = await page.locator('#main-panel a[href*="/batch-control/"]').evaluateAll((as) => as.map((a) => a.getAttribute('href')));
    for (const h of hrefs.filter((x) => /\/\d{8}-/.test(x)).slice(0, 2)) screens.push(new URL(h, BASE).pathname);
  }
  const findings = [];
  for (const p of screens) {
    const code = await status(page, p);
    const back = await page.locator('#main-panel a, #main-panel button').evaluateAll((as) => as.map((a) => a.innerText.trim()).filter((t) => /^back\b|back to/i.test(t)));
    const crumbs = await page.locator('.jenkins-breadcrumbs__list-item, #breadcrumbs li').evaluateAll((li) => li.map((l) => l.innerText.trim()).filter(Boolean));
    findings.push(`${p} ${code} crumbs=[${crumbs.join(' > ')}] back=[${back.join(',')}]`);
  }
  for (const f of findings) log(L, `A-11 ${f}`);
  // walk the crumbs of a deep page: every crumb must open
  await page.goto(`${BASE}/batch-control/grants/`);
  await shot(page, '.jenkins-breadcrumbs, #breadcrumbBar', 'A-11-crumbs', { pad: 8 });
  const crumbLinks = await page.locator('.jenkins-breadcrumbs a, #breadcrumbs a').evaluateAll((as) => as.map((a) => a.getAttribute('href')).filter(Boolean));
  const cs = [];
  for (const h of crumbLinks) cs.push(`${h}=${(await page.goto(new URL(h, BASE).href)).status()}`);
  log(L, `A-11 crumb targets from Grants: ${cs.join(' ')}`);
  await context.close();
};

// ---------------------------------------------------------------- A-18 landing page
rows['A-18'] = async () => {
  const { context, page } = await login('admin');
  await page.goto(`${BASE}/batch-control/`);
  const appBar = await page.locator('#main-panel .jenkins-app-bar').count();
  const side = await sidePanelLinks(page).allInnerTexts();
  const mainButtons = await page.locator('#main-panel a.jenkins-button, #main-panel .jenkins-button').allInnerTexts();
  log(L, `A-18 admin landing: app-bar=${appBar} sidebar=[${side.map((s) => s.trim()).join(', ')}] main buttons=[${mainButtons.join(', ')}]`);
  const gaps = await sidePanelLinks(page).evaluateAll((as) => as.map((a) => Math.round(a.getBoundingClientRect().top)));
  log(L, `A-18 sidebar entry tops (px): ${gaps.join(',')}`);
  await shot(page, ['#side-panel', '#main-panel .jenkins-app-bar', '#main-panel p'], 'A-18', { pad: 10 });
  await context.close();
};


// ---------------------------------------------------------------- A-07 permission names
rows['A-07'] = async () => {
  const { context, page } = await login('admin');
  await page.goto(`${BASE}/manage/configuration-as-code/`);
  const view = page.locator('form[action="viewExport"] button').first();
  log(L, `A-07 "Export configuration" control present: ${await view.count()}`);
  await Promise.all([page.waitForNavigation({ waitUntil: 'load' }), view.click()]);
  await page.waitForTimeout(2000);
  const text = await page.locator('body').innerText();
  fs.writeFileSync(path.join(OUT, 'A-07-export.yaml'), text);
  const names = [...new Set(text.match(/(Batch ?Control)\/[A-Za-z]+/g) || [])];
  log(L, `A-07 export page: permission names ${names.join(', ')}; contains "Batch Control/": ${text.includes('Batch Control/')}`);
  const dup = ['approvers', 'grantDurationOptions', 'incidentResults'].filter((k) => text.includes(`    ${k}:`) && text.includes(`    ${k}Text:`));
  log(L, `A-07 batchControl fields exported twice (list + Text): ${dup.join(', ')}`);
  const line = page.getByText('BatchControl/Request', { exact: false }).first();
  const bcLine = page.getByText('batchControlProjectMatrix', { exact: false }).first();
  await shot(page, [bcLine, line], 'A-07-export', { pad: 30 });
  const cfg = page.getByText('batchControl:', { exact: false }).first();
  await shot(page, cfg, 'A-07-export-global', { pad: 30 });

  // matrix UI group title: open an entry's editor on the security page
  await page.goto(`${BASE}/manage/configureSecurity/`);
  const card = page.locator('.mas-card[data-sid="requester"]');
  await card.locator('.mas-card__header').click();
  await page.waitForTimeout(1000);
  const bcTitle = card.getByText('Batch Control', { exact: true }).first();
  const visible = await bcTitle.isVisible().catch(() => false);
  const tips = await card.locator('[tooltip], [title]').evaluateAll((es) => es.map((e) => e.getAttribute('tooltip') || e.getAttribute('title')).filter((t) => /Batch ?Control|Request|Approve|ViewHistory|Manage/.test(t || '')).slice(0, 8));
  log(L, `A-07 matrix card expanded; group title "Batch Control" visible: ${visible}; BC tooltips: ${JSON.stringify(tips)}`);
  const grp = bcTitle.locator('xpath=ancestor::*[contains(@class,"group") or self::fieldset or self::section or self::table][1]');
  await shot(page, (await grp.count()) ? grp : bcTitle, 'A-07-matrix-group', { pad: 16 });
  const readme = fs.readFileSync(path.resolve(OUT, '../../README.md'), 'utf8');
  log(L, `A-07 README names: ${[...new Set(readme.match(/`BatchControl\/[A-Za-z]+`/g))].join(', ')}`);
  await context.close();
};

// ---------------------------------------------------------------- A-08 selects (light/dark)
async function selectReport(page, label) {
  return page.locator('select').evaluateAll((ss) => ss.filter((s) => s.offsetParent !== null).map((s) => {
    const r = s.getBoundingClientRect();
    return `${s.name}: wrapped=${!!s.closest('.jenkins-select')} h=${Math.round(r.height)} w=${Math.round(r.width)}`;
  }));
}
rows['A-08'] = async () => {
  for (const theme of ['light', 'dark']) {
    const { context, page } = await login('requester', { colorScheme: theme });
    if (theme === 'dark') {
      // Jenkins' own theme switch: user -> Appearance (as a user would); fall back to the OS scheme.
      await page.goto(`${BASE}/me/appearance/`);
      const dark = page.locator('label:has-text("Dark")').first();
      if (await dark.count()) {
        await dark.click();
        await Promise.all([page.waitForLoadState('load'), page.locator('button:has-text("Save"), button[name="Submit"]').first().click()]);
      } else log(L, 'A-08 no Appearance page for the user; relying on the OS colour scheme');
    }
    await page.goto(`${BASE}/batch-control/grants/`);
    // D-71: no scope type selector; the Duration select is the remaining styled select used for this check
    const scope = page.locator('select[name="durationMinutes"]');
    const dur = page.locator('select[name="durationMinutes"]');
    log(L, `A-08 ${theme} grants form selects: ${(await selectReport(page)).join(' | ')}`);
    // keyboard operability
    await scope.focus();
    await page.keyboard.type('3');
    const kb = await scope.inputValue();
    await scope.selectOption('15');
    await page.locator('h2:has-text("New Grant Request")').click();
    await shot(page, [scope, dur, page.locator('input[name="scopeFullName"]')], `A-08-${theme}`, { pad: 20 });
    log(L, `A-08 ${theme} keyboard type-ahead '3' on focused Duration -> ${kb}`);
    if (theme === 'dark') {
      await page.goto(`${BASE}/me/appearance/`);
      const def = page.locator('label:has-text("Default"), label:has-text("Light")').first();
      if (await def.count()) {
        await def.click();
        await Promise.all([page.waitForLoadState('load'), page.locator('button:has-text("Save"), button[name="Submit"]').first().click()]);
      }
    }
    await context.close();
  }
  // core reference: a core select on the same instance for comparison
  const { context, page } = await login('admin');
  await page.goto(`${BASE}/manage/configureSecurity/`);
  log(L, `A-08 core selects for comparison: ${(await selectReport(page)).slice(0, 2).join(' | ')}`);
  await context.close();
};

// ---------------------------------------------------------------- A-09 + A-20 (+A-17 grant)
rows['A-09'] = async () => {
  const { context, page } = await login('requester');
  await page.goto(`${BASE}/batch-control/grants/`);
  await page.waitForSelector('input[name="scopeFullName"]');
  await page.fill('input[name="scopeFullName"]', 'team'); // D-71: one item (folder), no scope type
  const cb = (v) => page.locator(`input[name="actions"][value="${v}"]`);
  const lbl = (v) => cb(v).locator('xpath=following-sibling::label[1]'); // #107 renames the Create id to cb<n>
  // click the boxes and their labels, as a user would
  await lbl('CREATE').click();
  await lbl('CONFIGURE').click();
  await lbl('DELETE').click();
  await page.waitForTimeout(400);
  const all = [await cb('CREATE').isChecked(), await cb('CONFIGURE').isChecked(), await cb('DELETE').isChecked()];
  await shot(page, page.locator('input[name="actions"]').first().locator('xpath=ancestor::*[contains(@class,"jenkins-form-item")][1]'), 'A-09-1-all-checked', { pad: 12 });
  await lbl('CONFIGURE').click(); // uncheck by label
  await page.waitForTimeout(400);
  const after = [await cb('CREATE').isChecked(), await cb('CONFIGURE').isChecked(), await cb('DELETE').isChecked()];
  await shot(page, page.locator('input[name="actions"]').first().locator('xpath=ancestor::*[contains(@class,"jenkins-form-item")][1]'), 'A-09-2-create-delete', { pad: 12 });
  const gap = await page.locator('input[name="actions"]').evaluateAll((es) => es.map((e) => Math.round(e.getBoundingClientRect().top)));
  log(L, `A-09 label clicks -> checked C/Cf/D ${all}; after unchecking Configure by its label ${after}; checkbox tops ${gap}`);
  await page.fill('input[name="createNamePattern"]', 'app-2');
  await page.selectOption('select[name="durationMinutes"]', '15');
  await page.fill('textarea[name="reason"]', 'Create the app-2 job in team/ for the settlement batch (A-09/A-20).');
  await page.locator('#grant-approver-0 + label').click();
  await Promise.all([page.waitForLoadState('load'), page.locator('button:has-text("Request Grant")').click()]);
  log(L, `A-09 submitted -> ${page.url()}`);
  await shot(page, '#main-panel table', 'A-09-3-grant-requests-row', { pad: 12 });
  const idLink = page.locator('#main-panel table a[href*="grants/"]').first();
  const url = new URL(await idLink.getAttribute('href'), page.url()).href;
  await page.goto(url);
  await shot(page, '#main-panel table', 'A-09-4-grant-detail', { pad: 12 });
  const detail = (await page.locator('#main-panel').innerText()).replace(/\s+/g, ' ');
  log(L, `A-09 grant detail: ${detail.slice(0, 500)}`);
  fs.writeFileSync(path.join(OUT, 'grant-a09.url'), url);
  await context.close();
};

rows['A-20'] = async () => {
  const url = fs.readFileSync(path.join(OUT, 'grant-a09.url'), 'utf8').trim();
  // approver-1 sees the restriction before deciding
  const ap = await login('approver-1');
  await ap.page.goto(url);
  const table = ap.page.locator('#main-panel table').first();
  const restr = ap.page.locator('tr:has-text("restriction"), tr:has-text("Name")').first();
  await shot(ap.page, table, 'A-20-1-approver-sees-restriction', { pad: 12 });
  log(L, `A-20 approver detail rows: ${(await table.innerText()).replace(/\s+/g, ' ')}`);
  if (await ap.page.locator('form[name="approve"]').count()) {
    await ap.page.fill('form[name="approve"] textarea[name="comment"]', 'Approved for app-2 only.');
    await Promise.all([ap.page.waitForLoadState('load'), ap.page.locator('form[name="approve"] button:has-text("Approve")').click()]);
  }
  await ap.page.goto(url);
  await shot(ap.page, '#main-panel table', 'A-20-2-approved', { pad: 12 });
  log(L, `A-20 after decision: ${(await ap.page.locator('#main-panel table').first().innerText()).replace(/\s+/g, ' ').slice(0, 300)}`);
  await ap.context.close();
  // requester: Grants shows the active grant; then New Item in team/
  const { context, page } = await login('requester');
  await page.goto(`${BASE}/batch-control/grants/`);
  const active = page.locator('h2:has-text("Active Grants") + * table, table:has(th:has-text("Expires"))').first();
  await shot(page, (await active.count()) ? active : '#main-panel', 'A-20-3-active-grant', { pad: 12 });
  await page.goto(`${BASE}/job/team/`);
  const newItem = page.locator('#side-panel a:has-text("New Item")');
  log(L, `A-20 team/ sidebar has New Item: ${await newItem.count()}`);
  await newItem.click();
  await page.waitForLoadState('load');
  const name = page.locator('#name');
  await name.fill('app-3');
  await page.waitForTimeout(1500);
  const err3 = (await page.locator('#itemname-invalid, .input-validation-message, .error').allInnerTexts()).join(' ').trim();
  await page.locator('label:has-text("Freestyle project")').first().click();
  await page.waitForTimeout(400);
  await shot(page, [name, page.locator('#itemname-invalid, .input-validation-message').first()], 'A-20-4-app-3-validation', { pad: 16 });
  const okBtn = page.locator('#ok-button');
  const okDisabled = await okBtn.isDisabled();
  log(L, `A-20 typing app-3: validation "${err3}", OK disabled=${okDisabled}`);
  let postCode = 'not tried';
  if (!okDisabled) {
    const [resp] = await Promise.all([page.waitForNavigation({ waitUntil: 'load' }).catch(() => null), okBtn.click()]);
    postCode = resp ? resp.status() : 'no-nav';
    log(L, `A-20 app-3 submit -> ${postCode} ${page.url()} "${(await page.locator('#main-panel, body').first().innerText()).replace(/\s+/g, ' ').slice(0, 200)}"`);
    await shot(page, '#main-panel, body', 'A-20-4b-app-3-refused', { pad: 8 });
  }
  await page.goto(`${BASE}/job/team/newJob`);
  await page.locator('#name').fill('app-2');
  await page.waitForTimeout(1500);
  await page.locator('label:has-text("Freestyle project")').first().click();
  await Promise.all([page.waitForNavigation({ waitUntil: 'load' }), page.locator('#ok-button').click()]);
  log(L, `A-20 app-2 created -> ${page.url()}`);
  await page.waitForTimeout(1000);
  await shot(page, '#main-panel .jenkins-app-bar, #main-panel h1, form[name="config"]', 'A-20-5-app-2-configure', { pad: 8 });
  const save = page.locator('button[name="Submit"]:has-text("Save")');
  if (await save.count()) await Promise.all([page.waitForLoadState('load'), save.click()]);
  log(L, `A-20 after save ${page.url()}`);
  await context.close();
  const a2 = await api('admin', '/job/team/job/app-2/api/json?tree=name');
  const a3 = await api('admin', '/job/team/job/app-3/api/json?tree=name');
  log(L, `A-20 server: team/app-2 ${a2.status}, team/app-3 ${a3.status}`);
};

// ---------------------------------------------------------------- A-23
rows['A-23'] = async () => {
  const { context, page } = await login('requester');
  await page.goto(`${BASE}/batch-control/grants/`);
  await page.waitForSelector('input[name="scopeFullName"]');
  // D-71: no scope type selector; the window names one item and its kind is shown by the name check
  const noScopeType = await page.locator('select[name="scopeType"]').count();
  await page.fill('input[name="scopeFullName"]', 'team-mb');
  await page.locator('input[name="scopeFullName"]').blur();
  await page.waitForTimeout(1200);
  const kind = (await page.locator('[data-batch-control-item-kind]').count())
    ? await page.locator('[data-batch-control-item-kind]').first().getAttribute('data-batch-control-item-kind') : null;
  // help text of the item name field
  const help = page.locator('input[name="scopeFullName"]').locator('xpath=ancestor::*[contains(@class,"jenkins-form-item")][1]').locator('a.jenkins-help-button, .jenkins-help-button').first();
  await help.click().catch(() => {});
  await page.waitForTimeout(1200);
  const helpText = (await page.locator('.help-area .help, .help').allInnerTexts()).join(' ').replace(/\s+/g, ' ');
  await shot(page, page.locator('input[name="scopeFullName"]').locator('xpath=ancestor::*[contains(@class,"jenkins-form-item")][1]'), 'A-23', { pad: 12 });
  log(L, `A-23 no scope type selector=${noScopeType === 0}; team-mb kind=${kind}; help: ${helpText.slice(0, 400)}`);
  await page.locator('input[name="actions"][value="CONFIGURE"]').locator('xpath=following-sibling::label[1]').click();
  await page.fill('textarea[name="reason"]', 'Adjust the multibranch source of team-mb (A-23).');
  await page.locator('#grant-approver-1 + label').click();
  await Promise.all([page.waitForLoadState('load'), page.locator('button:has-text("Request Grant")').click()]);
  log(L, `A-23 submitted -> ${page.url()} ${(await page.locator('#main-panel').innerText()).replace(/\s+/g, ' ').slice(0, 260)}`);
  await shot(page, '#main-panel table, #main-panel', 'A-23-2-folder-request', { pad: 10 });
  await context.close();
};


// ---------------------------------------------------------------- A-16 (+A-17 run, A-15 part)
rows['A-16'] = async () => {
  const before = await job('batch-daily');
  const rq = await login('requester');
  const url = await requestRun(rq.page, '/job/batch-daily/', {
    reason: 'Re-run the 2026-09-30 partial load after the upstream fix (A-16).',
    approvers: ['approver-1', 'approver-2'],
    params: { DATE: '2026-09-30', MODE: 'partial' },
  });
  log(L, `A-16 request -> ${url}`);
  await shot(rq.page, '#main-panel table', 'A-16-1-request-both-approvers', { pad: 12 });
  fs.writeFileSync(path.join(OUT, 'run-a16.url'), url);
  // approver-2 decides
  const a2 = await login('approver-2');
  await a2.page.goto(url);
  await shot(a2.page, '#main-panel', 'A-16-2-approver-2-decision-screen', { pad: 8 });
  await a2.page.fill('form[name="approve"] textarea[name="comment"]', 'Go ahead (approver-2).');
  await Promise.all([a2.page.waitForLoadState('load'), a2.page.locator('form[name="approve"] button:has-text("Approve")').click()]);
  const notice = (await a2.page.locator('#main-panel').innerText()).replace(/\s+/g, ' ');
  log(L, `A-16 after approve (approver-2 sees): ${notice.slice(0, 300)}`);
  await shot(a2.page, '#main-panel table', 'A-16-3-approved-by-approver-2', { pad: 12 });
  const built = await waitFor(async () => { const j = await job('batch-daily', 'nextBuildNumber,lastBuild[number,result,building]'); return j.lastBuild && !j.lastBuild.building && j.nextBuildNumber > before.nextBuildNumber ? j : null; }, { timeout: 90000 });
  const n = built && built.lastBuild.number;
  const b = await api('admin', `/job/batch-daily/${n}/api/json?tree=result,actions[parameters[name,value],causes[shortDescription]]`);
  const params = (b.json.actions.find((x) => x.parameters) || {}).parameters;
  const causes = (b.json.actions.find((x) => x.causes) || {}).causes;
  log(L, `A-16 build #${n} ${b.json.result} params ${JSON.stringify(params)} causes ${JSON.stringify(causes)}; builds added: ${built.nextBuildNumber - before.nextBuildNumber}`);
  // approver-1 sees it closed, no decision form
  const a1 = await login('approver-1');
  await a1.page.goto(url);
  const forms = await a1.page.locator('form[name="approve"], form[name="reject"]').count();
  log(L, `A-16 approver-1 view: decision forms=${forms}; ${(await a1.page.locator('#main-panel table').first().innerText()).replace(/\s+/g, ' ').slice(0, 400)}`);
  await shot(a1.page, '#main-panel', 'A-16-4-approver-1-closed', { pad: 8 });
  // A-17 run detail at 1280 and 800
  await shot(a1.page, '#main-panel table', 'A-17-run-1280', { pad: 8 });
  await a1.page.setViewportSize({ width: 800, height: 900 });
  await a1.page.goto(url);
  await shot(a1.page, '#main-panel table', 'A-17-run-800', { pad: 8 });
  // A-10: the approved build's own action and cause, as requester sees the build page
  await rq.page.goto(`${BASE}/job/batch-daily/${n}/`);
  const act = rq.page.locator('#side-panel a:has-text("Approved"), #side-panel a:has-text("Batch Control"), #side-panel a:has-text("Request")');
  log(L, `A-16 build page sidebar: ${(await rq.page.locator('#side-panel a').allInnerTexts()).map((t) => t.trim()).join(', ')}`);
  await shot(rq.page, ['#side-panel', '#main-panel'], 'A-16-5-build-page', { pad: 8 });
  const m1 = await mails('to:approver-1@e2e.local'); const m2 = await mails('to:approver-2@e2e.local'); const mr = await mails('to:requester@e2e.local');
  log(L, `A-16 mails approver-1: ${m1.slice(0, 3).map((x) => x.Subject).join(' | ')}`);
  log(L, `A-16 mails approver-2: ${m2.slice(0, 3).map((x) => x.Subject).join(' | ')}`);
  log(L, `A-16 mails requester: ${mr.slice(0, 3).map((x) => x.Subject).join(' | ')}`);
  for (const c of [rq, a1, a2]) await c.context.close();
};

// ---------------------------------------------------------------- A-17 grant
rows['A-17'] = async () => {
  const url = fs.readFileSync(path.join(OUT, 'grant-a09.url'), 'utf8').trim();
  const { context, page } = await login('approver-1');
  await page.goto(url);
  await shot(page, '#main-panel table', 'A-17-grant-1280', { pad: 8 });
  const cls = await page.locator('#main-panel table').first().getAttribute('class');
  const rowsH = await page.locator('#main-panel table').first().locator('tr').evaluateAll((trs) => trs.map((t) => Math.round(t.getBoundingClientRect().height)));
  await page.setViewportSize({ width: 800, height: 900 });
  await page.goto(url);
  await shot(page, '#main-panel table', 'A-17-grant-800', { pad: 8 });
  const w = await page.locator('#main-panel table').first().evaluate((t) => [Math.round(t.getBoundingClientRect().width), t.scrollWidth, document.documentElement.scrollWidth]);
  log(L, `A-17 grant table class="${cls}" row heights ${rowsH}; at 800px width table/scroll/doc ${w}`);
  await context.close();
};


async function configureRecords(target) {
  const r = await api('admin', '/batch-control/history/changes.csv');
  return r.text.split('\n').filter((l) => l.split(',')[1] === 'CONFIGURE' && l.split(',')[2] === target);
}

// ---------------------------------------------------------------- A-12 no-op save
rows['A-12'] = async () => {
  const target = 'batch-pipeline';
  const r0 = await configureRecords(target);
  const { context, page } = await login('admin');
  // 1) UI: open configure, Save without editing
  await page.goto(`${BASE}/job/${target}/configure`);
  await page.waitForTimeout(1500);
  await Promise.all([page.waitForNavigation({ waitUntil: 'load' }), page.locator('button[name="Submit"]').click()]);
  await sleep(1500);
  const r1 = await configureRecords(target);
  // 2) REST: same config.xml with only plugin="name@version" attributes changed
  const xml = (await api('admin', `/job/${target}/config.xml`, { raw: true })).text;
  const bumped = xml.replace(/plugin="([^"@]+)@[^"]*"/g, 'plugin="$1@9999.v-e2e-bumped"');
  const changedAttrs = (xml.match(/plugin="[^"]+"/g) || []).length;
  const post = await api('admin', `/job/${target}/config.xml`, { method: 'POST', body: bumped, headers: { 'Content-Type': 'application/xml' } });
  await sleep(1500);
  const r2 = await configureRecords(target);
  // 3) a real edit in the UI: the description
  await page.goto(`${BASE}/job/${target}/configure`);
  await page.waitForTimeout(1500);
  await page.fill('textarea[name="description"]', 'Pipeline batch. Approval required. (edited in A-12)');
  await Promise.all([page.waitForNavigation({ waitUntil: 'load' }), page.locator('button[name="Submit"]').click()]);
  await sleep(1500);
  const r3 = await configureRecords(target);
  log(L, `A-12 CONFIGURE records for ${target}: start ${r0.length}, after no-op UI save ${r1.length}, after plugin-attr-only POST (HTTP ${post.status}, ${changedAttrs} attrs) ${r2.length}, after real edit ${r3.length}`);
  // Change Records screen: the new row and its diff
  await page.goto(`${BASE}/batch-control/changes/`);
  const row = page.locator(`#main-panel tr:has-text("${target}")`).first();
  await shot(page, [page.locator('#main-panel table tr').first(), row], 'A-12', { pad: 10 });
  const diffLink = row.locator('a').filter({ hasText: /diff|view|show|detail/i }).first();
  if (await diffLink.count()) {
    await diffLink.click(); await page.waitForTimeout(1000);
    await shot(page, '#main-panel pre, #main-panel .diff, #main-panel', 'A-12-diff', { pad: 10 });
  }
  const rowText = (await row.innerText()).replace(/\s+/g, ' ');
  log(L, `A-12 Change Records row: ${rowText.slice(0, 300)}`);
  // the team/app-2 CONFIGURE written by A-20's first Save of a new job, as the screen shows it
  const r4 = page.locator('#main-panel tr:has-text("team/app-2")').first();
  if (await r4.count()) {
    log(L, `A-12 team/app-2 rows: ${(await page.locator('#main-panel tr:has-text("team/app-2")').allInnerTexts()).map((t) => t.replace(/\s+/g, ' ')).join(' || ')}`);
    await shot(page, page.locator('#main-panel tr:has-text("team/app-2")'), 'A-12-app-2-first-save', { pad: 10 });
  }
  await context.close();
};

const wanted = process.argv.slice(2);
try {
  for (const id of wanted) {
    if (!rows[id]) { console.log(`no row ${id}`); continue; }
    console.log(`== ${id}`);
    await rows[id]();
  }
} finally {
  await close();
}
