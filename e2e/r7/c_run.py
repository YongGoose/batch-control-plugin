"""C1: approval in the browser -> run once with the stored parameters; marker reuse via Rebuild refused."""
import re, time
from lib import Session, close, api, log, clean
res = {}
def approve(rid, tag):
    s = Session("approver-1")
    s.go(f"/batch-control/requests/{rid}/")
    f = s.page.locator("form[action$='approve'], form[name=approve]").first
    s.shot(f if f.count() else "#main-panel", f"{tag}-01-decision-form")
    if f.count():
        ta = f.locator("textarea")
        if ta.count(): ta.first.fill("e2e-07 approve")
        with s.page.expect_navigation() as nav:
            f.locator("button[type=submit], button[name=Submit]").first.click()
        s.page.wait_for_load_state("load")
        st = nav.value.status
    else:
        st = None
    d = s.page.locator("dialog[open]")
    t = re.sub(r"\s+", " ", s.text())[:400]
    s.shot("#main-panel", f"{tag}-02-after-approve")
    s.done()
    return {"approve": st, "after": t}
res["B2_approve"] = approve("20261003-123922-v0b0k5", "C1a")
res["B1_approve"] = approve("20261003-123921-mf58tt", "C1b")
time.sleep(20)
res["builds"] = api("admin", "/job/batch-daily/api/json?tree=builds[number,result,actions[parameters[name,value],causes[shortDescription]]]").json()
res["d1"] = api("requester", "/batch-control/requests/20261003-123922-v0b0k5/").status_code
res["d1_text"] = clean(api("requester", "/batch-control/requests/20261003-123922-v0b0k5/").text)[:0]
# marker reuse via rebuild (requester has Build)
s = Session("requester")
r = s.go("/job/batch-daily/1/rebuild/")
res["rebuild_page"] = r.status
s.shot("#main-panel", "C1c-01-rebuild-form")
btn = s.page.locator("button[name=Submit], button:has-text('Rebuild')").first
if btn.count():
    with s.page.expect_navigation() as nav:
        btn.click()
    s.page.wait_for_load_state("load")
    res["rebuild_submit"] = nav.value.status
    res["rebuild_text"] = re.sub(r"\s+", " ", s.text())[:500]
    s.shot("#main-panel", "C1c-02-rebuild-refused")
s.done(); close()
time.sleep(3)
res["next_after_rebuild"] = api("admin", "/job/batch-daily/api/json?tree=nextBuildNumber").json()
ch = clean(api("admin", "/batch-control/changes/").text)
res["marker_records"] = re.findall(r".{0,80}MARKER_REUSE_BLOCKED.{0,200}", ch)[:3]
log("c_run", res)
for k, v in res.items(): print(k, v)
