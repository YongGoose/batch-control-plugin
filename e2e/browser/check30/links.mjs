// Re-check on 9fabbf3 (DEF-41): which re-run links a user sees on a marked run, on an unmarked run and on the job page.
// Usage: BC_SHOTS=... node links.mjs <markedRun> <unmarkedRun>
import { login, close, BASE, shot } from '../lib.mjs';
import { ev } from '../audit/rec.mjs';
const J = 'batch-upstream';
const [M, U] = process.argv.slice(2);
for (const u of ['requester', 'configurer', 'admin']) {
  const { page, context } = await login(u);
  for (const [tag, p] of [['marked', `/job/${J}/${M}/`], ['unmarked', `/job/${J}/${U}/`], ['job', `/job/${J}/`]]) {
    await page.goto(`${BASE}${p}`);
    const links = await page.locator('#tasks a').evaluateAll((as) => as.map((a) => `${a.innerText.trim()}=${a.getAttribute('href')}`).filter((t) => /Rebuild|Replay|Retry/.test(t)));
    const notice = (await page.locator('#main-panel .jenkins-alert, #main-panel p').filter({ hasText: /replayed under a|permission window/i }).allInnerTexts()).map((t) => t.replace(/\s+/g, ' ')).join(' | ');
    ev(`RECHECK links ${u} ${tag} ${p}: ${JSON.stringify(links)} notice="${notice.slice(0, 300)}"`);
    if (u !== 'admin' && tag !== 'job') await shot(page, page.locator('#tasks').first(), `DEF-41-${u}-${tag}-sidebar`, { pad: 8 });
  }
  await context.close();
}
await close();
