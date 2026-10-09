// Scenario 6: refusals a user can hit in the browser.
import { login, BASE, shot, text, api, log, close, sleep } from './lib.mjs';

const flat = (s) => s.replace(/\s+/g, ' ');
const S = 'S6';
const nbuilds = async (j) => (await api('admin', `/job/${j}/api/json?tree=builds%5Bnumber%5D`)).json().builds.length;
const lastChanges = async (filter) => {
  const { page, context } = await login('admin');
  await page.goto(`${BASE}/batch-control/changes/`);
  const rows = (await page.locator('tbody tr').allInnerTexts()).filter((r) => r.includes(filter)).slice(0, 3).map(flat);
  await context.close();
  return rows;
};

if (!process.env.ONLY02) {
// 01 Direct Build (needs approval) on a parameterised job, and on a job without parameters
for (const [user, job] of [['requester', 'fresh-daily'], ['requester', 'fresh-token'], ['nobc', 'fresh-token'], ['reqonly', 'fresh-token']]) {
  const { page, context } = await login(user);
  await page.goto(`${BASE}/job/${job}/`);
  const side = flat(await page.locator('#tasks').innerText().catch(() => ''));
  const b = page.locator('#tasks a', { hasText: /Build/ }).first();
  log(S + '-01', user, job, 'sidebar:', side);
  if (!(await b.count())) { await context.close(); continue; }
  await shot(page, b, `${S}-01-${user}-${job}-direct-build-link`);
  const before = await nbuilds(job);
  await b.click();
  await page.waitForLoadState('load');
  await sleep(1500);
  if (/build\?delay/.test(page.url()) || (await page.locator('form[name="parameters"]').count())) {
    log('  parameters page', page.url());
    await shot(page, '#main-panel', `${S}-01-${user}-${job}-params-page`);
    await Promise.all([page.waitForLoadState('load'), page.locator('form[name="parameters"] button[name="Submit"], #main-panel button.jenkins-button--primary').first().click()]);
    await sleep(1500);
  }
  const t = flat(await text(page));
  const toast = await page.locator('.jenkins-notification, #notification-bar, .tippy-box').allInnerTexts().catch(() => []);
  log(S + '-01', user, job, 'after click url', page.url(), '| toast', toast, '|', t.slice(0, 500));
  await shot(page, (await page.locator('.jenkins-notification').count()) ? page.locator('.jenkins-notification').first() : '#main-panel', `${S}-01-${user}-${job}-after-direct-build`);
  log('  builds before/after', before, await nbuilds(job));
  await context.close();
}

}
// 02 Rebuild of the approved build fresh-daily#1 (rebuild plugin)
{
  const { page, context } = await login('requester');
  await page.goto(`${BASE}/job/${process.env.RB || 'fresh-daily'}/1/`);
  log(S + '-02 build sidebar', flat(await page.locator('#tasks').innerText()));
  const rb = page.locator('#tasks a', { hasText: /^Rebuild$/ }).first();
  if (await rb.count()) {
    await shot(page, rb, `${S}-02-rebuild-link`);
    const before = await nbuilds(process.env.RB || 'fresh-daily');
    await rb.click();
    await page.waitForLoadState('load');
    log('  rebuild page', page.url(), flat(await text(page)).slice(0, 300));
    await shot(page, '#main-panel', `${S}-02-rebuild-page`);
    const submit = page.locator('#main-panel button.jenkins-button--primary, #main-panel button[name="Submit"], #main-panel input[type=submit]').first();
    if (await submit.count()) { await Promise.all([page.waitForLoadState('load'), submit.click()]); await sleep(1500); }
    log(S + '-02 after rebuild', page.url(), flat(await text(page)).slice(0, 500));
    await shot(page, '#main-panel', `${S}-02-after-rebuild`);
    log('  builds before/after', before, await nbuilds(process.env.RB || 'fresh-daily'));
  }
  await context.close();
  log('  records', await lastChanges('fresh-daily'));
}

if (!process.env.ONLY02) {
// 03 Retry (naginator) of fresh-fail#1
{
  const { page, context } = await login('requester');
  await page.goto(`${BASE}/job/fresh-fail/1/`);
  log(S + '-03 build sidebar', flat(await page.locator('#tasks').innerText()));
  const rt = page.locator('#tasks a', { hasText: /Retry/ }).first();
  if (await rt.count()) {
    await shot(page, rt, `${S}-03-retry-link`);
    const before = await nbuilds('fresh-fail');
    await rt.click();
    await page.waitForLoadState('load'); await sleep(1500);
    log(S + '-03 after retry', page.url(), flat(await text(page)).slice(0, 500));
    await shot(page, '#main-panel', `${S}-03-after-retry`);
    log('  builds before/after', before, await nbuilds('fresh-fail'));
  }
  await context.close();
  log('  records', await lastChanges('fresh-fail'));
}

// 04 Replay of fresh-pipe#1 as admin (requester has no Run/Replay)
for (const user of ['requester', 'admin']) {
  const { page, context } = await login(user);
  const r = await page.goto(`${BASE}/job/fresh-pipe/1/`);
  log(S + '-04', user, 'build sidebar', flat(await page.locator('#tasks').innerText()));
  const rp = page.locator('#tasks a', { hasText: /Replay/ }).first();
  if (await rp.count()) {
    await shot(page, rp, `${S}-04-${user}-replay-link`);
    const before = await nbuilds('fresh-pipe');
    await rp.click(); await page.waitForLoadState('load');
    await shot(page, '#main-panel', `${S}-04-${user}-replay-page`);
    await Promise.all([page.waitForLoadState('load'), page.locator('#main-panel button[name="Submit"], #main-panel button.jenkins-button--primary').first().click()]);
    await sleep(2500);
    log(S + '-04', user, 'after replay', page.url(), flat(await text(page)).slice(0, 500));
    await shot(page, '#main-panel', `${S}-04-${user}-after-replay`);
    log('  builds before/after', before, await nbuilds('fresh-pipe'));
  }
  await context.close();
}
log('  records', await lastChanges('fresh-pipe'));
}
await close();
