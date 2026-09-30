// B16 retention + B17 restart durability + B8-L4 legacy request. Usage: node b17.mjs before | after
import { login, close, shot, api, job, BASE, log, requestRun, decide, requestGrant, sleep, waitFor, setGlobal, globalCfg, groovy, changeRows, queue } from './lib.mjs';
import { execSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { OUT } from './lib.mjs';
const L = 'section-b.log';
const STATE = path.join(OUT, 'b17-state.json');
const H = '/var/jenkins_home/batch-control';
const sh = (c) => execSync(`docker exec batch-control-e2e sh -c '${c}'`).toString().trim();
const rowOf = async (id) => (await api('admin', '/batch-control/history/requests.csv')).text.split('\n').find((l) => l.startsWith(id));

if (process.argv[2] === 'before') {
  // ---- B16 arrange (store files written directly: console-equivalent ARRANGE)
  sh(`cd ${H} && head -3 runs/2026-09.jsonl | sed "s/2026-09-29T/2026-06-15T/g" > runs/2026-06.jsonl && head -3 changes/2026-09.jsonl | sed "s/2026-09-29T/2026-06-15T/g" > changes/2026-06.jsonl && ls runs changes`);
  const june = Date.UTC(2026, 5, 15, 3, 0, 0);
  const reqXml = (id, status, extra = '') => `<io.jenkins.plugins.batchcontrol.model.RunRequest plugin="batch-control@999999-SNAPSHOT"><id>${id}</id><jobFullName>batch-pipeline</jobFullName><parameters class="linked-hash-map"/><reason>Old ${status} request (B16-02).</reason><requester>requester</requester><approvers><string>approver-1</string></approvers><expiringNotified>true</expiringNotified><status>${status}</status><createdAtMillis>${june}</createdAtMillis>${extra}<selfApproved>false</selfApproved><approverChanges/></io.jenkins.plugins.batchcontrol.model.RunRequest>`;
  fs.writeFileSync('/tmp/b16-closed.xml', reqXml('20260615-120000-oldcls', 'CANCELLED', `<decidedAtMillis>${june + 60000}</decidedAtMillis>`));
  // B8-L4: a request saved before approver sets existed, with a single <approver>
  const now = Date.now();
  fs.writeFileSync('/tmp/b8-l4.xml', `<io.jenkins.plugins.batchcontrol.model.RunRequest plugin="batch-control@999999-SNAPSHOT"><id>20260929-230000-legacy</id><jobFullName>batch-pipeline</jobFullName><parameters class="linked-hash-map"/><reason>Legacy single-approver request (B8-L4).</reason><requester>requester</requester><approver>approver-1</approver><status>PENDING</status><createdAtMillis>${now}</createdAtMillis><selfApproved>false</selfApproved><approverChanges/></io.jenkins.plugins.batchcontrol.model.RunRequest>`);
  execSync(`docker cp /tmp/b16-closed.xml batch-control-e2e:${H}/requests/run/20260615-120000-oldcls.xml && docker cp /tmp/b8-l4.xml batch-control-e2e:${H}/requests/run/20260929-230000-legacy.xml && docker exec -u root batch-control-e2e chown jenkins:jenkins ${H}/requests/run/20260615-120000-oldcls.xml ${H}/requests/run/20260929-230000-legacy.xml`);
  // ---- B17 states through the UI
  const rq = await login('requester');
  const pending = await requestRun(rq.page, '/job/batch-pipeline/', { reason: 'Pending across a restart (B17-01).', approvers: ['approver-1'] });
  const g15 = await requestGrant(rq.page, { scope: 'batch-pipeline', actions: ['CONFIGURE'], minutes: 15, reason: 'Active across a restart (B17-03).' }); await decide(g15.url);
  const g1 = await requestGrant(rq.page, { scope: 'team/app-1', actions: ['CONFIGURE'], minutes: 1, reason: 'Expires during the downtime (B17-04).' }); await decide(g1.url);
  // B17-02: quiet down, then approve: queued but not started
  const ad = await login('admin');
  await ad.page.goto(`${BASE}/manage/`);
  await ad.page.locator('a:has-text("Prepare for Shutdown"), button:has-text("Prepare for Shutdown")').first().click(); await ad.page.waitForTimeout(1000);
  const ok = ad.page.locator('dialog[open] button[data-id="ok"], button:has-text("Yes"), button[name="Submit"]:visible').first(); if (await ok.count()) await ok.click().catch(() => {});
  await ad.page.waitForTimeout(1500);
  const quiet = (await groovy('println jenkins.model.Jenkins.get().isQuietingDown()')).trim();
  const approved = await requestRun(rq.page, '/job/batch-daily/', { reason: 'Approved while Jenkins prepares for shutdown (B17-02).', approvers: ['approver-1'], params: { DATE: '2026-10-05' } });
  await decide(approved);
  await sleep(3000);
  const q = (await queue()).filter((i) => i.task && i.task.name === 'batch-daily');
  const counts = { runs: (await api('admin', '/batch-control/history/runs.csv')).text.split('\n').length, changes: (await changeRows()).length, incidents: (await api('admin', '/batch-control/history/incidents.csv')).text.split('\n').length };
  const state = { pending, approved, g15: g15.url, g1: g1.url, nextDaily: (await job('batch-daily')).nextBuildNumber, counts, cfg: await globalCfg(), strategy: (await groovy('println jenkins.model.Jenkins.get().authorizationStrategy.class.simpleName')).trim(), cfgBefore: (await api('admin', '/job/batch-daily/config.xml', { raw: true })).text.match(/<io\.jenkins\.plugins\.batchcontrol[\s\S]*?BatchControlJobProperty>/)?.[0] };
  fs.writeFileSync(STATE, JSON.stringify(state, null, 1));
  await rq.page.goto(`${BASE}/batch-control/grants/`);
  await shot(rq.page, rq.page.locator('table:has(th:has-text("Expires"))').first(), 'B17-03-1-before-restart', { pad: 8 });
  log(L, `B17 before restart: quietingDown=${quiet}; approved ${approved.replace(BASE, '')} status ${(await rowOf(approved.split('/requests/')[1].replace('/', '')))?.split(',')[6]} queue ${JSON.stringify(q)}; state ${JSON.stringify({ ...state, cfg: undefined, cfgBefore: !!state.cfgBefore })}`);
  await close();
} else {
  const st = JSON.parse(fs.readFileSync(STATE, 'utf8'));
  const id = (u) => u.split('/requests/')[1].replace('/', '');
  await sleep(10000);
  const n1 = (await job('batch-daily')).nextBuildNumber;
  await waitFor(async () => (await job('batch-daily', 'nextBuildNumber,lastBuild[building]')).nextBuildNumber > st.nextDaily, { timeout: 90000 });
  await sleep(15000);
  const j = await job('batch-daily', 'nextBuildNumber,builds[number,result,actions[causes[shortDescription]]]');
  const fromApproved = j.builds.filter((b) => (b.actions.find((a) => a && a.causes) || { causes: [] }).causes.some((c) => c.shortDescription.includes(id(st.approved))));
  log(L, `B17-02 approved-and-queued across the restart: batch-daily next ${st.nextDaily}->${j.nextBuildNumber}; builds of request ${id(st.approved)}: ${fromApproved.map((b) => '#' + b.number + ' ' + b.result).join(', ')}; request ${(await rowOf(id(st.approved)))?.split(',')[6]}`);
  const rq = await login('requester');
  await rq.page.goto(st.pending);
  const p1 = (await rq.page.locator('#main-panel table').first().innerText()).replace(/\s+/g, ' ');
  const a1 = await login('approver-1');
  await a1.page.goto(st.pending);
  const f1 = await a1.page.locator('form[name="approve"]').count();
  log(L, `B17-01 pending request after restart: "${p1.slice(0, 120)}"; approver-1 decision form ${f1}`);
  await rq.page.goto(`${BASE}/batch-control/grants/`);
  const act = rq.page.locator('table:has(th:has-text("Expires")) tbody tr');
  const rows = (await act.allInnerTexts()).map((t) => t.replace(/\s+/g, ' '));
  await shot(rq.page, rq.page.locator('table:has(th:has-text("Expires"))').first(), 'B17-03-2-after-restart', { pad: 8 });
  const c3 = (await rq.page.goto(`${BASE}/job/batch-pipeline/configure`)).status();
  const c4 = (await rq.page.goto(`${BASE}/job/team/job/app-1/configure`)).status();
  log(L, `B17-03/04 after restart: active rows ${JSON.stringify(rows)}; batch-pipeline configure (15-min window) -> ${c3}; team/app-1 configure (1-min window, expired during downtime) -> ${c4}`);
  const cfg = await globalCfg();
  const strategy = (await groovy('println jenkins.model.Jenkins.get().authorizationStrategy.class.simpleName')).trim();
  const prop = (await api('admin', '/job/batch-daily/config.xml', { raw: true })).text.match(/<io\.jenkins\.plugins\.batchcontrol[\s\S]*?BatchControlJobProperty>/)?.[0];
  log(L, `B17-05 global config unchanged ${JSON.stringify(cfg) === JSON.stringify(st.cfg)}; strategy ${strategy} (was ${st.strategy}); batch-daily property unchanged ${prop === st.cfgBefore}`);
  const counts = { runs: (await api('admin', '/batch-control/history/runs.csv')).text.split('\n').length, changes: (await changeRows()).length, incidents: (await api('admin', '/batch-control/history/incidents.csv')).text.split('\n').length };
  log(L, `B17-06 records before ${JSON.stringify(st.counts)} after ${JSON.stringify(counts)} (runs grow with cron/the queued run; nothing lost)`);
  // B8-L4 legacy single-approver request
  await a1.page.goto(`${BASE}/batch-control/requests/20260929-230000-legacy/`);
  const l4 = (await a1.page.locator('#main-panel table').first().innerText().catch(() => 'NOT FOUND')).replace(/\s+/g, ' ');
  const f4 = await a1.page.locator('form[name="approve"]').count();
  await shot(a1.page, '#main-panel table', 'B8-L4', { pad: 8 });
  if (f4) { await a1.page.fill('form[name="reject"] textarea[name="comment"]', 'legacy request decided'); await Promise.all([a1.page.waitForLoadState('load'), a1.page.locator('form[name="reject"] button').first().click()]); }
  log(L, `B8-L4 legacy single-approver request: "${l4.slice(0, 200)}"; approver-1 decision form ${f4}; after reject ${(await rowOf('20260929-230000-legacy'))?.split(',').slice(5, 7).join(' ')}`);
  // B16 retention: 1 month in the UI, then trigger the periodic work (console ARRANGE of the timing only)
  const ad = await login('admin');
  await setGlobal(ad.page, { retentionMonths: 1 });
  const before = { files: sh(`cd ${H} && ls runs changes | tr "\\n" " "`), old: sh(`ls ${H}/requests/run | grep -c 20260615 || true`) };
  const cronBefore = (await job('batch-cron')).nextBuildNumber;
  log(L, `B16 trigger: ${(await groovy('io.jenkins.plugins.batchcontrol.ops.RetentionPeriodicWork.all().get(io.jenkins.plugins.batchcontrol.ops.RetentionPeriodicWork).doRun(); println "retention run done"')).split('\n')[0]}`);
  await sleep(3000);
  const after = { files: sh(`cd ${H} && ls runs changes | tr "\\n" " "`), old: sh(`ls ${H}/requests/run | grep -c 20260615 || true`), pendingStill: sh(`ls ${H}/requests/run | grep -c ${id(st.pending)} || true`) };
  const ret = (await changeRows(/,RETENTION,/)).slice(0, 3);
  log(L, `B16-01 retentionMonths=1: store before "${before.files}" after "${after.files}"; RETENTION records ${ret.join(' || ')}`);
  log(L, `B16-02 old closed request files ${before.old}->${after.old}; open (current) request kept ${after.pendingStill}`);
  const codes = [];
  for (const p of ['/batch-control/history/', '/batch-control/dashboard/', '/batch-control/changes/', '/batch-control/changes/?month=2026-06']) codes.push(`${p}=${(await ad.page.goto(BASE + p)).status()}`);
  await ad.page.goto(`${BASE}/batch-control/changes/`);
  const rr = ad.page.locator('#main-panel tr:has-text("RETENTION")').first();
  if (await rr.count()) await shot(ad.page, rr, 'B16-01', { pad: 8 });
  await sleep(65000);
  log(L, `B16-01 screens after retention ${codes.join(' ')}; B16-03 batch-cron kept starting: next ${cronBefore}->${(await job('batch-cron')).nextBuildNumber}`);
  await setGlobal(ad.page, { retentionMonths: 24 });
  await close();
}
