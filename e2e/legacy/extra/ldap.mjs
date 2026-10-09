// e2e-05 check 2: the LDAP realm (compose.ldap.yml, casc/profile-ldap.yaml). node ldap.mjs <step>
//   apply <file>      admin applies /var/jenkins_casc/<file> on Manage Jenkins -> Configuration as Code
//   validate <phase>  admin: approver validation on Batch Control -> Configuration
//                     phase "up": known LDAP id accepted, unknown refused; phase "down": LDAP stopped -> warning
//   who               each LDAP account logs in; authorities and the Batch Control entry points per group
//   monitor           admin: the standing-permission monitor on Manage Jenkins (group entries)
//   run               lrequester requests a run, lapprover-1 approves; build + mails to @ldap.e2e.local
//   grant             lrequester requests a CONFIGURE window, lapprover-2 approves, lrequester edits the job
//   down-request      (LDAP stopped) lrequester, already logged in, submits a run request
import { login, BASE, shot, text, api, groovy, log, close, sleep, mails, mailBody } from './lib.mjs';

const flat = (s) => s.replace(/\s+/g, ' ').trim();
const [step, arg] = process.argv.slice(2);
const L = 'L';

async function realm() {
  return groovy('def j = jenkins.model.Jenkins.get(); println j.securityRealm.class.simpleName + " / " + j.authorizationStrategy.class.simpleName + " / approvers=" + io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration.get().approvers');
}

if (step === 'apply') {
  const { page } = await login('admin');
  await page.goto(`${BASE}/manage/configuration-as-code/`);
  await page.locator('button:has-text("Apply configuration")').first().click(); await sleep(800);
  await page.locator('dialog[open] input[name="_.newSource"]').fill(`/var/jenkins_casc/${arg}`);
  await shot(page, 'dialog[open]', `${L}-00-apply-${arg.replace('.yaml', '')}`);
  await Promise.all([page.waitForNavigation({ waitUntil: 'load' }).catch(() => null), page.locator('dialog[open] button[name="replace"], dialog[open] button:has-text("Apply configuration")').last().click()]);
  await sleep(3000);
  log('apply', arg, '->', page.url().replace(BASE, ''), '|', flat(await text(page)).slice(0, 160));
  log('realm now:', await realm());
  await page.context().close();
}

async function checkField(page, value) {
  const f = page.locator('textarea[name="_.approversText"]');
  await f.fill(value); await f.blur(); await sleep(2500);
  const item = f.locator('xpath=ancestor::div[contains(@class,"jenkins-form-item")][1]');
  const msg = flat(await item.locator('.validation-error-area, .error, .warning, .ok').allInnerTexts().then((a) => a.join(' | ')));
  return { item, msg };
}
async function save(page) {
  const st = [];
  page.on('response', (r) => { if (r.request().method() === 'POST') st.push(r.status()); });
  await Promise.all([page.waitForLoadState('load'), page.locator('button[name="Submit"]').first().click()]);
  await sleep(800);
  return st;
}

if (step === 'validate') {
  const { page } = await login('admin');
  if (arg === 'up') {
    for (const [tag, value] of [['known', 'lapprover-1\nlapprover-2\nadmin\nlnobody'], ['unknown', 'lapprover-1\nlapprover-2\nadmin\nnosuch-ldap']]) {
      await page.goto(`${BASE}/batch-control-configuration/`);
      const { item, msg } = await checkField(page, value);
      await shot(page, item, `${L}-01-validate-${tag}-inline`);
      const st = await save(page);
      const t = flat(await text(page));
      const banner = page.locator('#main-panel .jenkins-alert').first();
      await shot(page, (await banner.count()) ? [banner, 'textarea[name="_.approversText"]'] : '#main-panel h1', `${L}-02-validate-${tag}-saved`);
      log(`validate ${tag}: inline "${msg}" | save HTTP ${st} -> ${page.url().replace(BASE, '')} | ${t.slice(0, 200)}`);
      log('  stored:', await realm());
    }
  } else {
    // Logged in while LDAP is up; the directory is stopped now. The admin session stays; the id below was never seen by Jenkins.
    const { execSync } = await import('node:child_process');
    execSync('docker stop batch-control-e2e-ldap', { stdio: 'ignore' });
    await sleep(2000);
    await page.goto(`${BASE}/batch-control-configuration/`);
    const { item, msg } = await checkField(page, 'lapprover-1\nlapprover-2\nadmin\nlnobody\nlauditor-new');
    await shot(page, item, `${L}-03-validate-ldap-down-inline`);
    const st = await save(page);
    const t = flat(await text(page));
    const banner = page.locator('#main-panel .jenkins-alert').first();
    await shot(page, (await banner.count()) ? [banner, 'textarea[name="_.approversText"]'] : '#main-panel h1', `${L}-04-validate-ldap-down-saved`);
    log(`validate down: inline "${msg}" | save HTTP ${st} -> ${page.url().replace(BASE, '')} | ${t.slice(0, 300)}`);
    log('  stored:', await realm());
  }
  await page.context().close();
}

