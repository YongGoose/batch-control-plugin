"""#88: admin overview 'Open Items' table with correct counts (vs the section lists)."""
import re
from lib import Session, close, log, api, clean
res = {}
def pending(section):
    t = clean(api("admin", f"/batch-control/{section}/").text)
    return len(re.findall(r"\bPENDING\b", t)), len(re.findall(r"\bAPPROVED\b(?! window)", t))
res["section PENDING/APPROVED counts (admin lists)"] = {s: pending(s) for s in ["requests", "activations", "grants"]}
s = Session("admin")
s.go("/batch-control/")
tbl = s.page.locator("#main-panel table").filter(has_text=re.compile("Run|Activation|Grant")).first
h = s.page.locator("#main-panel h2, #main-panel h3").all_inner_texts()
res["headings"] = h
res["open items rows"] = [re.sub(r"\s+", " ", r) for r in tbl.locator("tr").all_inner_texts()] if tbl.count() else None
s.shot(tbl if tbl.count() else "#main-panel", "88-01-admin-open-items")
s.done()
for u in ["approver-1", "manager", "auditor"]:
    x = Session(u); x.go("/batch-control/")
    res[u + " headings"] = x.page.locator("#main-panel h2, #main-panel h3").all_inner_texts()
    if u == "approver-1": x.shot("#main-panel", "88-02-approver1-overview")
    x.done()
close()
for k, v in res.items(): print(k, "=>", v)
log("s88", res)
