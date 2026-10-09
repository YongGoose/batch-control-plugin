// Scenario 1: install and first configuration, as the README tells a new administrator.
import { login, BASE, shot, text, api, groovy, log, close } from './lib.mjs';

const S = 'S1';
const bcSection = (page) => page.locator('section.jenkins-section', { has: page.locator('input[name="_.runControlEnabled"]') });

// 01: admin follows "Manage Jenkins -> System -> Batch Control"
{
  const { page } = await login('admin');
  await page.goto(BASE + '/manage/');
  const tile = page.locator('a[href*="batch-control-configuration"], a:has-text("Batch Control")').first();
  log(S + '-01 manage tile href', await tile.getAttribute('href'));
  await shot(page, tile, `${S}-01-manage-tile`);
  await page.goto(BASE + '/manage/configure');
  const sec = bcSection(page);
  log(S + '-01 system section visible', await sec.isVisible());
  await shot(page, sec, `${S}-01-system-section`);
  // Turn both switches off, save, then on again: a new admin's first enablement.
  await sec.locator('input[name="_.runControlEnabled"]').evaluate((e) => e.scrollIntoView());
  const rc = sec.locator('input[name="_.runControlEnabled"]');
  const cc = sec.locator('input[name="_.changeControlEnabled"]');
  log('before', await rc.isChecked(), await cc.isChecked());
  await rc.setChecked(false, { force: true });
  await cc.setChecked(false, { force: true });
  await Promise.all([page.waitForNavigation(), page.click('button[name="Submit"]')]);
  log('after save off url', page.url());
  log('store off', await groovy('def c=io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration.get(); println c.runControlEnabled+" "+c.changeControlEnabled'));
  await page.goto(BASE + '/manage/configure');
  const sec2 = bcSection(page);
  await sec2.locator('input[name="_.runControlEnabled"]').setChecked(true, { force: true });
  await sec2.locator('input[name="_.changeControlEnabled"]').setChecked(true, { force: true });
  await shot(page, [sec2.locator('input[name="_.runControlEnabled"]').locator('xpath=ancestor::div[contains(@class,"jenkins-form-item")][1]'), sec2.locator('input[name="_.changeControlEnabled"]').locator('xpath=ancestor::div[contains(@class,"jenkins-form-item")][1]')], `${S}-01-switches-on`);
  await Promise.all([page.waitForNavigation(), page.click('button[name="Submit"]')]);
  log('store on', await groovy('def c=io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration.get(); println c.runControlEnabled+" "+c.changeControlEnabled'));
  await page.goto(BASE + '/batch-control/changes/');
  const rows = page.locator('tr', { hasText: 'CONFIG_TOGGLE' });
  log('toggle rows', (await rows.allInnerTexts()).slice(0, 4));
  await shot(page, rows.first().locator('xpath=ancestor::table[1]').locator('tr').nth(0), `${S}-01-toggle-records-hdr`).catch(() => {});
  await shot(page, [rows.nth(0), rows.nth(3)], `${S}-01-toggle-records`);
  await page.context().close();
}

// 02: manager (BatchControl/Manage, no Administer)
{
  const { page } = await login('manager');
  const r = await page.goto(BASE + '/manage/configure');
  log(S + '-02 manager /manage/configure', r.status(), (await text(page)).slice(0, 200));
  await page.goto(BASE + '/batch-control/');
  const link = page.locator('a[href*="batch-control-configuration"]').first();
  await shot(page, link, `${S}-02-manager-config-link`);
  await link.click();
  await page.waitForLoadState('load');
  log('manager config url', page.url());
  const f = page.locator('input[name="_.pendingTimeoutHours"]');
  const before = await f.inputValue();
  await f.fill('2');
  await Promise.all([page.waitForNavigation(), page.click('button[name="Submit"]')]);
  log('manager save url', page.url(), (await text(page)).slice(0, 200));
  log('pendingTimeout after manager save', await groovy('println io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration.get().pendingTimeoutHours'));
  await page.goto(BASE + '/batch-control-configuration/');
  await shot(page, page.locator('input[name="_.pendingTimeoutHours"]').locator('xpath=ancestor::div[contains(@class,"jenkins-form-item")][1]'), `${S}-02-manager-saved`);
  await page.fill('input[name="_.pendingTimeoutHours"]', before);
  await Promise.all([page.waitForNavigation(), page.click('button[name="Submit"]')]);
  log('restored', await groovy('println io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration.get().pendingTimeoutHours'));

  // 03: invalid input as manager
  const cases = [
    ['_.pendingTimeoutHours', '0'],
    ['_.maxGrantMinutes', '-5'],
    ['_.grantDurationOptions', 'ten, 20'],
    ['_.incidentResults', 'FAILURE, BOGUS'],
    ['_.retentionMonths', '0'],
    ['_.approversText', 'no-such-user'],
  ];
  for (const [name, val] of cases) {
    await page.goto(BASE + '/batch-control-configuration/');
    const el = page.locator(`[name="${name}"]`);
    const orig = await el.inputValue();
    await el.fill(val);
    await el.blur();
    await page.waitForTimeout(1200);
    const item = el.locator('xpath=ancestor::div[contains(@class,"jenkins-form-item")][1]');
    const inline = (await item.innerText()).replace(/\s+/g, ' ');
    await shot(page, item, `${S}-03-invalid-${name.slice(2)}-inline`);
    const resp = await Promise.all([page.waitForNavigation().catch(() => null), page.click('button[name="Submit"]')]);
    await page.waitForLoadState('load');
    const t = (await text(page)).replace(/\s+/g, ' ').slice(0, 300);
    const kept = (await page.locator(`[name="${name}"]`).count()) ? await page.locator(`[name="${name}"]`).inputValue() : '(field not on page)';
    log(S + '-03', name, JSON.stringify(val), '| inline:', inline.slice(0, 200), '| after submit url:', page.url(), '| text:', t, '| kept:', kept);
    await shot(page, '#main-panel', `${S}-03-invalid-${name.slice(2)}-submit`);
    // restore when the invalid value was stored
    const stored = await groovy(`def c=io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration.get(); println([c.pendingTimeoutHours,c.maxGrantMinutes,c.grantDurationOptions,c.incidentResults,c.retentionMonths,c.approvers])`);
    log('   stored now', stored);
  }
  await page.context().close();
}
await close();
