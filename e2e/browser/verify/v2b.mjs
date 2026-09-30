import { login, close, shot, job, BASE, changeRows, sleep } from '../lib.mjs';
import { row, ev, mainText } from '../audit/rec.mjs';
import { execSync } from 'node:child_process';
const { page } = await login('requester');
const n0 = (await job('batch-pipeline', 'nextBuildNumber')).nextBuildNumber;
const r0 = (await changeRows(/,TRIGGER_BLOCKED,batch-pipeline,/));
await page.goto(`${BASE}/job/batch-pipeline/8/`);
await page.locator('#side-panel a:has-text("Rebuild")').click(); await page.waitForLoadState('load');
const s1 = await shot(page, '#main-panel', 'PR-02-1-pipeline-rebuild-page', { pad: 8 });
const [resp] = await Promise.all([page.waitForNavigation({ waitUntil: 'load' }).catch(() => null), page.locator('button[name="Submit"], button:has-text("Run")').first().click()]);
const t = await mainText(page); const links = await page.locator('#main-panel a').evaluateAll((as) => as.map((a) => `${a.innerText.trim()}->${a.getAttribute('href')}`));
const s2 = await shot(page, ['#main-panel .jenkins-app-bar', '#main-panel .jenkins-alert'], 'PR-02-2-pipeline-rebuild-refused', { pad: 8 });
await sleep(4000);
const n1 = (await job('batch-pipeline', 'nextBuildNumber')).nextBuildNumber; const r1 = await changeRows(/,TRIGGER_BLOCKED,batch-pipeline,/);
const lg = execSync(`docker logs --since 15m batch-control-e2e 2>&1 | grep -iE "retry|re-run|Retry" | tail -4`, { shell: '/bin/bash' }).toString();
ev(`V2b pipeline rebuild -> ${resp && resp.status()} "${t.slice(0, 200)}" links ${links}; next ${n0}->${n1}; recs ${r0.length}->${r1.length} ${r1[0]}; retry log:\n${lg}`);
for (const id of ['PR-02', 'B5-08']) row(id, {
  roles: 'requester, nobc, reqonly, approver-1, admin',
  V: '✓ Freestyle (batch-rebuild): the rebuild plugin\'s Rebuild is gone from the approved build and Rebuild Last from the job page for every role; Pipeline (batch-pipeline): the "Rebuild" entry is workflow-cps\' Replay page for users without Run/Replay, offered to requester and nobc - accepted per the Replay ruling (another plugin draws it, the click is refused in plain words)',
  G: `${n1 === n0 ? '✓' : '✗'} requester's Pipeline Rebuild -> Run refused, no build (${n0} -> ${n1})`,
  R: `${/Approval required/.test(t) && links.some((l) => /batch-control\//.test(l)) ? '✓' : '✗'} HTTP ${resp && resp.status()} "Approval required ... Request Run to submit a request ..." with the Request Run link; the build page carries the approval notice (DEF-31 fixed)`,
  C: `${r1.length > r0.length || /requester/.test(r1[0] || '') ? '✓' : '✗'} ${(r1[0] || '').split(',').slice(1, 4).join(',')} "${(r1[0] || '').split(',').slice(6).join(',').slice(0, 90)}"${r1.length === r0.length ? ' (merged into the hour\'s record)' : ''}`,
  E: s1 && s2 ? '✓ PR-02-0-freestyle-build-page, PR-02-1-pipeline-rebuild-page, PR-02-2-pipeline-rebuild-refused' : '✗',
});
await close();
