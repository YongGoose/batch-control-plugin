// Coordinator extra check: New Item validation for a name-restricted CREATE grant holder.
import { login, close, shot, BASE, log, changeRows, api } from './lib.mjs';
import { execSync } from 'node:child_process';
const L = 'section-b.log';
const countLog = () => Number(execSync("docker logs batch-control-e2e 2>&1 | grep -c 'AccessDeniedException3: requester is missing the Job/Create permission' || true", { shell: '/bin/bash' }).toString().trim());
const rq = await login('requester'); const p = rq.page;
// grant: FOLDER team, CREATE, /app-[0-9]+/
await p.goto(`${BASE}/batch-control/grants/`);
await p.selectOption('select[name="scopeType"]', 'FOLDER');
await p.fill('input[name="scopeFullName"]', 'team');
await p.locator('#grant-action-create + label').click();
await p.fill('input[name="createNamePattern"]', '/app-[0-9]+/');
await p.selectOption('select[name="durationMinutes"]', '15');
await p.fill('textarea[name="reason"]', 'Name-restricted create, New Item validation check (B7-15 extra).');
await p.locator('#grant-approver-0 + label').click();
await Promise.all([p.waitForLoadState('load'), p.locator('button:has-text("Request Grant")').click()]);
const href = await p.locator('#main-panel table').first().locator('tbody tr', { hasText: 'New Item validation check' }).first().locator('a').first().getAttribute('href');
const ap = await login('approver-1');
await ap.page.goto(new URL(href, p.url()).href);
await ap.page.fill('form[name="approve"] textarea[name="comment"]', 'ok');
await Promise.all([ap.page.waitForLoadState('load'), ap.page.locator('form[name="approve"] button').first().click()]);
const v0 = (await changeRows(/GRANT_VIOLATION/)).length;
for (const [name, tag] of [['app-x', 'non-matching'], ['app-9', 'matching']]) {
  const l0 = countLog();
  await p.goto(`${BASE}/job/team/newJob`);
  await p.locator('#name').pressSequentially(name, { delay: 120 });
  await p.waitForTimeout(1500);
  const under = (await p.locator('#name').locator('xpath=ancestor::*[contains(@class,"jenkins-form-item") or contains(@class,"header")][1]').innerText()).replace(/\s+/g, ' ');
  const msgs = (await p.locator('#itemname-invalid, #itemname-required, .input-message, .input-validation-message').evaluateAll((es) => es.filter((e) => e.offsetParent !== null).map((e) => e.innerText.trim()))).filter(Boolean);
  const okBefore = await p.locator('#ok-button').isDisabled();
  await p.locator('label:has-text("Freestyle project")').first().click();
  await p.waitForTimeout(500);
  const okAfter = await p.locator('#ok-button').isDisabled();
  await shot(p, [p.locator('#name').locator('xpath=ancestor::*[contains(@class,"jenkins-form-item") or contains(@class,"header")][1]'), p.locator('#ok-button')], `B7-15-typing-${tag}`, { pad: 14 });
  const l1 = countLog();
  let submit = 'not clicked';
  if (!okAfter) {
    const [r] = await Promise.all([p.waitForNavigation().catch(() => null), p.locator('#ok-button').click()]);
    submit = `${r && r.status()} ${p.url().replace(BASE, '')} "${(await p.locator('#main-panel, body').first().innerText()).replace(/\s+/g, ' ').slice(0, 120)}"`;
    await shot(p, '#main-panel, body', `B7-15-submit-${tag}`, { pad: 8 });
    if (p.url().includes('/configure')) await Promise.all([p.waitForNavigation(), p.locator('button[name="Submit"]').click()]);
  }
  log(L, `B7-15 extra, grant holder types "${name}" (${tag}): visible under the field ${JSON.stringify(msgs)} (block text "${under.slice(0, 160)}"); OK disabled before/after choosing a type ${okBefore}/${okAfter}; OK -> ${submit}; AccessDeniedException3 log lines while typing: +${l1 - l0} (${name.length} keystrokes)`);
}
log(L, `B7-15 extra: GRANT_VIOLATION records added ${(await changeRows(/GRANT_VIOLATION/)).length - v0}; team/app-9 exists ${(await api('admin', '/job/team/job/app-9/api/json')).status}; total AccessDeniedException3 lines for requester Job/Create in the log: ${countLog()}`);
const sample = execSync("docker logs batch-control-e2e 2>&1 | grep -B2 -A3 'AccessDeniedException3: requester is missing the Job/Create permission' | head -8", { shell: '/bin/bash' }).toString();
log(L, `B7-15 extra log sample:\n${sample}`);
await close();
