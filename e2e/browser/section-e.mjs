// Section E of CHECKLIST.md: plugin precedence. Each plugin is configured on a
// job in the browser (as admin), the unapproved/unactivated path is tried as
// the requester, then the approved/activated path is shown to run exactly once.
// Usage: node section-e.mjs <E-xx> [...]
import { login, close, shot, api, job, queue, groovy, waitFor, sleep, BASE, log, clickBuildEntry } from './lib.mjs';
import { requestRun } from './lib.mjs';

const L = 'section-e.log';
const rows = {};

// ------------------------------------------------------------ helpers
async function openConfig(page, jobName) {
  await page.goto(`${BASE}/job/${jobName}/configure`);
  await page.waitForTimeout(1500);
}
async function saveConfig(page) {
  await Promise.all([page.waitForNavigation({ waitUntil: 'load' }), page.locator('button[name="Submit"]').click()]);
}
/** Ticks a checkbox / optional block by its label text if not ticked yet. */
async function tick(page, label, on = true) {
  const l = page.locator('label').filter({ hasText: new RegExp(`^\\s*${label.replace(/[()?.*+]/g, '\\$&')}\\s*$`) }).first();
  await l.scrollIntoViewIfNeeded();
  const cur = await l.evaluate((x) => { const i = x.parentElement.querySelector('input[type=checkbox]') || x.previousElementSibling; return !!(i && i.checked); });
  if (cur !== on) { await l.click(); await page.waitForTimeout(500); }
}
async function fieldAfter(page, label) {
  return page.locator('.jenkins-form-item').filter({ has: page.locator('.jenkins-form-label', { hasText: label }) }).last().locator('input, textarea, select').first();
}
async function approveAs(user, url, comment = 'ok') {
  const { context, page } = await login(user);
  await page.goto(url);
  const form = page.locator('form[name="approve"]');
  await form.locator('textarea[name="comment"]').fill(comment);
  await Promise.all([page.waitForLoadState('load'), form.locator('button').first().click()]);
  const txt = (await page.locator('#main-panel').innerText()).replace(/\s+/g, ' ');
  await context.close();
  return txt;
}
async function activate(jobName, reason) {
  const rq = await login('requester');
  await rq.page.goto(`${BASE}/job/${jobName}/batch-control/activation`);
  await rq.page.fill('textarea[name="reason"]', reason);
  await rq.page.locator('input[name="approvers"][value="approver-1"] + label').click();
  await Promise.all([rq.page.waitForNavigation({ waitUntil: 'load' }), rq.page.locator('button:has-text("Submit Request")').click()]);
  const url = rq.page.url();
  await rq.context.close();
  await approveAs('approver-1', url, 'activate for section E');
  return url;
}
async function changes(filter) {
  const r = await api('admin', '/batch-control/history/changes.csv');
  return r.text.split('\n').filter((l) => filter.test(l));
}
async function builds(name) {
  const j = await job(name, 'nextBuildNumber,inQueue,builds[number,result,actions[causes[shortDescription]]]');
  return { next: j.nextBuildNumber, inQueue: j.inQueue, list: j.builds.map((b) => `#${b.number} ${b.result} [${(b.actions.find((a) => a && a.causes) || { causes: [] }).causes.map((c) => c.shortDescription).join('; ')}]`) };
}
async function waitBuilt(name, next) {
  return waitFor(async () => { const j = await job(name, 'nextBuildNumber,lastBuild[number,building,result]'); return j.nextBuildNumber > next && j.lastBuild && !j.lastBuild.building ? j : null; }, { timeout: 120000 });
}

