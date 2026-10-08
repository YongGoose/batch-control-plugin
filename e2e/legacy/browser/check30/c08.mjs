// Final check on 30e9252: C-08 / DEF-38 (D-58..D-58c), Mark as reviewed, Replay under a grant.
// Usage: BC_SHOTS=run-3-check BC_ROUND=check30 node c08.mjs <stage> [args]
// Stages (in order): grant, selfgrant, replay, revoke, after, rerun, review-admin, widen-kept, restore,
//                    grant2, desc, revoke2, native-before, review-native, native-after
import { login, close, shot, api, job, BASE, OUT, decide, requestGrant, sleep, waitFor, clickBuildEntry, groovy } from '../lib.mjs';
import { ev, mainText } from '../audit/rec.mjs';
import { uiRevoke } from '../audit/restsubmit.mjs';
import fs from 'node:fs';

const J = 'batch-upstream';
const STATE = OUT + '/check30.json';
const st = fs.existsSync(STATE) ? JSON.parse(fs.readFileSync(STATE, 'utf8')) : {};
const save = () => fs.writeFileSync(STATE, JSON.stringify(st, null, 1));
const flat = (s) => (s || '').replace(/\s+/g, ' ').trim();
const SELF = `properties([authorizationMatrix(inheritanceStrategy: inheritingGlobal(), entries: [user(name: 'requester', permissions: ['Job/Configure', 'Job/Read'])])])\necho 'check30-selfgrant'\n`;
const ORIG = `build job: 'batch-daily', parameters: [string(name: 'DATE', value: '2026-01-01')], wait: true\n`;
const stage = process.argv[2];

async function changes(re) {
  const t = (await api('admin', '/batch-control/history/changes.csv')).text.split('\n').slice(1).filter(Boolean);
  return t.filter((l) => re.test(l));
}
async function authEntries() {
  const x = (await api('admin', `/job/${J}/config.xml`, { raw: true })).text;
  const m = x.match(/<hudson\.security\.AuthorizationMatrixProperty>[\s\S]*?<\/hudson\.security\.AuthorizationMatrixProperty>|<com\.cloudbees[^>]*AuthorizationMatrix[\s\S]*?<\/[^>]*AuthorizationMatrixProperty>/);
  const prop = x.match(/<properties>[\s\S]*?<\/properties>|<properties\/>/);
  return { entries: (prop ? prop[0] : '').match(/<(permission|entry)[^>]*>[^<]*<\/\1>|<entry>[\s\S]*?<\/entry>/g) || [], raw: prop ? flat(prop[0]).slice(0, 400) : '', has: !!m };
}
async function pasteScript(page, script) {
  const ed = page.locator('.ace_editor').first();
  await ed.scrollIntoViewIfNeeded();
  await ed.click();
  await page.keyboard.press('Meta+A');
  await page.keyboard.press('Backspace');
  await page.keyboard.insertText(script);
  await page.waitForTimeout(400);
}
async function buildNow(user) {
  const n = (await job(J, 'nextBuildNumber')).nextBuildNumber;
  const c = await login(user);
  await c.page.goto(`${BASE}/job/${J}/`);
  const r = await clickBuildEntry(c.page, 'Build Now');
  await waitFor(async () => { const j = await job(J, 'lastBuild[number,building]'); return j.lastBuild && j.lastBuild.number >= n && !j.lastBuild.building; }, { timeout: 120000 });
  await sleep(2500);
  const b = (await job(J, 'lastBuild[number,result]')).lastBuild;
  return { c, n: b.number, result: b.result, notif: r.notif };
}
async function consoleShot(page, n, re, name) {
  await page.goto(`${BASE}/job/${J}/${n}/console`);
  await page.waitForTimeout(1200);
  const all = (await page.locator('#out, pre.console-output, .console-output').first().innerText().catch(() => '')).split('\n');
  const lines = all.filter((l) => re.test(l));
  const target = page.locator('#out span, #out div, pre.console-output span, .console-output div, .console-output span').filter({ hasText: re }).first();
  await shot(page, (await target.count()) ? target : page.locator('#out, pre.console-output, .console-output').first(), name, { pad: 10 });
  return { lines, running: all.filter((l) => /Running as|Started by|Finished/.test(l)) };
}
async function monitorItem(name, clickReview = false) {
  const c = await login('admin');
  await c.page.goto(`${BASE}/manage/`);
  const mon = c.page.locator('#main-panel .jenkins-alert', { hasText: /review|changed under a/i }).first();
  const text = flat(await mon.innerText().catch(() => 'NONE'));
  await shot(c.page, mon, name, { pad: 8 });
  let after = null;
  if (clickReview) {
    const row = mon.locator('li, tr, p, div').filter({ hasText: J }).filter({ has: c.page.locator('button, a', { hasText: /Mark as reviewed/i }) }).last();
    const btn = row.locator('button, a', { hasText: /Mark as reviewed/i }).first();
    ev(`CHECK30 monitor review row: ${flat(await row.innerText().catch(() => 'NONE')).slice(0, 300)}`);
    c.page.once('dialog', (d) => d.accept());
    await Promise.all([c.page.waitForLoadState('load').catch(() => null), btn.click()]);
    await c.page.waitForTimeout(800);
    const dlg = c.page.locator('dialog[open] button[data-id="ok"], dialog[open] button:has-text("Yes"), dialog[open] button:has-text("Mark")').first();
    if (await dlg.count()) await Promise.all([c.page.waitForLoadState('load').catch(() => null), dlg.click()]);
    await c.page.waitForTimeout(1200);
    after = { url: c.page.url(), text: flat(await mainText(c.page)).slice(0, 400) };
    await c.page.goto(`${BASE}/manage/`);
    const mon2 = c.page.locator('#main-panel .jenkins-alert', { hasText: /review|changed under a/i }).first();
    after.monitor = flat(await mon2.innerText().catch(() => 'NONE'));
    await shot(c.page, (await mon2.count()) ? mon2 : c.page.locator('#main-panel').first(), `${name}-after`, { pad: 8 });
  }
  await c.context.close();
  return { text, after };
}
async function historyShot(user, re, name) {
  const c = await login(user);
  await c.page.goto(`${BASE}/batch-control/history/?kind=changes&job=${J}`);
  await c.page.waitForTimeout(800);
  const row = c.page.locator('tbody tr').filter({ hasText: re }).first();
  const t = flat(await row.innerText().catch(() => 'NONE'));
  await shot(c.page, row, name, { pad: 8 });
  await c.context.close();
  return t;
}

