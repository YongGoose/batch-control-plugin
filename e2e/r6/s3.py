"""Scenario 3: Batch Control matrix variant display name; global matrix -> monitor warning + confirmation -> install."""
import re
from lib import Session, close, groovy, log, SHOTS

r = {}
# 3.1 display name on the security page
s = Session("admin")
s.go("/manage/configureSecurity/")
labels = s.page.locator("label").all_inner_texts()
r["strategy_labels"] = [l for l in labels if "Batch Control" in l or "Matrix" in l or "Role" in l]
lab = s.page.locator("label", has_text="Batch Control: Project-based Matrix Authorization Strategy")
r["label_found"] = lab.count()
if lab.count():
    s.shot(lab.first, "S3-01-security-page-label")
s.done()

# 3.2 arrange: plain global matrix with every current entry (script console = arrangement only)
r["arrange"] = groovy(r'''
import jenkins.model.Jenkins
import hudson.security.*
def j = Jenkins.get(); def old = j.getAuthorizationStrategy()
def g = new GlobalMatrixAuthorizationStrategy()
old.getGrantedPermissionEntries().each { p, es -> es.each { e -> g.add(p, e) } }
j.setAuthorizationStrategy(g); j.save()
return j.getAuthorizationStrategy().getClass().name
''')

s = Session("admin")
s.go("/manage/")
mon = s.page.locator(".jenkins-alert", has_text="Before you install")
r["monitor_count"] = mon.count()
if mon.count():
    body = mon.first.inner_text()
    r["monitor_text"] = body[:900]
    r["before_you_install_first"] = body.strip().startswith("Before you install") or body.find("Before you install") < body.find("change control is enabled")
    s.shot(mon.first, "S3-02-monitor-before-you-install")
    btn = mon.first.locator("a, button", has_text="Install the Batch Control variant")
    r["install_control"] = btn.count()
    btn.first.click()
    s.page.wait_for_timeout(800)
    dlg = s.page.locator("dialog.jenkins-dialog, .jenkins-dialog")
    r["dialog_count"] = dlg.count()
    if dlg.count():
        r["dialog_text"] = dlg.first.inner_text()[:400]
        s.shot(dlg.first, "S3-03-confirm-dialog")
        r["strategy_before_ok"] = groovy("return jenkins.model.Jenkins.get().getAuthorizationStrategy().getClass().name")
        with s.page.expect_navigation():
            dlg.first.locator("button", has_text="Install").click()
        s.page.wait_for_load_state("load")
        r["after_url"] = s.page.url
        s.page.screenshot(path=str(SHOTS / "S3-04-after-install.png"))
r["strategy_after"] = groovy("return jenkins.model.Jenkins.get().getAuthorizationStrategy().getClass().name")
s.go("/manage/")
r["monitor_after"] = s.page.locator(".jenkins-alert", has_text="is not a Batch Control strategy").count()
r["console"] = s.console
r["bad"] = s.bad
s.done()
close()
log("s3", r)
for k, v in r.items():
    print(f"{k}: {v}")
