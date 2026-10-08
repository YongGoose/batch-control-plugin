// B1-09 / B6-02 / B15-05 kickoff: pendingTimeoutHours=1 through the UI, one request left pending.
import { login, close, shot, log, setGlobal, globalCfg, requestRun, BASE } from './lib.mjs';
const L = 'section-b.log';
const ad = await login('admin');
const r = await setGlobal(ad.page, { pendingTimeoutHours: 1 });
log(L, `B1-09 save pendingTimeoutHours=1 -> ${r.status} ${r.url}; config now ${JSON.stringify(await globalCfg())}`);
const rq = await login('requester');
const url = await requestRun(rq.page, '/job/batch-daily/', { reason: 'Left pending on purpose: must expire after 1 hour (B1-09).', approvers: ['approver-2'], params: { DATE: '2026-10-01' } });
log(L, `B1-09 pending request ${url} created at ${new Date().toISOString()}`);
await shot(rq.page, '#main-panel table', 'B1-09-1-pending', { pad: 8 });
await close();
