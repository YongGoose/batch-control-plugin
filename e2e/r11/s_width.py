"""R4-8/DEF-01 follow-up: which grants-page table is wider than the 1280 px viewport, and why (cell widths)."""
import json, sys
from lib import Session, close, log, SHOTS
res = {}
for user in sys.argv[1:]:
    s = Session(user)
    s.go("/batch-control/grants/")
    res[user] = s.page.evaluate("""() => ({scrollW: document.documentElement.scrollWidth, mainW: Math.round(document.querySelector('#main-panel').getBoundingClientRect().width),
      tables: [...document.querySelectorAll('#main-panel table')].map(t => ({w: Math.round(t.getBoundingClientRect().width),
        cols: [...t.querySelectorAll('thead th')].map((th, i) => th.innerText.trim() + '=' + Math.round(th.getBoundingClientRect().width)),
        firstRow: t.tBodies[0] && t.tBodies[0].rows[0] ? [...t.tBodies[0].rows[0].cells].map(c => c.innerText.trim().replace(/\\s+/g,' ').slice(0,50) + ' [' + getComputedStyle(c).whiteSpace + ']') : []}))})""")
    s.page.screenshot(path=str(SHOTS / f"W-{user}-grants-1280.png"), full_page=True)
    s.done()
close(); log("s_width", res); print(json.dumps(res, indent=1))
