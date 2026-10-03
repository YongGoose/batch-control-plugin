"""C2: activation of batch-cron through the real flow with two approvers; approver-2 decides (first decision wins).
usage: c_act.py ACTIVATE|HOLD"""
import re, sys, time
from lib import Session, close, api, log, clean
action = sys.argv[1] if len(sys.argv) > 1 else "ACTIVATE"
tag = "C2a" if action == "ACTIVATE" else "C2h"
res = {"action": action}
s = Session("requester")
s.go("/job/batch-cron/")
s.shot(s.page.get_by_text("Batch Control: this job").first, f"{tag}-01-job-notice", pad=40)
s.go(f"/job/batch-cron/batch-control/activation/?action={action}")
f = s.page.locator("form[name=batch-control-activation]")
res["form_action"] = f.get_attribute("action")
s.page.fill("textarea[name=reason]", f"e2e-07 {action} batch-cron")
s.page.click("#batch-control-approver-0 + label"); s.page.click("#batch-control-approver-1 + label")
s.shot(f, f"{tag}-02-form")
with s.page.expect_navigation() as nav:
    f.locator("button[name=Submit]").click()
s.page.wait_for_load_state("load")
res["submit"] = nav.value.status; res["landed"] = s.page.url
rid = re.search(r"(\d{8}-\d{6}-\w+)", s.page.url)
rid = rid.group(1) if rid else None
res["id"] = rid
s.shot("#main-panel", f"{tag}-03-pending")
s.done()
a = Session("approver-2")
a.go("/batch-control/")
res["approver2_overview"] = re.sub(r"\s+", " ", a.text())[:300]
a.shot("#main-panel", f"{tag}-04-approver2-overview")
a.go("/batch-control/activations/")
a.shot("#main-panel", f"{tag}-05-activations-tab")
a.go(f"/batch-control/activations/{rid}/")
form = a.page.locator("form[action$='approve']").first
if form.count():
    ta = form.locator("textarea")
    if ta.count(): ta.first.fill("ok")
    with a.page.expect_navigation() as nav:
        form.locator("button").first.click()
    a.page.wait_for_load_state("load")
    res["approve"] = nav.value.status
res["after"] = re.sub(r"\s+", " ", a.text())[:400]
a.shot("#main-panel", f"{tag}-06-decided")
a.done()
b = Session("approver-1")
r = b.go(f"/batch-control/activations/{rid}/")
res["approver1_after"] = {"status": r.status, "approve_form": b.page.locator("form[action$='approve']").count()}
b.done(); close()
log("c_act", res)
for k, v in res.items(): print(k, v)
