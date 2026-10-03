"""R4-9: Request Run dialog validation (reason only / approver only) - is the error shown inside the dialog?"""
import re, sys
from lib import Session, close, log
res = {}
for case in ("no-approver", "no-reason"):
    s = Session("requester")
    net = []
    s.page.on("response", lambda r: net.append(f"{r.status} {r.request.method} {r.url}") if r.request.method == "POST" else None)
    s.go("/job/batch-daily/")
    s.page.locator("[data-testid=app-bar-overflow-button]").first.locator("xpath=..").locator("a, button", has_text="Request Run").first.click()
    s.page.wait_for_selector("dialog[open] textarea[name=reason]")
    d = s.page.locator("dialog[open]").first
    if case == "no-approver":
        d.locator("textarea[name=reason]").fill("kept reason e2e-11")
    else:
        d.locator("input[name=approvers][value=approver-2]").locator("xpath=following-sibling::label").click()
    d.locator("input[name=value][type=text]").first.fill("2026-12-31")
    d.get_by_role("button", name="Submit Request").click()
    s.page.wait_for_timeout(3000)
    d = s.page.locator("dialog[open]").first
    res[case] = {"post": net, "url": s.page.url, "dialog": s.page.locator("dialog[open]").count(),
                 "text_top": re.sub(r"\s+", " ", d.inner_text())[:400] if d.count() else None,
                 "reason": d.locator("textarea[name=reason]").input_value() if d.count() else None,
                 "approver2": d.locator("input[name=approvers][value=approver-2]").is_checked() if d.count() else None,
                 "date": d.locator("input[name=value][type=text]").first.input_value() if d.count() else None}
    if d.count():
        d.evaluate("e => e.querySelector('.jenkins-dialog__contents').scrollTop = 0")
    s.shot("dialog[open]", f"D-02-run-dialog-{case}")
    s.done()
close(); log("s_rundlg_err", res)
for k, v in res.items(): print(k, "=>", v)
