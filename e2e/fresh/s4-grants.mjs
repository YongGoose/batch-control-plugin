// Scenario 4: change permission windows. node s4-grants.mjs <step> ...
//  invalid                       - invalid submissions on the grant form
//  request <type> <scope> <actions,> <minutes> [pattern]  - requester files a window request
//  approve <id> [user]           - approver approves it
//  configure <job> <tag>         - requester edits the job description through the config form
//  escalate <job>                - requester adds itself to the job's authorization matrix (self-grant)
//  expired <job>                 - requester after expiry: config page, Grants screen
//  create <folder> <name> <tag>  - requester creates a job through New Item inside the folder
//  delete <job> <user> <tag>     - user deletes a job through the UI
import fs from 'node:fs';
import { login, BASE, shot, text, api, log, close, mails, sleep, OUT } from './lib.mjs';

const flat = (s) => s.replace(/\s+/g, ' ');
const S = 'S4';
const [step, ...a] = process.argv.slice(2);
const form = (page) => page.locator('form[action$="grants/create"]');

async function fill(page, { type = 'JOB', scope, actions = [], minutes, custom, pattern, reason, approvers = ['approver-1'] }) {
  const f = form(page);
  await f.locator('select[name="scopeType"]').selectOption(type);
  await f.locator('input[name="scopeFullName"]').fill(scope ?? '');
  for (const act of ['CREATE', 'CONFIGURE', 'DELETE']) await f.locator(`input[name="actions"][value="${act}"]`).setChecked(actions.includes(act), { force: true });
  if (pattern !== undefined) await f.locator('input[name="createNamePattern"]').fill(pattern);
  if (minutes) await f.locator('select[name="durationMinutes"]').selectOption(String(minutes));
  if (custom !== undefined) await f.locator('input[name="customDurationMinutes"]').fill(String(custom));
  if (reason !== undefined) await f.locator('textarea[name="reason"]').fill(reason);
  for (const ap of approvers) await f.locator(`input[name="approvers"][value="${ap}"]`).check({ force: true });
}
async function submit(page) {
  await Promise.all([page.waitForLoadState('load'), form(page).locator('button[name="Submit"]').click()]);
  await sleep(300);
}

if (step === 'invalid') {
  const { page } = await login('requester');
  const cases = [
    ['no-action', { scope: 'fresh-daily', actions: [], reason: 'x' }],
    ['no-reason', { scope: 'fresh-daily', actions: ['CONFIGURE'], reason: '' }],
    ['too-long', { scope: 'fresh-daily', actions: ['CONFIGURE'], custom: 999, reason: 'too long' }],
    ['no-such-job', { scope: 'fresh-nope', actions: ['CONFIGURE'], reason: 'missing job' }],
    ['bad-regex', { type: 'FOLDER', scope: 'fresh-folder', actions: ['CREATE'], pattern: '/fresh-(/', reason: 'bad regex' }],
    ['pattern-without-create', { scope: 'fresh-daily', actions: ['CONFIGURE'], pattern: 'fresh-x', reason: 'pattern w/o create' }],
    ['no-approver', { scope: 'fresh-daily', actions: ['CONFIGURE'], reason: 'nobody', approvers: [] }],
  ];
  for (const [tag, c] of cases) {
    await page.goto(`${BASE}/batch-control/grants/`);
    await fill(page, c);
    await submit(page);
    const t = flat(await text(page));
    const f = form(page);
    const kept = (await f.count()) ? await f.evaluate((x) => [...x.elements].filter((e) => ['scopeFullName', 'reason', 'createNamePattern', 'customDurationMinutes'].includes(e.name) || (e.type === 'checkbox' && e.checked)).map((e) => `${e.name}=${e.value}`).join(' ')) : '(no form)';
    const errs = await page.$$eval('.error, .jenkins-alert-danger, .validation-error-area:not(:empty), .jenkins-form-item .error', (es) => es.map((e) => e.innerText.trim()).filter(Boolean));
    log(S + '-inv', tag, '| url', page.url(), '| errors:', errs, '| kept:', kept, '| head:', t.slice(0, 200));
    const errEl = page.locator('.error, .jenkins-alert-danger').first();
    await shot(page, (await errEl.count()) ? [errEl, page.locator('.error').last()] : '#main-panel', `${S}-inv-${tag}`);
  }
  await page.context().close();
}

