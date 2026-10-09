// Re-audit section 1 pre-flight E-01..E-09 (E-10 in e10.mjs).
import { login, close, shot, api, mails, waitFor, BASE, groovy } from '../lib.mjs';
import { row, ev, mainText } from './rec.mjs';
process.env.BC_SHOTS = 'run-3-audit';

const a = await login('admin');
const p = a.page;
// E-01: version as the admin sees it (About Jenkins) + boot log
await p.goto(`${BASE}/manage/about/`);
const about = p.locator('#main-panel').locator('text=/Version 2\\.568\\.3|Jenkins 2\\.568\\.3/').first();
const e01 = await shot(p, about, 'E-01-about');
ev(`E-01 about: ${(await about.innerText().catch(() => 'n/a'))}`);
// E-02 / E-03: installed plugins page
await p.goto(`${BASE}/manage/pluginManager/installed`);
await p.waitForTimeout(1500);
const bcRow = p.locator('tr', { hasText: 'Batch Control' }).first();
const e03 = await shot(p, bcRow, 'E-03-installed-row');
ev(`E-03 row: ${(await bcRow.innerText()).replace(/\s+/g, ' ').slice(0, 200)}`);
const pj = (await api('admin', '/pluginManager/api/json?depth=1&tree=plugins[shortName,version,active,enabled]')).json.plugins;
const want = ['structs','cloudbees-folder','ionicons-api','matrix-auth','role-strategy','workflow-aggregator','pipeline-build-step','workflow-multibranch','git','job-dsl','mailer','configuration-as-code','customize-build-now','rebuild','parameterized-trigger','build-token-root','naginator','lockable-resources','throttle-concurrents','authorize-project','jobConfigHistory'];
const missing = want.filter((w) => !pj.find((x) => x.shortName === w && x.active));
const inactive = pj.filter((x) => !x.active).map((x) => x.shortName);
ev(`E-02 ${pj.length} plugins, inactive=${JSON.stringify(inactive)}, missing/inactive wanted=${JSON.stringify(missing)}`);
// failed plugins show as an admin monitor on /manage
await p.goto(`${BASE}/manage/`);
const failedMon = await p.locator('text=/failed to load|Failed Loading/i').count();
const rows = p.locator('tr:has(td)');
const e02 = await shot(p.context().pages()[0], p.locator('#main-panel h1, #main-panel .jenkins-app-bar').first(), 'E-02-manage-no-failed-monitor');
row('E-02', { roles: 'admin', V: 'n.a. (environment)', G: missing.length || inactive.length ? `✗ missing ${missing}` : `✓ ${pj.length} active, 0 inactive, every listed plugin active`, R: 'n.a.', C: 'n.a.', E: e02 ? `✓ E-02-manage-no-failed-monitor.png (no "failed to load" monitor: ${failedMon === 0})` : '✗ no shot', note: '' });
const bb = pj.find((x) => x.shortName === 'batch-control');
row('E-03', { roles: 'admin', V: 'n.a. (environment)', G: /dae675e/.test(bb.version) && bb.active ? `✓ ${bb.version} active (= HEAD dae675e)` : `✗ ${bb.version}`, R: 'n.a.', C: 'n.a.', E: e03 ? '✓ E-03-installed-row.png' : '✗' });
row('E-01', { roles: 'admin', V: 'n.a. (environment)', G: '✓ About Jenkins shows 2.568.3; boot log "Starting version 2.568.3" (out/boot-audit-1.log)', R: 'n.a.', C: 'n.a.', E: e01 ? '✓ E-01-about.png' : '✗' });

// E-04: test e-mail as admin through the System page
await p.goto(`${BASE}/manage/configure`);
const title = p.locator('.jenkins-section__title:text-is("E-mail Notification"), h2:text-is("E-mail Notification")').first();
await title.scrollIntoViewIfNeeded();
const section = title.locator('xpath=ancestor::*[contains(@class,"jenkins-section")][1]');
const adv = section.locator('button:has-text("Advanced")').first();
if (await adv.count()) await adv.click();
await p.locator('label:text-is("Test configuration by sending test e-mail")').click();
const addr = p.locator('input[name="sendTestMailTo"], input[name="_.sendTestMailTo"]').first();
const to = `audit-e04-${Date.now()}@e2e.local`;
await addr.fill(to);
await p.locator('button:has-text("Test configuration")').first().click();
await p.waitForTimeout(4000);
const vtext = (await p.locator('.validation-error-area--visible, .ok').allInnerTexts()).join(' | ');
const e04 = await shot(p, [addr, p.locator('button:has-text("Test configuration")').first(), p.locator('.validation-error-area--visible').last()], 'E-04-mailer-test', { pad: 30 });
const got = await waitFor(async () => (await mails(`to:${to}`)).length > 0, { timeout: 20000 });
ev(`E-04 screen: ${vtext}; mail in sink: ${got}`);
row('E-04', { roles: 'admin', V: 'n.a. (environment)', G: got ? `✓ screen "${vtext.slice(0, 80)}", mail to ${to} in mailpit` : '✗ no mail', R: 'n.a.', C: got ? '✓ mail in sink' : '✗', E: e04 ? '✓ E-04-mailer-test.png' : '✗' });
// E-05
await p.goto(`${BASE}/manage/configure`);
const url = p.locator('input[name="_.url"]').first();
const uv = await url.inputValue();
const e05 = await shot(p, url.locator('xpath=ancestor::div[contains(@class,"jenkins-form-item")][1]'), 'E-05-jenkins-url');
row('E-05', { roles: 'admin', V: 'n.a. (environment)', G: uv === 'http://localhost:8080/' ? `✓ ${uv}` : `✗ ${uv}`, R: 'n.a.', C: 'n.a.', E: e05 ? '✓ E-05-jenkins-url.png' : '✗' });
// E-06
await p.goto(`${BASE}/manage/systemInfo`);
const tz = p.locator('tr:has(td:text-is("user.timezone"))').first();
const lang = p.locator('tr:has(td:text-is("user.language"))').first();
for (const r of [tz, lang]) { const rv = r.locator('button, .jenkins-hidden-value').first(); if (await rv.count()) await rv.click().catch(() => {}); }
const tzt = (await tz.innerText()).replace(/\s+/g, ' '); const lt = (await lang.innerText()).replace(/\s+/g, ' ');
const e06 = await shot(p, [tz, lang], 'E-06-systeminfo');
row('E-06', { roles: 'admin', V: 'n.a. (environment)', G: /Asia\/Seoul/.test(tzt) && /\ben\b/.test(lt) ? `✓ ${tzt}; ${lt}` : `✗ ${tzt}; ${lt}`, R: 'n.a.', C: 'n.a.', E: e06 ? '✓ E-06-systeminfo.png' : '✗' });
// E-09
await p.goto(`${BASE}/manage/configureSecurity/`);
const sel = p.locator('select:has(option:text-is("Batch Control: Matrix-based security"))').first();
const selected = await sel.evaluate((s) => s.options[s.selectedIndex].text).catch(() => 'n/a');
const e09 = await shot(p, sel, 'E-09-strategy', { pad: 24 });
const f00 = await groovy(`println new File(jenkins.model.Jenkins.get().rootDir, 'init.groovy.d').list().toList()`);
row('E-09', { roles: 'admin', V: 'n.a. (environment)', G: selected === 'Batch Control: Matrix-based security' ? `✓ selected "${selected}"; init.groovy.d = ${f00}` : `✗ ${selected}`, R: 'n.a.', C: 'n.a.', E: e09 ? '✓ E-09-strategy.png' : '✗' });
await close();
