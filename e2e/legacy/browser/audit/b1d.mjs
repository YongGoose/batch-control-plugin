// B1-20 (save failure) and B1-21 (JCasC at boot with an unwritable file). Usage: node b1d.mjs b120 | b121pre | b121post
import { login, close, shot, BASE, setGlobal, globalCfg, changeRows, clickBuildEntry, job, sleep, api } from '../lib.mjs';
import { row, ev, mainText } from './rec.mjs';
import { execSync } from 'node:child_process';
const F = '/var/jenkins_home/io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration.xml';
const sh = (c) => execSync(`docker exec -u jenkins batch-control-e2e sh -c '${c}'`).toString();
const block = () => sh(`mv ${F} ${F}.audit-bak && mkdir ${F}`);
const unblock = () => sh(`rmdir ${F} && mv ${F}.audit-bak ${F}`);
const toggles = async () => (await changeRows(/,CONFIG_TOGGLE,/)).length;
const gate = async () => { const n = (await job('batch-lock', 'nextBuildNumber')).nextBuildNumber; const r = await api('requester', '/job/batch-lock/build', { method: 'POST' }); await sleep(4000); return { status: r.status, built: (await job('batch-lock', 'nextBuildNumber')).nextBuildNumber > n }; };
const phase = process.argv[2];
if (phase === 'b120') {
  const ad = await login('admin');
  const t0 = await toggles();
  block();
  const r = await setGlobal(ad.page, { runControlEnabled: false });
  const s1 = await shot(ad.page, '#main-panel', 'B1-20-1-save-failed', { pad: 8 });
  const mem = (await globalCfg()).run;
  await ad.page.goto(`${BASE}/manage/configure`); await ad.page.waitForTimeout(1200);
  const form = await ad.page.locator('[name="_.runControlEnabled"]').first().isChecked();
  const g = await gate();
  const t1 = await toggles();
  unblock();
  ev(`B1-20 save -> ${r.status} "${r.text}"; memory run=${mem}; form shows ${form}; gate ${JSON.stringify(g)}; toggles ${t0}->${t1}`);
  row('B1-20', { roles: 'admin, requester (gate)', V: 'n.a.', G: `${mem === true && form === true && !g.built && t1 === t0 ? '✓' : '✗'} Save with an unwritable config file -> error; run control still on in memory (${mem}) and in the reopened form (${form}); the gate still refuses (${g.status}, built ${g.built})`, R: `${/could not be saved/.test(r.text) ? '✓' : '✗'} "${r.text.slice(0, 200)}"`, C: `${t1 === t0 ? '✓' : '✗'} no CONFIG_TOGGLE written (${t0} -> ${t1})`, E: s1 ? '✓ B1-20-1-save-failed' : '✗', note: 'the page shows the raw file path and Java exception text (U-25)' });
  await close();
} else if (phase === 'b121pre') {
  const ad = await login('admin');
  await setGlobal(ad.page, { runControlEnabled: false });
  ev(`B1-21 pre: run=${(await globalCfg()).run}; toggles ${await toggles()}`);
  await close();
  block();
} else if (phase === 'b121post') {
  const mem = (await globalCfg()).run;
  const g = await gate();
  const log = execSync('docker logs --since 3m batch-control-e2e 2>&1 | grep -iE "SEVERE|will not survive" | head -5').toString();
  const last = (await changeRows(/,CONFIG_TOGGLE,runControlEnabled,/))[0];
  unblock();
  // make the in-memory state durable again (JCasC reload writes the file)
  const rl = await api('admin', '/manage/configuration-as-code/reload', { method: 'POST' });
  const ad = await login('admin'); await ad.page.goto(`${BASE}/manage/configure`);
  const s = await shot(ad.page, ad.page.locator('[name="_.runControlEnabled"]').first().locator('xpath=ancestor::*[contains(@class,"jenkins-form-item")][1]'), 'B1-21-run-control-on-after-boot', { pad: 8 });
  ev(`B1-21 post: run=${mem}; gate ${JSON.stringify(g)}; log ${log}; last toggle ${last}; reload ${rl.status}`);
  row('B1-21', { roles: 'admin, requester (gate)', V: 'n.a.', G: `${mem === true && !g.built ? '✓' : '✗'} saved off, config file made unwritable, restart: JCasC turned run control on (${mem}), the gate refuses (${g.status}, built ${g.built})`, R: 'n.a.', C: `${/SEVERE/.test(log) && /SYSTEM/.test(last || '') && /false -> true/.test(last || '') ? '✓' : '✗'} boot log: ${log.replace(/\s+/g, ' ').slice(0, 220)}; record: ${(last || '').split(',').slice(1, 4).join(',')} ${(last || '').split(',').slice(6).join(',')}`, E: s ? '✓ B1-21-run-control-on-after-boot + boot log excerpt' : '✗' });
  await close();
}
