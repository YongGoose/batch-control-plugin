"""Scenario 2d/2e: permission template add + role from template, delete role, delete template."""
from s2common import *
s = Session("admin")
calls = []
s.page.on("request", lambda r: calls.append(f"{r.method} {r.url.split('/jenkins')[1]}") if ("role-strategy" in r.url) and r.resource_type in ("fetch", "xhr") else None)
s.page.on("dialog", lambda dlg: (print("native dialog:", dlg.message), dlg.accept()))
s.go("/manage/role-strategy/permission-templates")
print("templates page:", s.text()[:400].replace("\n", " | "))
print("buttons:", [(b.inner_text()[:30], b.get_attribute("id"), b.get_attribute("aria-label")) for b in s.page.locator("#main-panel button").all()][:15])
add = s.page.locator("#main-panel button", has_text="Add").first
add.click(); s.page.wait_for_timeout(800)
d = s.page.locator("dialog[open]").first
print("tpl dialog:", d.inner_text()[:150].replace("\n", " | "))
d.locator("input[type=text]").first.fill("e2e-tpl")
d.locator("label[for$='-hudson.model.Item.Read']").first.click()
d.locator("label[for$='-hudson.model.Item.Workspace']").first.click()
s.shot(d, "S2-06-add-template-dialog")
d.locator("button.jenkins-button--primary").last.click(timeout=5000)
s.page.wait_for_timeout(1500)
s.shot("#main-panel", "S2-07-template-added")
print("after add:", s.text()[:400].replace("\n", " | "))
s.done()
check("2d add permission template")

# delete the item role e2e-prod
s = Session("admin")
s.page.on("request", lambda r: calls.append(f"{r.method} {r.url.split('/jenkins')[1]}") if ("role-strategy" in r.url) and r.resource_type in ("fetch", "xhr") else None)
s.page.on("dialog", lambda dlg: (print("native dialog:", dlg.message), dlg.accept()))
s.go("/manage/role-strategy/manage-roles")
s.page.click("#rsp-tab-projectRoles"); s.page.wait_for_timeout(300)
card = s.page.locator(".rsp-card", has_text="e2e-prod").first
card.locator("button[aria-label='Delete role']").click(); s.page.wait_for_timeout(800)
d = s.page.locator("dialog[open]")
if d.count():
    print("delete dialog:", d.first.inner_text()[:200].replace("\n", " | "))
    s.shot(d.first, "S2-08-delete-role-confirm")
    d.first.locator("button.jenkins-button--primary, button[data-id=ok]").last.click()
    s.page.wait_for_timeout(1500)
print("roles now:", [c.inner_text()[:40].replace("\n", " ") for c in s.page.locator(".rsp-card").all()])
s.done()
check("2e remove item role")

# delete the template
s = Session("admin")
s.page.on("request", lambda r: calls.append(f"{r.method} {r.url.split('/jenkins')[1]}") if ("role-strategy" in r.url) and r.resource_type in ("fetch", "xhr") else None)
s.page.on("dialog", lambda dlg: (print("native dialog:", dlg.message), dlg.accept()))
s.go("/manage/role-strategy/permission-templates")
card = s.page.locator(".rsp-card", has_text="e2e-tpl").first
print("tpl card actions:", [b.get_attribute("aria-label") for b in card.locator("button").all()])
card.locator("button.jenkins-\\!-destructive-color, button[aria-label*='Delete']").first.click(); s.page.wait_for_timeout(800)
d = s.page.locator("dialog[open]")
if d.count():
    print("delete tpl dialog:", d.first.inner_text()[:200].replace("\n", " | "))
    d.first.locator("button.jenkins-button--primary, button[data-id=ok]").last.click()
    s.page.wait_for_timeout(1500)
print("templates now:", s.text()[:300].replace("\n", " | "))
print("calls:", calls); print("console:", s.console); print("bad:", s.bad)
s.done(); close()
check("2f remove permission template")
