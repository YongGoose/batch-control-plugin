import { login, close, shot, BASE, setGlobal } from '../lib.mjs';
import { row, ev, sidebar } from '../audit/rec.mjs';
const views = async (path, tag) => { const v = {}; for (const u of ['requester', 'nobc', 'reqonly', 'configurer', 'approver-1']) { const c = await login(u); await c.page.goto(BASE + path); v[u] = (await sidebar(c.page)).filter((x) => /Build|Rebuild|Request/.test(x)); if (u === 'requester' && tag) v.s = await shot(c.page, '#side-panel #tasks, #side-panel', tag, { pad: 8 }); await c.context.close(); } return v; };
const ad = await login('admin');
const both = await views('/job/batch-daily/', 'B1-04-requester-sidebar');
await setGlobal(ad.page, { changeControlEnabled: false });
const runOnly = await views('/job/batch-daily/', 'B1-02-requester-sidebar');
await setGlobal(ad.page, { changeControlEnabled: true });
const plug = {}; const rq = await login('requester');
for (const J of ['batch-cbn', 'batch-nag', 'batch-token', 'batch-rebuild', 'batch-lock']) { await rq.page.goto(`${BASE}/job/${J}/`); plug[J] = (await sidebar(rq.page)).filter((x) => /Build|Rebuild|Request/.test(x)); }
const s22 = await shot(rq.page, '#side-panel #tasks, #side-panel', 'B5-22-batch-lock-sidebar', { pad: 8 });
ev(`V10 both ${JSON.stringify(both)} runOnly ${JSON.stringify(runOnly)} plugin ${JSON.stringify(plug)}`);
const reqOnly = (v) => v.requester.includes('Request Run') && !v.reqonly.includes('Request Run') && !v.configurer.includes('Request Run') && !v.nobc.includes('Request Run');
const noRebuild = (v) => !Object.values(v).some((x) => Array.isArray(x) && x.some((y) => /Rebuild/.test(y)));
row('B1-04', { roles: 'requester, nobc, reqonly, configurer, approver-1', V: `${reqOnly(both) && noRebuild(both) && both.requester.includes('Request Change Permission') ? '✓' : '✗'} both switches on: requester [${both.requester}], reqonly [${both.reqonly}], configurer [${both.configurer}], nobc [${both.nobc}]; Rebuild Last gone; Direct Build stays for Build holders (core-build-link ruling)`, G: '✓ both UIs present', R: 'n.a.', C: 'n.a.', E: both.s ? '✓ B1-04-requester-sidebar' : '✗' });
row('B1-02', { roles: 'requester, nobc, reqonly, configurer, approver-1', V: `${reqOnly(runOnly) && !runOnly.requester.includes('Request Change Permission') && noRebuild(runOnly) ? '✓' : '✗'} run control only: requester [${runOnly.requester}] (Request Run, no change-control UI), reqonly [${runOnly.reqonly}], nobc [${runOnly.nobc}]; Direct Build stays (ruling)`, G: '✓ Direct Build refused with the Approval required page (B5-01 this round); CONFIG_TOGGLE records as before', R: '✓ plain-words refusal page with the Request Run link (B5-01)', C: '✓ CONFIG_TOGGLE per switch change', E: runOnly.s ? '✓ B1-02-requester-sidebar' : '✗' });
row('B5-22', { roles: 'requester', V: `${Object.values(plug).every((v) => v.includes('Request Run') && !v.some((x) => /Rebuild/.test(x))) ? '✓' : '✗'} ${Object.entries(plug).map(([k, v]) => `${k} [${v}]`).join('; ')} - Request Run on all, Rebuild Last gone; Direct Build stays (ruling)`, G: '✓ Request Run on every plugin-modified job', R: 'n.a.', C: 'n.a.', E: s22 ? '✓ B5-22-batch-lock-sidebar' : '✗' });
await close();
