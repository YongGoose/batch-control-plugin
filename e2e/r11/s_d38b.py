"""D-38b regression: opsreq holds BatchControl/Request + Item/Read only on folder ops (side/: Item/Read only).
Request Run dialog on the new job page of ops/job-a submits; side/job-b offers no Request Run and refuses the submit."""
import re
from lib import Session, close, api, log
res = {}
s = Session("opsreq")
s.go("/job/ops/job/job-a/")
bar = s.page.locator("[data-testid=app-bar-overflow-button]").first.locator("xpath=..")
res["appbar ops/job-a"] = [re.sub(r"\s+", " ", b.inner_text()).strip() for b in bar.locator("a, button").all() if b.inner_text().strip()]
bar.locator("a, button", has_text="Request Run").first.click()
s.page.wait_for_selector("dialog[open] textarea[name=reason]")
d = s.page.locator("dialog[open]").first
d.locator("textarea[name=reason]").fill("e2e-11 D-38b folder-scoped requester")
d.locator("input[name=approvers][value=approver-1]").locator("xpath=following-sibling::label").click()
s.shot("dialog[open]", "D38B-01-dialog")
d.get_by_role("button", name="Submit Request").click()
s.page.wait_for_url(re.compile(r".*/batch-control/requests/[0-9a-f-]{36}/$"), timeout=15000)
res["landing"] = s.page.url
s.shot("#main-panel", "D38B-02-detail")
s.go("/job/side/job/job-b/")
res["appbar side/job-b"] = [re.sub(r"\s+", " ", b.inner_text()).strip() for b in s.page.locator("[data-testid=app-bar-overflow-button]").first.locator("xpath=..").locator("a, button").all() if b.inner_text().strip()]
s.done()
res["side submit"] = api("opsreq", "/job/side/job/job-b/batch-control/submit", "POST", data={"reason": "x", "approvers": "approver-1"}).status_code
res["side dialog GET"] = api("opsreq", "/job/side/job/job-b/batch-control/dialog").status_code
close(); log("s_d38b", res)
for k, v in res.items(): print(k, "=>", v)
