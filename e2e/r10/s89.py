"""Item 1 (DEF-01/#89): grants tables at 1280 px, with active + ended windows approved by approver-2: no horizontal
scroll, short cells and headers on one line (Reason / state line may wrap, a date may break between date and time)."""
from lib import Session, close, log, SHOTS
import os; TAG = os.environ.get("TAG", ""); res = {"tag": TAG}
WRAP = """(tbl) => { const out = []; tbl.querySelectorAll('tr').forEach(tr => tr.querySelectorAll('td, th').forEach((td, ci) => {
  const txt = td.innerText.trim(); if (!txt) return; const r = document.createRange(); r.selectNodeContents(td);
  const tops = new Set([...r.getClientRects()].filter(x => x.width > 0).map(x => Math.round(x.top)));
  const head = tbl.querySelectorAll('thead th')[ci]; out.push([td.tagName, head ? head.innerText.trim() : '', txt.replace(/\\s+/g,' ').slice(0, 60), tops.size]); })); return out; }"""
for u in ["admin", "mover1", "approver-2"]:
    s = Session(u)
    s.go("/batch-control/grants/")
    tables = s.page.locator("#main-panel table")
    out = {}
    for i in range(tables.count()):
        cap = tables.nth(i).evaluate("t => { let e = t; for (let k = 0; k < 8 && e; k++) { e = e.previousElementSibling || e.parentElement; if (e && /^H[1-4]$/.test(e.tagName)) return e.innerText; const h = e && e.querySelector && e.querySelector('h2,h3'); if (h) return h.innerText; } return 't' + Math.random(); }")
        cells = tables.nth(i).evaluate(WRAP)
        out[cap] = {"rows": tables.nth(i).locator("tbody tr").count(), "wrapped": [c for c in cells if c[3] > 1],
                    "width": tables.nth(i).evaluate("t => Math.round(t.getBoundingClientRect().right)")}
    res[u + " tables"] = out
    res[u + " scrollWidth"] = s.page.evaluate("[document.documentElement.scrollWidth, document.documentElement.clientWidth]")
    s.page.screenshot(path=str(SHOTS / f"89-{u}-grants-1280{TAG}.png"), full_page=True)
    s.done()
close()
import json
for k, v in res.items(): print(k, "=>", json.dumps(v, indent=1))
log("s89", res)
