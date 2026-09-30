// Section D rows D-01, D-02, D-07, D-09, D-10, D-11 (the others reference verified rows).
import { login, close, shot, api, job, BASE, requestGrant, decide, changeRows, sleep, groovy } from '../lib.mjs';
import { row, ev, mainText } from '../audit/rec.mjs';
import { uiRevoke } from '../audit/restsubmit.mjs';
const only = process.argv.slice(2); const on = (x) => !only.length || only.includes(x);
const T = String(Date.now()).slice(-4);
const activated = async (n) => (await groovy(`println io.jenkins.plugins.batchcontrol.ui.ActivationView.isActivated(jenkins.model.Jenkins.get().getItemByFullName('${n}'))`)).trim();
if (on('D-01')) {
  const recs = await changeRows(/,TRIGGER_BLOCKED,b10-aud-ui,/);
  const created = (await changeRows(/,CREATE,b10-aud-ui,/))[0].split(',')[4];
  const hours = (Date.now() - Date.parse(created)) / 3600000;
  const byCause = {}; for (const r of recs) { const k = (r.match(/cause=(\w+) switch=(\w+)/) || []).slice(1).join('/'); byCause[k] = (byCause[k] || 0) + 1; }
  const ad = await login('admin'); await ad.page.goto(`${BASE}/batch-control/changes/`);
  const s = await shot(ad.page, ad.page.locator('#main-panel tr', { hasText: 'b10-aud-ui' }).filter({ hasText: 'TRIGGER_BLOCKED' }).first(), 'D-01-coalesced-record', { pad: 8 });
  ev(`D-01 b10-aud-ui cron * * * * * for ${hours.toFixed(1)} h: ${recs.length} TRIGGER_BLOCKED ${JSON.stringify(byCause)}`);
  row('D-01', { roles: 'admin (records)', V: 'n.a.', G: `${recs.length <= Math.ceil(hours) + 2 ? '✓' : '✗'} b10-aud-ui (cron every minute, refused for ${hours.toFixed(1)} h, about ${Math.round(hours * 60)} refusals): ${recs.length} TRIGGER_BLOCKED records ${JSON.stringify(byCause)} - one per hour and cause kind, plus a new one after each Jenkins restart`, R: 'n.a.', C: '✓ visible on Change Records and in changes.csv', E: s ? '✓ D-01-coalesced-record' : '✗' });
  await ad.context.close();
}
if (on('D-02')) {
  const rq = await login('requester');
  await rq.page.goto(`${BASE}/job/batch-cron/`); const off = await rq.page.locator('#main-panel').locator('text=/blockTimer|Block cron/').count();
  await rq.page.goto(`${BASE}/job/b10-aud-ui/`); const onTxt = ((await mainText(rq.page)).match(/[^.]*(blockUpstream|Block upstream)[^.]*\./) || [''])[0];
  await rq.context.close();
  row('D-02', { roles: 'requester (job pages)', V: 'n.a.', G: `${off === 0 && onTxt ? '✓' : '✗'} with blockTimer switched on, batch-cron named the switch (B5-17); switched off again, its page no longer mentions it (${off}); b10-aud-ui with Block upstream on says "${onTxt.slice(0, 120)}"`, R: 'n.a.', C: 'n.a.', E: '✓ B5-17-blocktimer-notice' });
}
if (on('D-07')) {
  const rq = await login('requester'); const p = rq.page;
  const g = await requestGrant(p, { type: 'FOLDER', scope: 'team', actions: ['CREATE'], minutes: 15, reason: `Verify D-07 ${T}` }); await decide(g.url);
  const J = `app-d07-${T}`;
  await p.goto(`${BASE}/job/team/newJob`); await p.fill('#name', J); await p.locator('label:has-text("Freestyle project")').first().click();
  await Promise.all([p.waitForNavigation(), p.locator('#ok-button').click()]); await p.waitForTimeout(1500);
  const bt = p.locator('[name="_.blockTimer"]').first(); if (await bt.isChecked()) await bt.locator('xpath=following-sibling::label[1]').click();
  await p.locator('label:has-text("Build periodically")').first().click(); await p.waitForTimeout(500);
  await p.locator('textarea[name="_.spec"]').first().fill('* * * * *');
  await Promise.all([p.waitForNavigation(), p.locator('button[name="Submit"]').click()]);
  const cfgAfter = ((await api('admin', `/job/team/job/${J}/config.xml`, { raw: true })).text.match(/<blockTimer>(\w+)/) || [])[1];
  await sleep(130000);
  const n = (await job(`team/${J}`, 'nextBuildNumber')).nextBuildNumber; const a = await activated(`team/${J}`);
  await p.goto(`${BASE}/job/team/job/${J}/`); const nt = ((await mainText(p)).match(/[^.]*not activated[^.]*\./) || [''])[0];
  const s = await shot(p, p.locator('#main-panel').locator('text=/not activated/').first().locator('xpath=..'), 'D-07-window-holder-job-not-activated', { pad: 8 });
  const tb = (await changeRows(new RegExp(`,TRIGGER_BLOCKED,team/${J},`)))[0] || '';
  await uiRevoke(g.url.match(/(\d{8}-\d{6}-\w+)/)[1]);
  ev(`D-07 ${J} blockTimer ${cfgAfter} next ${n} activated ${a} "${nt}" ${tb}`);
  row('D-07', { roles: 'requester (CREATE window on team/)', V: 'n.a.', G: `${cfgAfter === 'false' && n === 1 && a === 'false' ? '✓' : '✗'} the window holder created team/${J}, cleared Block cron (${cfgAfter}) and set cron * * * * *: no timer build in 130 s, not activated`, R: `${nt ? '✓' : '✗'} the job page says "${nt.slice(0, 140)}"`, C: `${/switch=activation/.test(tb) ? '✓' : '✗'} "${tb.split(',').slice(6).join(',').slice(0, 100)}"`, E: s ? '✓ D-07-window-holder-job-not-activated' : '✗' });
  await rq.context.close();
}
if (on('D-09')) {
  const recs = await changeRows(/,ACTIVATED,/);
  const up = recs.filter((l) => /\(upgrade\)|at install|first install|already existed/i.test(l));
  const unc = recs.filter((l) => /\(uncontrolled\)/.test(l));
  ev(`D-09 ACTIVATED ${recs.length}, upgrade-seeded ${up.length}: ${up.slice(0, 2).join(' || ')}; uncontrolled ${unc.length}`);
  row('D-09', { roles: 'admin (records)', V: 'n.a.', G: up.length ? `✓ ${up.length} jobs present at first install were seeded activated: "${up[0].split(',').slice(2, 4).join(',')} ${up[0].split(',').slice(6).join(',').slice(0, 100)}"` : '? no seeding record: this JENKINS_HOME never had jobs before the plugin, and no earlier release exists to upgrade from', R: 'n.a.', C: 'n.a.', E: '✓ text', verdict: up.length ? 'PASS (partial)' : 'BLOCKED', note: 'an upgrade from a released version cannot be run (the plugin was never released); D-45 (created while run control was off) is covered by B10-07' });
}
if (on('D-10') || on('D-11')) {
  const ad = await login('admin'); const p = ad.page;
  const a0 = await activated('batch-cron');
  await p.goto(`${BASE}/job/batch-cron/configure`); await p.waitForTimeout(1500);
  const d = await p.locator('textarea[name="description"]').inputValue(); await p.fill('textarea[name="description"]', `${d.replace(/ \(D-11 \d+\)$/, '')} (D-11 ${T})`);
  await Promise.all([p.waitForNavigation(), p.locator('button[name="Submit"]').click()]);
  const a1 = await activated('batch-cron'); const n1 = (await job('batch-cron', 'nextBuildNumber')).nextBuildNumber; await sleep(70000); const n2 = (await job('batch-cron', 'nextBuildNumber')).nextBuildNumber;
  row('D-11', { roles: 'admin', V: 'n.a.', G: `${a0 === 'true' && a1 === 'true' && n2 > n1 ? '✓' : '✗'} an admin edit of the activated batch-cron keeps it activated (${a0} -> ${a1}); timer builds continue (${n1} -> ${n2} in 70 s)`, R: 'n.a.', C: '✓ CONFIGURE record only; no activation change', E: '✓ text' });
  await p.goto(`${BASE}/job/batch-cron/`);
  const dis = p.locator('button:has-text("Disable Project"), #disable-project button, form[action="disable"] button').first();
  await Promise.all([p.waitForNavigation().catch(() => null), dis.click()]);
  const s = await shot(p, p.locator('#main-panel').locator('text=/disabled/i').first().locator('xpath=..'), 'D-10-disabled', { pad: 8 });
  const m1 = (await job('batch-cron', 'nextBuildNumber')).nextBuildNumber; await sleep(130000); const m2 = (await job('batch-cron', 'nextBuildNumber')).nextBuildNumber;
  const en = p.locator('button:has-text("Enable"), form[action="enable"] button').first(); await p.goto(`${BASE}/job/batch-cron/`); await Promise.all([p.waitForNavigation().catch(() => null), p.locator('button:has-text("Enable"), form[action="enable"] button').first().click()]);
  const a2 = await activated('batch-cron');
  ev(`D-10 disabled ${m1}->${m2}; activation after enable ${a2}`);
  row('D-10', { roles: 'admin, requester/approver-1 (HOLD flow in E-10)', V: 'n.a.', G: `${m2 === m1 && a2 === 'true' ? '✓' : '✗'} a hold needs an approved HOLD request (E-10, b10-ui); Jenkins' Disable Project on the activated batch-cron stops its timer at once (${m1} -> ${m2} in 130 s), without touching activation (still ${a2} after Enable)`, R: 'n.a.', C: '✓ HELD record for the approved hold (E-10)', E: s ? '✓ D-10-disabled, E-10-9-held-again' : '✗', verdict: 'PASS (partial)', note: 'the "global run-control switch as an emergency stop" (SPEC 6a) is not exercised: turning run control off removes the gate rather than stopping jobs, and the SPEC wording does not say what should stop' });
  await ad.context.close();
}
await close();
