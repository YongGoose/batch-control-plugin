"""R4-9/R4-14: dialogs from the classic job sidebar (user `classic`, new job page off) and from a folder page
(user `fonly`, one-item window on the folder ops, D-71; used by s_folder_only.py)."""
import re
from lib import Session, close, api, log
UUID = r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
res = {}
def submit(s, d, label, key):
    d.get_by_role("button", name=label).click()
    try:
        s.page.wait_for_url(lambda u: "/batch-control/" in u and ("/grants/" in u or "/requests/" in u) and not u.endswith("/grants/"), timeout=8000)
    except Exception:
        dd = s.page.locator("dialog[open]").first
        res[key + "_stuck"] = re.sub(r"\s+", " ", dd.inner_text())[:900] if dd.count() else s.page.url
        s.shot("dialog[open]" if dd.count() else "body", key + "-stuck")
    s.page.wait_for_load_state("load")

def tick(d, name, value):
    box = d.locator(f"input[name={name}][value={value}]")
    res.setdefault("preticked", []).append((name, value, box.is_checked()))
    if not box.is_checked():   # job pages pre-tick Configure, folder pages pre-tick Create
        box.locator("xpath=following-sibling::label").first.click()
s = Session("classic")
s.go("/user/classic/experiments/")
s.page.locator("tr", has_text="new-job-page.flag").locator("select").select_option(label="Disabled")
s.page.click("button[name=Submit]"); s.page.wait_for_load_state("load")
s.go("/job/batch-pipeline/")
res["E_side"] = [re.sub(r"\s+", " ", a.inner_text()).strip() for a in s.page.locator("#tasks a, #tasks button").all()][:14]
s.shot("#side-panel", "E-01-classic-sidebar")
s.page.locator("#tasks a, #tasks button", has_text="Request Run").first.click()
s.page.wait_for_selector("dialog[open] textarea[name=reason]")
res["E_run_url_while_open"] = s.page.url
d = s.page.locator("dialog[open]").first
d.locator("textarea[name=reason]").fill("e2e-11 classic run dialog")
tick(d, "approvers", "approver-2")
s.shot("dialog[open]", "E-02-classic-run-dialog")
submit(s, d, "Submit Request", "E_run")
res["E_run_landing"] = s.page.url
res["E_run_uuid"] = bool(re.search(r"/requests/" + UUID + "/$", s.page.url))
s.shot("#main-panel", "E-03-classic-run-detail")
s.go("/job/batch-pipeline/")
s.page.locator("#tasks a, #tasks button", has_text="Request Change Permission").first.click()
s.page.wait_for_selector("dialog[open] input[name=scopeFullName]")
d = s.page.locator("dialog[open]").first
res["E_grant_url_while_open"] = s.page.url
res["E_grant_prefill"] = ((d.locator("[data-batch-control-item-kind]").first.get_attribute("data-batch-control-item-kind") if d.locator("[data-batch-control-item-kind]").count() else None), d.locator("input[name=scopeFullName]").input_value())
tick(d, "actions", "CONFIGURE")
d.locator("select[name=durationMinutes]").select_option("15")
d.locator("textarea[name=reason]").fill("e2e-11 classic grant dialog (revoke test)")
tick(d, "approvers", "approver-1")
s.shot("dialog[open]", "E-04-classic-grant-dialog")
submit(s, d, "Request Grant", "E_grant")
res["E_grant_landing"] = s.page.url
s.shot("#main-panel", "E-05-classic-grant-detail")
s.done()

# F. folder page, new-job-page user (folders render the classic page for everyone) - fonly asks a window on the folder ops (D-71)
s = Session("fonly")
s.go("/job/ops/")
res["F_side"] = [re.sub(r"\s+", " ", a.inner_text()).strip() for a in s.page.locator("#tasks a, #tasks button").all()][:14]
appbar = s.page.locator("[data-testid=app-bar-overflow-button]")
res["F_newui_appbar"] = appbar.count()
s.shot("#side-panel" if s.page.locator("#side-panel").count() else "body", "F-01-folder-page")
s.page.locator("#tasks a, #tasks button, .jenkins-app-bar a, .jenkins-app-bar button", has_text="Request Change Permission").first.click()
s.page.wait_for_selector("dialog[open] input[name=scopeFullName]")
d = s.page.locator("dialog[open]").first
res["F_url_while_open"] = s.page.url
res["F_prefill"] = ((d.locator("[data-batch-control-item-kind]").first.get_attribute("data-batch-control-item-kind") if d.locator("[data-batch-control-item-kind]").count() else None), d.locator("input[name=scopeFullName]").input_value())
res["F_item_kind"] = d.locator("[data-batch-control-item-kind]").first.get_attribute("data-batch-control-item-kind") if d.locator("[data-batch-control-item-kind]").count() else None
# D-71: scope type selector removed (no select_option)
s.page.wait_for_timeout(300)
res["F_scope_help_visible"] = [t.strip() for t in d.locator(".jenkins-form-description, .help").all_inner_texts() if "Folder only" in t or "folder" in t.lower()][:3]
for a in ("CREATE", "CONFIGURE", "DELETE"):
    tick(d, "actions", a)
d.locator("select[name=durationMinutes]").select_option("60")
d.locator("textarea[name=reason]").fill("e2e-11 folder-only window on ops")
tick(d, "approvers", "approver-1")
s.shot("dialog[open]", "F-02-folder-dialog-folder-only")
submit(s, d, "Request Grant", "F")
res["F_landing"] = s.page.url
res["F_detail"] = re.sub(r"\s+", " ", s.text())[:300]
s.shot("#main-panel", "F-03-folder-only-detail")
s.done()
close(); log("s_classic", res)
for k, v in res.items(): print(k, "=>", v)
