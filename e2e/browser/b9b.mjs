import { login, close, shot, api, BASE, log, changeRows, requestGrant, decide, sleep } from './lib.mjs';
const L = 'section-b.log';
const rq = await login('requester'); const p = rq.page;
const cfg = async (path) => (await p.goto(`${BASE}${path}configure`)).status();
const g = await requestGrant(p, { type: 'FOLDER', scope: 'team', actions: ['CREATE'], minutes: 15, reason: 'Only a CREATE window (B9-04/06/07 rerun).' });
await decide(g.url);
await p.goto(`${BASE}/job/team/newJob`); await p.fill('#name', 'app-g3');
await p.locator('label:has-text("Freestyle project")').first().click();
await Promise.all([p.waitForNavigation(), p.locator('#ok-button').click()]);
await Promise.all([p.waitForNavigation(), p.locator('button[name="Submit"]').click()]);
const perms = ((await api('admin', '/job/team/job/app-g3/config.xml', { raw: true })).text.match(/<permission>[^<]+<\/permission>/g) || []);
log(L, `B9-04 rerun (app-g3): team/app-g3 created under only a CREATE window: item permissions ${JSON.stringify(perms)}; requester configure (D-35c) -> ${await cfg('/job/team/job/app-g3/')}; team/app-1 configure -> ${await cfg('/job/team/job/app-1/')}`);
// B9-06 copy
await p.goto(`${BASE}/job/team/newJob`); await p.fill('#name', 'app-copy');
await p.locator('#duplicate-job').click(); await p.waitForTimeout(400); await p.locator('#from').fill('batch-daily'); await p.waitForTimeout(1000);
const [r6] = await Promise.all([p.waitForNavigation().catch(() => null), p.locator('#ok-button').click()]);
const t6 = (await p.locator('#main-panel, body').first().innerText()).replace(/\s+/g, ' ').slice(0, 160);
if (p.url().includes('/configure')) await Promise.all([p.waitForNavigation(), p.locator('button[name="Submit"]').click()]);
await sleep(1000);
const e6 = ((await api('admin', '/job/team/job/app-copy/config.xml', { raw: true })).text.match(/<permission>[^<]+<\/permission>/g) || []);
const v6 = (await changeRows(/GRANT_VIOLATION,team\/app-copy/));
log(L, `B9-06 rerun: copy batch-daily (auditor Read) -> ${r6 && r6.status()} ${p.url().replace(BASE, '')} "${t6}"; permissions on the copy ${JSON.stringify(e6)}; GRANT_VIOLATION ${v6[0] || 'none'}`);
// B9-07 admin deletes and recreates team/app-g3 while the window is still open
const ad = await login('admin');
await ad.page.goto(`${BASE}/job/team/job/app-g3/`);
ad.page.once('dialog', (d) => d.accept());
await ad.page.locator('#side-panel a:has-text("Delete Project")').click(); await ad.page.waitForTimeout(700);
{ const ok = ad.page.locator('dialog[open] button[data-id="ok"]').first(); if (await ok.count()) await Promise.all([ad.page.waitForNavigation().catch(() => null), ok.click()]); }
await ad.page.goto(`${BASE}/job/team/newJob`); await ad.page.fill('#name', 'app-g3');
await ad.page.locator('label:has-text("Freestyle project")').first().click();
await Promise.all([ad.page.waitForNavigation(), ad.page.locator('#ok-button').click()]);
await Promise.all([ad.page.waitForNavigation(), ad.page.locator('button[name="Submit"]').click()]);
log(L, `B9-07 rerun: admin re-created team/app-g3 while requester's CREATE window is open: requester configure -> ${await cfg('/job/team/job/app-g3/')}`);
await close();
