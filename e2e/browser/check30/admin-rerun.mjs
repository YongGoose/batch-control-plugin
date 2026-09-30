// Final check on 30e9252 (D-58c amended): an administrator's re-run of a marked run is allowed and is itself marked.
import { login, close, BASE, shot, job, api, waitFor, sleep } from '../lib.mjs';
import { ev, mainText } from '../audit/rec.mjs';
const J = 'batch-upstream', SRC = process.argv[2] || '15';
const n0 = (await job(J, 'nextBuildNumber')).nextBuildNumber;
const { page } = await login('admin');
await page.goto(`${BASE}/job/${J}/${SRC}/replay/`);
await Promise.all([page.waitForNavigation({ waitUntil: 'load' }).catch(() => null), page.locator('#main-panel button[name="Submit"], #main-panel button:has-text("Run")').first().click()]);
await waitFor(async () => { const j = await job(J, 'lastBuild[number,building]'); return j.lastBuild && j.lastBuild.number >= n0 && !j.lastBuild.building; }, { timeout: 120000 });
await sleep(2000);
const b = (await job(J, 'lastBuild[number,result]')).lastBuild;
const rows = (await api('admin', '/batch-control/history/changes.csv')).text.split('\n').filter((l) => /batch-upstream/.test(l)).slice(0, 2);
ev(`CHECK30 admin replay of #${SRC}: new run #${b.number} ${b.result} (next ${n0}); records ${JSON.stringify(rows)}`);
// the requester's Pipeline Rebuild of the admin's run is refused as well (the run is marked)
const r = await login('requester');
await r.page.goto(`${BASE}/job/${J}/${b.number}/replay/`);
await Promise.all([r.page.waitForNavigation({ waitUntil: 'load' }).catch(() => null), r.page.locator('#main-panel button[name="Submit"], #main-panel button:has-text("Run")').first().click()]);
await r.page.waitForTimeout(1200);
const t = (await mainText(r.page)).slice(0, 250);
await shot(r.page, '#main-panel', 'C-08-24-rerun-of-admin-rerun-refused', { pad: 8 });
ev(`CHECK30 requester Pipeline Rebuild of admin's #${b.number}: ${t}; next ${(await job(J, 'nextBuildNumber')).nextBuildNumber}`);
await close();
