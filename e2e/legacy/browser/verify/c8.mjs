// C-08: Pipeline properties([authorizationMatrix(...)]) written by a build, under a CONFIGURE window.
import { login, close, shot, api, job, BASE, requestGrant, decide, changeRows, sleep, waitFor, clickBuildEntry, groovy } from '../lib.mjs';
import { row, ev, mainText } from '../audit/rec.mjs';
import { uiRevoke } from '../audit/restsubmit.mjs';
const J = 'batch-upstream';
const orig = (await api('admin', `/job/${J}/config.xml`, { raw: true })).text;
const auth = (await groovy(`def c = jenkins.security.QueueItemAuthenticatorConfiguration.get(); println c.authenticators.collect { it.class.simpleName + (it.respondsTo('getStrategyEnabledMap') ? ' ' + it.strategyEnabledMap : '') }`)).trim();
const { page } = await login('requester');
const g = await requestGrant(page, { scope: J, actions: ['CONFIGURE'], minutes: 15, reason: 'Verify C-08: pipeline writes its own authorization' }); await decide(g.url);
const x = orig.replace(/<script>[\s\S]*?<\/script>/, `<script>properties([authorizationMatrix(inheritanceStrategy: inheritingGlobal(), entries: [user(name: 'requester', permissions: ['Job/Configure', 'Job/Read'])])])\necho 'c08'</script>`);
const put = await api('requester', `/job/${J}/config.xml`, { method: 'POST', body: x, headers: { 'Content-Type': 'application/xml' } });
const n = (await job(J, 'nextBuildNumber')).nextBuildNumber;
await page.goto(`${BASE}/job/${J}/`); const sb = (await page.locator('#side-panel a').allInnerTexts()).map((t) => t.trim());
await clickBuildEntry(page, 'Build Now');
await waitFor(async () => { const j = await job(J, 'lastBuild[number,building]'); return j.lastBuild && j.lastBuild.number >= n && !j.lastBuild.building; }, { timeout: 120000 });
await sleep(3000);
const con = (await api('admin', `/job/${J}/${n}/consoleText`, { raw: true })).text.split('\n').filter((l) => /Running as|c08|ERROR|authorizationMatrix|Finished/.test(l)).slice(0, 6).join(' | ');
const entries = ((await api('admin', `/job/${J}/config.xml`, { raw: true })).text.match(/<permission>[^<]*requester[^<]*<\/permission>|<entry>[\s\S]*?requester[\s\S]*?<\/entry>/g) || []);
await uiRevoke(g.url.match(/(\d{8}-\d{6}-\w+)/)[1]);
const after = (await api('requester', `/job/${J}/configure`)).status;
const viol = (await changeRows(new RegExp(`,GRANT_VIOLATION,${J},`)))[0] || '';
const ad = await login('admin'); await ad.page.goto(`${BASE}/manage/`);
const mon = (await ad.page.locator('.jenkins-alert', { hasText: /build authenticator|SYSTEM/ }).count());
// restore the job as it was (admin)
await api('admin', `/job/${J}/config.xml`, { method: 'POST', body: orig, headers: { 'Content-Type': 'application/xml' } });
ev(`C-08 authenticators ${auth}; put ${put.status}; sidebar ${sb.filter((s) => /Build|Replay/.test(s))}; #${n} console ${con}; requester entries after the build ${entries.length}; configure after revoke ${after}; violation ${viol}; SYSTEM-builds monitor ${mon}`);
row('C-08', { roles: 'requester (CONFIGURE window on an uncontrolled Pipeline), admin', V: 'n.a.', G: `${after === 403 ? '✓' : '✗'} with Authorize Project configured (${auth}) the build ran "${con.slice(0, 120)}"; after the window was revoked the requester's configure answers ${after} (permanent entry kept by the build: ${entries.length ? 'yes' : 'no'})`, R: 'n.a.', C: `${viol || !entries.length ? '✓' : '✗'} ${viol ? 'GRANT_VIOLATION ' + viol.split(',').slice(6).join(',').slice(0, 100) : 'no entry to record'}`, E: '✓ text', note: 'LIMITATIONS 35: a build that runs as SYSTEM can still write a permanent authorization; the SYSTEM-builds warning covers the no-authenticator case (B8-08); job restored by admin' });
await close();
