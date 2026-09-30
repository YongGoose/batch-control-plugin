// Exploration aid: node peek.mjs <user|-> <path> [--links] [--forms]
// Prints status, title, side panel and main text of a page as that user sees it.
import { login, BASE, text, sidebar, close, OUT } from './lib.mjs';
import path from 'node:path';

const [user, p, ...flags] = process.argv.slice(2);
const { page } = await login(user === '-' ? null : user);
const resp = await page.goto(BASE + p);
console.log('STATUS', resp && resp.status(), 'URL', page.url());
console.log('TITLE', await page.title());
console.log('--- SIDE ---\n' + (await sidebar(page)));
console.log('--- MAIN ---\n' + (await text(page)).slice(0, 6000));
if (flags.includes('--links')) {
  const links = await page.$$eval('#main-panel a[href], #side-panel a[href], #tasks a[href]', (as) => as.map((a) => `${a.innerText.trim().slice(0, 50)} -> ${a.getAttribute('href')}`));
  console.log('--- LINKS ---\n' + links.join('\n'));
}
if (flags.includes('--forms')) {
  const f = await page.$$eval('#main-panel form', (fs) => fs.map((f) => `${f.method} ${f.getAttribute('action')} [${[...f.elements].map((e) => `${e.name}:${e.type}`).join(', ')}]`));
  console.log('--- FORMS ---\n' + f.join('\n'));
}
await page.screenshot({ path: path.join(OUT, 'peek.png'), fullPage: true });
await close();