// ------------------------------------------------------------ E-01 customize-build-now
rows['PR-01'] = async () => {
  const name = 'batch-cbn';
  const ad = await login('admin');
  await openConfig(ad.page, name);
  await tick(ad.page, 'Provide alternate Labels for Build Links and Buttons');
  await (await fieldAfter(ad.page, 'New Label for Build Now')).fill('Run it now');
  await shot(ad.page, (await fieldAfter(ad.page, 'New Label for Build Now')).locator('xpath=ancestor::*[contains(@class,"optionalBlock-container") or contains(@class,"jenkins-form-item")][2]'), 'PR-01-1-configure', { pad: 10 });
  await saveConfig(ad.page);
  await ad.context.close();
  const b0 = await builds(name);
  const rq = await login('requester');
  await rq.page.goto(`${BASE}/job/${name}/`);
  const side = (await rq.page.locator('#side-panel a').allInnerTexts()).map((t) => t.trim()).filter(Boolean);
  log(L, `E-01 requester sidebar on ${name}: ${JSON.stringify(side)}`);
  await shot(rq.page, '#side-panel .task, #tasks', 'PR-01-2-sidebar', { pad: 10 });
  await shot(rq.page, '#side-panel', 'PR-01-2-sidebar', { pad: 6 });
  const label = side.find((t) => /Run it now/.test(t)) || side.find((t) => /Direct Build|Build Now/.test(t));
  const r = await clickBuildEntry(rq.page, label);
  log(L, `E-01 clicked "${label}": HTTP ${r.status} ${r.url} "${r.text.slice(0, 260)}"`);
  await shot(rq.page, '#main-panel', 'PR-01-3-refusal', { pad: 8 });
  const links = await rq.page.locator('#main-panel a[href*="batch-control"]').count();
  await sleep(5000);
  const b1 = await builds(name);
  log(L, `E-01 after refused click: clickable link to request form=${links}; nextBuildNumber ${b0.next}->${b1.next}, inQueue=${b1.inQueue}`);
  // the approved path
  const url = await requestRun(rq.page, `/job/${name}/`, { reason: 'Run the customised job once (E-01).', approvers: ['approver-1'] });
  await approveAs('approver-1', url);
  await waitBuilt(name, b1.next - 1);
  await sleep(4000);
  const b2 = await builds(name);
  log(L, `E-01 after approval: builds ${JSON.stringify(b2.list)} (added ${b2.next - b1.next})`);
  await rq.page.goto(`${BASE}/job/${name}/`);
  await shot(rq.page, ['#side-panel'], 'PR-01-4-after-approved-run', { pad: 6 });
  await rq.context.close();
};

// ------------------------------------------------------------ E-02 rebuild
rows['PR-02'] = async () => {
  const name = 'batch-rebuild';
  const rq = await login('requester');
  const b0 = await builds(name);
  const url = await requestRun(rq.page, `/job/${name}/`, { reason: 'First approved run, to be rebuilt (E-02).', approvers: ['approver-1'] });
  await approveAs('approver-1', url);
  const j = await waitBuilt(name, b0.next - 1);
  const n = j.lastBuild.number;
  const before = await changes(/MARKER_REUSE_BLOCKED/);
  await rq.page.goto(`${BASE}/job/${name}/${n}/`);
  await shot(rq.page, '#side-panel', 'PR-02-1-build-sidebar-rebuild', { pad: 6 });
  const r = await clickBuildEntry(rq.page, 'Rebuild');
  log(L, `E-02 requester clicked Rebuild on #${n}: HTTP ${r.status} ${r.url} "${r.text.slice(0, 260)}"`);
  await shot(rq.page, '#main-panel', 'PR-02-2-refusal', { pad: 8 });
  await sleep(5000);
  const b1 = await builds(name);
  const after = await changes(/MARKER_REUSE_BLOCKED/);
  log(L, `E-02 after Rebuild: builds ${JSON.stringify(b1.list)}; MARKER_REUSE_BLOCKED records ${before.length}->${after.length} ${after.slice(0, 1).join('')}`);
  const ad = await login('admin');
  await ad.page.goto(`${BASE}/batch-control/changes/`);
  const row = ad.page.locator('#main-panel tr:has-text("MARKER_REUSE_BLOCKED")').first();
  if (await row.count()) await shot(ad.page, row, 'PR-02-3-record', { pad: 8 });
  await ad.context.close(); await rq.context.close();
};

