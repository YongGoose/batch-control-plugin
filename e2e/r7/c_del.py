from lib import Session, close, api, log
import re
res = {}
s = Session("requester")
s.go("/job/team/job/e2e7-created/")
try:
    s.page.locator("[data-testid=app-bar-overflow-button]").first.click(); s.page.wait_for_timeout(800)
    items = s.page.locator(".jenkins-dropdown a, .jenkins-dropdown button, .tippy-box a, .tippy-box button")
    res["menu"] = [i.inner_text().strip()[:40] for i in items.all()]
except Exception as e:
    res["menu_err"] = str(e)[:150]
res["console"] = [c for c in s.console if "startsWith" in c or "TypeError" in c][:2]
r = s.go("/job/team/job/e2e7-created/delete")
res["delete_page"] = r.status
s.shot("#main-panel", "C3c-06-delete-page")
b = s.page.locator("form button[name=Submit], form button.jenkins-button--primary").first
if b.count():
    with s.page.expect_navigation():
        b.click()
    s.page.wait_for_load_state("load")
res["landed"] = s.page.url
s.done(); close()
res["after_api"] = api("admin", "/job/team/job/e2e7-created/api/json").status_code
log("c_del", res); print(res)