if (step === 'who') {
  for (const u of ['lrequester', 'lapprover-1', 'lconfigurer', 'lauditor', 'lnobody', 'requester']) {
    let s;
    try { s = await login(u); } catch (e) { log(`who ${u}: LOGIN FAILED (${e.message})`); continue; }
    const { page, context } = s;
    await page.goto(`${BASE}/whoAmI/`);
    const who = flat(await text(page));
    await shot(page, '#main-panel', `${L}-10-whoami-${u}`);
    const codes = {};
    for (const p of ['/batch-control/', '/batch-control/requests/', '/batch-control/grants/', '/batch-control/history/', '/job/fresh-daily/batch-control/', '/job/fresh-daily/configure', '/manage/']) {
      codes[p] = (await page.goto(BASE + p)).status();
    }
    await page.goto(`${BASE}/batch-control/`);
    const side = await page.$$eval('#main-panel a, #side-panel a, #tasks a', (as) => [...new Set(as.map((a) => a.innerText.trim()).filter(Boolean))].slice(0, 20));
    await shot(page, '#main-panel', `${L}-11-landing-${u}`);
    log(`who ${u}: ${(who.match(/Authorities:?(.*?)(Details|Is authenticated|$)/) || [, who.slice(0, 300)])[1].slice(0, 240)}`);
    log(`   codes ${JSON.stringify(codes)} | landing links ${JSON.stringify(side)}`);
    await context.close();
  }
}

if (step === 'monitor') {
  const { page } = await login('admin');
  let found = '';
  for (let i = 0; i < 8; i++) {
    await page.goto(`${BASE}/manage/`);
    const m = page.locator('.app-adminmonitor, .jenkins-alert', { hasText: 'Standing permissions' }).first();
    if (await m.count()) { found = flat(await m.innerText()); await shot(page, m, `${L}-20-standing-monitor`); break; }
    const any = page.locator('.jenkins-alert', { hasText: 'outside the just-in-time grant flow' }).first();
    if (await any.count()) { found = flat(await any.innerText()); await shot(page, any, `${L}-20-standing-monitor`); break; }
    await sleep(45000);
  }
  log('monitor:', found || '(not shown within 6 minutes)');
  await page.context().close();
}

async function waitMail(to, subjectRe, since) {
  for (let i = 0; i < 20; i++) {
    const ms = (await mails(`to:${to}`)).filter((m) => new Date(m.Created) >= since && subjectRe.test(m.Subject));
    if (ms.length) return ms.map((m) => `${m.Created.slice(11, 19)} "${m.Subject}" to ${m.To.map((x) => x.Address).join(',')}`);
    await sleep(3000);
  }
  return [];
}

if (step === 'run') {
  const since = new Date(Date.now() - 2000);
  const b0 = (await api('admin', '/job/fresh-daily/api/json?tree=builds[number]')).json().builds.map((b) => b.number);
  const { page, context } = await login('lrequester');
  await page.goto(`${BASE}/job/fresh-daily/batch-control/`);
  const offered = await page.$$eval('input[name="approvers"]', (es) => es.map((e) => `${e.value}(${e.closest('label, .jenkins-checkbox, div')?.innerText.trim()})`));
  await page.fill('textarea[name="reason"]', 'e2e-05 L run by an LDAP user');
  await page.locator('input[name="approvers"][value="lapprover-1"]').check({ force: true });
  await shot(page, 'form:has(textarea[name="reason"])', `${L}-30-run-form-lrequester`);
  await Promise.all([page.waitForLoadState('load'), page.click('button[name="Submit"]')]);
  const id = (page.url().match(/requests\/([^/]+)/) || [])[1];
  log('run: offered approvers', offered, '| request', id, '|', flat(await text(page)).slice(0, 160));
  await context.close();
  log('  mail created ->', await waitMail('lapprover-1@ldap.e2e.local', /./, since));
  const a = await login('lapprover-1');
  await a.page.goto(`${BASE}/batch-control/requests/${id}/`);
  await Promise.all([a.page.waitForLoadState('load'), a.page.locator('form[action$="/approve"] button[name="Submit"]').click()]);
  await sleep(10000);
  await a.page.reload();
  await shot(a.page, '#main-panel table', `${L}-31-run-approved-lapprover-1`);
  const b1 = (await api('admin', '/job/fresh-daily/api/json?tree=builds[number]')).json().builds.map((b) => b.number);
  const last = (await api('admin', `/job/fresh-daily/${b1[0]}/api/json?tree=number,result,actions[causes[shortDescription]]`)).json();
  log('  approved:', flat(await text(a.page)).slice(0, 200), '| builds', b0, '->', b1, '| cause', JSON.stringify(last.actions.filter((x) => x.causes).map((x) => x.causes.map((c) => c.shortDescription))));
  log('  mail approved ->', await waitMail('lrequester@ldap.e2e.local', /./, since));
  await a.context.close();
}

