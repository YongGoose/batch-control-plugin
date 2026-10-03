"""Scenario 4d: Request Run page under the new job page (requester) and the classic UI (reqonly, flag off)."""
from lib import Session, close, log, SHOTS
r = {}
s = Session("requester")
s.go("/job/batch-daily/batch-control/")
r["new_title"] = s.page.title()
r["new_build_bar"] = s.page.locator(".app-build-bar").count()
r["new_h1"] = s.page.locator("h1").all_inner_texts()
r["new_h2"] = s.page.locator("#main-panel h2").all_inner_texts()[:3]
r["new_side_panel"] = s.page.locator("#side-panel").count()
r["new_breadcrumbs"] = s.page.locator(".jenkins-breadcrumbs__list-item").all_inner_texts()
s.page.screenshot(path=str(SHOTS / "S4d-01-request-run-new-ui.png"), full_page=True)
r["new_console"] = s.console
s.done()

s = Session("reqonly")
s.go("/me/experiments/")
s.page.select_option('select[name="[new-job-page.flag]"]', "false")
s.shot("#main-panel", "S4d-02-experiments-flag-off")
btn = s.page.locator("#main-panel button", has_text="Save")
if btn.count():
    with s.page.expect_navigation():
        btn.first.click()
s.go("/job/batch-daily/")
r["classic_side_panel"] = s.page.locator("#side-panel, #tasks").count()
r["classic_tasks"] = s.page.locator("#tasks").inner_text()[:400] if s.page.locator("#tasks").count() else None
s.page.screenshot(path=str(SHOTS / "S4d-03-job-page-classic.png"), full_page=True)
s.go("/job/batch-daily/batch-control/")
r["classic_title"] = s.page.title()
r["classic_h1"] = s.page.locator("h1").all_inner_texts()
r["classic_form"] = s.page.locator("form[action$='submit']").count()
r["classic_text"] = s.text()[:500]
s.page.screenshot(path=str(SHOTS / "S4d-04-request-run-classic.png"), full_page=True)
link = s.page.locator("#tasks a", has_text="Request Change")
r["classic_grant_link"] = link.first.get_attribute("href") if link.count() else None
r["classic_console"] = s.console
s.done(); close()
log("s4d", r)
for k, v in r.items():
    print(f"{k}: {v}")
