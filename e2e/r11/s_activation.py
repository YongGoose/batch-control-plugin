"""Arrangement in the browser: requester files an activation request for batch-daily (feeds the Activations list)."""
import re
from lib import Session, close, log
s = Session("requester")
s.go("/job/batch-daily/batch-control-activation/")
f = s.page.locator("#main-panel form").first
if f.locator("textarea[name=reason]").count():
    f.locator("textarea[name=reason]").fill("e2e-11 activation")
b = f.locator("input[name=approvers][value=approver-1]")
if b.count() and not b.is_checked():
    b.locator("xpath=following-sibling::label").first.click()
with s.page.expect_navigation():
    f.locator("button[name=Submit], button[type=submit]").first.click()
s.page.wait_for_load_state("load")
print(s.page.url, re.sub(r"\s+", " ", s.text())[:200])
log("s_activation", {"url": s.page.url})
s.done(); close()
