"""#85: windows ended by turning change control off read 'Revoked (change control turned off) by <admin>'; manual revocation unchanged."""
import re
from lib import Session, close, log, api, clean, SHOTS
res = {}
res["approve 6oty09"] = api("approver-1", "/batch-control/grants/20261003-180032-6oty09/approve", "POST", data={"comment": "e2e-09 #85"}).status_code
a = Session("admin")
def toggle(on, tag):
    a.go("/manage/configure")
    cb = a.page.locator("input[name='_.changeControlEnabled']")
    if cb.is_checked() != on:
        cb.locator("xpath=following-sibling::label[1]").click()
    a.shot(cb.locator("xpath=ancestor::div[contains(@class,'jenkins-form-item') or contains(@class,'optionalBlock-container')][1]"), f"85-{tag}-config")
    a.page.click("button[name=Submit]")
    a.page.wait_for_load_state("load")
    dlg = a.page.locator("dialog[open]")
    if dlg.count():
        res[f"dialog {tag}"] = re.sub(r"\s+", " ", dlg.first.inner_text())
        a.shot(dlg.first, f"85-{tag}-dialog")
        dlg.first.locator("button.jenkins-button--primary").last.click(); a.page.wait_for_load_state("load")
    res[f"after save {tag}"] = a.page.url
toggle(False, "01-off")
a.go("/batch-control/grants/")
ended = a.page.locator("#main-panel table").last
res["ended rows"] = [re.sub(r"\s+", " ", t) for t in ended.locator("tbody tr").all_inner_texts()]
a.shot(ended, "85-02-ended-grants")
a.go("/batch-control/changes/")
t = a.text()
res["GRANT_REVOKE records"] = [l for l in t.split("\n") if "GRANT_REVOKE" in l][:5]
a.shot("#main-panel table", "85-03-changes")
toggle(True, "04-on")
a.done(); close()
for k, v in res.items(): print(k, "=>", v)
log("s85", res)
