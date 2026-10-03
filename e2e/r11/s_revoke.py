"""R4-12: revoke from the window's detail page. Holder `classic` (no Manage) sees no revoke and gets 403 on POST;
`manager` revokes in the browser with the confirmation dialog; the window ends."""
import re, sys
from lib import Session, close, api, log
gid = sys.argv[1]
res = {}
P = f"/batch-control/grants/{gid}/"
s = Session("classic")
s.go(P)
res["holder_buttons"] = [b.inner_text().strip() for b in s.page.locator("#main-panel button, #main-panel a.jenkins-button").all() if b.inner_text().strip()]
res["holder_text"] = re.sub(r"\s+", " ", s.text())[:400]
s.shot("#main-panel", "R12-01-holder-detail")
s.done()
m = Session("manager")
m.go(P)
forms = m.page.locator("#main-panel form")
res["manager_forms"] = [(f.get_attribute("name"), f.get_attribute("action")) for f in forms.all()]
btn = m.page.locator("#main-panel button, #main-panel a", has_text=re.compile("Revoke", re.I)).first
res["manager_revoke_label"] = btn.inner_text().strip() if btn.count() else None
m.shot("#main-panel", "R12-02-manager-detail")
action = next((a for n, a in res["manager_forms"] if a and "revoke" in a.lower()), None)
res["holder_post_revoke"] = api("classic", action.replace("/jenkins", "", 1), "POST").status_code if action else "no form"
if btn.count():
    btn.click()
    m.page.wait_for_timeout(1000)
    dl = m.page.locator("dialog[open]").first
    res["confirm_dialog"] = re.sub(r"\s+", " ", dl.inner_text())[:300] if dl.count() else None
    m.shot("dialog[open]" if dl.count() else "#main-panel", "R12-03-confirm")
    if dl.count():
        with m.page.expect_navigation() as nav:
            dl.locator("button", has_text=re.compile("Revoke|Yes|OK", re.I)).last.click()
        m.page.wait_for_load_state("load")
        res["after_revoke"] = (nav.value.status if nav.value else "same-document", m.page.url)
    res["after_text"] = re.sub(r"\s+", " ", m.text())[:500]
    m.shot("#main-panel", "R12-04-revoked")
m.done()
res["holder_configure_after"] = api("classic", "/job/batch-pipeline/configure").status_code
close(); log("s_revoke", res)
for k, v in res.items(): print(k, "=>", v)
