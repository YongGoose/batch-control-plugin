"""Item 6 smoke: Build Now refused -> Request Run -> approve -> runs once; Configure window works and expires;
tabs/badges for requester and approver-1; dark theme sanity."""
import re, time, datetime
from lib import Session, close, api, log, SHOTS
res = {}
import atexit
atexit.register(lambda: [print(k, v) for k, v in res.items()])
now = lambda: datetime.datetime.now().strftime("%H:%M:%S")
# c. Configure under the window (browser), save description
s = Session("requester")
r = s.go("/job/team/job/app-1/configure")
res["configure_in_window"] = (now(), r.status)
if r.status == 200:
    s.page.fill("textarea[name=description]", "e2e-09 smoke configure")
    with s.page.expect_navigation() as nav:
        s.page.click("button[name=Submit]")
    s.page.wait_for_load_state("load")
    res["configure_save"] = nav.value.status
s.shot("#main-panel", "SM1-01-configured")
s.done()
# a. Build Now refused (REST + new job page button), b. Request Run
q0 = api("admin", "/job/batch-pipeline/api/json?tree=nextBuildNumber").json()["nextBuildNumber"]
r = api("requester", "/job/batch-pipeline/build", "POST")
res["rest_build"] = (r.status_code, re.sub(r"\s+", " ", re.sub(r"<[^>]+>", " ", r.text))[:160])
s = Session("requester")
s.go("/job/batch-pipeline/")
bar = s.page.locator("[data-testid=app-bar-overflow-button]").first.locator("xpath=..")
res["appbar"] = [re.sub(r"\s+", " ", b.inner_text()).strip() for b in bar.locator("a, button").all() if b.inner_text().strip()]
bar.locator("a, button", has_text="Direct Build").first.click()
s.page.wait_for_timeout(2500)
res["direct_build_toast"] = [t for t in s.page.locator(".jenkins-notification, #notification-bar, .tippy-box").all_inner_texts() if t.strip()]
s.shot(bar, "SM2-01-direct-build")
bar.locator("a, button", has_text="Request Run").first.click(); s.page.wait_for_load_state("load")
s.page.fill("textarea[name=reason]", "e2e-09 smoke run")
s.page.click("#batch-control-approver-0 + label")
with s.page.expect_navigation():
    s.page.click("button[name=Submit]")
s.page.wait_for_load_state("load")
rid = re.search(r"/requests/([^/]+)/", s.page.url).group(1)
res["request"] = rid
s.done()
res["queued_after_refusals"] = api("admin", "/job/batch-pipeline/api/json?tree=nextBuildNumber").json()["nextBuildNumber"] - q0
# d. tabs/badges
for u in ["requester", "approver-1"]:
    s = Session(u)
    s.go("/batch-control/")
    res[f"tabs {u}"] = [re.sub(r"\s+", " ", t).strip() for t in s.page.locator("nav[data-batch-control-tabs] a").all_inner_texts()]
    res[f"overview {u}"] = re.sub(r"\s+", " ", s.text())[:260]
    s.shot("#main-panel", f"SM3-{u}-overview")
    s.done()
# approve in the browser
a = Session("approver-1")
a.go(f"/batch-control/requests/{rid}/")
f = a.page.locator("form[name=approve]").first
if f.locator("textarea").count(): f.locator("textarea").first.fill("ok")
with a.page.expect_navigation() as nav:
    f.locator("button[type=submit], button[name=Submit]").first.click()
a.page.wait_for_load_state("load")
res["approve"] = nav.value.status
a.done()
for _ in range(30):
    b = api("admin", "/job/batch-pipeline/api/json?tree=builds%5Bnumber,result,building%5D").json()["builds"]
    if b and not b[0].get("building") and b[0].get("result"):
        break
    time.sleep(2)
res["pipeline_builds"] = b
res["build1_api"] = api("admin", "/job/batch-pipeline/1/api/json").status_code
s = Session("requester")
s.go(f"/batch-control/requests/{rid}/")
res["request_text"] = re.findall(r"Executed[^\n]{0,60}|APPROVED|EXECUTED", s.text())[:3]
s.shot("#main-panel", "SM2-02-executed")
s.done()
# dark theme sanity (approver-1)
s = Session("approver-1")
s.go("/user/approver-1/appearance/")
s.page.locator("input[data-theme=dark]").check(force=True)
s.page.locator("button[name=Submit]").first.click(); s.page.wait_for_load_state("load")
for p, n in [("/batch-control/", "SM5-dark-overview"), ("/batch-control/requests/", "SM5-dark-requests"), (f"/batch-control/requests/{rid}/", "SM5-dark-detail"),
             ("/batch-control/changes/", "SM5-dark-changes"), ("/job/ops/job/mv-job/", "SM5-dark-moved-job")]:
    s.go(p)
    s.page.screenshot(path=str(SHOTS / f"{n}.png"), full_page=False)
    res[n] = s.page.evaluate("""() => { const b = getComputedStyle(document.body); const n = document.querySelector('nav[data-batch-control-tabs]');
      return {text: b.color, tabsH: n ? Math.round(n.getBoundingClientRect().height) : null, scrollW: document.documentElement.scrollWidth} }""")
s.go("/user/approver-1/appearance/")
s.page.locator("input[data-theme=none]").check(force=True)
s.page.locator("button[name=Submit]").first.click(); s.page.wait_for_load_state("load")
s.done()
# c. expiry
for _ in range(20):
    st = api("requester", "/job/team/job/app-1/configure").status_code
    if st == 403:
        break
    time.sleep(10)
res["configure_after_expiry"] = (now(), st)
s = Session("requester")
r = s.go("/job/team/job/app-1/configure")
res["configure_after_expiry_browser"] = r.status
s.shot("#main-panel" if s.page.locator("#main-panel").count() else "body", "SM1-02-expired")
s.go("/batch-control/grants/")
res["grants_status_text"] = re.findall(r"APPROVED \([^)]*\)", s.text())[:4]
s.done()
res["configure_record"] = [l for l in api("admin", "/batch-control/history/changes.csv").text.splitlines() if ",CONFIGURE,team/app-1,requester," in l]
close()
log("smoke9", res)