// ------------------------------------------------------------ E-03 parameterized-trigger
rows['PR-03'] = async () => {
  const src = 'batch-pt-source'; const dst = 'batch-up-target';
  const ad = await login('admin');
  await openConfig(ad.page, src);
  if (!(await ad.page.getByText('Trigger parameterized build on other projects').count())) {
    const add = ad.page.locator('button.hetero-list-add:has-text("Add post-build action")');
    await add.scrollIntoViewIfNeeded(); await add.click(); await ad.page.waitForTimeout(600);
    await ad.page.locator('.jenkins-dropdown button, .jenkins-dropdown__item, .yuimenuitem a').filter({ hasText: 'Trigger parameterized build on other projects' }).first().click();
    await ad.page.waitForTimeout(1200);
    await (await fieldAfter(ad.page, 'Projects to build')).fill(dst);
  }
  await tick(ad.page, 'Trigger build without parameters');
  const proj = await fieldAfter(ad.page, 'Projects to build');
  await shot(ad.page, proj.locator('xpath=ancestor::*[contains(@class,"repeated-chunk")][1]'), 'PR-03-1-configure', { pad: 10 });
  await saveConfig(ad.page);
  await ad.context.close();
  // put the (already activated) target on hold through the HOLD request flow
  const rh = await login('requester');
  await rh.page.goto(`${BASE}/job/${dst}/batch-control/activation`);
  if (await rh.page.getByText('Request a Hold', { exact: false }).count()) {
    await rh.page.fill('textarea[name="reason"]', 'Hold before the unattended-path check (E-03).');
    await rh.page.locator('input[name="approvers"][value="approver-1"] + label').click();
    await Promise.all([rh.page.waitForNavigation({ waitUntil: 'load' }), rh.page.locator('button:has-text("Submit Request")').click()]);
    await approveAs('approver-1', rh.page.url(), 'hold ok');
  }
  await rh.page.goto(`${BASE}/job/${dst}/`);
  log(L, `E-03 target notice before trigger: ${(await rh.page.locator('#main-panel .jenkins-alert').allInnerTexts()).map((t) => t.replace(/\s+/g, ' ')).join(' / ')}`);
  await rh.context.close();
  const rq = await login('requester');
  // 1) not activated: the downstream is refused
  const t0 = await builds(dst);
  const tb0 = await changes(/TRIGGER_BLOCKED,batch-up-target/);
  await rq.page.goto(`${BASE}/job/${src}/`);
  const r = await clickBuildEntry(rq.page, 'Build Now');
  const s = await waitBuilt(src, (await job(src)).nextBuildNumber - 2);
  await sleep(6000);
  const n = (await job(src, 'lastBuild[number]')).lastBuild.number;
  const con = (await api('admin', `/job/${src}/${n}/consoleText`, { raw: true })).text;
  const t1 = await builds(dst);
  const tb1 = await changes(/TRIGGER_BLOCKED,batch-up-target/);
  log(L, `E-03 (not activated) ${src} #${n} ${(await job(src, 'lastBuild[result]')).lastBuild.result}; console tail: ${con.trim().split('\n').slice(-3).join(' | ')}; ${dst} next ${t0.next}->${t1.next}; TRIGGER_BLOCKED ${tb0.length}->${tb1.length} ${tb1.slice(0, 1).join('')}`);
  await rq.page.goto(`${BASE}/job/${src}/${n}/console`);
  await shot(rq.page, '#main-panel pre, #out, .console-output', 'PR-03-2-upstream-console', { pad: 8 });
  await rq.page.goto(`${BASE}/job/${dst}/`);
  await shot(rq.page, ['#main-panel .jenkins-alert', '#side-panel'], 'PR-03-3-target-page', { pad: 8 });
  await rq.context.close();
  // 2) activate the target through the ACTIVATE flow, trigger again: exactly one downstream run
  await activate(dst, 'Let batch-pt-source trigger it (E-03).');
  const rq2 = await login('requester');
  await rq2.page.goto(`${BASE}/job/${src}/`);
  await clickBuildEntry(rq2.page, 'Build Now');
  await waitBuilt(dst, t1.next - 1);
  await sleep(6000);
  const t2 = await builds(dst);
  log(L, `E-03 (activated) ${dst} builds ${JSON.stringify(t2.list)} (added ${t2.next - t1.next})`);
  await rq2.page.goto(`${BASE}/job/${dst}/`);
  await shot(rq2.page, ['#main-panel .jenkins-alert', '#side-panel'], 'PR-03-4-target-after-activation', { pad: 8 });
  // 3) blockUpstream=true on the target: refused again although activated
  const ad2 = await login('admin');
  await openConfig(ad2.page, dst);
  await tick(ad2.page, 'Block upstream triggers', true);
  await saveConfig(ad2.page);
  await ad2.context.close();
  await rq2.page.goto(`${BASE}/job/${src}/`);
  await clickBuildEntry(rq2.page, 'Build Now');
  await sleep(15000);
  const t3 = await builds(dst);
  await rq2.page.goto(`${BASE}/job/${dst}/`);
  const notice = (await rq2.page.locator('#main-panel .jenkins-alert').allInnerTexts()).map((t) => t.replace(/\s+/g, ' '));
  log(L, `E-03 (blockUpstream on) ${dst} next ${t2.next}->${t3.next}; job page notices ${JSON.stringify(notice)}`);
  await shot(rq2.page, '#main-panel .jenkins-alert', 'PR-03-5-blockupstream-notice', { pad: 8 });
  // restore
  const ad3 = await login('admin');
  await openConfig(ad3.page, dst);
  await tick(ad3.page, 'Block upstream triggers', false);
  await saveConfig(ad3.page);
  await ad3.context.close(); await rq2.context.close();
};

