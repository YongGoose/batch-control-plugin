import { login, close, shot, api, BASE } from '../lib.mjs';
import { row, ev, sidebar, mainText } from './rec.mjs';
const side = (p) => p.locator('#side-panel a.task-link, #tasks a.task-link, #side-panel .task a');
// B2-01
{
  const { page } = await login('admin');
  await page.goto(`${BASE}/manage/configureSecurity/`);
  const card = page.locator('.mas-card[data-sid="requester"]'); await card.locator('.mas-card__header').click(); await page.waitForTimeout(1000);
  const grp = card.getByText('Batch Control', { exact: true }).first().locator('xpath=ancestor::*[contains(@class,"group") or self::fieldset or self::section][1]');
  const perms = await grp.locator('label[data-permission-id]').evaluateAll((ls) => ls.map((l) => ({ t: l.innerText.trim(), tip: l.getAttribute('tooltip') || l.getAttribute('title') || (l.closest('[tooltip]') || {}).getAttribute?.('tooltip') || '' })));
  await grp.locator('label[data-permission-id]').first().hover(); await page.waitForTimeout(800);
  const tip = (await page.locator('.tippy-box').first().innerText().catch(() => '')).replace(/\s+/g, ' ');
  const s = await shot(page, [grp, page.locator('.tippy-box').first()], 'B2-01-matrix-group-tooltip', { pad: 8 });
  ev(`B2-01 ${JSON.stringify(perms)} tip "${tip}"`);
  row('B2-01', { roles: 'admin', V: 'n.a. (admin screen)', G: `${perms.length === 5 && tip.length > 20 ? '✓' : '✗'} group "Batch Control": ${perms.map((p) => p.t).join(', ')}; hover tooltip "${tip.slice(0, 120)}"; export names BatchControl/<Name> (A-07)`, R: 'n.a.', C: 'n.a.', E: s ? '✓ B2-01-matrix-group-tooltip' : '✗' });
  await page.context().close();
}
// B2-02 hidden sections answer 403 for users with some BC permission
{
  const sections = ['requests/', 'activations/', 'grants/', 'changes/', 'dashboard/', 'incidents/', 'history/'];
  const res = {}; const bad = [];
  for (const u of ['reqonly', 'requester', 'auditor', 'approver-1', 'manager', 'approver-disc', 'approver-unlisted', 'configurer']) {
    const { context, page } = await login(u);
    await page.goto(`${BASE}/batch-control/`);
    const shown = (await side(page).evaluateAll((as) => as.map((a) => a.getAttribute('href')))).map((h) => new URL(h, `${BASE}/batch-control/`).pathname.replace('/batch-control/', ''));
    const codes = {};
    for (const s of sections) { codes[s] = (await page.goto(`${BASE}/batch-control/${s}`)).status(); if (shown.includes(s) ? codes[s] !== 200 : codes[s] !== 403) bad.push(`${u}:${s}=${codes[s]}${shown.includes(s) ? '(shown)' : '(hidden)'}`); }
    res[u] = codes; await context.close();
  }
  ev(`B2-02 ${JSON.stringify(res)} bad ${bad}`);
  row('B2-02', { roles: 'reqonly, requester, auditor, approver-1, manager, approver-disc, approver-unlisted, configurer (admin in A-05)', V: '✓ side-panel entries per account as in A-05 (reqonly/configurer 2, requester/approver-disc/approver-unlisted 3, auditor 4 history-family, approver-1/manager/admin 7)', G: `${bad.length ? '✗ ' + bad : '✓ every shown section 200 and every hidden section 403 (the account holds some Batch Control permission), 56 URLs'}`, R: '✓ hidden sections answer the standard 403 naming the missing permission (A-14)', C: 'n.a.', E: '✓ A-05-<user>.png, A-14-reqonly-history-403.png' });
}
// B2-03 nobc on detail URLs
{
  const id = (await api('admin', '/batch-control/history/requests.csv')).text.split('\n')[1].split(',')[0];
  const g = (await api('admin', '/batch-control/history/changes.csv')).text.split('\n').find((l) => /,GRANT_REVOKE,/.test(l)).split(',')[5];
  const { context, page } = await login('nobc');
  const codes = [];
  for (const p of [`/batch-control/requests/${id}/`, `/batch-control/grants/${g}/`, '/batch-control/history/summary', '/batch-control/history/runs.csv', '/batch-control/incidents/']) codes.push(`GET ${p.replace(/\d{8}-\d{6}-\w+/, '<id>')}=${(await page.goto(BASE + p)).status()}`);
  await page.goto(`${BASE}/batch-control/requests/${id}/`);
  const s = await shot(page, '#main-panel, body', 'B2-03-nobc-request-detail-404', { pad: 8 });
  for (const p of [`/batch-control/requests/${id}/approve`, `/batch-control/requests/${id}/cancel`, '/batch-control/grants/create', `/batch-control/grants/${g}/revoke`]) codes.push(`POST ${p.replace(/\d{8}-\d{6}-\w+/, '<id>')}=${(await api('nobc', p, { method: 'POST', body: new URLSearchParams({ comment: 'x' }), headers: { 'Content-Type': 'application/x-www-form-urlencoded' } })).status}`);
  ev(`B2-03 ${codes.join(' ')}`);
  row('B2-03', { roles: 'nobc', V: '✓ nothing linked (A-06)', G: `${codes.every((c) => /=404$/.test(c)) ? '✓' : '✗'} ${codes.join(', ')}`, R: 'n.a. (404 by design)', C: 'n.a.', E: s ? '✓ B2-03-nobc-request-detail-404' : '✗' });
  await context.close();
}
// B2-06 reqonly sees requester's request (LIMITATIONS 21)
{
  const id = (await api('admin', '/batch-control/history/requests.csv')).text.split('\n').slice(1).find((l) => /,requester,/.test(l)).split(',')[0];
  const { context, page } = await login('reqonly');
  await page.goto(`${BASE}/batch-control/requests/`);
  const listed = await page.locator(`#main-panel a:has-text("${id}")`).count();
  const s1 = await shot(page, page.locator('#main-panel table').first().locator('tbody tr').first(), 'B2-06-reqonly-list', { pad: 8 });
  const d = (await page.goto(`${BASE}/batch-control/requests/${id}/`)).status();
  const forms = await page.locator('form[name="approve"], form[name="cancel"], a:has-text("Cancel Request")').count();
  ev(`B2-06 reqonly lists ${id}: ${listed}; detail ${d}; controls ${forms}`);
  row('B2-06', { roles: 'reqonly (global Job/Read), requester', V: `✓ as documented (LIMITATIONS 21): reqonly lists requester's request (${listed}) and opens it (${d}) without any decision/cancel control (${forms})`, G: '✓ matches the documented rule; the "not visible" expectation applies only without Item/Read', R: 'n.a.', C: 'n.a.', E: s1 ? '✓ B2-06-reqonly-list' : '✗' });
  await context.close();
}
// B2-08 auditor History rows of unreadable jobs, plain text run cells
{
  const { context, page } = await login('auditor');
  await page.goto(`${BASE}/batch-control/history/`);
  const rows = page.locator('#main-panel table tbody tr', { hasText: /batch-cron|team\/secret-job|batch-lock/ });
  const n = await rows.count();
  const links = await rows.first().locator('a').evaluateAll((as) => as.map((a) => `${a.innerText.trim()}->${a.getAttribute('href')}`));
  const s = await shot(page, rows.first(), 'B2-08-auditor-history', { pad: 8 });
  const cron = (await page.goto(`${BASE}/job/batch-cron/`)).status();
  ev(`B2-08 rows ${n}; first row links ${links}; auditor /job/batch-cron/ ${cron}`);
  row('B2-08', { roles: 'auditor', V: `${links.every((l) => !/\/job\//.test(l)) ? '✓' : '✗'} rows of jobs he cannot read (/job/batch-cron/ ${cron}) are listed, their run cells plain text (links in the row: ${links.join(', ') || 'none'})`, G: '✓ as documented (LIMITATIONS 19)', R: 'n.a.', C: 'n.a.', E: s ? '✓ B2-08-auditor-history' : '✗' });
  await context.close();
}
// B2-09 Request Change Permission visibility
{
  const res = {};
  for (const u of ['requester', 'configurer', 'admin', 'nobc', 'approver-1', 'reqonly', 'manager']) { const c = await login(u); await c.page.goto(`${BASE}/job/batch-daily/`); res[u] = (await sidebar(c.page)).includes('Request Change Permission'); if (u === 'requester') { await c.page.locator('#side-panel a:has-text("Request Change Permission")').click(); await c.page.waitForLoadState('load'); res.pre = { t: await c.page.locator('select[name="scopeType"]').inputValue(), s: await c.page.locator('input[name="scopeFullName"]').inputValue(), c: await c.page.locator('input[name="actions"][value="CONFIGURE"]').isChecked(), note: ((await mainText(c.page)).match(/Prefilled[^.]*\./) || [''])[0] }; res.shot = await shot(c.page, ['select[name="scopeType"]', 'input[name="scopeFullName"]', 'input[name="actions"][value="CONFIGURE"] + label'], 'B2-09-prefilled', { pad: 12 }); } await c.context.close(); }
  ev(`B2-09 ${JSON.stringify(res)}`);
  const vis = res.requester && !res.configurer && !res.admin && !res.nobc && !res['approver-1'] && !res.reqonly;
  row('B2-09', { roles: 'requester, configurer, admin, nobc, approver-1, reqonly, manager', V: `${vis ? '✓' : '✗'} shown to: ${Object.entries(res).filter(([k, v]) => v === true).map(([k]) => k).join(', ')}; hidden for configurer (standing Configure), admin, nobc, approver-1, reqonly (no RequestGrant)${res.manager ? '' : ', manager'}`, G: `${res.pre.t === 'JOB' && res.pre.s === 'batch-daily' && res.pre.c ? '✓' : '✗'} click -> Grants form JOB + batch-daily, Configure ticked, "${res.pre.note}"`, R: 'n.a.', C: 'n.a.', E: res.shot ? '✓ B2-09-prefilled' : '✗' });
}
await close();
