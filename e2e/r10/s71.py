"""#71 / D-64: activation form at <item>/batch-control-activation/ for a job, a job in a folder and a multibranch project."""
import re
from lib import Session, close, api, log, clean
res = {}
s = Session("requester")
for sid, item in [("71a", "/job/batch-daily"), ("71b", "/job/team/job/app-1"), ("71c", "/job/team-mb")]:
    s.go(item + "/")
    links = s.page.locator(f'a[href$="{item.split("/")[-1]}/batch-control-activation/"], a[href*="batch-control-activation"]')
    res[sid + " job-page links"] = sorted(set(links.evaluate_all("els => els.map(e => e.getAttribute('href'))")))
    s.shot("#main-panel", f"{sid}-01-item-page")
    r = s.go(item + "/batch-control-activation/")
    res[sid + " form"] = r.status
    crumbs = [c.strip() for c in s.page.locator(".jenkins-breadcrumbs__list-item").all_inner_texts()]
    res[sid + " breadcrumbs"] = crumbs
    s.shot(["#breadcrumbBar, .jenkins-breadcrumbs", "form[name=batch-control-activation]"], f"{sid}-02-form")
    s.page.fill("textarea[name=reason]", f"e2e-10 {sid} activate")
    s.page.locator("#batch-control-approver-0 + label").click()
    with s.page.expect_navigation() as nav:
        s.page.click("form[name=batch-control-activation] button[name=Submit], form[name=batch-control-activation] button[type=submit]")
    s.page.wait_for_load_state("load")
    res[sid + " landed"] = (nav.value.status, s.page.url.replace("http://localhost:8080", ""))
    res[sid + " landed text"] = s.text()[:200].replace("\n", " | ")
    s.shot("#main-panel", f"{sid}-03-landed")
# old URLs
for p in ["/job/batch-daily/batch-control/activation", "/job/batch-daily/batch-control/activation/",
          "/job/team-mb/batch-control/activation", "/job/team/job/app-1/batch-control/activation"]:
    res["old GET " + p] = api("requester", p).status_code
for p in ["/job/batch-daily/batch-control/activation/submit", "/job/team-mb/batch-control/activation/submit"]:
    res["old POST " + p] = api("requester", p, "POST", data={"action": "ACTIVATE", "reason": "x", "approvers": "approver-1"}).status_code
# Request Run + pre-fill
r = s.go("/job/batch-daily/batch-control/?p.DATE=2026-10-09&p.MODE=full")
res["run form"] = r.status
res["run crumbs"] = [c.strip() for c in s.page.locator(".jenkins-breadcrumbs__list-item").all_inner_texts()]
res["prefill DATE"] = s.page.locator('form[name=batch-control-request] [name=value]').evaluate_all("els => els.map(e => e.value)")
s.shot("form[name=batch-control-request]", "71d-01-run-prefill")
s.done()
for u in ["nobc", "auditor", "approver-1"]:
    for p in ["/job/batch-daily/batch-control-activation/", "/job/team-mb/batch-control-activation/"]:
        res[f"{u} GET {p}"] = api(u, p).status_code
    res[f"{u} POST submit"] = api(u, "/job/batch-daily/batch-control-activation/submit", "POST", data={"action": "ACTIVATE", "reason": "x", "approvers": "approver-1"}).status_code
n = Session("nobc")
n.go("/job/batch-daily/")
res["nobc links"] = n.page.locator('a[href*="batch-control"]').count()
n.shot("#main-panel", "71e-01-nobc-job-page")
n.done()
log("s71", res)
for k, v in res.items():
    print(k, "=>", v)
close()