// ------------------------------------------------------------ E-04 build-token-root
rows['PR-04'] = async () => {
  const name = 'batch-token'; const token = 'e2e-token-4711';
  const ad = await login('admin');
  await openConfig(ad.page, name);
  await tick(ad.page, 'Trigger builds remotely (e.g., from scripts)');
  const f = await fieldAfter(ad.page, 'Authentication Token');
  await f.fill(token);
  await shot(ad.page, f.locator('xpath=ancestor::*[contains(@class,"jenkins-form-item")][2]'), 'PR-04-1-configure', { pad: 10 });
  await saveConfig(ad.page);
  await ad.context.close();
  const b0 = await builds(name);
  const rec0 = await changes(/batch-token/);
  const out = [];
  for (const p of [`/buildByToken/build?job=${name}&token=${token}`, `/job/${name}/build?token=${token}`]) {
    const r = await fetch(`${BASE}${p}`, { method: 'POST', redirect: 'manual' });
    const t = await r.text();
    out.push(`POST ${p} -> HTTP ${r.status} "${t.replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ').trim().slice(0, 160)}"`);
  }
  await sleep(6000);
  const b1 = await builds(name);
  const rec1 = await changes(/batch-token/);
  for (const o of out) log(L, `E-04 anonymous ${o}`);
  log(L, `E-04 after token calls: next ${b0.next}->${b1.next}; new records: ${rec1.slice(0, rec1.length - rec0.length).join(' || ')}`);
  const ad2 = await login('admin');
  await ad2.page.goto(`${BASE}/batch-control/changes/`);
  const row = ad2.page.locator('#main-panel tr:has-text("batch-token")').first();
  if (await row.count()) await shot(ad2.page, ad2.page.locator('#main-panel tr:has-text("batch-token")'), 'PR-04-2-records', { pad: 8 });
  await ad2.context.close();
  const rq = await login('requester');
  const url = await requestRun(rq.page, `/job/${name}/`, { reason: 'Run the token job once through approval (E-04).', approvers: ['approver-1'] });
  await approveAs('approver-1', url);
  await waitBuilt(name, b1.next - 1);
  await sleep(4000);
  const b2 = await builds(name);
  log(L, `E-04 approved path: builds ${JSON.stringify(b2.list)} (added ${b2.next - b1.next})`);
  await rq.context.close();
};

