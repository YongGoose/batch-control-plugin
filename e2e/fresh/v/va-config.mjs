// Re-verification A: FD-01, FD-02, FD-03, DD-02 and the configuration neighbours.
import { login, BASE, shot, text, groovy, log, close, sleep } from '../lib.mjs';

const flat = (s) => s.replace(/\s+/g, ' ');
const cfg = () => groovy('def c=io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration.get(); println([c.pendingTimeoutHours,c.maxGrantMinutes,c.approvers, Jenkins.get().authorizationStrategy.class.simpleName])');
async function records(types) {
  const { page, context } = await login('auditor');
  await page.goto(`${BASE}/batch-control/changes/`);
  const rows = (await page.locator('tbody tr').allInnerTexts()).filter((r) => types.some((t) => r.startsWith(t))).slice(0, 6).map(flat);
  await context.close();
  return rows;
}
const item = (page, name) => page.locator(`[name="${name}"]`).locator('xpath=ancestor::div[contains(@class,"jenkins-form-item")][1]');
log('V-A start', await cfg());

// FD-01: manager sees no revert control; admin does
for (const u of ['manager', 'admin']) {
  const { page, context } = await login(u);
  await page.goto(`${BASE}/batch-control-configuration/`);
  const rv = page.locator('a, button', { hasText: /Revert to the plain strategy/ });
  const sec = page.locator('.jenkins-form-item', { hasText: 'Batch Control strategy' });
  log('FD-01', u, 'revert control count', await rv.count(), '| strategy row:', flat(await sec.innerText().catch(() => '(no row)')));
  await shot(page, (await sec.count()) ? sec : page.locator('#main-panel h1').first(), `FD01-${u}-strategy-row`);
  // forced POST by manager
  if (u === 'manager') {
    const res = await page.evaluate(async () => {
      const r = await fetch('/manage/administrativeMonitor/batch-control-strategy/revert', { method: 'POST', headers: { 'Jenkins-Crumb': document.head.dataset.crumbValue } });
      return r.status;
    });
    log('FD-01 manager forced revert POST', res);
  }
  await context.close();
}

// FD-02: manager changes a field -> CONFIG_CHANGE; unchanged save -> nothing
{
  const { page, context } = await login('manager');
  await page.goto(`${BASE}/batch-control-configuration/`);
  log('FD-02 page sentence', flat((await page.locator('#main-panel').innerText()).split('\n').slice(0, 4).join(' ')));
  await page.fill('input[name="_.maxGrantMinutes"]', '200');
  await Promise.all([page.waitForNavigation(), page.click('button[name="Submit"]')]);
  log('FD-02 after save url', page.url(), await cfg());
  await sleep(500);
  log('FD-02 records', await records(['CONFIG_CHANGE', 'CONFIG_TOGGLE', 'STRATEGY_CHANGE']));
  await page.goto(`${BASE}/batch-control-configuration/`);
  await Promise.all([page.waitForNavigation(), page.click('button[name="Submit"]')]);
  await sleep(500);
  log('FD-02 records after unchanged save', await records(['CONFIG_CHANGE']));
  await page.goto(`${BASE}/batch-control-configuration/`);
  await page.fill('input[name="_.maxGrantMinutes"]', '240');
  await Promise.all([page.waitForNavigation(), page.click('button[name="Submit"]')]);
  await context.close();
  const { page: p2, context: c2 } = await login('auditor');
  await p2.goto(`${BASE}/batch-control/changes/`);
  const row = p2.locator('tbody tr', { hasText: 'CONFIG_CHANGE' }).first();
  await shot(p2, row, 'FD02-config-change-record');
  await c2.close();
}

