import { login, close, api, BASE, log, changeRows, requestGrant, decide, sleep } from './lib.mjs';
const L = 'section-b.log';
const rq = await login('requester'); const p = rq.page;
for (const [type, scope, a, r] of [['FOLDER', 'team', 'CREATE', 'Copy target (B9-06).'], ['JOB', 'batch-daily', 'CONFIGURE', 'Copy source (B9-06).']]) {
  const g = await requestGrant(p, { type, scope, actions: [a], minutes: 15, reason: r }); await decide(g.url);
}
await p.goto(`${BASE}/job/team/newJob`); await p.fill('#name', 'app-copy');
await p.locator('#duplicate-job').click(); await p.waitForTimeout(400); await p.locator('#from').fill('batch-daily'); await p.waitForTimeout(1000);
const [r6] = await Promise.all([p.waitForNavigation().catch(() => null), p.locator('#ok-button').click()]);
if (p.url().includes('/configure')) await Promise.all([p.waitForNavigation(), p.locator('button[name="Submit"]').click()]);
await sleep(1000);
const src = ((await api('admin', '/job/batch-daily/config.xml', { raw: true })).text.match(/<permission>[^<]+<\/permission>/g) || []);
const e6 = ((await api('admin', '/job/team/job/app-copy/config.xml', { raw: true })).text.match(/<permission>[^<]+<\/permission>/g) || []);
const v6 = (await changeRows(/GRANT_VIOLATION,team\/app-copy/));
log(L, `B9-06 copy batch-daily -> team/app-copy with CREATE(team)+CONFIGURE(batch-daily) windows: ${r6 && r6.status()}; source permissions ${JSON.stringify(src)}; copy permissions ${JSON.stringify(e6)}; GRANT_VIOLATION ${v6[0] || 'none'}`);
await close();
