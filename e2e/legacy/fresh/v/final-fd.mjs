// Final check on 30e9252: FD-14, FD-15, FD-16, DD-05, DD-06 (e2e-04 "Re-verification on 80e5271").
// Usage: BC_SHOTS=final node v/final-fd.mjs [incidentId]
import { login, BASE, shot, text, api, groovy, log, close, sleep } from '../lib.mjs';

const flat = (s) => s.replace(/\s+/g, ' ').trim();
const INC = process.argv[2] || '20260930-133056-nmnlps';
const cfg = () => groovy('def c=io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration.get(); println([pending: c.pendingTimeoutHours, max: c.maxGrantMinutes, retention: c.retentionMonths, approvers: c.approvers])');
const item = (page, name) => page.locator(`[name="${name}"]`).locator('xpath=ancestor::div[contains(@class,"jenkins-form-item")][1]');
const banner = async (page) => flat((await page.locator('.jenkins-alert-danger, .jenkins-alert').allInnerTexts()).join(' | '));
log('FINAL-FD start', await cfg());

// FD-14 (a): manager, unknown approver id on the Batch Control configuration page
{
  const { page, context } = await login('manager');
  await page.goto(`${BASE}/batch-control-configuration/`);
  const ta = page.locator('[name="_.approversText"]');
  const orig = await ta.inputValue();
  await ta.fill(`${orig}\nno-such-user`);
  await Promise.all([page.waitForLoadState('load'), page.click('button[name="Submit"]')]);
  await sleep(800);
  const html = await page.content();
  log('FD-14 manager banner:', await banner(page));
  log('FD-14 manager field:', flat(await item(page, '_.approversText').innerText()).slice(0, 250));
  log('FD-14 raw &amp;#039; in html:', html.includes('&amp;#039;'), '| visible "&#039;":', (await text(page)).includes('&#039;'));
  await shot(page, [page.locator('.jenkins-alert-danger, .jenkins-alert').first(), item(page, '_.approversText')], 'FD14-manager-banner');
  log('FD-14 stored after refusal', await cfg());
  await context.close();
}
// FD-14 (b): admin, unknown approver id on Manage Jenkins -> System
{
  const { page, context } = await login('admin');
  await page.goto(`${BASE}/manage/configure`);
  const ta = page.locator('textarea[name="_.approversText"]');
  const orig = await ta.inputValue();
  await ta.fill(`${orig}\nghost-user`);
  await Promise.all([page.waitForLoadState('load'), page.click('button[name="Submit"]')]);
  await sleep(800);
  const t = flat(await text(page));
  log('FD-14 admin system page ->', page.url(), '|', t.slice(0, 300), '| visible "&#039;":', t.includes('&#039;'));
  await shot(page, '#main-panel', 'FD14-system-page-unknown');
  log('FD-14 stored after system refusal', await cfg());
  await context.close();
}

// FD-15: refused numbers keep the typed value and show a message next to the field
{
  const { page, context } = await login('manager');
  await page.goto(`${BASE}/batch-control-configuration/`);
  await page.fill('[name="_.retentionMonths"]', '0');
  await page.fill('[name="_.maxGrantMinutes"]', '123');
  await page.fill('[name="_.pendingTimeoutHours"]', '0');
  await Promise.all([page.waitForLoadState('load'), page.click('button[name="Submit"]')]);
  await sleep(800);
  const vals = {};
  for (const n of ['retentionMonths', 'maxGrantMinutes', 'pendingTimeoutHours']) {
    vals[n] = { value: await page.locator(`[name="_.${n}"]`).inputValue(), item: flat(await item(page, `_.${n}`).innerText()).slice(0, 200) };
  }
  log('FD-15 banner:', await banner(page));
  log('FD-15 fields after submit:', vals);
  await shot(page, [page.locator('.jenkins-alert-danger, .jenkins-alert').first(), item(page, '_.retentionMonths'), item(page, '_.pendingTimeoutHours')], 'FD15-typed-values-kept');
  await shot(page, item(page, '_.retentionMonths'), 'FD15-retention-field-message');
  log('FD-15 stored', await cfg());
  await context.close();
}

