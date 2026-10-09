// B9 self-grant guard (D-35b/c).
import { login, close, shot, api, BASE, log, changeRows, requestGrant, decide, sleep, errText } from './lib.mjs';
const L = 'section-b.log';
const viol = async () => (await changeRows(/GRANT_VIOLATION/));
const entriesOf = async (path) => ((await api('admin', `${path}config.xml`, { raw: true })).text.match(/<permission>[^<]+<\/permission>|<entry>[\s\S]*?<\/entry>/g) || []).filter((e) => /requester|USER:|GROUP:/.test(e)).map((e) => e.replace(/\s+/g, ''));

const rq = await login('requester'); const p = rq.page;
async function addSelfInMatrix(configUrl, label = 'Enable project-based security') {
  await p.goto(`${BASE}${configUrl}`); await p.waitForTimeout(1500);
  const l = p.locator(`label:has-text("${label}")`).first();
  const on = await l.evaluate((x) => x.parentElement.querySelector('input[type=checkbox]').checked);
  if (!on) { await l.click(); await p.waitForTimeout(700); }
  let card = p.locator('.mas-card[data-sid="requester"]:visible').first();
  if (!(await card.count())) {
    await p.locator('button.matrix-auth-add-button:has-text("Add user"):visible').first().click(); await p.waitForTimeout(500);
    await p.locator('dialog[open] input').first().fill('requester');
    await p.locator('dialog[open] button[data-id="ok"]').click(); await p.waitForTimeout(800);
    card = p.locator('.mas-card[data-sid="requester"]:visible').first();
  }
  if (await card.locator('.mas-card__body--collapsed').count()) { await card.locator('.mas-card__toggle, .mas-card__header').first().click(); await p.waitForTimeout(500); }
  for (const perm of ['hudson.model.Item.Configure', 'hudson.model.Item.Delete']) {
    const box = card.locator(`input[name="[${perm}]"]`).first();
    if (!(await box.isChecked())) await card.locator(`label[data-permission-id="${perm}"]`).click();
  }
  await shot(p, card, `B9-${configUrl.includes('team') ? '03' : '01'}-1-adding-self`, { pad: 8 });
  const [r] = await Promise.all([p.waitForNavigation(), p.locator('button[name="Submit"]').click()]);
  return r.status();
}

// B9-01 UI, CONFIGURE window on batch-pipeline
let g = await requestGrant(p, { scope: 'batch-pipeline', actions: ['CONFIGURE'], reason: 'Self-grant guard via the form (B9-01).' });
await decide(g.url);
let v0 = (await viol()).length;
const s1 = await addSelfInMatrix('/job/batch-pipeline/configure');
await p.goto(`${BASE}/job/batch-pipeline/configure`); await p.waitForTimeout(1500);
const back = await p.locator('.mas-card[data-sid="requester"]').count();
const v1 = await viol();
await shot(p, p.locator('label:has-text("Enable project-based security")').first().locator('xpath=ancestor::*[contains(@class,"optionalBlock-container") or contains(@class,"jenkins-form-item")][1]'), 'B9-01-2-reopened', { pad: 8 });
log(L, `B9-01 requester adds himself (Configure+Delete) in batch-pipeline's project matrix, Save -> ${s1}; on reopen requester entry present: ${back}; config.xml requester entries ${JSON.stringify(await entriesOf('/job/batch-pipeline/'))}; GRANT_VIOLATION +${v1.length - v0}: ${v1[0]}`);
// B9-02 same via POST config.xml
const x = (await api('requester', '/job/batch-pipeline/config.xml', { raw: true })).text;
const injected = x.includes('hudson.security.AuthorizationMatrixProperty')
  ? x.replace(/(<hudson\.security\.AuthorizationMatrixProperty>[\s\S]*?)(<\/hudson\.security\.AuthorizationMatrixProperty>)/, '$1<permission>USER:hudson.model.Item.Configure:requester</permission>$2')
  : x.replace('<properties>', '<properties><hudson.security.AuthorizationMatrixProperty><inheritanceStrategy class="org.jenkinsci.plugins.matrixauth.inheritance.InheritParentStrategy"/><permission>USER:hudson.model.Item.Configure:requester</permission></hudson.security.AuthorizationMatrixProperty>');
