"""e2e-12: the list pager (Previous/Next) of the Run Requests pending table with > 50 pending requests, as admin and approver-2."""
import re
from lib import Session, close, BASE
import lib
for role in ["admin", "approver-2"]:
    s = Session(role)
    s.go("/batch-control/requests/")
    t = s.page.locator("#main-panel table").first
    row = {"role": role, "footer": [x.strip() for x in s.page.locator("#main-panel :text-matches('^Page \\\\d')").all_inner_texts()][:3],
           "rows_p1": t.locator("tbody tr").count(), "first_p1": t.locator("tbody tr").first.inner_text()[:50]}
    nxt = s.page.locator("#main-panel a", has_text=re.compile("^Next$"))
    row["next"] = nxt.count()
    if nxt.count():
        nxt.first.click(); s.page.wait_for_load_state("load")
        t = s.page.locator("#main-panel table").first
        row["p2"] = {"url": s.page.url.replace(BASE, ""), "rows": t.locator("tbody tr").count(), "first": t.locator("tbody tr").first.inner_text()[:50],
                     "footer": [x.strip() for x in s.page.locator("#main-panel :text-matches('^Page \\\\d')").all_inner_texts()][:3]}
        s.shot("#main-panel table", f"LP-{role}-p2")
        prv = s.page.locator("#main-panel a", has_text=re.compile("^Previous$"))
        row["prev"] = prv.count()
        if prv.count():
            prv.first.click(); s.page.wait_for_load_state("load")
            row["back_first"] = s.page.locator("#main-panel table").first.locator("tbody tr").first.inner_text()[:50]
            row["back_same"] = row["back_first"] == row["first_p1"]
    lib.log("listpager", row); print(row)
    s.done()
close()
