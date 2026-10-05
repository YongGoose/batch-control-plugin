"""e2e-11 copy of ../r9/r_role.py (grant id read from the Location header: ids are UUIDs, D-68).
C7: Batch Control: Role-Based Strategy (profile-role.yaml applied in the CasC UI): run gate, grant, 918 Manage Roles save."""
import re, time, sys
from lib import Session, close, api, log, groovy, clean
res = {}
import atexit
atexit.register(lambda: [print(k, v) for k, v in res.items()])
def strategy():
    return groovy("return jenkins.model.Jenkins.get().getAuthorizationStrategy().getClass().simpleName").replace("RResult: ", "")
def apply(path, tag):
    s = Session("admin")
    s.go("/manage/configuration-as-code/")
    s.page.click("#btn-open-apply-configuration"); s.page.wait_for_timeout(800)
    d = s.page.locator("dialog[open]").first
    d.locator("input[type=text], input:not([type])").first.fill(path)
    s.shot(d, f"{tag}-casc")
    d.locator("button.jenkins-button--primary, button[type=submit]").last.click()
    s.page.wait_for_load_state("load"); s.page.wait_for_timeout(1500)
    s.done()
apply("/var/jenkins_casc/profile-role.yaml", "RR1")
res["strategy"] = strategy()
res["req_build_team"] = api("requester", "/job/team/job/app-1/build", "POST").status_code
res["req_requestrun_team"] = api("requester", "/job/team/job/app-1/batch-control/").status_code
res["req_daily"] = api("requester", "/job/batch-daily/").status_code
res["req_configure_before"] = api("requester", "/job/team/job/app-1/configure").status_code
# grant
r = api("requester", "/batch-control/grants/create", "POST", data=[("scopeFullName", "team/app-1"), ("actions", "CONFIGURE"), ("durationMinutes", "15"), ("reason", "e2e-11 role"), ("approvers", "approver-1")])
res["grant_create"] = r.status_code
new = set(re.findall(r"/grants/([0-9a-f-]{36})/", r.headers.get("Location", "")))
res["grant_id"] = sorted(new)
for i in new:
    res["grant_approve"] = api("approver-1", f"/batch-control/grants/{i}/approve", "POST", data={"comment": "ok"}).status_code
res["req_configure_under_grant"] = api("requester", "/job/team/job/app-1/configure").status_code
s = Session("requester")
s.go("/job/team/job/app-1/configure")
s.page.fill("textarea[name=description]", "edited under role strategy e2e-11")
with s.page.expect_navigation() as nav:
    s.page.click("button[name=Submit]")
res["role_save"] = nav.value.status
s.done()
# 918 Manage Roles: add an item role through the page
s = Session("admin")
r = s.go("/manage/role-strategy/manage-roles")
res["manage_roles_page"] = r.status if r else None
if r and r.status != 200:
    r = s.go("/role-strategy/manage-roles"); res["manage_roles_page2"] = r.status
s.shot("#main-panel", "RR2-manage-roles")
res["manage_roles_console"] = [c[:120] for c in s.console][:3]
for page in ["/manage/role-strategy/manage-roles", "/manage/role-strategy/assign-roles"]:
    s.go(page)
    btn = s.page.locator("button", has_text="Save").first
    res[page + " save button"] = btn.count()
    if btn.count():
        btn.click(); s.page.wait_for_timeout(2500)
        res[page + " after save url"] = s.page.url.replace("http://localhost:8080", "")
        res[page + " strategy after save"] = strategy()
        res[page + " notification"] = [t for t in s.page.locator(".jenkins-notification, #notification-bar").all_inner_texts() if t.strip()]
s.done()
groovy_add = api("admin", "/role-strategy/strategy/addRole", "POST", data={"type": "projectRoles", "roleName": "e2e7-role", "permissionIds": "hudson.model.Item.Read", "overwrite": "true", "pattern": "prod/.*"})
res["addRole_endpoint"] = groovy_add.status_code
res["strategy_after_addRole"] = strategy()
res["req_configure_after_addRole"] = api("requester", "/job/team/job/app-1/configure").status_code
rm = api("admin", "/role-strategy/strategy/removeRoles", "POST", data={"type": "projectRoles", "roleNames": "e2e7-role"})
res["removeRoles"] = rm.status_code
res["strategy_after_remove"] = strategy()
s = Session("admin"); s.go("/manage/")
res["monitor_not_bc"] = s.page.locator(".jenkins-alert", has_text="is not a Batch Control strategy").count()
s.done()
apply("/var/jenkins_casc/jenkins.yaml", "RR9")
res["strategy_restored"] = strategy()
close()
log("r_role11", res)
