"""R4-1/R4-2 (Caffeine cache): the 'requester lacks Build' notice on a run request's detail page. reqonly (Request,
no Build) files a request; approver-1 sees the notice; a request by `classic` (has Build) shows none."""
import re, sys
from lib import Session, close, api, log
res = {}
r = api("reqonly", "/job/batch-pipeline/batch-control/submit", "POST", data={"reason": "e2e-11 reqonly", "approvers": "approver-1"}) if len(sys.argv) < 3 else type("R", (), {"status_code": "reused", "headers": {}})()
loc = r.headers.get("Location", "")
res["reqonly_submit"] = (r.status_code, loc)
m = re.search(r"/requests/([^/]+)/", loc)
rid = m.group(1) if m else sys.argv[2]
a = Session("approver-1")
for k, p in (("reqonly", rid), ("classic", sys.argv[1])):
    a.go(f"/batch-control/requests/{p}/")
    res[k] = [t.strip() for t in a.page.locator("#main-panel [role=status]").all_inner_texts() if t.strip()]
    a.shot("#main-panel", f"LB-{k}-detail")
a.done()
for k, p in (("reqonly", rid), ("classic", sys.argv[1])):   # second render (cached answer)
    res[k + " second"] = [("does not have Build permission" in api(u, f"/batch-control/requests/{p}/").text) for u in ("approver-1", "admin")]
close(); log("s_lacks_build", res)
for k, v in res.items(): print(k, "=>", v)
