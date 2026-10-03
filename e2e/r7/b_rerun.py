"""B: incident rerun by reqhist (Request+ViewHistory+Read, no Build); approver-1 (no Request) is not offered the form."""
import re
from lib import Session, close, api, log, clean
NOTICE = "The requester does not have Build permission on this job."
INC = "20261003-123943-z4eeb0"
res = {}
s = Session("reqhist")
s.go(f"/batch-control/incidents/{INC}/")
f = s.page.locator("form[name=rerun]")
f.scroll_into_view_if_needed()
s.page.click("#batch-control-rerun-approver-0 + label")
s.shot(f, "B5-01-rerun-form")
with s.page.expect_navigation() as nav:
    f.locator("button[name=Submit]").click()
s.page.wait_for_load_state("load")
res["submit"] = nav.value.status; res["landed"] = s.page.url
res["text"] = re.sub(r"\s+", " ", s.text())[:500]
s.shot("#main-panel", "B5-02-after-rerun")
m = re.search(r"(\d{8}-\d{6}-\w+)", s.page.url.replace(INC, ""))
s.done()
rid = None
t = api("approver-1", "/batch-control/requests/").text
ids = re.findall(r"requests/(\d{8}-\d{6}-\w+)/", t)
for i in ids:
    d = api("approver-1", f"/batch-control/requests/{i}/").text
    if "batch-failing" in d and "reqhist" in d:
        rid = i; res["notice_on_detail"] = NOTICE in clean(d); res["incident_linked"] = INC in d; break
res["rerun_request"] = rid
a = Session("approver-1")
a.go(f"/batch-control/incidents/{INC}/")
res["approver1_rerun_form"] = a.page.locator("form[name=rerun]").count()
a.shot("#main-panel", "B5-03-approver1-incident-no-rerun")
if rid:
    a.go(f"/batch-control/requests/{rid}/")
    a.shot(a.page.get_by_text(NOTICE).first if NOTICE in a.text() else "#main-panel", "B5-04-approver-rerun-detail", pad=60)
a.done(); close()
log("b_rerun", res)
print(res)
