"""DEF-01 click-through (rebuild disabled), DEF-02 behaviour, D-60 Direct Build, classic UI side panel."""
import re
from lib import Session, close, api, log
res = {}
import atexit
atexit.register(lambda: [print(k, v) for k, v in res.items()])
for job, tag in [("/job/batch-daily/", "A1-1"), ("/job/team/job/sub/job/deep-job/", "A1-2")]:
    s = Session("requester")
    s.go(job)
    bar = s.page.locator("[data-testid=app-bar-overflow-button]").first.locator("xpath=..")
    res[tag + "-appbar"] = [re.sub(r"\s+", " ", b.inner_text()).strip() for b in bar.locator("a, button").all() if b.inner_text().strip()]
    s.shot(bar, f"{tag}-01-app-bar")
    s.page.locator("[data-testid=app-bar-overflow-button]").first.click()
    s.page.wait_for_timeout(1200)
    entry = s.page.locator(".jenkins-dropdown a, .tippy-box a", has_text="Request Change").first
    rec = {"menu": [re.sub(r"\s+", " ", i.inner_text()).strip() for i in s.page.locator(".tippy-box a, .tippy-box button").all()], "entry": entry.count()}
    if entry.count():
        rec["href"] = entry.get_attribute("href")
        s.shot(s.page.locator(".tippy-box").first, f"{tag}-02-menu")
        with s.page.expect_navigation() as nav:
            entry.click()
        s.page.wait_for_load_state("load")
        rec["landed"] = s.page.url; rec["status"] = nav.value.status
        rec["itemKind"] = (s.page.locator("[data-batch-control-item-kind]").first.get_attribute("data-batch-control-item-kind") if s.page.locator("[data-batch-control-item-kind]").count() else None)
        rec["scopeFullName"] = s.page.locator("input[name=scopeFullName]").input_value()
        s.shot("form[name=createGrantRequest]", f"{tag}-03-prefilled")
    rec["console"] = [c[:100] for c in s.console if "MIME" not in c]
    res[tag] = rec
    s.done()
# D-60 Direct Build with parameters on the new job page
s = Session("requester")
s.go("/job/batch-daily/")
q0 = api("admin", "/job/batch-daily/api/json?tree=nextBuildNumber").json()["nextBuildNumber"]
s.page.locator("[data-testid=app-bar-overflow-button]").first.locator("xpath=..").locator("a, button", has_text="Direct Build").first.click()
s.page.wait_for_timeout(1500)
d = s.page.locator("dialog[open]")
res["d60_dialog"] = d.count()
if d.count():
    d.first.locator("input[name=value]").first.fill("2026-09-30")
    s.shot(d.first, "N1-01-param-dialog")
    with s.page.expect_navigation(timeout=20000):
        d.first.locator("button.jenkins-button--primary, button:has-text('Build')").last.click()
    s.page.wait_for_load_state("load")
res["d60_landed"] = s.page.url
res["d60_heading"] = s.page.locator("h1").first.inner_text() if s.page.locator("h1").count() else None
res["d60_date"] = s.page.locator("input[name=value]").first.input_value() if s.page.locator("input[name=value]").count() else None
res["d60_queued"] = api("admin", "/job/batch-daily/api/json?tree=nextBuildNumber").json()["nextBuildNumber"] - q0
s.shot("#main-panel", "N1-02-request-run-prefilled")
s.done()
# classic UI: requester turns the experiment off
s = Session("requester")
s.go("/me/experiments/")
tg = s.page.locator("input[type=checkbox]").first
res["exp_toggles"] = s.page.locator("input[type=checkbox]").count()
try:
    if tg.is_checked():
        s.page.locator("input[type=checkbox] + label").first.click(); s.page.wait_for_timeout(1500)
except Exception as e:
    res["exp_err"] = str(e)[:100]
s.go("/job/team/job/sub/job/deep-job/")
res["classic_side"] = [re.sub(r"\s+", " ", a.inner_text()).strip() for a in s.page.locator("#side-panel #tasks a, #tasks a").all()][:12]
link = s.page.locator("#tasks a", has_text="Request Change").first
if link.count():
    link.click(); s.page.wait_for_load_state("load")
    res["classic_prefill"] = (s.page.url, s.page.locator("input[name=scopeFullName]").input_value())
s.shot("#main-panel", "N2-classic-grant-prefill")
s.go("/me/experiments/")
try:
    if not s.page.locator("input[type=checkbox]").first.is_checked():
        s.page.locator("input[type=checkbox] + label").first.click(); s.page.wait_for_timeout(1500)
except Exception:
    pass
s.done(); close()
log("n_newjob", res)