if (/^grant\d?$/.test(stage)) {
  const rq = await login('requester');
  const g = await requestGrant(rq.page, { scope: J, actions: ['CONFIGURE'], minutes: 60, reason: `Check30 ${stage}: CONFIGURE window on ${J}` });
  ev(`CHECK30 ${stage} request ${JSON.stringify(g)}`);
  const d = await decide(g.url, 'approve', 'ok');
  ev(`CHECK30 ${stage} approve ${d.status} ${d.text.slice(0, 200)}`);
  st[stage] = { url: g.url, id: g.url.match(/(\d{8}-\d{6}-\w+)/)[1] };
  save();
} else if (stage === 'selfgrant') {
  // The window holder puts a properties([authorizationMatrix]) step into the Pipeline, in the browser.
  const rq = await login('requester');
  await rq.page.goto(`${BASE}/job/${J}/configure`); await rq.page.waitForTimeout(2000);
  await pasteScript(rq.page, SELF);
  const [resp] = await Promise.all([rq.page.waitForNavigation({ waitUntil: 'load' }).catch(() => null), rq.page.locator('button[name="Submit"]').click()]);
  ev(`CHECK30 selfgrant script save -> ${resp && resp.status()} ${rq.page.url()}`);
  const scriptNow = (await api('admin', `/job/${J}/config.xml`, { raw: true })).text.includes('check30-selfgrant');
  const gvBefore = (await changes(/GRANT_VIOLATION/)).length;
  const b = await buildNow('requester');
  const con = await consoleShot(b.c.page, b.n, /authoriz|not kept|reverted|Batch Control/i, 'C-08-10-build-log-reverted');
  const a = await authEntries();
  const gv = await changes(/GRANT_VIOLATION/);
  const cfg = (await api('requester', `/job/${J}/configure`)).status;
  st.selfgrant = { build: b.n, result: b.result, con, entries: a, gv: gv.length - gvBefore, gvRow: gv[0], cfgStatus: cfg, scriptNow };
  save();
  ev(`CHECK30 selfgrant #${b.n} ${b.result} log=${JSON.stringify(con)} props=${a.raw} GV +${gv.length - gvBefore}: ${gv[0]}`);
  const h = await historyShot('auditor', /GRANT_VIOLATION/, 'C-08-11-grant-violation-record');
  ev(`CHECK30 selfgrant history row (auditor): ${h}`);
  const m = await monitorItem('C-08-12-monitor-lists-item');
  ev(`CHECK30 selfgrant monitor: ${m.text.slice(0, 600)}`);
} else if (stage === 'replay') {
  // Replay by the grant-only window holder, with an edited script.
  const last = (await job(J, 'lastBuild[number]')).lastBuild.number;
  const n = (await job(J, 'nextBuildNumber')).nextBuildNumber;
  const rq = await login('requester');
  await rq.page.goto(`${BASE}/job/${J}/${last}/`);
  const sideHas = await rq.page.locator('#tasks a', { hasText: /^Replay$/ }).count();
  await rq.page.goto(`${BASE}/job/${J}/${last}/replay/`); await rq.page.waitForTimeout(1500);
  await pasteScript(rq.page, `echo 'check30-replayed-under-grant'\n`);
  await Promise.all([rq.page.waitForNavigation({ waitUntil: 'load' }).catch(() => null), rq.page.locator('button[name="Submit"], button:has-text("Run")').first().click()]);
  await waitFor(async () => { const j = await job(J, 'lastBuild[number,building]'); return j.lastBuild && j.lastBuild.number >= n && !j.lastBuild.building; }, { timeout: 120000 });
  await sleep(2000);
  const b = (await job(J, 'lastBuild[number,result]')).lastBuild;
  const rec = (await changes(/REPLAY_UNDER_GRANT/))[0];
  st.replay = { source: last, run: b.number, result: b.result, sideHas, rec };
  save();
  ev(`CHECK30 replay by requester of #${last}: sidebar Replay=${sideHas}; new run #${b.number} ${b.result}; record ${rec}`);
  const h = await historyShot('auditor', /REPLAY_UNDER_GRANT/, 'C-08-13-replay-under-grant-record');
  ev(`CHECK30 replay history row: ${h}`);
} else if (/^revoke\d?$/.test(stage)) {
  const g = st[stage.replace('revoke', 'grant')];
  const ok = await uiRevoke(g.id, 'manager');
  const cfg = (await api('requester', `/job/${J}/configure`)).status;
  ev(`CHECK30 ${stage} ${g.id} revoked=${ok}; requester configure now ${cfg}`);
  st[stage] = { ok, cfg }; save();
} else if (stage === 'after') {
  // After the window: the script is still there; a build by the requester (Build only) runs it again.
  const gvBefore = (await changes(/GRANT_VIOLATION/)).length;
  const b = await buildNow('requester');
  const con = await consoleShot(b.c.page, b.n, /authoriz|not kept|reverted|Batch Control/i, 'C-08-14-after-window-build-log');
  const a = await authEntries();
  const gv = await changes(/GRANT_VIOLATION/);
  st.after = { build: b.n, result: b.result, con, entries: a, gv: gv.length - gvBefore, gvRow: gv[0] }; save();
  ev(`CHECK30 after-window #${b.n} ${b.result} log=${JSON.stringify(con)} props=${a.raw} GV +${gv.length - gvBefore}: ${gv[0]}`);
  const m = await monitorItem('C-08-15-monitor-after-window');
  ev(`CHECK30 after-window monitor: ${m.text.slice(0, 600)}`);
} else if (stage === 'rerun') {
  // Re-runs of the marked run: requester (Pipeline Rebuild), configurer (Replay); admin (allowed, marked).
  const src = st.replay.run;
  const out = {};
  for (const [u, path, label] of [['requester', 'replay', 'Rebuild'], ['configurer', 'replay/', 'Replay']]) {
    const n0 = (await job(J, 'nextBuildNumber')).nextBuildNumber;
    const c = await login(u);
    await c.page.goto(`${BASE}/job/${J}/${src}/`);
    const side = flat(await c.page.locator('#tasks').innerText().catch(() => ''));
    const link = c.page.locator('#tasks a', { hasText: new RegExp(`^${label}$`) }).first();
    let status = null;
    if (await link.count()) {
      const [nav] = await Promise.all([c.page.waitForNavigation({ waitUntil: 'load' }).catch(() => null), link.click()]);
      status = nav && nav.status();
      await c.page.waitForTimeout(800);
      const btn = c.page.locator('#main-panel button[name="Submit"], #main-panel button:has-text("Run")').first();
      if (/replay\/?$/.test(c.page.url()) && await btn.count()) {
        const [n2] = await Promise.all([c.page.waitForNavigation({ waitUntil: 'load' }).catch(() => null), btn.click()]);
        status = n2 && n2.status();
      }
    }
    await c.page.waitForTimeout(1500);
    const t = flat(await mainText(c.page)).slice(0, 400);
    await shot(c.page, '#main-panel', `C-08-16-rerun-refused-${u}`, { pad: 8 });
    await sleep(3000);
    out[u] = { side, status, url: c.page.url(), text: t, next: `${n0} -> ${(await job(J, 'nextBuildNumber')).nextBuildNumber}` };
    await c.context.close();
  }
  out.records = (await changes(/TRIGGER_BLOCKED|REPLAY/)).slice(0, 3);
  st.rerun = out; save();
  ev(`CHECK30 rerun ${JSON.stringify(out)}`);
} else if (stage === 'review-admin') {
  const m = await monitorItem('C-08-17-monitor-mark-reviewed', true);
  const rec = (await changes(/GUARD_REVIEWED/))[0];
  st.reviewAdmin = { before: m.text, after: m.after, rec }; save();
  ev(`CHECK30 review-admin before: ${m.text.slice(0, 500)} || after: ${JSON.stringify(m.after)} || record ${rec}`);
  const h = await historyShot('auditor', /GUARD_REVIEWED/, 'C-08-18-guard-reviewed-record');
  ev(`CHECK30 review-admin history row: ${h}`);
} else if (stage === 'widen-kept') {
  // After the review, the same script's widening is no longer put back (LIMITATIONS 35 warns of exactly this).
  const gvBefore = (await changes(/GRANT_VIOLATION/)).length;
  const b = await buildNow('requester');
  const con = await consoleShot(b.c.page, b.n, /authoriz|not kept|reverted|Batch Control|check30/i, 'C-08-19-after-review-build-log');
  const a = await authEntries();
  const gv = (await changes(/GRANT_VIOLATION/)).length - gvBefore;
  st.widenKept = { build: b.n, result: b.result, con, entries: a, gv }; save();
  ev(`CHECK30 widen-kept #${b.n} ${b.result} log=${JSON.stringify(con)} props=${a.raw} GV +${gv}`);
  // replayed run still refused after the review (D-58c)
  const c = await login('configurer');
  await c.page.goto(`${BASE}/job/${J}/${st.replay.run}/replay/`); await c.page.waitForTimeout(800);
  const btn = c.page.locator('#main-panel button[name="Submit"], #main-panel button:has-text("Run")').first();
  if (await btn.count()) await Promise.all([c.page.waitForNavigation({ waitUntil: 'load' }).catch(() => null), btn.click()]);
  await c.page.waitForTimeout(1200);
  const t = flat(await mainText(c.page)).slice(0, 300);
  await shot(c.page, '#main-panel', 'C-08-20-replay-refused-after-review', { pad: 8 });
  st.widenKept.replayAfterReview = t; save();
  ev(`CHECK30 replay of marked run after review by configurer: ${t}`);
} else if (stage === 'restore') {
  // Admin restores the original job in the browser (an administrator's HTTP save is exempt).
  const c = await login('admin');
  await c.page.goto(`${BASE}/job/${J}/configure`); await c.page.waitForTimeout(2000);
  await pasteScript(c.page, ORIG);
  const [resp] = await Promise.all([c.page.waitForNavigation({ waitUntil: 'load' }).catch(() => null), c.page.locator('button[name="Submit"]').click()]);
  await c.context.close();
  // the entry written by the build is removed by admin (arrange, config.xml as admin over HTTP)
  const x = (await api('admin', `/job/${J}/config.xml`, { raw: true })).text.replace(/<properties>[\s\S]*?<\/properties>/, '<properties/>');
  const p = await api('admin', `/job/${J}/config.xml`, { method: 'POST', body: x, headers: { 'Content-Type': 'application/xml' } });
  ev(`CHECK30 restore: script save ${resp && resp.status()}, properties cleared ${p.status}; props now ${(await authEntries()).raw}`);
} else if (stage === 'desc') {
  // Under window 2 the requester changes only the description, in the browser.
  const c = await login('requester');
  await c.page.goto(`${BASE}/job/${J}/configure`); await c.page.waitForTimeout(1500);
  await c.page.fill('textarea[name="description"]', `Calls batch-daily through a build step (upstream path). Edited under a window at ${new Date().toISOString()}.`);
  const [resp] = await Promise.all([c.page.waitForNavigation({ waitUntil: 'load' }).catch(() => null), c.page.locator('button[name="Submit"]').click()]);
  ev(`CHECK30 desc save -> ${resp && resp.status()}`);
  await c.context.close();
} else if (stage === 'native-before' || stage === 'native-after') {
  // configurer (native Item/Configure) adds approver-2 Job/Read to the job's matrix in the browser.
  const c = await login('configurer');
  await c.page.goto(`${BASE}/job/${J}/configure`); await c.page.waitForTimeout(2000);
  const en = c.page.locator('input[name="useProjectSecurity"]').first();
  if (!(await en.isChecked())) await c.page.locator('label', { hasText: 'Enable project-based security' }).first().click();
  await c.page.waitForTimeout(800);
  await c.page.locator('button:visible', { hasText: /^Add user$/ }).first().click(); await c.page.waitForTimeout(500);
  await c.page.locator('dialog[open] input').first().fill('approver-2');
  await c.page.locator('dialog[open] button[data-id="ok"]').click(); await c.page.waitForTimeout(1000);
  const card = c.page.locator('#hudson-security-AuthorizationMatrixProperty .mas-card:visible', { hasText: 'E2E Approver Two' }).first();
  const readLabel = card.locator('label[data-permission-id="hudson.model.Item.Read"]').first();
  await readLabel.click(); await c.page.waitForTimeout(400);
  await shot(c.page, card, `C-08-21-${stage}-matrix`, { pad: 8 });
  ev(`CHECK30 ${stage}: card "${flat(await card.innerText().catch(() => 'NONE')).slice(0, 80)}"`);
  const [resp] = await Promise.all([c.page.waitForNavigation({ waitUntil: 'load' }).catch(() => null), c.page.locator('button[name="Submit"]').click()]);
  await c.page.waitForTimeout(1000);
  const t = flat(await mainText(c.page)).slice(0, 500);
  await shot(c.page, '#main-panel', `C-08-21-${stage}`, { pad: 8 });
  const a = await authEntries();
  const gv = (await changes(/GRANT_VIOLATION/))[0];
  st[stage] = { status: resp && resp.status(), text: t, props: a.raw, gv }; save();
  ev(`CHECK30 ${stage} save ${resp && resp.status()} "${t}" props=${a.raw} lastGV=${gv}`);
  await c.context.close();
} else if (stage === 'review-native') {
  const c = await login('configurer');
  await c.page.goto(`${BASE}/job/${J}/batch-control/`); await c.page.waitForTimeout(1000);
  const sec = c.page.locator('#main-panel').first();
  const before = flat(await sec.innerText().catch(() => 'NONE'));
  const para = c.page.locator('#main-panel p, #main-panel li, #main-panel .jenkins-alert').filter({ hasText: /review|permission window|replayed/i });
  const targets = [c.page.locator('button, a', { hasText: /Mark as reviewed/i }).first()];
  for (let i = 0; i < Math.min(await para.count(), 4); i++) targets.push(para.nth(i));
  await shot(c.page, targets, 'C-08-22-job-page-review-section', { pad: 8 });
  const btn = c.page.locator('button, a', { hasText: /Mark as reviewed/i }).first();
  const has = await btn.count();
  if (has) {
    c.page.once('dialog', (d) => d.accept());
    await Promise.all([c.page.waitForLoadState('load').catch(() => null), btn.click()]);
    await c.page.waitForTimeout(800);
    const dlg = c.page.locator('dialog[open] button[data-id="ok"], dialog[open] button:has-text("Yes"), dialog[open] button:has-text("Mark")').first();
    if (await dlg.count()) await Promise.all([c.page.waitForLoadState('load').catch(() => null), dlg.click()]);
    await c.page.waitForTimeout(1200);
  }
  const after = flat(await mainText(c.page)).slice(0, 500);
  await shot(c.page, '#main-panel', 'C-08-23-job-page-after-review', { pad: 8 });
  const rec = (await changes(/GUARD_REVIEWED/))[0];
  st.reviewNative = { before, has, after, rec }; save();
  ev(`CHECK30 review-native section "${before.slice(0, 500)}" button=${has} after "${after}" record ${rec}`);
  await c.context.close();
  // requester (grant-only in the past, Request holder) sees no review button
  const r = await login('requester');
  await r.page.goto(`${BASE}/job/${J}/batch-control/`);
  ev(`CHECK30 requester Mark-as-reviewed buttons on job page: ${await r.page.locator('button, a', { hasText: /Mark as reviewed/i }).count()}`);
  await r.context.close();
} else {
  console.log('unknown stage');
}
await close();
