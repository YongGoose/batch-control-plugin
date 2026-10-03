"""R4-9/D-66/D-68 (new job page user `requester`): grant dialog on the grants page, Request Run dialog on the
new job page, validation inside the dialog, landing on the new request's detail page with a UUID id,
D-60 refused direct build -> pre-filled Request Run page, unknown ids -> 404."""
import re, json
import sys
from lib import Session, close, api, log, SHOTS
WANT = sys.argv[1] if len(sys.argv) > 1 else 'ADGH'
UUID = r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
res = {}
def tick(loc):
    loc.locator("xpath=following-sibling::label").first.click()

def dlg(s):
    return s.page.locator("dialog[open]").first

# A. grant dialog from the grants page
def sec_A():
    s = Session("requester")
    s.go("/batch-control/grants/")
    s.page.locator("#main-panel a, #main-panel button", has_text="Request Change Permission").first.click()
    s.page.wait_for_selector("dialog[open] select[name=scopeType]")
    d = dlg(s)
    res["A_url_while_open"] = s.page.url
    d.locator("select[name=scopeType]").select_option("JOB")
    d.locator("input[name=scopeFullName]").fill("batch-daily")
    tick(d.locator("input[name=actions][value=CONFIGURE]"))
    d.locator("textarea[name=reason]").fill("e2e-11 grant dialog reason")
    s.shot("dialog[open]", "A-01-grant-dialog-filled")
    d.locator("button", has_text="Request Grant").click()      # no approver checked -> validation error
    s.page.wait_for_timeout(2500)
    d = dlg(s)
    res["A_after_invalid"] = {"url": s.page.url, "dialog_open": s.page.locator("dialog[open]").count(),
        "errors": [t.strip() for t in s.page.locator("dialog[open] .error, dialog[open] .jenkins-alert-danger, dialog[open] .validation-error-area, dialog[open] .jenkins-form-item .error").all_inner_texts() if t.strip()],
        "reason_kept": d.locator("textarea[name=reason]").input_value() if d.count() else None,
        "scope_kept": d.locator("input[name=scopeFullName]").input_value() if d.count() else None,
        "configure_kept": d.locator("input[name=actions][value=CONFIGURE]").is_checked() if d.count() else None}
    if not res["A_after_invalid"]["errors"] and d.count():
        res["A_after_invalid"]["dialog_text"] = re.sub(r"\s+", " ", d.inner_text())[:600]
    s.shot("dialog[open]" if d.count() else "#main-panel", "A-02-grant-dialog-error")
    if d.count():
        tick(d.locator("input[name=approvers][value=approver-1]"))
        with s.page.expect_navigation():
            d.locator("button", has_text="Request Grant").click()
        s.page.wait_for_load_state("load")
    res["A_landing"] = s.page.url
    m = re.search(r"/grants/(" + UUID + r")/", s.page.url)
    res["A_uuid"] = m.group(1) if m else None
    res["A_detail_text"] = re.sub(r"\s+", " ", s.text())[:500]
    s.shot("#main-panel", "A-03-grant-detail")
    s.done()

