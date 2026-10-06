"""R4-9: Request Change Permission from the new job page's overflow menu (requester, batch-daily) opens the dialog
on the job page, pre-filled; submit lands on the new request's detail page. Also the dialog URL of a nested job."""
import re, json
from lib import Session, close, api, log
res = {}
s = Session("requester")
s.go("/job/batch-daily/")
s.page.locator("[data-testid=app-bar-overflow-button]").first.click()
s.page.wait_for_timeout(1200)
res["overflow_items"] = [t.strip() for t in s.page.locator(".tippy-box a, .tippy-box button").all_inner_texts() if t.strip()]
s.shot(".tippy-box", "N-01-overflow-menu")
s.page.locator(".tippy-box a, .tippy-box button", has_text="Request Change Permission").first.click()
s.page.wait_for_selector("dialog[open] input[name=scopeFullName]")
d = s.page.locator("dialog[open]").first
res["url_while_open"] = s.page.url
res["prefill"] = ((d.locator("[data-batch-control-item-kind]").first.get_attribute("data-batch-control-item-kind") if d.locator("[data-batch-control-item-kind]").count() else None), d.locator("input[name=scopeFullName]").input_value(),
                  [x.get_attribute("value") for x in d.locator("input[name=actions]").all() if x.is_checked()])
d.locator("textarea[name=reason]").fill("e2e-11 new job page menu grant")
d.locator("input[name=approvers][value=approver-2]").locator("xpath=following-sibling::label").click()
s.shot("dialog[open]", "N-02-grant-dialog-newpage")
d.get_by_role("button", name="Request Grant").click()
s.page.wait_for_url(re.compile(r".*/batch-control/grants/[0-9a-f-]{36}/$"), timeout=15000)
res["landing"] = s.page.url
res["detail"] = re.sub(r"\s+", " ", s.text())[:200]
s.shot("#main-panel", "N-03-grant-detail")
s.done()
m = json.loads(api("requester", "/job/ops/job/job-a/contextMenu?menu-only=true").text)
res["nested dialog-url"] = [i["event"].get("attributes", {}).get("dialog-url") for i in m["items"] if i["displayName"] == "Request Change Permission"]
res["nested dialog GET"] = api("requester", "/batch-control/grants/dialog?scopeFullName=ops/job-a").status_code
m = json.loads(api("requester", "/job/batch-daily/contextMenu?menu-only=true").text)
res["build-less job Rebuild Last url (third-party)"] = [i["event"].get("url") for i in json.loads(api("requester", "/job/ops/job/job-a/contextMenu?menu-only=true").text)["items"] if i["displayName"] == "Rebuild Last"]
close(); log("s_newmenu", res)
for k, v in res.items(): print(k, "=>", v)
