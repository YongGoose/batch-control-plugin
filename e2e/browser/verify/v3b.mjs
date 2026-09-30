import { job, sleep } from '../lib.mjs';
import { execSync } from 'node:child_process';
const a = (await job('batch-daily', 'nextBuildNumber')).nextBuildNumber;
try { execSync('../scripts/cli.sh requester build batch-daily -p DATE=2031-03-04 2>&1', { shell: '/bin/bash' }); } catch (e) {}
await sleep(6000);
console.log(a, (await job('batch-daily', 'nextBuildNumber')).nextBuildNumber);
