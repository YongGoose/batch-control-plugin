import { login, close, requestRun, shot } from '../lib.mjs';
import { ev } from './rec.mjs';
import fs from 'node:fs';
const { page } = await login('requester');
const url = await requestRun(page, '/job/batch-pipeline/', { reason: 'Audit: left pending on purpose to observe the 1-hour expiry', approvers: ['approver-2'] });
await shot(page, '#main-panel table', 'EXP-0-pending-created');
fs.writeFileSync('../out/audit-pending.url', url + '\n' + new Date().toISOString());
ev(`EXP pending request ${url} at ${new Date().toISOString()}`);
await close();
