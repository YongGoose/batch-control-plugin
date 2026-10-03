"""Approve the D run request and the A grant in the browser (approver-1); the run executes exactly once."""
import re, sys, time
from lib import Session, close, api, log
rid, gid = sys.argv[1], sys.argv[2]
res = {}
a = Session("approver-1")
for path, n in ((f"/batch-control/requests/{rid}/", "run"), (f"/batch-control/grants/{gid}/", "grant")):
    a.go(path)
    f = a.page.locator("form[name=approve]").first
    res[f"{n}_forms"] = [x.get_attribute("name") for x in a.page.locator("#main-panel form").all()]
    if f.locator("textarea").count(): f.locator("textarea").first.fill("ok e2e-11")
    with a.page.expect_navigation() as nav:
        f.locator("button[type=submit], button[name=Submit]").first.click()
    a.page.wait_for_load_state("load")
    res[f"{n}_approve"] = (nav.value.status, a.page.url)
    a.shot("#main-panel", f"AP-{n}-approved")
a.done()
for _ in range(30):
    b = api("admin", "/job/batch-daily/api/json?tree=builds%5Bnumber,result,building%5D").json()["builds"]
    if b and not b[0].get("building") and b[0].get("result"):
        break
    time.sleep(2)
res["builds"] = b
res["build1_api"] = api("admin", "/job/batch-daily/1/api/json").status_code
time.sleep(2)
s = Session("requester")
s.go(f"/batch-control/requests/{rid}/")
res["request_text"] = re.sub(r"\s+", " ", s.text())[:400]
s.shot("#main-panel", "AP-run-executed")
s.go(f"/batch-control/grants/{gid}/")
res["grant_text"] = re.sub(r"\s+", " ", s.text())[:500]
res["grant_buttons_requester"] = [b.inner_text().strip() for b in s.page.locator("#main-panel button").all() if b.inner_text().strip()]
s.done(); close(); log("s_approve", res)
for k, v in res.items(): print(k, "=>", v)
