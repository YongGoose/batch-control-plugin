import { login, close, BASE } from '../lib.mjs';
import { uiCancel } from '../audit/restsubmit.mjs';
const { page } = await login('requester');
console.log(await uiCancel(page, `${BASE}/batch-control/grants/20260930-025507-38mqhn/`));
await close();