// ------------------------------------------------------------ E-05 naginator
rows['PR-05'] = async () => {
  const name = 'batch-nag';
  const ad = await login('admin');
  await openConfig(ad.page, name);
  const cmd = ad.page.locator('textarea[name="command"], .jenkins-form-item:has(.jenkins-form-label:text-is("Command")) textarea').first();

  // Freestyle shell step uses a CodeMirror editor on some versions; set the textarea value either way.
  await cmd.evaluate((t, v) => { t.value = v; t.dispatchEvent(new Event('change')); const cm = t.nextElementSibling && t.nextElementSibling.CodeMirror; if (cm) cm.setValue(v); }, 'echo "batch-nag: failing on purpose"; exit 1');
  const add = ad.page.locator('button.hetero-list-add:has-text("Add post-build action")');
  await add.scrollIntoViewIfNeeded(); await add.click(); await ad.page.waitForTimeout(600);
  await ad.page.locator('.jenkins-dropdown button, .jenkins-dropdown__item').filter({ hasText: 'Retry build after failure' }).first().click();
  await ad.page.waitForTimeout(1200);
  const chunk = ad.page.locator('.repeated-chunk').filter({ hasText: 'Retry build after failure' }).last();
  const labels = (await chunk.locator('label, .jenkins-form-label').allInnerTexts()).map((t) => t.trim()).filter(Boolean);
  log(L, `E-05 naginator form labels: ${JSON.stringify(labels)}`);
  const fixed = chunk.locator('label:has-text("Fixed")').first();
  if (await fixed.count()) { await fixed.click(); await ad.page.waitForTimeout(500); const d = chunk.locator('input[name="_.delay"]').first(); if (await d.count()) await d.fill('5'); }
  const max = chunk.locator('input[name="_.maxSchedule"]').first();
  if (await max.count()) await max.fill('1');
  await shot(ad.page, ad.page.locator('.jenkins-form-item, .repeated-chunk').filter({ hasText: 'Retry build after failure' }).last(), 'PR-05-1-configure', { pad: 10 });
  await saveConfig(ad.page);
  await ad.context.close();
  const b0 = await builds(name);
  const rq = await login('requester');
  const url = await requestRun(rq.page, `/job/${name}/`, { reason: 'Approved run that fails, to watch naginator (E-05).', approvers: ['approver-1'] });
  const m0 = await changes(/MARKER_REUSE_BLOCKED|TRIGGER_BLOCKED/);
  await approveAs('approver-1', url);
  await waitBuilt(name, b0.next - 1);
  await sleep(30000); // fixed 5 s retry delay configured above
  const b1 = await builds(name);
  const q = (await queue()).filter((i) => i.task && i.task.name === name);
  const m1 = await changes(/MARKER_REUSE_BLOCKED|TRIGGER_BLOCKED/);
  log(L, `E-05 after approved failing run: builds ${JSON.stringify(b1.list)}; queue ${JSON.stringify(q)}; new records ${m1.slice(0, m1.length - m0.length).join(' || ')}`);
  const n = (await job(name, 'lastBuild[number]')).lastBuild.number;
  await rq.page.goto(`${BASE}/job/${name}/${n}/`);
  await shot(rq.page, ['#side-panel', '#main-panel'], 'PR-05-2-failed-build', { pad: 6 });
  const retry = rq.page.locator('#side-panel a:has-text("Retry")');
  if (await retry.count()) {
    const r = await clickBuildEntry(rq.page, 'Retry');
    log(L, `E-05 manual Retry: HTTP ${r.status} ${r.url} "${r.text.slice(0, 240)}"`);
    await shot(rq.page, '#main-panel', 'PR-05-3-manual-retry-refusal', { pad: 8 });
  } else log(L, 'E-05 no Retry link on the build page for requester');
  await sleep(5000);
  const b2 = await builds(name);
  const m2 = await changes(/MARKER_REUSE_BLOCKED|TRIGGER_BLOCKED/);
  log(L, `E-05 end: builds ${JSON.stringify(b2.list)}; records added in total ${m2.length - m0.length}: ${m2.slice(0, m2.length - m0.length).join(' || ')}`);
  await rq.context.close();
};

