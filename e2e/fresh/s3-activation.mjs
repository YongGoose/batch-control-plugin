// Scenario 3: activation of a new job; timer and upstream blocked until activated; hold.
// Usage: node s3-activation.mjs <step>  (steps: request <job> [HOLD], approve <job>, observe <job>, upstream)
import { login, BASE, shot, text, api, log, close, mails, sleep } from './lib.mjs';

const flat = (s) => s.replace(/\s+/g, ' ');
const [step, job, action = 'ACTIVATE'] = process.argv.slice(2);
const S = 'S3';

async function records(filter) {
  const { page, context } = await login('admin');
  await page.goto(`${BASE}/batch-control/changes/`);
  const rows = (await page.locator('tbody tr').allInnerTexts()).filter((r) => r.includes(filter));
  await context.close();
  return rows.slice(0, 6).map(flat);
}
async function builds(j) {
  const r = await api('admin', `/job/${encodeURIComponent(j)}/api/json?tree=builds[number,result,timestamp,actions[causes[shortDescription]]]`);
  return r.json().builds.map((b) => `#${b.number} ${b.result} ${new Date(b.timestamp).toISOString().slice(11, 19)} ${b.actions.filter((a) => a.causes).flatMap((a) => a.causes.map((c) => c.shortDescription)).join('/')}`);
}

if (step === 'observe') {
  const { page, context } = await login('requester');
  await page.goto(`${BASE}/job/${job}/`);
  const notice = page.locator('#main-panel .jenkins-alert, #main-panel .alert, #main-panel div', { hasText: /activated|on hold/ }).last();
  log(S, 'observe', job, flat(await text(page)).slice(0, 1200));
  await shot(page, notice, `${S}-${job}-${process.argv[4] || 'state'}-notice`);
  await context.close();
  log('builds', job, await builds(job));
  log('records', job, await records(job));
}

if (step === 'request') {
  const { page, context } = await login('requester');
  await page.goto(`${BASE}/job/${job}/`);
  const link = page.locator('#main-panel a', { hasText: action === 'HOLD' ? /hold/i : /Request activation/i }).first();
  log('link', await link.getAttribute('href').catch(() => 'none'));
  await shot(page, link, `${S}-${job}-${action}-01-link`);
  await link.click();
  await page.waitForLoadState('load');
  log('form page', page.url(), flat(await text(page)).slice(0, 500));
  // Try an empty submit first
  await page.locator('input[name="approvers"][value="approver-1"]').check({ force: true });
  await Promise.all([page.waitForLoadState('load'), page.locator('#main-panel form button[name="Submit"]').first().click()]);
  log('empty reason ->', page.url(), flat(await text(page)).slice(0, 400));
  await shot(page, '#main-panel', `${S}-${job}-${action}-02-empty-reason`);
  await page.fill('textarea[name="reason"]', `${action} ${job} for the fresh e2e-04 pass`);
  if (!(await page.locator('input[name="approvers"][value="approver-1"]').isChecked())) await page.locator('input[name="approvers"][value="approver-1"]').check({ force: true });
  await Promise.all([page.waitForLoadState('load'), page.locator('#main-panel form button[name="Submit"]').first().click()]);
  log('submitted ->', page.url(), flat(await text(page)).slice(0, 700));
  await shot(page, '#main-panel', `${S}-${job}-${action}-03-submitted`);
  await context.close();
  await sleep(2000);
  log('mail approver-1', (await mails('to:approver-1@e2e.local')).slice(0, 2).map((m) => m.Subject));
}

if (step === 'approve') {
  const { page, context } = await login('approver-1');
  await page.goto(`${BASE}/batch-control/activations/`);
  log('activations screen', flat(await text(page)).slice(0, 900));
  const row = page.locator('tr', { hasText: job }).first();
  await shot(page, row, `${S}-${job}-${action}-04-inbox-row`);
  await row.locator('a').first().click();
  await page.waitForLoadState('load');
  log('detail', page.url(), flat(await text(page)).slice(0, 900));
  await shot(page, '#main-panel', `${S}-${job}-${action}-05-detail`);
  const af = page.locator('form[action$="approve"]').first();
  await Promise.all([page.waitForLoadState('load'), af.locator('button').first().click()]);
  log('after approve', page.url(), flat(await text(page)).slice(0, 700));
  await shot(page, '#main-panel', `${S}-${job}-${action}-06-approved`);
  await context.close();
  await sleep(2000);
  log('mail requester', (await mails('to:requester@e2e.local')).slice(0, 2).map((m) => m.Subject));
  log('records', job, await records(job));
}

if (step === 'upstream') {
  const { page, context } = await login('requester');
  await page.goto(`${BASE}/job/fresh-up-src/`);
  log('src sidebar', flat(await page.locator('#tasks, #side-panel').first().innerText()));
  const b = page.locator('#tasks a, #side-panel a', { hasText: /Build Now|Build/ }).first();
  await shot(page, b, `${S}-up-01-src-build`);
  await b.click();
  await sleep(1500);
  await context.close();
  for (let i = 0; i < 15; i++) { await sleep(2000); const bs = await builds('fresh-up-src'); if (bs.length && !bs[0].includes('null')) break; }
  await sleep(4000);
  log('src builds', await builds('fresh-up-src'));
  log('dst builds', await builds('fresh-up-dst'));
  log('dst records', await records('fresh-up-dst'));
}
await close();
