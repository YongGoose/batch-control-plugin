"""Scenario 2b/2c: Manage Roles - edit the item role (pattern check) and remove it."""
from s2common import *
s = Session("admin")
calls = []
s.page.on("request", lambda r: calls.append(f"{r.method} {r.url}") if ("role-strategy" in r.url or "descriptorByName" in r.url) and r.resource_type in ("fetch", "xhr") else None)
s.go("/manage/role-strategy/manage-roles")
s.page.click("#rsp-tab-projectRoles"); s.page.wait_for_timeout(300)
card = s.page.locator(".rsp-card", has_text="e2e-prod").first
acts = card.locator("button")
print("card actions:", [(b.get_attribute("aria-label"), b.get_attribute("title"), b.get_attribute("tooltip"), b.get_attribute("data-html-tooltip")) for b in acts.all()])
acts.nth(0).click(); s.page.wait_for_timeout(1200)
d = s.page.locator("dialog[open]").first
print("pattern check:", d.inner_text()[:300].replace("\n", " | "))
s.shot(d, "S2-03a-pattern-check")
s.page.keyboard.press("Escape"); s.page.wait_for_timeout(500)
acts.nth(1).click(); s.page.wait_for_timeout(800)
d = s.page.locator("dialog[open]").first
print("edit dialog:", d.inner_text()[:200].replace("\n", " | "))
pat = d.locator("#rsp-role-pattern")
if pat.count():
    pat.fill("prod(/.*)?")
links = d.locator("a, button")
print("dialog buttons:", [l.inner_text()[:40] for l in links.all()])
s.page.wait_for_timeout(800)
s.shot(d, "S2-03-edit-item-role-dialog")
d.locator("button.jenkins-button--primary").last.click(timeout=5000)
s.page.wait_for_timeout(1500)
s.shot(s.page.locator(".rsp-card", has_text="e2e-prod").first, "S2-04-item-role-edited")
print("after edit:", s.page.locator(".rsp-card", has_text="e2e-prod").first.inner_text())
print("calls:", calls); print("console:", s.console); print("bad:", s.bad)
s.done(); close()
check("2b edit item role pattern")