// ------------------------------------------------------------ E-06 lockable-resources
rows['PR-06'] = async () => {
  const name = 'batch-lock';
  const g = await groovy(`
def m = org.jenkins.plugins.lockableresources.LockableResourcesManager.get()
if (m.fromName('e2e-db') == null) { m.createResource('e2e-db'); m.save() }
println m.resources*.name`);
  log(L, `E-06 arrange: lockable resources ${g}`);
  const ad = await login('admin');
  await ad.page.goto(`${BASE}/lockable-resources/`);
  await shot(ad.page, ad.page.locator('tr:has-text("e2e-db")').first(), 'PR-06-0-resource', { pad: 10 });
  await openConfig(ad.page, name);
  await tick(ad.page, 'This build requires lockable resources');
  const res = ad.page.locator('input[name="_.resourceNames"]').first();
  await res.fill('e2e-db');
  await shot(ad.page, res.locator('xpath=ancestor::*[contains(@class,"optionalBlock-container") or contains(@class,"jenkins-form-item")][2]'), 'PR-06-1-configure', { pad: 10 });
  await saveConfig(ad.page);
  await ad.context.close();
  await directThenApproved('PR-06', name);
};

// ------------------------------------------------------------ E-07 throttle-concurrents
rows['PR-07'] = async () => {
  const name = 'batch-throttle';
  const ad = await login('admin');
  await openConfig(ad.page, name);
  await tick(ad.page, 'Throttle Concurrent Builds');
  await ad.page.locator('label:has-text("Throttle this project alone")').first().click();
  const tot = ad.page.locator('input[name="_.maxConcurrentTotal"]').first();
  await tot.fill('1');
  await shot(ad.page, tot.locator('xpath=ancestor::*[contains(@class,"optionalBlock-container")][1]'), 'PR-07-1-configure', { pad: 10 });
  await saveConfig(ad.page);
  await ad.context.close();
  await directThenApproved('PR-07', name);
};

async function directThenApproved(id, name) {
  const b0 = await builds(name);
  const rq = await login('requester');
  await rq.page.goto(`${BASE}/job/${name}/`);
  const side = (await rq.page.locator('#side-panel a').allInnerTexts()).map((t) => t.trim()).filter(Boolean);
  const r = await clickBuildEntry(rq.page, 'Direct Build (needs approval)');
  log(L, `${id} requester sidebar ${JSON.stringify(side)}; Direct Build: HTTP ${r.status} "${r.text.slice(0, 200)}"`);
  await shot(rq.page, '#main-panel', `${id}-2-refusal`, { pad: 8 });
  await sleep(5000);
  const b1 = await builds(name);
  log(L, `${id} after refusal: next ${b0.next}->${b1.next} inQueue=${b1.inQueue}`);
  const url = await requestRun(rq.page, `/job/${name}/`, { reason: `Approved run with the queue plugin configured (${id}).`, approvers: ['approver-1'] });
  await approveAs('approver-1', url);
  await waitBuilt(name, b1.next - 1);
  await sleep(4000);
  const b2 = await builds(name);
  log(L, `${id} approved path: builds ${JSON.stringify(b2.list)} (added ${b2.next - b1.next})`);
  await rq.page.goto(url);
  await shot(rq.page, '#main-panel table', `${id}-3-executed`, { pad: 8 });
  await rq.context.close();
}

