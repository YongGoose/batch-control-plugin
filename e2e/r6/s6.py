"""Scenario 6: regression spot-checks of the 2026-09-27 round."""
import re
from lib import Session, close, log, groovy, SHOTS
r = {"strategy": groovy("return jenkins.model.Jenkins.get().getAuthorizationStrategy().getClass().simpleName")}
s = Session("admin")
for p, n in [("/job/team/configure", "folder"), ("/job/batch-daily/configure", "job")]:
    s.go(p)
    el = s.page.locator("text=Enable project-based security").first
    r[f"per_item_{n}"] = el.count()
    if el.count():
        s.shot(el, f"S6-01-per-item-matrix-{n}")
spec_hits = {}
for p in ["/batch-control-configuration/", "/manage/", "/job/batch-daily/", "/job/batch-daily/batch-control/", "/batch-control/dashboard/", "/batch-control/incidents/", "/batch-control/activations/", "/job/team/"]:
    s.go(p)
    spec_hits[p] = bool(re.search(r"SPEC item|SPEC section|D-\d\d", s.page.locator("body").inner_text()))
r["spec_text_hits"] = spec_hits
s.done()
s = Session("nobc")
s.go("/")
r["nobc_header_bc_link"] = s.page.locator("a[href$='/batch-control/'], a[href$='/batch-control']").count()
s.page.screenshot(path=str(SHOTS / "S6-02-nobc-dashboard.png"))
resp = s.go("/batch-control/")
r["nobc_root_status"] = resp.status
s.done()
s = Session("requester")
s.go("/")
r["requester_header_bc_link"] = s.page.locator("a[href$='/batch-control/'], a[href$='/batch-control']").count()
s.done(); close()
log("s6", r)
for k, v in r.items():
    print(f"{k}: {v}")