// FD-16: the refused run-request page has no Rebuild Last
{
  const { page, context } = await login('requester');
  await page.goto(`${BASE}/job/fresh-daily/batch-control/`);
  await Promise.all([page.waitForLoadState('load'), page.click('button[name="Submit"]')]);
  await sleep(600);
  const side = flat(await page.locator('#tasks').innerText().catch(() => ''));
  log('FD-16 url', page.url(), '| sidebar:', side, '| Rebuild Last links:', await page.locator('#tasks a', { hasText: 'Rebuild Last' }).count());
  log('FD-16 message:', flat(await text(page)).slice(0, 300));
  await shot(page, [page.locator('#tasks').first(), page.locator('#main-panel .error, #main-panel .jenkins-alert, #main-panel .validation-error-area--visible').first()], 'FD16-refused-submit-sidebar');
  await page.goto(`${BASE}/job/fresh-daily/`);
  log('FD-16 job page Rebuild Last links:', await page.locator('#tasks a', { hasText: 'Rebuild Last' }).count());
  await context.close();
}

// DD-05: Pipeline's own Rebuild on a Pipeline build page, as the documents now describe it
{
  const { page, context } = await login('requester');
  await page.goto(`${BASE}/job/fresh-pipe/1/`);
  const side = flat(await page.locator('#tasks').innerText());
  log('DD-05 requester fresh-pipe#1 sidebar:', side);
  await shot(page, page.locator('#tasks a', { hasText: /^Rebuild$/ }).first(), 'DD05-pipeline-rebuild-link');
  const before = (await api('admin', '/job/fresh-pipe/api/json?tree=nextBuildNumber')).json().nextBuildNumber;
  const rb = page.locator('#tasks a', { hasText: /^Rebuild$/ }).first();
  if (await rb.count()) {
    await Promise.all([page.waitForLoadState('load'), rb.click()]); await sleep(1000);
    const btn = page.locator('#main-panel button[name="Submit"], #main-panel button.jenkins-button--primary').first();
    if (/rebuild|replay/.test(page.url()) && await btn.count()) { await Promise.all([page.waitForLoadState('load'), btn.click()]); await sleep(1500); }
    log('DD-05 after Rebuild ->', page.url(), '|', flat(await text(page)).slice(0, 300));
    await shot(page, '#main-panel', 'DD05-pipeline-rebuild-refused');
  }
  await sleep(3000);
  log('DD-05 nextBuildNumber', before, '->', (await api('admin', '/job/fresh-pipe/api/json?tree=nextBuildNumber')).json().nextBuildNumber);
  const rec = (await api('admin', '/batch-control/history/changes.csv')).body.split('\n').filter((l) => /fresh-pipe/.test(l) && /TRIGGER_BLOCKED/.test(l)).slice(-2);
  log('DD-05 records:', rec);
  await context.close();
}

// DD-06: incident rerun section wording (admin) vs README
{
  const { page, context } = await login('admin');
  const r = await page.goto(`${BASE}/batch-control/incidents/${INC}/`);
  const t = flat(await text(page));
  const i = t.indexOf('Request Rerun');
  log('DD-06 admin', r.status(), '|', i >= 0 ? t.slice(i, i + 450) : t.slice(0, 250));
  log('DD-06 rerun form fields', await page.$$eval('form[action$="rerun"] [name]', (es) => es.map((e) => `${e.name}:${e.type}`)));
  const sec = page.locator('#main-panel h2, #main-panel h3', { hasText: 'Request Rerun' });
  await shot(page, (await sec.count()) ? [sec.first(), page.locator('form[action$="rerun"]').first()] : '#main-panel', 'DD06-admin-rerun-section');
  await context.close();
}
await close();