if (step === 'request') {
  const [type, scope, acts, minutes, pattern] = a;
  const { page } = await login('requester');
  if (type === 'JOB') {
    await page.goto(`${BASE}/job/${scope}/`);
    await page.locator('#tasks a, #side-panel a', { hasText: 'Request Change Permission' }).first().click();
    await page.waitForLoadState('load');
  } else await page.goto(`${BASE}/batch-control/grants/`);
  await fill(page, { type, scope, actions: acts.split(','), minutes, pattern, reason: `fresh e2e-04: ${acts} on ${scope}${pattern ? ' named ' + pattern : ''}` });
  await shot(page, form(page), `${S}-${scope.replace(/\W/g, '_')}-${acts.replace(/,/g, '+')}-01-form`);
  await submit(page);
  const id = (page.url().match(/grants\/([0-9]{8}-[0-9]{6}-\w+)/) || [])[1] || (flat(await text(page)).match(/Status (\d{8}-\d{6}-\w+) /) || [])[1];
  log(S + ' request ->', page.url(), '| id', id, '|', flat(await text(page)).slice(0, 600));
  await shot(page, '#main-panel table', `${S}-${scope.replace(/\W/g, '_')}-${acts.replace(/,/g, '+')}-02-submitted`);
  fs.writeFileSync(`${OUT}/grant-last.txt`, id || '');
  await page.context().close();
}

if (step === 'approve') {
  const [id, user = 'approver-1'] = a;
  const { page } = await login(user);
  await page.goto(`${BASE}/batch-control/grants/`);
  log('approver grants page', flat(await text(page)).slice(0, 400));
  await page.goto(`${BASE}/batch-control/grants/${id}/`);
  log(S + ' approver detail', flat(await text(page)).slice(0, 900));
  await shot(page, '#main-panel', `${S}-${id}-03-approver-detail`);
  const af = page.locator('form[action$="approve"]').first();
  await Promise.all([page.waitForLoadState('load'), af.locator('button').first().click()]);
  log(S + ' after approve', flat(await text(page)).slice(0, 600));
  await shot(page, page.locator('#main-panel table').first(), `${S}-${id}-04-approved`);
  await page.context().close();
}

if (step === 'configure') {
  const [job, tag] = a;
  const { page } = await login('requester');
  await page.goto(`${BASE}/job/${job}/`);
  log('sidebar', flat(await page.locator('#tasks').innerText().catch(() => '')));
  const r = await page.goto(`${BASE}/job/${job}/configure`);
  log(S + ' configure page', r.status(), flat(await text(page)).slice(0, 200));
  if (r.status() === 200) {
    await page.locator('textarea[name="description"]').fill(`Edited by requester under a window (${tag})`);
    await Promise.all([page.waitForNavigation(), page.click('button[name="Submit"]')]);
    log(S + ' after save', page.url(), flat(await text(page)).slice(0, 200));
  } else await shot(page, '#main-panel', `${S}-${job}-${tag}-configure-refused`);
  await page.context().close();
  const { page: p2 } = await login('admin');
  await p2.goto(`${BASE}/batch-control/changes/`);
  const row = p2.locator('tbody tr', { hasText: job }).first();
  log(S + ' change record', flat(await row.innerText()));
  await shot(p2, row, `${S}-${job}-${tag}-change-record`);
  const det = row.locator('a', { hasText: /Diff|diff/ }).first();
  if (await det.count()) { await det.click(); await p2.waitForLoadState('load'); log('diff page', p2.url(), flat(await text(p2)).slice(0, 800)); await shot(p2, '#main-panel', `${S}-${job}-${tag}-diff`); }
  await p2.context().close();
}

if (step === 'escalate') {
  const [job] = a;
  const { page } = await login('requester');
  const r = await page.goto(`${BASE}/job/${job}/configure`);
  log('configure status', r.status());
  const xml = await page.evaluate(async (j) => (await fetch(`/job/${j}/config.xml`)).text(), job);
  log('config.xml readable by window holder', xml.length);
  const newXml = xml.replace('<properties>', `<properties><hudson.security.AuthorizationMatrixProperty><inheritanceStrategy class="org.jenkinsci.plugins.matrixauth.inheritance.InheritParentStrategy"/><permission>USER:hudson.model.Item.Configure:requester</permission><permission>USER:hudson.model.Item.Delete:requester</permission></hudson.security.AuthorizationMatrixProperty>`);
  const res = await page.evaluate(async ([j, body]) => {
    const crumb = document.head.dataset.crumbValue;
    const r = await fetch(`/job/${j}/config.xml`, { method: 'POST', headers: { 'Jenkins-Crumb': crumb, 'Content-Type': 'application/xml' }, body });
    return `${r.status} ${(await r.text()).replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ').slice(0, 600)}`;
  }, [job, newXml]);
  log(S + ' escalate via config.xml ->', res);
  // Through the form: the Authorization sidebar entry
  await page.goto(`${BASE}/job/${job}/`);
  log('sidebar during window', flat(await page.locator('#tasks').innerText().catch(() => '')));
  const after = await api('admin', `/job/${job}/config.xml`);
  log('matrix property kept?', /AuthorizationMatrixProperty/.test(after.body), (after.body.match(/USER:[^<]*requester/g) || []));
  await page.context().close();
}

