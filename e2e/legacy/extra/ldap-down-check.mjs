// e2e-05 EX repro: the approver check while the LDAP directory is stopped. Logs in first, then stops LDAP,
// then records every checkApproversText answer and the page after Save. Restarts LDAP at the end.
import { execSync } from 'node:child_process';
import { login, BASE, shot, text, log, close, sleep } from './lib.mjs';
const flat = (s) => s.replace(/\s+/g, ' ').trim();
const { page } = await login('admin');
execSync('docker stop batch-control-e2e-ldap', { stdio: 'ignore' });
await sleep(2000);
const answers = [];
page.on('response', async (r) => { if (/checkApproversText/.test(r.url())) answers.push(`${r.status()} ${Date.now() % 100000} ${flat(await r.text().catch(() => '?')).slice(0, 300)}`); });
await page.goto(`${BASE}/batch-control-configuration/`);
const f = page.locator('textarea[name="_.approversText"]');
await f.fill('lapprover-1\nlapprover-2\nadmin\nlnobody\nlnever-seen-2'); await f.blur();
for (let i = 0; i < 30 && !answers.some((a) => /lnever|warn|cannot|could not/i.test(a)); i++) await sleep(1000);
const item = f.locator('xpath=ancestor::div[contains(@class,"jenkins-form-item")][1]');
log('down-check: answers', answers, '| field area:', flat(await item.innerText()).slice(0, 300));
await shot(page, item, 'L-05-validate-ldap-down-inline-waited');
const direct = await page.context().request.get(`${BASE}/descriptorByName/io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration/checkApproversText?value=${encodeURIComponent('lnever-seen-3')}`);
log('down-check: direct check of lnever-seen-3 -> HTTP', direct.status(), flat(await direct.text()).slice(0, 300));
const st = [];
page.on('response', (r) => { if (r.request().method() === 'POST') st.push(r.status()); });
await Promise.all([page.waitForLoadState('load'), page.locator('button[name="Submit"]').first().click()]);
await sleep(1000);
log('down-check: save HTTP', st, '->', page.url().replace(BASE, ''), '|', flat(await text(page)).slice(0, 400));
await page.goto(`${BASE}/batch-control-configuration/`);
log('down-check: stored approvers now:', flat(await page.locator('textarea[name="_.approversText"]').inputValue()));
await shot(page, [page.locator('textarea[name="_.approversText"]').locator('xpath=ancestor::div[contains(@class,"jenkins-form-item")][1]')], 'L-06-validate-ldap-down-stored');
execSync('docker start batch-control-e2e-ldap', { stdio: 'ignore' });
await close();