# D. Request Run dialog on the new job page
def sec_D():
    s = Session("requester")
    s.go("/job/batch-daily/")
    bar = s.page.locator("[data-testid=app-bar-overflow-button]").first.locator("xpath=..")
    bar.locator("a, button", has_text="Request Run").first.click()
    s.page.wait_for_selector("dialog[open] textarea[name=reason]")
    d = dlg(s)
    res["D_url_while_open"] = s.page.url
    res["D_params"] = [e.evaluate("e => e.name + '=' + e.value") for e in d.locator("[name=value], select, input[type=text]").all()]
    d.locator("textarea[name=reason]").fill("e2e-11 run dialog reason")
    s.shot("dialog[open]", "D-01-run-dialog")
    d.locator("button[name=Submit], button[type=submit], button.jenkins-button--primary").first.click()
    s.page.wait_for_timeout(2500)
    d = dlg(s)
    res["D_after_invalid"] = {"url": s.page.url, "dialog_open": s.page.locator("dialog[open]").count(),
        "text": re.sub(r"\s+", " ", d.inner_text())[:700] if d.count() else re.sub(r"\s+", " ", s.text())[:400],
        "reason_kept": d.locator("textarea[name=reason]").input_value() if d.count() else None}
    s.shot("dialog[open]" if d.count() else "#main-panel", "D-02-run-dialog-error")
    if d.count():
        tick(d.locator("input[name=approvers][value=approver-1]"))
        with s.page.expect_navigation():
            d.locator("button[name=Submit], button[type=submit], button.jenkins-button--primary").first.click()
        s.page.wait_for_load_state("load")
    res["D_landing"] = s.page.url
    m = re.search(r"/requests/(" + UUID + r")/", s.page.url)
    res["D_uuid"] = m.group(1) if m else None
    res["D_detail_text"] = re.sub(r"\s+", " ", s.text())[:600]
    s.shot("#main-panel", "D-03-run-request-detail")
    res["D_console"] = [c for c in s.console if "81ab13ce/'" not in c][:5]
    s.done()

# G. D-60: Direct Build on the new job page -> core parameters dialog -> refused -> pre-filled Request Run page
def sec_G():
    s = Session("requester")
    s.go("/job/batch-daily/")
    bar = s.page.locator("[data-testid=app-bar-overflow-button]").first.locator("xpath=..")
    bar.locator("a, button", has_text="Direct Build").first.click()
    s.page.wait_for_timeout(2500)
    d = dlg(s)
    res["G_dialog"] = re.sub(r"\s+", " ", d.inner_text())[:300] if d.count() else None
    if d.count():
        for inp in d.locator("input[name=value][type=text]:visible").all():
            inp.fill("e2e11-" + inp.evaluate("e => e.closest('[name=parameter]')?.querySelector('[name=name]')?.value || 'x'"))
        s.shot("dialog[open]", "G-01-core-param-dialog")
        d.get_by_role("button", name="Build", exact=True).click()
        s.page.wait_for_timeout(5000)
        s.page.wait_for_load_state("load")
        res["G_after_click"] = {"dialogs": s.page.locator("dialog[open]").count(), "toasts": [x for x in s.page.locator(".jenkins-notification, #notification-bar, .tippy-box").all_inner_texts() if x.strip()],
                                "dialog_text": re.sub(r"\s+", " ", dlg(s).inner_text())[:500] if s.page.locator("dialog[open]").count() else None}
        s.page.screenshot(path=str(SHOTS / "G-01b-after-submit.png"))
    res["G_landing"] = s.page.url
    res["G_h1"] = s.page.locator("h1").first.inner_text() if s.page.locator("h1").count() else None
    res["G_prefill"] = [e.evaluate("e => (e.closest('[name=parameter]')?.querySelector('[name=name]')?.value||'') + '=' + e.value") for e in s.page.locator("#main-panel [name=parameter] [name=value]:not([type=hidden])").all()]
    res["G_text"] = re.sub(r"\s+", " ", s.text())[:400]
    s.shot("#main-panel", "G-02-prefilled-request-run")
    s.done()
    res["G_builds_queued"] = api("admin", "/job/batch-daily/api/json?tree=nextBuildNumber").json()

# H. unknown ids
def sec_H():
    for p in ["/batch-control/grants/00000000-0000-4000-8000-000000000000/", "/batch-control/grants/no-such-id/",
              "/batch-control/requests/00000000-0000-4000-8000-000000000000/", "/batch-control/activations/00000000-0000-4000-8000-000000000000/"]:
        res["H " + p] = api("admin", p).status_code
    for k in ("A_uuid", "D_uuid"):
        if res.get(k):
            p = ("/batch-control/grants/" if k == "A_uuid" else "/batch-control/requests/") + res[k] + "/"
            res["H detail " + p] = (api("requester", p).status_code, api("approver-1", p).status_code)
for k in WANT:
    globals()['sec_' + k]()
close()
log("s_dialogs", res)
for k, v in res.items(): print(k, "=>", v)
