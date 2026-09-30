// Prints the Batch Control links a user sees on the landing page and the job action (discovery helper).
import { login, BASE, close } from './lib.mjs';
const user = process.argv[2] || 'admin';
const { page } = await login(user);
for (const u of ['/batch-control/', '/job/fresh-daily/']) {
  await page.goto(BASE + u);
  const links = await page.$$eval('a[href]', (as) => as.map((a) => a.getAttribute('href')).filter((h) => /batch-control/.test(h)));
  console.log(u, [...new Set(links)].join('\n  '));
}
await close();
