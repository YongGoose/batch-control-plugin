// Mail helpers for the re-audit: read the sink, open a message as the recipient sees it, follow its link after login.
import { MAIL, BASE, launch, shot, password, waitFor } from '../lib.mjs';
export async function findMail(query, { timeout = 30000 } = {}) {
  return waitFor(async () => {
    const r = await (await fetch(`${MAIL}/api/v1/search?query=${encodeURIComponent(query)}&limit=20`)).json();
    return (r.messages || [])[0] || null;
  }, { timeout, every: 2000 });
}
export async function mailText(m) { return (await (await fetch(`${MAIL}/api/v1/message/${m.ID}`)).json()).Text; }
export async function mailShot(m, name) {
  const b = await launch(); const ctx = await b.newContext({ viewport: { width: 1000, height: 800 } }); const p = await ctx.newPage();
  await p.goto(`${MAIL}/view/${m.ID}`); await p.waitForTimeout(2000);
  const s = await shot(p, 'body', name, { pad: 0 }); await ctx.close(); return s;
}
/** Recipient clicks the mail link while logged out: login page, then the target. Returns {status, url, title}. */
export async function followAsRecipient(link, user) {
  const b = await launch(); const ctx = await b.newContext({ viewport: { width: 1280, height: 900 } }); const p = await ctx.newPage();
  await p.goto(link);
  if (p.url().includes('/login')) {
    await p.fill('#j_username', user); await p.fill('input[name="j_password"]', password(user));
    await Promise.all([p.waitForLoadState('load'), p.click('button[name="Submit"], button[type="submit"]')]);
  }
  const url = p.url(); const title = (await p.locator('#main-panel h1, .jenkins-app-bar h1').first().innerText().catch(() => '')).trim();
  await ctx.close();
  return { url, title };
}
