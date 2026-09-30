// Scenario 8: things a real user would try. node s8-misc.mjs <step>
import { login, BASE, shot, text, api, log, close, sleep, mails } from './lib.mjs';

const flat = (s) => s.replace(/\s+/g, ' ');
const S = 'S8';
const step = process.argv[2];
const countReq = async (job) => {
  const { page, context } = await login('admin');
  await page.goto(`${BASE}/batch-control/requests/`);
  const n = (await page.locator('tbody tr', { hasText: job }).allInnerTexts()).length;
  await context.close();
  return n;
};

if (step === 'double-submit') {
  const before = await countReq('fresh-pipe');
  const { page, context } = await login('requester');
  await page.goto(`${BASE}/job/fresh-pipe/batch-control/`);
  await page.fill('textarea[name="reason"]', 'double-click on Submit (fresh e2e-04)');
  await page.locator('input[name="approvers"][value="approver-2"]').check({ force: true });
  await page.locator('button[name="Submit"]').dblclick();
  await page.waitForLoadState('load'); await sleep(1500);
  log(S + '-01 double submit ->', page.url());
  // back button, then submit again from the restored form
  await page.goBack(); await page.waitForLoadState('load');
  log('   back ->', page.url(), 'reason kept:', await page.locator('textarea[name="reason"]').inputValue().catch(() => '(no form)'));
  await context.close();
  log(S + '-01 requests for fresh-pipe before/after', before, await countReq('fresh-pipe'));
}

if (step === 'long-xss') {
  const { page, context } = await login('requester');
  await page.goto(`${BASE}/job/fresh-daily/batch-control/`);
  await page.fill('textarea[name="reason"]', 'x'.repeat(4001));
  await page.locator('input[name="value"]').first().fill('y'.repeat(10001));
  await page.locator('input[name="approvers"][value="approver-2"]').check({ force: true });
  await Promise.all([page.waitForLoadState('load'), page.click('button[name="Submit"]')]);
  const errs = await page.$$eval('.error, .jenkins-alert-danger', (es) => es.map((e) => e.innerText.trim()));
  log(S + '-02 long input ->', page.url(), errs, 'reason kept length', (await page.locator('textarea[name="reason"]').inputValue()).length, 'param kept length', (await page.locator('input[name="value"]').first().inputValue()).length);
  await shot(page, page.locator('.error').first(), `${S}-02-long-reason`);
  await page.fill('textarea[name="reason"]', '<script>alert("r")</script><img src=x onerror=alert(2)> 한국어 사유 ✓ "quotes" & ampersand');
  await page.locator('input[name="value"]').first().fill('<b>bold</b>=1+2');
  let dialogs = 0; page.on('dialog', (d) => { dialogs++; d.dismiss(); });
  await Promise.all([page.waitForLoadState('load'), page.click('button[name="Submit"]')]);
  const id = (page.url().match(/requests\/([^/]+)/) || [])[1];
  log(S + '-03 xss/unicode request', id, flat(await text(page)).slice(0, 400));
  await shot(page, page.locator('#main-panel').first(), `${S}-03-xss-unicode-requester`);
  await context.close();
  const a = await login('approver-2');
  a.page.on('dialog', (d) => { dialogs++; d.dismiss(); });
  await a.page.goto(`${BASE}/batch-control/requests/${id}/`);
  await a.page.goto(`${BASE}/batch-control/requests/`);
  await a.context.close();
  log(S + '-03 dialogs fired', dialogs);
  await sleep(2000);
  const m = (await mails('to:approver-2@e2e.local')).find((x) => x.Subject.includes(id));
  if (m) { const r = await fetch(`http://localhost:8025/api/v1/message/${m.ID}`); const j = await r.json(); log(S + '-03 mail text', flat(j.Text).slice(0, 600)); }
}

