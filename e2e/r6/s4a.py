"""Scenario 4a: new job page overflow menu 'Request Change permission' lands on /jenkins/batch-control/grants/..."""
from lib import Session, close, log, SHOTS
out = []
for job, tag in [("/job/batch-daily/", "S4a-1"), ("/job/team/job/sub/job/deep-job/", "S4a-2")]:
    s = Session("requester")
    s.go(job)
    more = s.page.locator(".jenkins-app-bar button, .jenkins-app-bar a").filter(has_text="").last
    # the overflow button is the last control in the app bar
    btns = s.page.locator("#main-panel .jenkins-app-bar button, .jenkins-app-bar button, .app-bar button")
    print("appbar buttons:", [(b.inner_text()[:30], b.get_attribute("class")) for b in btns.all()])
    s.page.locator("[data-testid=app-bar-overflow-button]").first.click()
    s.page.wait_for_timeout(800)
    items = s.page.locator(".jenkins-dropdown a, .jenkins-dropdown button, .tippy-box a, .tippy-box button")
    print("menu:", [(i.inner_text().strip()[:40], i.get_attribute("href")) for i in items.all()])
    entry = s.page.locator(".jenkins-dropdown a, .tippy-box a", has_text="Request Change").first
    rec = {"job": job, "entry": entry.count(), "href": entry.get_attribute("href") if entry.count() else None}
    if entry.count():
        s.shot(s.page.locator(".tippy-box, .jenkins-dropdown").first, f"{tag}-01-overflow-menu")
        resp = None
        with s.page.expect_navigation() as nav:
            entry.click()
        resp = nav.value
        s.page.wait_for_load_state("load")
        rec["landed"] = s.page.url
        rec["status"] = resp.status if resp else None
        rec["prefilled_scope"] = s.page.locator("input[name=scopeFullName]").input_value() if s.page.locator("input[name=scopeFullName]").count() else None
        s.shot("#main-panel", f"{tag}-02-landed")
    rec["console"] = s.console; rec["bad"] = s.bad
    out.append(rec)
    print(rec)
    s.done()
close()
log("s4a", out)
