"""#75: requester-lacks-Build notice across repeated views and after the requester gains Build and re-designates."""
import re
from lib import Session, close, log, groovy, api
N = "The requester does not have Build permission on this job."
res = {}
r = api("reqonly", "/job/batch-daily/batch-control/submit", "POST",
        data=[("reason", "e2e-09 #75 reqonly"), ("approvers", "approver-1"), ("DATE", "2026-10-03"), ("MODE", "full")])
rid = re.search(r"requests/([^/]+)/", r.headers.get("Location", "")).group(1)
res["submit"] = (r.status_code, rid)
P = f"/batch-control/requests/{rid}/"
a = Session("approver-1"); q = Session("reqonly")
def views(tag, n=3):
    out = []
    for i in range(n):
        a.go(P); out.append(("approver-1", N in a.text()))
        q.go(P); out.append(("reqonly", N in q.text()))
    res[tag] = out
views("before Build")
a.shot("#main-panel", "75-01-approver-notice")
res["grant Build"] = groovy('''def s=jenkins.model.Jenkins.get().authorizationStrategy; s.add(hudson.model.Item.BUILD, new org.jenkinsci.plugins.matrixauth.PermissionEntry(org.jenkinsci.plugins.matrixauth.AuthorizationType.USER,'reqonly')); jenkins.model.Jenkins.get().save(); return 'ok' ''')
views("after Build, before re-designate", 1)
q.go(P)
f = q.page.locator("form[name=changeApprover]")
res["change form"] = f.count()
f.locator("#batch-control-approver-1 + label").click()   # add approver-2
q.shot(f, "75-02-redesignate")
with q.page.expect_navigation():
    f.locator("button[type=submit], button[name=Submit]").first.click()
q.page.wait_for_load_state("load")
res["approvers after"] = re.findall(r"Approvers\s+[^\n]+", q.text())[:1]
views("after re-designate", 2)
b = Session("approver-2"); b.go(P); res["approver-2 sees notice"] = N in b.text(); b.shot("#main-panel", "75-03-approver2-no-notice"); b.done()
a.go(P); a.shot("#main-panel", "75-04-approver1-after")
res["revoke Build"] = groovy('''def s=jenkins.model.Jenkins.get().authorizationStrategy; def m=s.getGrantedPermissionEntries(); m.get(hudson.model.Item.BUILD)?.removeIf{it.sid=='reqonly'}; jenkins.model.Jenkins.get().save(); return s.hasPermission2(hudson.model.User.getById('reqonly',false).impersonate2(), hudson.model.Item.BUILD) ''')
a.done(); q.done(); close()
for k, v in res.items(): print(k, "=>", v)
log("s75", res)
