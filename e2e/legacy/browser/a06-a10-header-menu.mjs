import { login, close, BASE, shot } from './lib.mjs';
for (const u of ['requester', 'nobc']) {
  const { page } = await login(u);
  await page.goto(BASE + '/');
  await page.click('#header-more-actions');
  await page.waitForTimeout(800);
  const items = await page.locator('.tippy-box a, .tippy-box button, .jenkins-dropdown a, .jenkins-dropdown__item').evaluateAll(as => as.map(a => (a.innerText || '').trim() + ' ' + (a.getAttribute('href') || '')));
  console.log(u, items);
  const menu = page.locator('.tippy-box, .jenkins-dropdown').first();
  await shot(page, [menu, page.locator('#header-more-actions')], u === 'nobc' ? 'A-06-3-nobc-header-menu' : 'A-10-root-entry-menu', { pad: 10 });
  await page.context().close();
}
await close();
