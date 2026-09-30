// C-27 (console): a consumed approval marker presented again, on another job and on its own job.
import { api, job, groovy, sleep, changeRows } from '../lib.mjs';
import { row, ev } from '../audit/rec.mjs';
const csv = (await api('admin', '/batch-control/history/requests.csv')).text.split('\n');
const used = csv.find((l) => /^\d{8}-\d{6}-\w+,batch-daily,/.test(l) && /,EXECUTED,/.test(l));
const id = used.split(',')[0];
const before = { lock: (await job('batch-lock', 'nextBuildNumber')).nextBuildNumber, daily: (await job('batch-daily', 'nextBuildNumber')).nextBuildNumber };
const r0 = (await changeRows(/,MARKER_REUSE_BLOCKED,/)).length;
const submit = (name) => groovy(`import hudson.model.*
def j = jenkins.model.Jenkins.get().getItemByFullName('${name}')
def f = j.scheduleBuild2(0, new CauseAction(new Cause.UserIdCause('requester')), new io.jenkins.plugins.batchcontrol.queue.ApprovedRunAction('${id}'))
println(f == null ? 'refused at submission' : 'queued')`);
const s1 = await submit('batch-lock');
const s2 = await submit('batch-daily');
await sleep(8000);
const after = { lock: (await job('batch-lock', 'nextBuildNumber')).nextBuildNumber, daily: (await job('batch-daily', 'nextBuildNumber')).nextBuildNumber };
const recs = await changeRows(/,MARKER_REUSE_BLOCKED,/);
const nw = recs.slice(0, recs.length - r0);
ev(`C-27 marker ${id}: batch-lock ${s1}, batch-daily ${s2}; builds ${JSON.stringify(before)} -> ${JSON.stringify(after)}; new records ${nw.join(' || ')}`);
row('C-27', {
  roles: 'script console standing in for another plugin (console), admin (records)',
  V: 'n.a.',
  G: `${after.lock === before.lock && after.daily === before.daily ? '✓' : '✗'} the consumed marker of request ${id} (batch-daily, EXECUTED) presented on batch-lock and again on batch-daily: ${s1} / ${s2}; no build (${JSON.stringify(before)} -> ${JSON.stringify(after)})`,
  R: 'n.a. (no user; a plugin submission)',
  C: `${nw.length >= 1 ? '✓' : '✗'} ${nw.length} MARKER_REUSE_BLOCKED: ${nw.map((l) => l.split(',').slice(2, 4).join(',') + ' "' + l.split(',').slice(6).join(',').slice(0, 90) + '"').join(' || ')}`,
  E: '✓ text (console output and records in audit.log)',
});
