"""C6: global switches off in the browser = Jenkins behaviour; back on. Also UX-7 cancel dialog text."""
import re, time
from lib import Session, close, api, log, clean
res = {}
def toggle(run, change, tag):
    s = Session("admin")
    s.go("/manage/batch-control-configuration/")
    for name, want in [("runControlEnabled", run), ("changeControlEnabled", change)]:
        cb = s.page.locator(f"input[name='_.{name}']")
        if cb.is_checked() != want:
            s.page.locator(f"input[name='_.{name}'] + label").click()
    s.shot(s.page.locator("input[name='_.runControlEnabled']").locator("xpath=ancestor::div[contains(@class,'jenkins-section')][1]"), f"{tag}-01-switches")
    with s.page.expect_navigation() as nav:
        s.page.click("button[name=Submit]")
    s.page.wait_for_load_state("load")
    st = nav.value.status
    s.done()
    return st
# cancel dialog wording
r = api("requester", "/job/batch-pipeline/batch-control/submit", "POST", data={"reason": "e2e-07 cancel dialog", "approvers": "approver-1"})
rid = re.search(r"(\d{8}-\d{6}-\w+)", r.headers.get("Location", "")).group(1)
s = Session("requester")
s.go(f"/batch-control/requests/{rid}/")
s.page.locator("#main-panel a, #main-panel button", has_text="Cancel Request").first.click()
s.page.wait_for_timeout(1500)
d = s.page.locator("dialog[open]")
res["cancel_dialog"] = [x.inner_text().strip() for x in d.first.locator("button").all()] if d.count() else None
res["cancel_dialog_text"] = re.sub(r"\s+", " ", d.first.inner_text())[:200] if d.count() else None
if d.count(): s.shot(d.first, "A4-01b-cancel-dialog")
s.done()

res["off_save"] = toggle(False, False, "C6a")
res["off_build_pipeline"] = api("requester", "/job/batch-pipeline/build", "POST").status_code
time.sleep(12)
res["off_pipeline_builds"] = api("admin", "/job/batch-pipeline/api/json?tree=builds[number,result,actions[causes[shortDescription]]]").json()["builds"][:1]
res["off_configure_with_window"] = api("requester", "/job/team/job/app-1/configure").status_code
s = Session("requester")
s.go("/job/batch-daily/")
res["off_job_notice"] = "Batch Control" in s.text()
res["off_appbar_text"] = re.findall(r"(Build with Parameters|Build Now|Request Run|Direct Build[^\n]*)", s.text())[:4]
s.shot("#main-panel", "C6a-02-job-page-off")
s.go("/batch-control/")
res["off_tabs"] = [re.sub(r"\s+", " ", t.inner_text()).strip() for t in s.page.locator("nav[data-batch-control-tabs] a").all()]
s.shot("#main-panel", "C6a-03-root-off")
s.done()
ch = clean(api("admin", "/batch-control/changes/").text)
res["toggle_records"] = re.findall(r"CONFIG_TOGGLE .{0,160}", ch)[:2]
res["grants_after_off"] = re.findall(r"Revoked[^.]{0,80}", clean(api("admin", "/batch-control/grants/").text))[:3]
res["on_save"] = toggle(True, True, "C6b")
res["on_build_pipeline"] = api("requester", "/job/batch-pipeline/build", "POST").status_code
close()
log("c_switch", res)
for k, v in res.items(): print(k, v)