// FD-03: unknown approver, empty list; neighbour: invalid number keeps the form (UX-3)
{
  const { page, context } = await login('manager');
  for (const [tag, name, val] of [['unknown', '_.approversText', 'approver-1\nno-such-user'], ['empty', '_.approversText', ''], ['number', '_.pendingTimeoutHours', '0']]) {
    await page.goto(`${BASE}/batch-control-configuration/`);
    await page.locator(`[name="${name}"]`).fill(val);
    await page.locator(`[name="${name}"]`).blur();
    await page.waitForTimeout(1200);
    log('FD-03', tag, 'inline:', flat(await item(page, name).innerText()).slice(0, 250));
    await shot(page, item(page, name), `FD03-${tag}-inline`);
    await Promise.all([page.waitForLoadState('load'), page.click('button[name="Submit"]')]);
    await sleep(800);
    const kept = (await page.locator(`[name="${name}"]`).count()) ? await page.locator(`[name="${name}"]`).inputValue() : '(no field)';
    const errs = await page.$$eval('.error, .jenkins-alert-danger, .validation-error-area .error', (es) => es.map((e) => e.innerText.trim()).filter(Boolean));
    log('FD-03', tag, 'after submit url', page.url(), '| errors', errs, '| kept', JSON.stringify(kept), '| stored', await cfg());
    await shot(page, (await page.locator('.error, .jenkins-alert-danger').count()) ? [page.locator('.jenkins-alert-danger, .error').first(), item(page, name)] : '#main-panel', `FD03-${tag}-after-submit`);
  }
  await context.close();
}

// FD-02 neighbour: admin changes approvers on Manage Jenkins -> System; unknown id there too
{
  const { page, context } = await login('admin');
  await page.goto(`${BASE}/manage/configure`);
  const ta = page.locator('textarea[name="_.approversText"]');
  const orig = await ta.inputValue();
  await ta.fill(orig + '\nghost-user');
  await Promise.all([page.waitForLoadState('load'), page.click('button[name="Submit"]')]);
  await sleep(800);
  log('FD-03 system page unknown id ->', page.url(), flat(await text(page)).slice(0, 300), '| stored', await cfg());
  await shot(page, '#main-panel', 'FD03-system-page-unknown');
  await page.goto(`${BASE}/manage/configure`);
  await page.locator('textarea[name="_.approversText"]').fill(orig.split('\n').filter((x) => x.trim() !== 'approver-disc').join('\n'));
  await Promise.all([page.waitForLoadState('load'), page.click('button[name="Submit"]')]);
  log('FD-02 admin system-page approver change stored', await cfg());
  await page.goto(`${BASE}/manage/configure`);
  await page.locator('textarea[name="_.approversText"]').fill(orig);
  await Promise.all([page.waitForLoadState('load'), page.click('button[name="Submit"]')]);
  log('FD-02 restored', await cfg());
  await context.close();
  log('FD-02 records', await records(['CONFIG_CHANGE']));
}

// FD-02 STRATEGY_CHANGE: admin revert + reinstall
{
  const { page, context } = await login('admin');
  await page.goto(`${BASE}/batch-control-configuration/`);
  await page.locator('a, button', { hasText: /Revert to the plain strategy/ }).first().click();
  await page.locator('dialog[open] button', { hasText: 'Yes' }).click();
  await page.waitForLoadState('load'); await sleep(1000);
  log('revert ->', page.url(), await cfg());
  await page.goto(`${BASE}/manage/`);
  const mon = page.locator('.jenkins-alert', { hasText: 'Install the Batch Control variant' }).first();
  log('monitor', flat(await mon.innerText().catch(() => 'none')).slice(0, 300));
  await mon.locator('button', { hasText: /Install/ }).first().click();
  await page.waitForTimeout(600);
  if (await page.locator('dialog[open]').count()) await page.locator('dialog[open] button', { hasText: /yes|install/i }).first().click();
  await page.waitForLoadState('load'); await sleep(1000);
  log('install ->', page.url(), await cfg());
  await context.close();
  log('STRATEGY records', await records(['STRATEGY_CHANGE']));
  const { page: p2, context: c2 } = await login('auditor');
  await p2.goto(`${BASE}/batch-control/changes/`);
  await shot(p2, [p2.locator('tbody tr', { hasText: 'STRATEGY_CHANGE' }).nth(0), p2.locator('tbody tr', { hasText: 'STRATEGY_CHANGE' }).nth(1)], 'FD02-strategy-change-records');
  await c2.close();
}
await close();
