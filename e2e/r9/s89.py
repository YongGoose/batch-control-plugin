"""#89: grant request submit lands on its detail page; grants tables do not wrap short cells at 1280 px (with active and ended windows)."""
import re
from lib import Session, close, log, api, SHOTS
res = {}
WRAP = """(tbl) => { const out = []; tbl.querySelectorAll('tr').forEach(tr => tr.querySelectorAll('td, th').forEach(td => {
  const txt = td.innerText.trim(); if (!txt) return; const r = document.createRange(); r.selectNodeContents(td);
  const tops = new Set([...r.getClientRects()].filter(x => x.width > 0).map(x => Math.round(x.top)));
  out.push([txt.slice(0, 40), txt.length, tops.size]); })); return out; }"""
a = Session("admin")
a.go("/batch-control/grants/")
row = a.page.locator("tr", has_text="20261003-180250-neoq9u")
row.locator("button, a", has_text="Revoke").first.click()
dlg = a.page.locator("dialog[open]").first
dlg.wait_for(); a.shot(dlg, "89-01-revoke-dialog")
res["revoke dialog"] = re.sub(r"\s+", " ", dlg.inner_text())
with a.page.expect_navigation():
    dlg.locator("button.jenkins-button--primary, button", has_text="Revoke").last.click()
a.page.wait_for_load_state("load")
a.done()
r = Session("requester")
r.go("/batch-control/grants/")
f = r.page.locator("form#new-grant-request, form[action$='grants/create'], form[action$='create']").first
f.locator("select[name=scopeType]").select_option("JOB")
f.locator("input[name=scopeFullName]").fill("batch-pipeline")
f.locator("input[name=actions][value=CONFIGURE] + label").click()
f.locator("textarea[name=reason]").fill("e2e-09 #89 browser submit")
f.locator("#batch-control-approver-0 + label, input[name=approvers] + label").first.click()
r.shot(f, "89-02-grant-form")
with r.page.expect_navigation() as nav:
    f.locator("button[type=submit], button[name=Submit]").first.click()
r.page.wait_for_load_state("load")
res["submit landed"] = (nav.value.status, r.page.url.replace("http://localhost:8080", ""))
res["landed text"] = r.text()[:300].replace("\n", " | ")
r.shot("#main-panel", "89-03-landed-detail")
r.done()
for u in ["admin", "requester", "mover1"]:
    s = Session(u)
    s.go("/batch-control/grants/")
    tables = s.page.locator("#main-panel table")
    out = {}
    for i in range(tables.count()):
        cap = tables.nth(i).evaluate("t => { let e = t; for (let k = 0; k < 6 && e; k++) { e = e.previousElementSibling || e.parentElement; if (e && /^H[1-4]$/.test(e.tagName)) return e.innerText; } return 't' + Math.random(); }")
        cells = tables.nth(i).evaluate(WRAP)
        out[cap] = {"rows": tables.nth(i).locator("tbody tr").count(), "wrapped": [c for c in cells if c[2] > 1]}
    res[u + " tables"] = out
    res[u + " hscroll"] = s.page.evaluate("document.documentElement.scrollWidth > document.documentElement.clientWidth")
    s.page.screenshot(path=str(SHOTS / f"89-04-{u}-grants-1280.png"), full_page=True)
    s.done()
close()
for k, v in res.items(): print(k, "=>", v)
log("s89", res)
