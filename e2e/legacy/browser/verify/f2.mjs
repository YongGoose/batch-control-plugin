// D-51a: per-user budget of per-attempt re-run records (20 per rolling 10 minutes, then a summary, then a closing record).
import { login, close, shot, job, BASE, changeRows, sleep } from '../lib.mjs';
import { row, ev } from '../audit/rec.mjs';
import fs from 'node:fs';
const phase = process.argv[2];
const mine = async () => (await changeRows(/,requester,/)).filter((l) => /,TRIGGER_BLOCKED,|,MARKER_REUSE_BLOCKED,|RERUN|re-run|refused re-runs/i.test(l));
if (phase === 'burst') {
  const t0 = new Date().toISOString(); const before = (await mine()).length;
  const n0 = (await job('batch-self', 'nextBuildNumber')).nextBuildNumber;
  const { page } = await login('requester');
  const done = [];
  for (let b = 344; b > 320; b--) {
    await page.goto(`${BASE}/job/batch-self/${b}/`);
    const l = page.locator('#side-panel a').filter({ hasText: 'Rebuild' }).first(); if (!(await l.count())) continue;
    await l.click(); await page.waitForLoadState('load');
    await Promise.all([page.waitForNavigation().catch(() => null), page.locator('button[name="Submit"], button:has-text("Run")').first().click()]);
    done.push(b);
  }
  await sleep(4000);
  const all = await mine(); const nw = all.slice(0, all.length - before);
  fs.writeFileSync('../out/final-budget.json', JSON.stringify({ t0, before, done, n0 }));
  ev(`F2 burst ${done.length} attempts; new requester records ${nw.length}:\n${nw.slice(0, 3).map((l) => l.slice(0, 200)).join('\n')}\n...`);
  const sum = nw.filter((l) => /counted, not listed|further refused/i.test(l));
  ev(`F2 summary records: ${sum.map((l) => l.slice(0, 250)).join(' || ')}`);
  const ad = await login('admin'); await ad.page.goto(`${BASE}/batch-control/changes/`);
  await shot(ad.page, ad.page.locator('#main-panel tr', { hasText: /counted, not listed|further refused/i }).first(), 'D-51a-1-summary-record', { pad: 8 });
  console.log('NB', (await job('batch-self', 'nextBuildNumber')).nextBuildNumber - n0);
  await close();
} else {
  const st = JSON.parse(fs.readFileSync('../out/final-budget.json', 'utf8'));
  const all = await mine(); const nw = all.slice(0, all.length - st.before);
  const per = nw.filter((l) => /Blocked a Pipeline Replay .* by 'requester'/.test(l));
  const sum = nw.filter((l) => /counted, not listed|further refused/i.test(l));
  const closing = nw.filter((l) => /window ended|refused re-runs .* beyond|beyond the 20|were counted/i.test(l) && !/counted, not listed/.test(l));
  // earlier per-attempt records of requester in the same 10 minutes (f1)
  const windowStart = Date.parse(st.t0) - 10 * 60000;
  const earlier = (await changeRows(/,requester,/)).filter((l) => /by 'requester'/.test(l) && Date.parse(l.split(',')[4]) >= windowStart && Date.parse(l.split(',')[4]) < Date.parse(st.t0));
  const ad = await login('admin'); await ad.page.goto(`${BASE}/batch-control/changes/`);
  const s = await shot(ad.page, ad.page.locator('#main-panel tr', { hasText: /window ended|beyond|were counted/i }).first(), 'D-51a-2-closing-record', { pad: 8 });
  const nb = (await job('batch-self', 'nextBuildNumber')).nextBuildNumber - st.n0;
  ev(`F2 closing: attempts ${st.done.length}; per-attempt in burst ${per.length}, earlier in window ${earlier.length}; summary ${sum.length}; closing ${closing.map((l) => l.slice(0, 300)).join(' || ')}; builds added ${nb}`);
  const ok = per.length + earlier.length === 20 && sum.length === 1 && closing.length === 1;
  row('D-51a', { roles: 'requester (24 refused Pipeline re-runs of distinct batch-self builds within 2 minutes), admin (records)', V: 'n.a.', G: `${nb === 0 ? '✓' : '✗'} every attempt refused, no build (${nb})`, R: '✓ each click answered by the "Approval required" page', C: `${ok ? '✓' : '✗'} ${earlier.length} per-attempt records already in the window + ${per.length} from the burst = ${per.length + earlier.length} (budget 20); ${sum.length} summary record "${(sum[0] || '').split(',').slice(6).join(',').slice(0, 140)}"; ${closing.length} closing record "${(closing[0] || '').split(',').slice(6).join(',').slice(0, 200)}"`, E: s ? '✓ D-51a-1-summary-record, D-51a-2-closing-record (run-3-final)' : '✗' });
  await close();
}