if (step === 'grant') {
  const since = new Date(Date.now() - 2000);
  const { page, context } = await login('lrequester');
  const before = (await page.goto(`${BASE}/job/fresh-daily/configure`)).status();
  await page.goto(`${BASE}/batch-control/grants/?scopeFullName=fresh-daily`);
  const f = page.locator('form[action$="grants/create"]');
  await f.locator('input[name="actions"][value="CONFIGURE"]').check({ force: true });
  await f.locator('select[name="durationMinutes"]').selectOption('15');
  await f.locator('textarea[name="reason"]').fill('e2e-05 L grant for an LDAP user');
  await f.locator('input[name="approvers"][value="lapprover-2"]').check({ force: true });
  await Promise.all([page.waitForLoadState('load'), f.locator('button[name="Submit"]').click()]);
  const gl = await api('admin', '/batch-control/grants/');
  const gid = (gl.body.match(/href="[^"]*?(\d{8}-\d{6}-[a-z0-9]{6})\/?"/) || [])[1];
  log('grant: configure before window HTTP', before, '| grant', gid);
  log('  mail created ->', await waitMail('lapprover-2@ldap.e2e.local', /./, since));
  const a = await login('lapprover-2');
  await a.page.goto(`${BASE}/batch-control/grants/${gid}/`);
  await Promise.all([a.page.waitForLoadState('load'), a.page.locator('form[action$="/approve"] button[name="Submit"]').click()]);
  await shot(a.page, '#main-panel table', `${L}-40-grant-approved-lapprover-2`);
  log('  approved:', flat(await text(a.page)).slice(0, 220));
  await a.context.close();
  log('  mail approved ->', await waitMail('lrequester@ldap.e2e.local', /grant|window|approved/i, since));
  const r = await page.goto(`${BASE}/job/fresh-daily/configure`);
  const tag = `ldap-${Date.now() % 100000}`;
  await page.locator('textarea[name="description"]').fill(`Edited by lrequester (LDAP) under a window (${tag})`);
  await Promise.all([page.waitForLoadState('load'), page.locator('button[name="Submit"]').first().click()]);
  await sleep(1500);
  const ch = await api('admin', '/batch-control/changes/');
  const row = (flat(ch.body.replace(/<[^>]+>/g, ' ')).match(/CONFIG\w* fresh-daily lrequester[^|]{0,160}/) || ['(no record row found)'])[0];
  log('  configure in window HTTP', r.status(), '-> after save', page.url().replace(BASE, ''), '| record:', row.slice(0, 200));
  const cp = await login('admin');
  await cp.page.goto(`${BASE}/batch-control/changes/`);
  const rowEl = cp.page.locator('tr', { hasText: 'lrequester' }).first();
  await shot(cp.page, rowEl, `${L}-41-change-record-lrequester`);
  await cp.context.close(); await context.close();
}

if (step === 'down-request') {
  // Needs a session that logged in while LDAP was up: log in, then the caller stops LDAP, then we submit.
  const { page, context } = await login('lrequester');
  await page.goto(`${BASE}/job/fresh-daily/batch-control/`);
  await page.fill('textarea[name="reason"]', 'e2e-05 L request while LDAP is down');
  await page.locator('input[name="approvers"][value="lapprover-1"]').check({ force: true });
  log('down-request: form ready, stopping LDAP now');
  const { execSync } = await import('node:child_process');
  execSync('docker stop batch-control-e2e-ldap', { stdio: 'ignore' });
  await sleep(2000);
  const st = [];
  page.on('response', (r) => { if (r.request().method() === 'POST') st.push(r.status()); });
  await Promise.all([page.waitForLoadState('load'), page.click('button[name="Submit"]')]);
  const t = flat(await text(page));
  await shot(page, page.locator('#main-panel .jenkins-alert, #main-panel .error, #main-panel h1').first(), `${L}-50-request-ldap-down`);
  log('down-request: HTTP', st, '->', page.url().replace(BASE, ''), '| trace', /Exception|Stack trace|Oops/.test(t), '|', t.slice(0, 300));
  const r2 = await page.goto(`${BASE}/batch-control/requests/`);
  log('  list while down HTTP', r2.status());
  await context.close();
}
await close();
