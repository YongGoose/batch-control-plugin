"""Scenario 2c: assign a user to the item role on Assign Roles (index), then delete the role."""
from s2common import *
s = Session("admin")
calls = []
s.page.on("request", lambda r: calls.append(f"{r.method} {r.url}") if ("role-strategy" in r.url or "descriptorByName" in r.url) and r.resource_type in ("fetch", "xhr", "document") else None)
s.go("/manage/role-strategy/")
# Item roles table: add user mover1 and tick e2e-prod
tables = s.page.locator("table")
print("tables:", tables.count())
item_tbl = s.page.locator("#projectRoles, table").nth(1)
print("item table head:", item_tbl.locator("thead, tr").first.inner_text()[:200])
btns = s.page.locator("button", has_text="Add User")
print("add user buttons:", btns.count())
s.page.once("dialog", lambda dlg: dlg.accept("mover1"))
btns.nth(1).click(); s.page.wait_for_timeout(800)
d = s.page.locator("dialog[open]")
if d.count():
    print("add user dialog:", d.first.inner_text()[:200])
    d.first.locator("input").first.fill("mover1")
    d.first.locator("button.jenkins-button--primary, button", has_text="OK").first.click()
    s.page.wait_for_timeout(800)
row = item_tbl.locator("tr", has_text="mover1").first
print("row:", row.count())
cbs = row.locator("input[type=checkbox]")
print("row checkboxes:", cbs.count(), [c.get_attribute("title") or c.get_attribute("name") for c in cbs.all()])
head = [h.inner_text().strip() for h in item_tbl.locator("th").all()]
print("head:", head)
# tick column of e2e-prod
idx = None
for i, c in enumerate(cbs.all()):
    t = (c.get_attribute("title") or "") + (c.get_attribute("name") or "") + (c.get_attribute("data-role") or "")
    if "e2e-prod" in t:
        idx = i
if idx is None and cbs.count():
    idx = 0
cb = cbs.nth(idx)
try:
    cb.check(timeout=3000)
except Exception:
    cb.evaluate("e => e.click()")
s.page.wait_for_timeout(300)
s.shot(row, "S2-05-assign-roles-row")
with s.page.expect_navigation():
    s.page.locator("button", has_text="Save").last.click()
s.page.wait_for_load_state("load")
print("after save url:", s.page.url)
print("calls:", calls); print("console:", s.console); print("bad:", s.bad)
s.done(); close()
print(groovy('''def s=jenkins.model.Jenkins.get().getAuthorizationStrategy(); def m=s.getGrantedRolesEntries(com.michelin.cio.hudson.plugins.rolestrategy.RoleType.Project); return m.collect{ r,es -> r.name+"="+es*.sid }'''))
check("2c assign roles save")
