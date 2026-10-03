"""Scenario 5b: requester submits a run request; tab bar per user, badges, breadcrumb dropdown, section URLs, compact tables, no back links."""
import re
from lib import Session, close, log, api, SHOTS
r = {}
# requester submits a run request on batch-pipeline to approver-1
s = Session("requester")
s.go("/job/batch-pipeline/batch-control/")
f = s.page.locator("form[action$='submit']").first
f.locator("textarea[name=reason], input[name=reason]").first.fill("e2e-06 badge check")
f.locator("label", has_text="approver-1").first.click()
with s.page.expect_navigation():
    f.locator("button", has_text="Submit").first.click()
s.page.wait_for_load_state("load")
r["after_submit_url"] = s.page.url
s.done()

USERS = ["admin", "requester", "approver-1", "auditor", "reqonly", "manager", "nobc"]
for u in USERS:
    s = Session(u)
    resp = s.go("/batch-control/")
    rec = {"status": resp.status}
    nav = s.page.locator("[data-batch-control-tabs]")
    rec["side_panel"] = s.page.locator("#side-panel #tasks").count()
    rec["tabs"] = [re.sub(r"\s+", " ", a.inner_text()).strip() for a in nav.locator("a").all()] if nav.count() else None
    rec["overview_text"] = re.sub(r"\s+", " ", s.text())[:400]
    rec["app_bar_buttons"] = [b.inner_text().strip() for b in s.page.locator(".jenkins-app-bar__controls a, .jenkins-app-bar__controls button").all()]
    rec["root_link_in_header_or_dashboard"] = None
    if nav.count():
        s.shot(nav.first, f"S5b-tabs-{u}")
    if u in ("approver-1", "requester"):
        s.shot("#main-panel", f"S5b-overview-{u}")
    r[u] = rec
    s.done()

# breadcrumb dropdown on a sub-page (approver-1) + section URLs + table class + back links
s = Session("admin")
hrefs = []
s.go("/batch-control/")
hrefs = [a.get_attribute("href") for a in s.page.locator("[data-batch-control-tabs] a").all()]
sec = {}
for h in hrefs:
    resp = s.page.goto("http://localhost:8080" + h)
    s.page.wait_for_load_state("load")
    t = s.text()
    sec[h] = {"status": resp.status, "tables": [c for c in s.page.locator("#main-panel table").evaluate_all("ts => ts.map(t => t.className)")],
              "back_link": s.page.locator("#main-panel a", has_text=re.compile(r"^\s*(Back|«\s*Back|Back to)", re.I)).count(),
              "spec_item_text": bool(re.search(r"SPEC item", t)), "active_tab": s.page.locator("[data-batch-control-tabs] a[aria-current=page]").all_inner_texts()}
r["sections"] = sec
s.go("/batch-control/requests/")
chev = s.page.locator(".jenkins-breadcrumbs__list-item", has_text="Batch Control").locator("button, .jenkins-menu-dropdown-chevron, [data-href]")
r["breadcrumb_chevron"] = chev.count()
if chev.count():
    chev.first.click(); s.page.wait_for_timeout(1000)
    items = s.page.locator(".jenkins-dropdown__item, .tippy-box a")
    r["breadcrumb_menu"] = [re.sub(r"\s+", " ", i.inner_text()).strip() for i in items.all()]
    if s.page.locator(".tippy-box").count():
        s.shot([s.page.locator(".jenkins-breadcrumbs").first, s.page.locator(".tippy-box").first], "S5b-breadcrumb-dropdown-admin")
r["console_admin"] = s.console
s.done()
# breadcrumb dropdown for requester (filtered)
s = Session("requester")
s.go("/batch-control/requests/")
chev = s.page.locator(".jenkins-breadcrumbs__list-item", has_text="Batch Control").locator("button, .jenkins-menu-dropdown-chevron, [data-href]")
if chev.count():
    chev.first.click(); s.page.wait_for_timeout(1000)
    r["breadcrumb_menu_requester"] = [re.sub(r"\s+", " ", i.inner_text()).strip() for i in s.page.locator(".jenkins-dropdown__item, .tippy-box a").all()]
    if s.page.locator(".tippy-box").count():
        s.shot([s.page.locator(".jenkins-breadcrumbs").first, s.page.locator(".tippy-box").first], "S5b-breadcrumb-dropdown-requester")
r["console_requester"] = s.console
s.done(); close()
log("s5b", r)
for k, v in r.items():
    print(f"{k}: {v}")