v0 = (await viol()).length;
const pr = await api('requester', '/job/batch-pipeline/config.xml', { method: 'POST', body: injected, headers: { 'Content-Type': 'application/xml' } });
await sleep(1500);
log(L, `B9-02 POST config.xml with a requester Configure entry -> ${pr.status}; requester entries now ${JSON.stringify(await entriesOf('/job/batch-pipeline/'))}; GRANT_VIOLATION +${(await viol()).length - v0}`);
// B9-03 inherited folder window editing team/app-1
g = await requestGrant(p, { scope: 'team', actions: ['CONFIGURE'], reason: 'Self-grant guard via a folder window (B9-03).' });
await decide(g.url);
v0 = (await viol()).length;
const s3 = await addSelfInMatrix('/job/team/job/app-1/configure');
await sleep(1000);
log(L, `B9-03 folder window, requester adds himself on team/app-1 -> ${s3}; requester entries now ${JSON.stringify(await entriesOf('/job/team/job/app-1/'))}; GRANT_VIOLATION +${(await viol()).length - v0}`);
// B9-04 / B9-05 / B9-06 under a CREATE window on team/
g = await requestGrant(p, { scope: 'team', actions: ['CREATE'], minutes: 1, reason: 'Create under the guard (B9-04..06).' });
await decide(g.url);
await p.goto(`${BASE}/job/team/newJob`);
await p.fill('#name', 'app-guard');
await p.locator('label:has-text("Freestyle project")').first().click();
await Promise.all([p.waitForNavigation(), p.locator('#ok-button').click()]);
await Promise.all([p.waitForNavigation(), p.locator('button[name="Submit"]').click()]);
const e4 = await entriesOf('/job/team/job/app-guard/');
const c4 = (await p.goto(`${BASE}/job/team/job/app-guard/configure`)).status();
v0 = (await viol()).length;
const payload = `<?xml version='1.1' encoding='UTF-8'?><project><properties><hudson.security.AuthorizationMatrixProperty><inheritanceStrategy class="org.jenkinsci.plugins.matrixauth.inheritance.InheritParentStrategy"/><permission>USER:hudson.model.Item.Configure:requester</permission><permission>USER:hudson.model.Item.Read:requester</permission></hudson.security.AuthorizationMatrixProperty></properties><builders/></project>`;
const r5 = await api('requester', '/job/team/createItem?name=app-payload', { method: 'POST', body: payload, headers: { 'Content-Type': 'application/xml' } });
await sleep(1000);
const e5 = await entriesOf('/job/team/job/app-payload/');
const v5 = (await viol()).length - v0;
// B9-06 copy from batch-daily (carries auditor Read)
await p.goto(`${BASE}/job/team/newJob`);
await p.fill('#name', 'app-copy');
await p.locator('#duplicate-job label, label:has-text("Duplicate an existing item"), label:has-text("Copy from")').first().click().catch(() => {});
await p.locator('#from').fill('batch-daily'); await p.waitForTimeout(800);
const [r6] = await Promise.all([p.waitForNavigation().catch(() => null), p.locator('#ok-button').click()]);
if (p.url().includes('/configure')) await Promise.all([p.waitForNavigation(), p.locator('button[name="Submit"]').click()]);
await sleep(1000);
const e6 = ((await api('admin', '/job/team/job/app-copy/config.xml', { raw: true })).text.match(/<permission>[^<]+<\/permission>/g) || []);
const v6 = (await viol()).length - v0 - v5;
log(L, `B9-04 created team/app-guard under a CREATE window: requester entries ${JSON.stringify(e4)}; configure during the window -> ${c4}`);
log(L, `B9-05 createItem with a payload granting requester Configure -> ${r5.status}; entries on team/app-payload ${JSON.stringify(e5)}; GRANT_VIOLATION +${v5}`);
log(L, `B9-06 copy of batch-daily as team/app-copy -> ${r6 && r6.status()}; permissions on the copy ${JSON.stringify(e6)}; GRANT_VIOLATION +${v6}`);
await sleep(65000);
log(L, `B9-04 after the 1-minute window: requester team/app-guard/configure -> ${(await p.goto(`${BASE}/job/team/job/app-guard/configure`)).status()}`);
// B9-07 delete + recreate by admin: requester (window closed) has no access
const ad = await login('admin');
await ad.page.goto(`${BASE}/job/team/job/app-guard/`);
ad.page.once('dialog', (d) => d.accept());
await ad.page.locator('#side-panel a:has-text("Delete Project")').click(); await ad.page.waitForTimeout(700);
{ const ok = ad.page.locator('dialog[open] button[data-id="ok"]').first(); if (await ok.count()) await Promise.all([ad.page.waitForNavigation().catch(() => null), ok.click()]); }
await ad.page.goto(`${BASE}/job/team/newJob`); await ad.page.fill('#name', 'app-guard');
await ad.page.locator('label:has-text("Freestyle project")').first().click();
await Promise.all([ad.page.waitForNavigation(), ad.page.locator('#ok-button').click()]);
await Promise.all([ad.page.waitForNavigation(), ad.page.locator('button[name="Submit"]').click()]);
log(L, `B9-07 admin deleted and re-created team/app-guard: requester configure -> ${(await p.goto(`${BASE}/job/team/job/app-guard/configure`)).status()}`);
// B9-08 admin edits the property: not reverted, no record
v0 = (await viol()).length;
await ad.page.goto(`${BASE}/job/batch-pipeline/configure`); await ad.page.waitForTimeout(1500);
const l = ad.page.locator('label:has-text("Enable project-based security")').first();
if (!(await l.evaluate((x2) => x2.parentElement.querySelector('input[type=checkbox]').checked))) { await l.click(); await ad.page.waitForTimeout(600); }
await ad.page.locator('button.matrix-auth-add-button:has-text("Add user"):visible').first().click(); await ad.page.waitForTimeout(500);
await ad.page.locator('dialog[open] input').first().fill('auditor');
await ad.page.locator('dialog[open] button[data-id="ok"]').click(); await ad.page.waitForTimeout(800);
const card = ad.page.locator('.mas-card[data-sid="auditor"]:visible').first();
if (await card.locator('.mas-card__body--collapsed').count()) { await card.locator('.mas-card__toggle, .mas-card__header').first().click(); await ad.page.waitForTimeout(500); }
await card.locator('label[data-permission-id="hudson.model.Item.Read"]').click();
await Promise.all([ad.page.waitForNavigation(), ad.page.locator('button[name="Submit"]').click()]);
await sleep(1000);
const kept = ((await api('admin', '/job/batch-pipeline/config.xml', { raw: true })).text.match(/<permission>USER:hudson\.model\.Item\.Read:auditor<\/permission>/g) || []).length;
log(L, `B9-08 admin adds auditor Read on batch-pipeline: kept ${kept}; GRANT_VIOLATION +${(await viol()).length - v0}`);
await ad.page.goto(`${BASE}/batch-control/changes/`);
await shot(ad.page, ad.page.locator('#main-panel tr:has-text("GRANT_VIOLATION")').first(), 'B9-01-3-record', { pad: 8 });
await close();
