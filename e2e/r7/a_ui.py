"""A: re-check E2E-1 DEF-01, DEF-02 (limitation), UX-7, UX-8, UX-9, UX-10, tab bar per role, context menu."""
import re
from lib import Session, close, api, log
res = {}
# DEF-01 + DEF-02
for job, tag in [("/job/batch-daily/", "A1-1"), ("/job/team/job/sub/job/deep-job/", "A1-2")]:
    s = Session("requester")
    s.go(job)
    bar = s.page.locator(".jenkins-app-bar").first
    btns = [b.inner_text().strip() for b in s.page.locator(".jenkins-app-bar .jenkins-button").all() if b.inner_text().strip()]
    s.shot(bar, f"{tag}-01-app-bar")
    s.page.locator("[data-testid=app-bar-overflow-button]").first.click()
    s.page.wait_for_timeout(1000)
    entry = s.page.locator(".jenkins-dropdown a, .tippy-box a", has_text="Request Change").first
    rec = {"appbar": btns, "entry": entry.count()}
    if entry.count():
        rec["href"] = entry.get_attribute("href")
        s.shot(s.page.locator(".tippy-box, .jenkins-dropdown").first, f"{tag}-02-menu")
        with s.page.expect_navigation() as nav:
            entry.click()
        s.page.wait_for_load_state("load")
        rec["landed"] = s.page.url; rec["status"] = nav.value.status
        rec["scopeType"] = s.page.locator("select[name=scopeType]").input_value()
        rec["scopeFullName"] = s.page.locator("input[name=scopeFullName]").input_value()
        s.shot("form[name=createGrantRequest]", f"{tag}-03-prefilled")
    rec["console"] = [c[:120] for c in s.console]
    res[tag] = rec
    s.done()
# classic UI link
s = Session("requester")
s.go("/me/experiments/")
res["experiments_page"] = s.page.url
s.done()
# tabs per role at 1280
for u in ["admin", "manager", "requester", "approver-1", "auditor", "reqonly", "reqhist"]:
    s = Session(u)
    r = s.go("/batch-control/requests/")
    nav = s.page.locator("nav[data-batch-control-tabs]")
    rec = {"status": r.status}
    if nav.count():
        tabs = nav.locator("a")
        rec["tabs"] = [re.sub(r"\s+", " ", t.inner_text()).strip() for t in tabs.all()]
        tops = sorted(set(round(t.bounding_box()["y"]) for t in tabs.all()))
        rec["rows"] = len(tops)
        rec["current"] = nav.locator("[aria-current=page]").inner_text().strip() if nav.locator("[aria-current=page]").count() else None
        s.shot(nav, f"A6-tabs-{u}")
    rec["appbar"] = [b.inner_text().strip() for b in s.page.locator(".jenkins-app-bar a, .jenkins-app-bar button").all() if b.inner_text().strip()]
    res[f"tabs_{u}"] = rec
    s.done()
# breadcrumb context menu (requester and admin)
for u in ["requester", "admin"]:
    s = Session(u)
    s.go("/batch-control/requests/")
    crumb = s.page.locator(".jenkins-breadcrumbs__list-item", has_text="Batch Control").first
    rec = {}
    try:
        crumb.hover(); s.page.wait_for_timeout(300)
        dd = crumb.locator("button, .jenkins-menu-dropdown-chevron, .dropdown-indicator").first
        if dd.count():
            dd.click(); s.page.wait_for_timeout(900)
        items = s.page.locator(".tippy-box a, .jenkins-dropdown a")
        rec["menu"] = [re.sub(r"\s+", " ", i.inner_text()).strip() for i in items.all()]
        if items.count():
            s.shot(s.page.locator(".tippy-box, .jenkins-dropdown").first, f"A6-breadcrumb-menu-{u}")
    except Exception as e:
        rec["err"] = str(e)[:150]
    res[f"crumb_{u}"] = rec
    s.done()
# UX-10 configuration breadcrumb, Save only
s = Session("admin")
s.go("/batch-control-configuration/")
res["config"] = {"breadcrumb": [re.sub(r"\s+", " ", x.inner_text()).strip() for x in s.page.locator(".jenkins-breadcrumbs__list-item").all()],
                 "buttons": [b.inner_text().strip() for b in s.page.locator("#bottom-sticker button, .jenkins-bottom-app-bar button, form button[name=Submit], button[name=Apply]").all()]}
s.shot(".jenkins-breadcrumbs, #breadcrumbBar", "A7-config-breadcrumb")
# UX-7 Revert dialog
rv = s.page.locator("a, button", has_text="Revert to the plain").first
if rv.count():
    rv.scroll_into_view_if_needed(); rv.click(); s.page.wait_for_timeout(700)
    d = s.page.locator("dialog[open]")
    if d.count():
        res["config"]["revert_dialog"] = [x.inner_text().strip() for x in d.first.locator("button").all()]
        s.shot(d.first, "A5-revert-dialog")
        d.first.locator("button", has_text="Cancel").first.click()
s.done()
close()
log("a_ui", res)
for k, v in res.items(): print(k, v)