// ------------------------------------------------------------ E-08 authorize-project
rows['PR-08'] = async () => {
  const name = 'batch-authz';
  const ad = await login('admin');
  // global: Security -> Access Control for Builds -> Configure Build Authorizations in Project Configuration
  await ad.page.goto(`${BASE}/manage/configureSecurity/`);
  await ad.page.waitForTimeout(1500);
  const addAc = ad.page.locator('button.hetero-list-add').filter({ hasText: /Add/ }).last();
  const sec = ad.page.locator('.jenkins-section, section').filter({ hasText: 'Access Control for Builds' }).first();
  await sec.scrollIntoViewIfNeeded();
  const has = await sec.getByText('Project default Build Authorization', { exact: false }).count() + await sec.getByText('Per-project configurable', { exact: false }).count();
  if (!has) {
    await sec.locator('button.hetero-list-add').first().click();
    await ad.page.waitForTimeout(600);
    await ad.page.locator('.jenkins-dropdown button, .jenkins-dropdown__item').filter({ hasText: 'Per-project configurable Build Authorization' }).first().click();
    await ad.page.waitForTimeout(1200);
  }
  // enable the "Run as Specific User" strategy for projects (off by default)
  const spec = sec.locator('label').filter({ hasText: /Run as Specific User/ }).first();
  if (await spec.count()) {
    const on = await spec.evaluate((x) => { const i = x.parentElement.querySelector('input[type=checkbox]'); return i ? i.checked : null; });
    if (on === false) await spec.click();
  }
  log(L, `E-08 global strategies offered: ${JSON.stringify((await sec.locator('label').allInnerTexts()).map((t) => t.trim()).filter(Boolean))}`);
  await shot(ad.page, sec, 'PR-08-1-global', { pad: 8 });
  await Promise.all([ad.page.waitForNavigation({ waitUntil: 'load' }), ad.page.locator('button[name="Submit"]').click()]);
  // job: Authorization -> Run as Specific User -> admin
  await ad.page.goto(`${BASE}/job/${name}/authorization`);
  await ad.page.waitForTimeout(1200);
  await tick(ad.page, 'Configure Build Authorization');
  const sel = ad.page.locator('#main-panel select').first();
  const opts = await sel.locator('option').allInnerTexts();
  const pick = opts.find((o) => /Specific User/i.test(o));
  await sel.selectOption({ label: pick });
  await ad.page.waitForTimeout(800);
  await ad.page.locator('input[name="_.userid"]').first().fill('admin');
  await shot(ad.page, '#main-panel form', 'PR-08-2-job-authorization', { pad: 8 });
  await Promise.all([ad.page.waitForNavigation({ waitUntil: 'load' }), ad.page.locator('button[name="Submit"], button:has-text("Save")').first().click()]);
  log(L, `E-08 job authorization options ${JSON.stringify(opts)} -> "${pick}" userid=admin saved (${ad.page.url()})`);
  await ad.page.goto(`${BASE}/manage/`);
  log(L, `E-08 /manage SYSTEM-builds warning still shown: ${await ad.page.locator('.jenkins-alert:has-text("no build authenticator")').count()}`);
  await ad.context.close();
  await directThenApproved('PR-08', name);
  const n = (await job(name, 'lastBuild[number]')).lastBuild.number;
  const con = (await api('admin', `/job/${name}/${n}/consoleText`, { raw: true })).text;
  log(L, `E-08 approved build #${n} console head: ${con.split('\n').slice(0, 3).join(' | ')}`);
};

// ------------------------------------------------------------ E-09 jobConfigHistory
rows['PR-09'] = async () => {
  const name = 'batch-jch';
  const rec = async () => (await api('admin', '/batch-control/history/changes.csv')).text.split('\n').filter((l) => l.split(',')[1] === 'CONFIGURE' && l.split(',')[2] === name);
  const ad = await login('admin');
  // settle: first form save normalises defaults added by other plugins
  await openConfig(ad.page, name); await saveConfig(ad.page); await sleep(1500);
  const r0 = (await rec()).length;
  const h0 = (await api('admin', `/job/${name}/jobConfigHistory/api/json`)).json;
  await openConfig(ad.page, name);
  await ad.page.fill('textarea[name="description"]', 'Section E: edited once with jobConfigHistory installed (E-09).');
  await saveConfig(ad.page); await sleep(2000);
  const r1 = (await rec()).length;
  await openConfig(ad.page, name); await saveConfig(ad.page); await sleep(2000);
  const r2 = (await rec()).length;
  log(L, `E-09 CONFIGURE records for ${name}: settled ${r0}, after one real UI save ${r1}, after an unchanged save ${r2}`);
  await ad.page.goto(`${BASE}/job/${name}/jobConfigHistory/`);
  const jch = (await ad.page.locator('#main-panel table tr').allInnerTexts()).slice(0, 5).map((t) => t.replace(/\s+/g, ' '));
  log(L, `E-09 Job Config History rows: ${JSON.stringify(jch)}`);
  await shot(ad.page, '#main-panel table', 'PR-09-1-jobconfighistory', { pad: 8 });
  await ad.page.goto(`${BASE}/batch-control/changes/`);
  await shot(ad.page, ad.page.locator(`#main-panel tr:has-text("${name}")`), 'PR-09-2-change-records', { pad: 8 });
  await ad.context.close();
};

const wanted = process.argv.slice(2);
try {
  for (const id of wanted) {
    if (!rows[id]) { console.log(`no row ${id}`); continue; }
    console.log(`== ${id}`);
    await rows[id]();
  }
} finally {
  await close();
}
