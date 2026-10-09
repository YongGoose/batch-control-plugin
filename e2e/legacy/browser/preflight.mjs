// Section 1 pre-flight rows that need a browser (E-04, E-05, E-06, E-09) and
// E-10: activating batch-cron through the real ACTIVATE request flow.
import { login, close, shot, api, job, mails, waitFor, BASE, log } from './lib.mjs';

const L = 'preflight.log';
const only = process.argv[2];

async function e04() {
  const { page } = await login('admin');
  await page.goto(`${BASE}/manage/configure`);
  const title = page.locator('h2:text-is("E-mail Notification"), .jenkins-section__title:text-is("E-mail Notification")').first();
  await title.scrollIntoViewIfNeeded();
  const section = title.locator('xpath=ancestor::*[contains(@class,"jenkins-section")][1]');
  const adv = section.locator('button:has-text("Advanced")').first();
  if (await adv.count()) await adv.click();
  await page.locator('label:text-is("Test configuration by sending test e-mail")').click();
  const addr = page.locator('input[name="sendTestMailTo"], input[name="_.sendTestMailTo"]').first();
  await addr.fill('e04-check@e2e.local');
  await page.locator('button:has-text("Test configuration")').first().click();
  await page.waitForTimeout(4000);
  const res = addr.locator('xpath=ancestor::*[contains(@class,"jenkins-form-item") or contains(@class,"optionalBlock")][1]/following::*[contains(@class,"validation-error-area") or contains(@class,"ok") or contains(@class,"error")][1]');
  log(L, `E-04 validation text: ${(await page.locator('.validation-error-area--visible, .ok, .error').allInnerTexts()).join(' | ').slice(0, 300)}`);
  await shot(page, [addr, page.locator('button:has-text("Test configuration")').first()], 'E-04-mailer-test', { pad: 40 });
  const got = await waitFor(async () => (await mails('to:e04-check@e2e.local')).length > 0, { timeout: 20000 });
  log(L, `E-04 test mail in sink: ${got}`);
  await page.context().close();
}

async function e05e06e09() {
  const { page } = await login('admin');
  await page.goto(`${BASE}/manage/configure`);
  const url = page.locator('input[name="_.url"]').first();
  log(L, `E-05 Jenkins URL field = ${await url.inputValue()}`);
  await shot(page, url.locator('xpath=ancestor::div[contains(@class,"jenkins-form-item")][1]'), 'E-05-jenkins-url');
  await page.goto(`${BASE}/manage/systemInfo`);
  // Values are hidden behind a click on this core version: click to reveal, as a user would.
  const tz = page.locator('tr:has(td:text-is("user.timezone"))').first();
  const lang = page.locator('tr:has(td:text-is("user.language"))').first();
  for (const row of [tz, lang]) {
    const reveal = row.locator('button, a, .jenkins-hidden-value, [data-hidden-value]').first();
    if (await reveal.count()) await reveal.click().catch(() => {});
  }
  log(L, `E-06 ${(await tz.innerText()).replace(/\s+/g, ' ')} | ${(await lang.innerText()).replace(/\s+/g, ' ')}`);
  await shot(page, [tz, lang], 'E-06-systeminfo');
  await page.goto(`${BASE}/manage/configureSecurity/`);
  const strategy = await page.evaluate(() => [...document.querySelectorAll('select')]
    .map((s) => s.options[s.selectedIndex] && s.options[s.selectedIndex].text));
  log(L, `E-09 selected options on security page: ${JSON.stringify(strategy)}`);
  const sel = page.locator('select:has(option:text-is("Batch Control: Matrix-based security"))').first();
  const options = await sel.locator('option').allInnerTexts();
  log(L, `E-09 authorization options: ${JSON.stringify(options)}`);
  await shot(page, sel, 'E-09-strategy', { pad: 24 });
  await page.context().close();
}

async function e10() {
  // requester: job page notice -> Request activation -> form -> submit
  const req = await login('requester');
  const p = req.page;
  await p.goto(`${BASE}/job/batch-cron/`);
  const notice = p.locator('.jenkins-alert:has-text("Batch Control")').first();
  log(L, `E-10 notice before: ${(await notice.innerText()).replace(/\s+/g, ' ')}`);
  await shot(p, notice, 'E-10-1-not-activated-notice');
  await notice.locator('a:has-text("Request activation")').click();
  await p.waitForLoadState('load');
  log(L, `E-10 form url ${p.url()}`);
  await p.fill('textarea[name="reason"]', 'Put the one-minute feed job into service for the e2e run (dashboard volume).');
  await p.locator('label:has-text("approver-1")').first().click();
  await shot(p, 'form[name="batch-control-activation"]', 'E-10-2-activation-form');
  await Promise.all([p.waitForLoadState('load'), p.click('button:has-text("Submit Request")')]);
  const reqUrl = p.url();
  log(L, `E-10 submitted -> ${reqUrl}`);
  await shot(p, '#main-panel', 'E-10-3-activation-request-pending');
  const id = decodeURIComponent(reqUrl.split('/activations/')[1].replace(/\/$/, ''));

  // approver-1: find it from the Batch Control landing page, then decide.
  const ap = await login('approver-1');
  const a = ap.page;
  await a.goto(`${BASE}/batch-control/`);
  await shot(a, '#main-panel', 'E-10-4-approver-landing');
  const links = await a.locator('#main-panel a').allInnerTexts();
  log(L, `E-10 approver landing links: ${JSON.stringify(links)}`);
  const inboxLink = a.locator(`a[href*="${id}"]`).first();
  if (await inboxLink.count()) {
    log(L, 'E-10 request linked directly from the landing page');
  } else {
    await a.goto(`${BASE}/batch-control/requests/`);
    log(L, `E-10 requests page contains the activation id: ${(await a.content()).includes(id)}`);
    await shot(a, '#main-panel', 'E-10-4b-approver-requests-inbox');
  }
  await a.goto(reqUrl);
  await a.fill('form[name="approve"] textarea[name="comment"]', 'OK for the e2e run.');
  await shot(a, 'form[name="approve"]', 'E-10-5-approve-form');
  await Promise.all([a.waitForLoadState('load'), a.click('button:has-text("Approve Activation")')]);
  await shot(a, '#main-panel', 'E-10-6-approved');

  await p.goto(`${BASE}/job/batch-cron/`);
  const after = p.locator('.jenkins-alert:has-text("Batch Control")').first();
  log(L, `E-10 notice after: ${(await after.innerText()).replace(/\s+/g, ' ')}`);
  await shot(p, after, 'E-10-7-activated-notice');
  const built = await waitFor(async () => {
    const j = await job('batch-cron', 'builds[number,result,actions[causes[shortDescription]]]');
    return j.builds.length > 0 ? j : null;
  }, { timeout: 130000, every: 5000 });
  log(L, `E-10 batch-cron builds after activation: ${JSON.stringify(built && built.builds.slice(0, 2))}`);
  const m = await mails('to:approver-1@e2e.local');
  log(L, `E-10 mails to approver-1: ${m.map((x) => x.Subject).join(' | ')}`);
  await req.context.close(); await ap.context.close();
}

try {
  if (!only || only === 'e04') await e04();
  if (!only || only === 'e05') await e05e06e09();
  if (!only || only === 'e10') await e10();
} finally {
  await close();
}
