"""e2e-12: incident Resolve (shown once acknowledged) and Add Comment (the comment appears on the page), as admin and
approver-1; Request Rerun with no approver ticked stays on the page with an error and the reason kept."""
import re
from lib import Session, close, api, BASE
import lib
from actions import f_incident


def emit(row):
    lib.log("incident", row)
    print(row)


for role in ["admin", "approver-1"]:
    _, iid = f_incident()
    s = Session(role)
    s.go(f"/batch-control/incidents/{iid}/")
    row = {"role": role, "id": iid}
    f = s.page.locator("form[name=addComment]")
    f.locator("textarea").fill(f"comment by {role} e2e-12")
    with s.page.expect_navigation() as nav:
        f.locator("button[type=submit], input[type=submit], button:not([type]), button.jenkins-button--primary").first.click()
    row["comment_status"] = nav.value.status
    row["comment_visible"] = f"comment by {role} e2e-12" in s.text()
    f = s.page.locator("form[name=acknowledge]")
    f.locator("textarea").fill("ack")
    with s.page.expect_navigation():
        f.locator("button[type=submit], input[type=submit], button:not([type]), button.jenkins-button--primary").first.click()
    row["after_ack_forms"] = [x.get_attribute("name") for x in s.page.locator("#main-panel form[method=post]").all()]
    f = s.page.locator("form[name=resolve]")
    if f.count():
        f.locator("textarea").fill("resolved e2e-12")
        with s.page.expect_navigation() as nav:
            f.locator("button[type=submit], input[type=submit], button:not([type]), button.jenkins-button--primary").first.click()
        row["resolve_status"] = nav.value.status
        row["state_after_resolve"] = re.sub(r"\s+", " ", s.page.locator("#main-panel tr:has(th:text-is('Status')) td").first.inner_text()) if s.page.locator("#main-panel tr:has(th:text-is('Status')) td").count() else re.sub(r"\s+", " ", s.text())[:200]
        row["forms_after_resolve"] = [x.get_attribute("name") for x in s.page.locator("#main-panel form[method=post]").all()]
    s.shot("#main-panel", f"I-{role}-resolved")
    s.done()
    emit(row)
# rerun validation (admin)
_, iid = f_incident()
s = Session("admin")
s.go(f"/batch-control/incidents/{iid}/")
f = s.page.locator("form[name=rerun]")
row = {"case": "rerun validation", "fields": [x.get_attribute("name") for x in f.locator("input, textarea, select").all()][:12]}
ta = f.locator("textarea[name=reason]")
if ta.count():
    ta.fill("rerun reason kept?")
for b in f.locator("input[name=approvers]").all():
    if b.is_checked():
        b.locator("xpath=following-sibling::label").first.click()
with s.page.expect_navigation() as nav:
    f.locator("button[type=submit], input[type=submit], button:not([type]), button.jenkins-button--primary").first.click()
row["status"] = nav.value.status
row["url"] = s.page.url.replace(BASE, "")
row["errors"] = [t.strip()[:120] for t in s.page.locator(".error, .jenkins-alert-danger").all_inner_texts() if t.strip()][:3]
row["reason_kept"] = s.page.locator("form[name=rerun] textarea[name=reason]").input_value() if s.page.locator("form[name=rerun] textarea[name=reason]").count() else None
s.shot("#main-panel", "I-rerun-validation")
s.done(); close()
emit(row)
