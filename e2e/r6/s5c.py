"""Scenario 5c: Cancel Request (requester) and Revoke (admin, grants table) are red with red confirm dialogs."""
import re
from lib import Session, close, log, api
r = {}
def probe(s, label, shotname, confirm):
    b = s.page.locator("#main-panel a, #main-panel button", has_text=label).first
    out = {"count": s.page.locator("#main-panel a, #main-panel button", has_text=label).count()}
    if not out["count"]:
        return out
    out["class"] = b.get_attribute("class")
    out["color"] = b.evaluate("e => getComputedStyle(e).color")
    out["in_table"] = b.evaluate("e => !!e.closest('table')")
    b.scroll_into_view_if_needed()
    b.click(); s.page.wait_for_timeout(700)
    d = s.page.locator("dialog[open]")
    if d.count():
        out["dialog"] = re.sub(r"\s+", " ", d.first.inner_text())[:250]
        out["dialog_buttons"] = [(x.inner_text().strip(), x.get_attribute("class"), x.evaluate("e => getComputedStyle(e).backgroundColor")) for x in d.first.locator("button").all()]
        s.shot(d.first, shotname)
        if confirm:
            with s.page.expect_navigation():
                d.first.locator("button.jenkins-button--primary").first.click()
            s.page.wait_for_load_state("load")
            out["after_confirm"] = re.sub(r"\s+", " ", s.text())[:250]
        else:
            d.first.locator("button", has_text="Cancel").first.click()
    return out

s = Session("requester")
s.go("/batch-control/requests/20261003-115454-qgvdnv/")
s.shot(s.page.locator("#main-panel a, #main-panel button", has_text="Cancel Request").first, "S5c-01-cancel-request-button")
r["cancel_request"] = probe(s, "Cancel Request", "S5c-02-cancel-request-dialog", True)
s.done()

s = Session("admin")
s.go("/batch-control/grants/")
rv = s.page.locator("#main-panel table a, #main-panel table button", has_text="Revoke")
r["revoke_rows"] = rv.count()
if rv.count():
    s.shot(rv.first.locator("xpath=ancestor::tr"), "S5c-03-revoke-in-table")
r["revoke"] = probe(s, "Revoke", "S5c-04-revoke-dialog", False)
s.done(); close()
log("s5c", r)
for k, v in r.items():
    print(f"{k}: {v}")
