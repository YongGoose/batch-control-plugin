import { login, close, log, setGlobal, globalCfg, changeRows, api, job, sleep } from './lib.mjs';
import { execSync } from 'node:child_process';
const L = 'section-b.log';
const f = '/var/jenkins_home/io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration.xml';
const phase = process.argv[2];
if (phase === 'before') {
  const ad = await login('admin');
  await setGlobal(ad.page, { runControlEnabled: false });
  log(L, `B1-21 arrange: run control saved off: ${(await globalCfg()).run}`);
  execSync(`docker exec batch-control-e2e sh -c 'cp ${f} /tmp/bc-cfg.bak && rm ${f} && mkdir ${f} && echo x > ${f}/keep'`);
  await close();
} else {
  const cfg = await globalCfg();
  const t = await changeRows(/CONFIG_TOGGLE,runControlEnabled/);
  const n = (await job('batch-pipeline')).nextBuildNumber;
  const b = await api('requester', '/job/batch-pipeline/build', { method: 'POST' });
  await sleep(3000);
  const logtxt = execSync('docker logs --since 4m batch-control-e2e 2>&1').toString().split('\n').filter((l) => /SEVERE|Batch Control configuration|could not be (saved|written)/i.test(l)).slice(0, 4);
  log(L, `B1-21 after boot with blocked file: run=${cfg.run} change=${cfg.change}; REST build -> ${b.status} next ${n}->${(await job('batch-pipeline')).nextBuildNumber}; latest toggle ${t[0]}; log: ${logtxt.join(' || ')}`);
  execSync(`docker exec batch-control-e2e sh -c 'rm -rf ${f} && cp /tmp/bc-cfg.bak ${f}'`);
  const ad = await login('admin');
  await setGlobal(ad.page, { runControlEnabled: true });
  log(L, `B1-21 file restored and saved from the UI: ${JSON.stringify(await globalCfg())}`);
  await close();
}
