"""Item 2: plain global matrix installed (console arrangement) -> monitor warns before install; core dialog; install."""
import re
from lib import Session, close, api, log, groovy
res = {}
res["arrange"] = groovy('''
import jenkins.model.Jenkins
import hudson.security.*
import org.jenkinsci.plugins.matrixauth.*
def j = Jenkins.get(); def old = j.getAuthorizationStrategy()
def g = new GlobalMatrixAuthorizationStrategy()
old.getGrantedPermissionEntries().each { p, es -> es.each { e -> g.add(p, e) } }
j.setAuthorizationStrategy(g); j.save(); return j.getAuthorizationStrategy().getClass().name''')
s = Session("admin")
s.go("/manage/")
mon = s.page.locator(".jenkins-alert", has_text="Before you install").first
res["mon"] = re.sub(r"\s+", " ", mon.inner_text())[:600] if mon.count() else None
if mon.count(): s.shot(mon, "S3R9-01-monitor-before-install")
b = s.page.locator("a, button", has_text="Install the Batch Control").first
res["install_btn"] = b.count()
if b.count():
    b.click(); s.page.wait_for_timeout(1200)
    d = s.page.locator("dialog[open]")
    if d.count():
        res["dialog"] = re.sub(r"\s+", " ", d.first.inner_text())[:300]
        s.shot(d.first, "S3R9-02-install-dialog")
        with s.page.expect_navigation():
            d.first.locator("button.jenkins-button--primary").last.click()
        s.page.wait_for_load_state("load")
s.go("/manage/configureSecurity/")
res["strategy_options"] = [o for o in s.page.locator("select option, .jenkins-radio__label, label").all_inner_texts() if "Batch Control" in o][:4]
s.done(); close()
res["after"] = groovy("return jenkins.model.Jenkins.get().getAuthorizationStrategy().getClass().name")
res["folderreq_still"] = api("folderreq", "/job/team/job/app-1/batch-control/").status_code
log("s3", res)
for k, v in res.items(): print(k, v)