if (step === 'expired') {
  const [job] = a;
  const { page } = await login('requester');
  const r = await page.goto(`${BASE}/job/${job}/configure`);
  log(S + ' configure after expiry', r.status(), flat(await text(page)).slice(0, 300));
  await shot(page, '#main-panel', `${S}-${job}-expired-configure-403`);
  await page.goto(`${BASE}/job/${job}/`);
  log('sidebar after expiry', flat(await page.locator('#tasks').innerText().catch(() => '')));
  await page.goto(`${BASE}/batch-control/grants/`);
  const ended = page.locator('h2:has-text("Ended Grants") + *, h2:has-text("Ended Grants") ~ table').first();
  const t = flat(await text(page));
  log(S + ' grants page: active/ended', t.slice(t.indexOf('Active Grants'), t.indexOf('Active Grants') + 700));
  await shot(page, [page.locator('h2', { hasText: 'Active Grants' }), page.locator('tr', { hasText: job }).nth(0)], `${S}-${job}-expired-grants`);
  await page.context().close();
}

if (step === 'create') {
  const [folder, name, tag] = a;
  const { page } = await login('requester');
  const r0 = await page.goto(`${BASE}/job/${folder}/`);
  log('folder sidebar', r0.status(), flat(await page.locator('#tasks').innerText().catch(() => '')));
  const r = await page.goto(`${BASE}/job/${folder}/newJob`);
  log('newJob status', r.status());
  if (r.status() === 200) {
    await page.fill('#name', name);
    await page.waitForTimeout(1200);
    const hint = flat(await page.locator('#itemname-invalid, #itemname-required, .input-validation-message, .validation-error-area').allInnerTexts().then((x) => x.join(' ')));
    log('name validation while typing', hint);
    await page.locator('input[name=mode][value="hudson.model.FreeStyleProject"]').locator('xpath=..').click();
    await page.waitForTimeout(300);
    await shot(page, '#main-panel', `${S}-${folder}-${tag}-newjob`);
    const [resp] = await Promise.all([page.waitForNavigation().catch(() => null), page.click('#ok-button')]);
    await page.waitForLoadState('load');
    log(S + ' create', name, '->', page.url(), resp && resp.status(), flat(await text(page)).slice(0, 500));
    await shot(page, '#main-panel', `${S}-${folder}-${tag}-created`);
    if (page.url().endsWith('/configure')) await Promise.all([page.waitForNavigation(), page.click('button[name="Submit"]')]);
  }
  await page.context().close();
  log('exists?', (await api('admin', `/job/${folder}/job/${encodeURIComponent(name)}/api/json`)).status);
}

if (step === 'delete') {
  const [job, user, tag] = a;
  const { page } = await login(user);
  await page.goto(`${BASE}/job/${job}/`);
  const del = page.locator('#tasks a, #side-panel a', { hasText: /Delete/ }).first();
  log(user, 'delete link?', await del.count());
  if (await del.count()) {
    await shot(page, del, `${S}-${job}-${tag}-delete-link`);
    page.on('dialog', (d) => { log('native dialog', d.message()); d.accept(); });
    await del.click();
    await page.waitForTimeout(700);
    const dlg = page.locator('dialog[open]');
    if (await dlg.count()) { log('jenkins dialog', flat(await dlg.innerText())); await dlg.locator('button', { hasText: /yes|delete|ok/i }).first().click(); }
    await page.waitForLoadState('load');
    await sleep(1000);
    log(S + ' after delete', page.url(), flat(await text(page)).slice(0, 600));
    await shot(page, '#main-panel', `${S}-${job}-${tag}-after-delete`);
  }
  await page.context().close();
  log('exists after?', (await api('admin', `/job/${job}/api/json`)).status);
}
await close();
