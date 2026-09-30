// Ad-hoc: full-page screenshots to the scratchpad for orientation (not evidence).
import { login, close, BASE } from './lib.mjs';
const SP = '/private/tmp/claude-501/-Users-mac-al03175622-Desktop-coding-personal-batch-control-plugin/9f0fec8e-9a36-4be9-b065-acef1132a8bf/scratchpad/';
const [user, ...paths] = process.argv.slice(2);
const { page } = await login(user);
for (const p of paths) {
  const r = await page.goto(BASE + p);
  const name = (user + p).replace(/[^a-z0-9]+/gi, '_');
  await page.screenshot({ path: SP + name + '.png', fullPage: true });
  console.log(p, r.status(), SP + name + '.png');
}
await close();
