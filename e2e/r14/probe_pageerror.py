"""e2e-14 diagnostic: which script throws "Cannot read properties of undefined (reading 'replace')" on a page
(stack + whether #breadcrumbBar overflows), N fresh loads per page."""
import sys, json
from lib import Session, close, log
user, n, pages = sys.argv[1], int(sys.argv[2]), sys.argv[3:]
for p in pages:
    hits = []
    for i in range(n):
        s = Session(user)
        errs = []
        s.page.on("pageerror", lambda e: errs.append((str(e), (e.stack or "")[:500])))
        s.go(p)
        s.page.wait_for_timeout(1500)
        ov = s.page.evaluate("() => { const b = document.querySelector('#breadcrumbBar, .jenkins-breadcrumbs'); return b ? {sw: b.scrollWidth, cw: b.clientWidth, items: [...b.querySelectorAll('li')].map(l => l.innerText.trim()).filter(Boolean)} : null }")
        hits.append({"errs": errs, "crumbs": ov})
        s.done()
    row = {"user": user, "page": p, "loads": n, "with_error": sum(1 for h in hits if h["errs"]), "sample": next((h for h in hits if h["errs"]), hits[0])}
    log("pageerror", row)
    print(json.dumps(row)[:1500])
close()
