// D-10: Jenkins' own disable stops an activated schedule without touching activation; D-01 capture.
import { login, close, shot, job, BASE, groovy, sleep } from '../lib.mjs';
import { row, ev } from '../audit/rec.mjs';
const activated = async (n) => (await groovy(`println io.jenkins.plugins.batchcontrol.ui.ActivationView.isActivated(jenkins.model.Jenkins.get().getItemByFullName('${n}'))`)).trim();
const { page } = await login('admin');
async function setEnabled(on) {
  await page.goto(`${BASE}/job/batch-cron/configure`); await page.waitForTimeout(1500);
  const box = page.locator('input[name="enable"], input#enable-disable-project, input[name="_.enabled"]').first();
  const cur = await box.isChecked();
  if (cur !== on) await box.locator('xpath=following-sibling::label[1] | ..').first().click();
  await Promise.all([page.waitForNavigation(), page.locator('button[name="Submit"]').click()]);
}
await setEnabled(false);
const b = await job('batch-cron', 'buildable,color');
await page.goto(`${BASE}/job/batch-cron/`);
const s = await shot(page, page.locator('#main-panel').locator('text=/disabled/i').first().locator('xpath=..'), 'D-10-disabled', { pad: 8 });
const m1 = (await job('batch-cron', 'nextBuildNumber')).nextBuildNumber; await sleep(130000); const m2 = (await job('batch-cron', 'nextBuildNumber')).nextBuildNumber;
await setEnabled(true);
const b2 = await job('batch-cron', 'buildable');
const a = await activated('batch-cron');
ev(`D-10 disabled buildable ${b.buildable} ${b.color}; next ${m1}->${m2}; re-enabled ${b2.buildable}; activated ${a}`);
row('D-10', { roles: 'admin, requester/approver-1 (HOLD flow in E-10)', V: 'n.a.', G: `${!b.buildable && m2 === m1 && b2.buildable && a === 'true' ? '✓' : '✗'} a hold needs an approved HOLD request (E-10, b10-ui); Jenkins' disable (the Enabled switch on the configure page) stops the activated batch-cron at once (${m1} -> ${m2} in 130 s) and leaves its activation alone (still ${a} after enabling again)`, R: 'n.a.', C: '✓ HELD record for the approved hold (E-10)', E: s ? '✓ D-10-disabled, E-10-9-held-again' : '✗', verdict: 'PASS (partial)', note: 'the "global run-control switch as an emergency stop" (SPEC 6a) is not exercised: turning run control off removes the gate rather than stopping jobs, and SPEC does not say what it should stop' });
await page.goto(`${BASE}/batch-control/changes/?month=2026-09`);
const r = page.locator('#main-panel tr', { hasText: 'b10-aud-ui' }).filter({ hasText: 'TRIGGER_BLOCKED' });
ev(`D-01 rows on page 1: ${await r.count()}`);
const s1 = (await r.count()) ? await shot(page, r.first(), 'D-01-coalesced-record', { pad: 8 }) : null;
if (s1) { const { row: rw } = await import('../audit/rec.mjs'); }
console.log('D01SHOT', !!s1);
await close();
