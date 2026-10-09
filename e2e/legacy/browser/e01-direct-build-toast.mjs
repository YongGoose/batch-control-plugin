import { login, close, BASE, shot } from './lib.mjs';
for (const [u, j] of [['requester', 'batch-cbn'], ['requester', 'batch-lock'], ['admin', 'batch-cbn']]) {
  const { page } = await login(u);
  await page.goto(`${BASE}/job/${j}/`);
  const side = (await page.locator('#side-panel a').allInnerTexts()).map((t) => t.trim()).filter(Boolean);
  const entry = page.locator('#side-panel a').filter({ hasText: /Direct Build|Run it now|Build Now/ }).first();
  const label = (await entry.innerText()).trim();
  await entry.click();
  await page.waitForTimeout(1500);
  const toast = page.locator('#notification-bar, .jenkins-notification, [class*="notification"]').first();
  const tt = await toast.innerText().catch(() => '');
  console.log(u, j, JSON.stringify(side), 'clicked', label, '-> toast:', tt.replace(/\s+/g, ' '));
  await shot(page, [entry, toast], `E-01-3-${u}-${j}-toast`, { pad: 12 });
  await page.context().close();
}
await close();