if (step === 'unicode-job') {
  const name = 'fresh-정산 배치 ü';
  const { page, context } = await login('admin');
  await page.goto(`${BASE}/view/all/newJob`);
  await page.fill('#name', name);
  await page.locator('input[name=mode][value="hudson.model.FreeStyleProject"]').locator('xpath=..').click();
  await Promise.all([page.waitForNavigation(), page.click('#ok-button')]);
  await Promise.all([page.waitForNavigation(), page.click('button[name="Submit"]')]);
  log(S + '-04 unicode job url', page.url());
  await context.close();
  const r = await login('requester');
  await r.page.goto(page.url().replace(/configure$/, ''));
  await r.page.goto(r.page.url());
  const side = r.page.locator('#tasks a', { hasText: 'Request Run' }).first();
  await side.click(); await r.page.waitForLoadState('load');
  await r.page.fill('textarea[name="reason"]', 'unicode job name run');
  await r.page.locator('input[name="approvers"][value="approver-1"]').check({ force: true });
  await Promise.all([r.page.waitForLoadState('load'), r.page.click('button[name="Submit"]')]);
  const id = (r.page.url().match(/requests\/([^/]+)/) || [])[1];
  log(S + '-04 unicode request', id, flat(await text(r.page)).slice(0, 300));
  await shot(r.page, r.page.locator('#main-panel table').first(), `${S}-04-unicode-request`);
  await r.page.goto(`${BASE}/job/${encodeURIComponent(name)}/batch-control/activation`);
  await r.page.fill('textarea[name="reason"]', 'unicode activation');
  await r.page.locator('input[name="approvers"][value="approver-1"]').check({ force: true });
  await Promise.all([r.page.waitForLoadState('load'), r.page.locator('#main-panel form button[name="Submit"]').first().click()]);
  log(S + '-04 unicode activation', r.page.url(), flat(await text(r.page)).slice(0, 200));
  await r.context.close();
  const a = await login('approver-1');
  await a.page.goto(`${BASE}/batch-control/requests/${id}/`);
  await Promise.all([a.page.waitForLoadState('load'), a.page.locator('form[action$="/approve"] button').first().click()]);
  await sleep(4000);
  await a.page.goto(`${BASE}/batch-control/requests/${id}/`);
  log(S + '-04 unicode after approve', flat(await text(a.page)).slice(0, 400));
  await a.page.goto(`${BASE}/batch-control/changes/`);
  log(S + '-04 unicode records', (await a.page.locator('tbody tr', { hasText: '정산' }).allInnerTexts()).slice(0, 3).map(flat));
  await a.context.close();
  const csv = await api('admin', '/batch-control/history/requests.csv');
  log(S + '-04 csv unicode rows', csv.body.split('\n').filter((l) => l.includes('정산')).slice(0, 1));
}

if (step === 'rename-pending') {
  // a pending request whose job is renamed: what does the requester see and get told?
  const { page, context } = await login('requester');
  await page.goto(`${BASE}/job/fresh-up-src/`);
  await context.close();
  const r = await login('requester');
  await r.page.goto(`${BASE}/job/fresh-fail/batch-control/`);
  await r.page.fill('textarea[name="reason"]', 'pending when the job is renamed');
  await r.page.locator('input[name="approvers"][value="approver-1"]').check({ force: true });
  await Promise.all([r.page.waitForLoadState('load'), r.page.click('button[name="Submit"]')]);
  const id = (r.page.url().match(/requests\/([^/]+)/) || [])[1];
  await r.context.close();
  const a = await login('admin');
  await a.page.goto(`${BASE}/job/fresh-fail/confirm-rename`);
  await a.page.fill('input[name="newName"]', 'fresh-fail-renamed');
  await a.page.waitForTimeout(800);
  await Promise.all([a.page.waitForNavigation(), a.page.locator('#main-panel button[name="Submit"], #main-panel button.jenkins-button--primary').first().click()]);
  log(S + '-05 renamed ->', a.page.url());
  await a.context.close();
  const q = await login('requester');
  await q.page.goto(`${BASE}/batch-control/requests/${id}/`);
  log(S + '-05 requester view after rename', id, flat(await text(q.page)).slice(0, 600));
  await shot(q.page, q.page.locator('#main-panel table').first(), `${S}-05-invalidated`);
  await q.context.close();
  await sleep(2000);
  log(S + '-05 mails to requester about', id, (await mails('to:requester@e2e.local')).filter((m) => m.Subject.includes(id)).map((m) => m.Subject));
}

if (step === 'disabled-approve') {
  // approved but cannot be queued (job disabled): does it expire after approvedRunTimeoutMinutes (1)?
  const r = await login('requester');
  await r.page.goto(`${BASE}/job/fresh-token/batch-control/`);
  await r.page.fill('textarea[name="reason"]', 'approved while the job is disabled');
  await r.page.locator('input[name="approvers"][value="approver-1"]').check({ force: true });
  await Promise.all([r.page.waitForLoadState('load'), r.page.click('button[name="Submit"]')]);
  const id = (r.page.url().match(/requests\/([^/]+)/) || [])[1];
  await r.context.close();
  log('disable', (await api('admin', '/job/fresh-token/disable', { method: 'POST' })).status);
  const a = await login('approver-1');
  await a.page.goto(`${BASE}/batch-control/requests/${id}/`);
  await Promise.all([a.page.waitForLoadState('load'), a.page.locator('form[action$="/approve"] button').first().click()]);
  log(S + '-06 approve on disabled job', id, flat(await text(a.page)).slice(0, 700));
  await shot(a.page, '#main-panel', `${S}-06-approve-disabled-job`);
  await a.context.close();
  console.log(id);
}
await close();
