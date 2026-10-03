"""Scenario 2a: Manage Roles - add an item role with a pattern."""
from s2common import *
s = Session("admin")
calls = []
s.page.on("request", lambda r: calls.append(f"{r.method} {r.url}") if "role-strategy" in r.url and r.resource_type in ("fetch", "xhr", "document") else None)
s.go("/manage/role-strategy/manage-roles")
s.page.click("#rsp-tab-projectRoles"); s.page.wait_for_timeout(300)
s.page.click("#rsp-add-role-btn"); s.page.wait_for_timeout(500)
d = s.page.locator("dialog[open]").first
d.locator("#rsp-role-name").fill("e2e-prod")
d.locator("#rsp-role-pattern").fill("prod/.*")
d.locator("label[for$='-hudson.model.Item.Read']").first.click()
s.page.wait_for_timeout(800)
s.shot(d, "S2-01-add-item-role-dialog")
d.locator("button", has_text="Add").click()
s.page.wait_for_timeout(1500)
print("after add text:", s.text()[:600])
s.shot("#main-panel", "S2-02-item-role-added")
print("calls:", calls); print("console:", s.console); print("bad:", s.bad)
cards = s.page.locator(".rsp-card")
print("cards:", cards.count(), [c.inner_text()[:80] for c in cards.all()])
s.done(); close()
check("2a add item role")
