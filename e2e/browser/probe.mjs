// Ad-hoc probe: node probe.mjs <user> <path> [selector]
import { login, close, BASE } from './lib.mjs';
const [user, p, sel] = process.argv.slice(2);
const { page } = await login(user === '-' ? null : user);
const r = await page.goto(BASE + p);
console.log('HTTP', r.status(), page.url());
const text = await page.locator(sel || '#main-panel').first().innerText().catch(e => 'ERR ' + e.message);
console.log(text.slice(0, 4000));
await close();
