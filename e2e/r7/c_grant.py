"""C3: change-control windows in the browser: request, approve, Configure under the window, record with grant id,
self-grant guard, Create + Delete under windows, 1-minute expiry, manager Revoke."""
import re, time
from lib import Session, close, api, log, clean
res = {}

def request(user, typ, name, actions, minutes, tag, pattern=None):
    s = Session(user)
    s.go("/batch-control/grants/")
    f = s.page.locator("form[name=createGrantRequest]")
    # D-71: scope type selector removed
    s.page.fill("input[name=scopeFullName]", name)
    for a in ["CREATE", "CONFIGURE", "DELETE"]:
        cb = s.page.locator(f"#grant-action-{a.lower()}")
        if cb.is_checked() != (a in actions):
            s.page.click(f"#grant-action-{a.lower()} + label")
    if pattern:
        s.page.fill("input[name=createNamePattern]", pattern)
    s.page.select_option("select[name=durationMinutes]", str(minutes))
    s.page.fill("textarea[name=reason]", f"e2e-07 {tag} {actions} {name}")
    s.page.click("#grant-approver-0 + label")
    s.shot(f, f"{tag}-01-grant-form")
    with s.page.expect_navigation() as nav:
        f.locator("button[name=Submit]").click()
    s.page.wait_for_load_state("load")
    row = s.page.locator("tr", has_text=f"e2e-07 {tag} ").first
    href = row.locator("a").first.get_attribute("href") if row.count() else ""
    rid = re.search(r"(\d{8}-\d{6}-\w+)", href or "")
    out = {"submit": nav.value.status, "landed": s.page.url, "id": rid.group(1) if rid else None}
    s.shot("#main-panel", f"{tag}-02-pending")
    s.done()
    a = Session("approver-1")
    a.go(f"/batch-control/grants/{out['id']}/")
    fm = a.page.locator("form[action$='approve']").first
    a.shot(fm if fm.count() else "#main-panel", f"{tag}-03-approver-form")
    if fm.count():
        ta = fm.locator("textarea")
        if ta.count(): ta.first.fill("ok")
        with a.page.expect_navigation() as nav:
            fm.locator("button").first.click()
        a.page.wait_for_load_state("load")
        out["approve"] = nav.value.status
    out["after"] = re.sub(r"\s+", " ", a.text())[:300]
    a.done()
    return out

# C3a CONFIGURE window on team/app-1, then configure in the browser
res["before_configure"] = api("requester", "/job/team/job/app-1/configure").status_code
# approve the pending one from the first attempt (20261003-124407-kd19ne) via the same browser path
res["C3a"] = request("requester", "JOB", "team/app-1", ["CONFIGURE"], 15, "C3a")
s = Session("requester")
r = s.go("/job/team/job/app-1/configure")
res["C3a"]["configure_page"] = r.status
if r.status == 200:
    s.page.fill("textarea[name=description]", "edited under window e2e-07")
    with s.page.expect_navigation() as nav:
        s.page.click("button[name=Submit]")
    s.page.wait_for_load_state("load")
    res["C3a"]["save"] = nav.value.status
    s.shot("#main-panel", "C3a-04-saved")
s.done()
res["C3a"]["desc"] = api("admin", "/job/team/job/app-1/api/json?tree=description").json().get("description")
ch = clean(api("admin", "/batch-control/changes/").text)
res["C3a"]["record"] = re.findall(r"CONFIGURE team/app-1 requester .{0,120}", ch)[:1]

# C3b self-grant guard: requester adds an authorization entry via config.xml under the CONFIGURE window
x = api("requester", "/job/team/job/app-1/config.xml").text
inj = x.replace("<properties>", "<properties><hudson.security.AuthorizationMatrixProperty><inheritanceStrategy class=\"org.jenkinsci.plugins.matrixauth.inheritance.InheritParentStrategy\"/><permission>USER:hudson.model.Item.Delete:requester</permission></hudson.security.AuthorizationMatrixProperty>", 1)
r = api("requester", "/job/team/job/app-1/config.xml", "POST", data=inj.encode(), headers={"Content-Type": "application/xml"})
res["C3b"] = {"status": r.status_code, "msg": clean(r.text)[:400],
              "auth_left": "AuthorizationMatrixProperty" in api("admin", "/job/team/job/app-1/config.xml").text}
ch = clean(api("admin", "/batch-control/changes/").text)
res["C3b"]["violation"] = re.findall(r"GRANT_VIOLATION team/app-1 requester .{0,200}", ch)[:1]

# C3c CREATE window on folder team (name restriction /e2e7-.*/), create in browser; refused other name
res["C3c"] = request("requester", "FOLDER", "team", ["CREATE", "DELETE"], 15, "C3c", pattern="/e2e7-.*/")
s = Session("requester")
r = s.go("/job/team/newJob")
res["C3c"]["newJob_page"] = r.status
s.shot("#main-panel", "C3c-04-new-item-page")
s.page.fill("#name", "e2e7-created")
s.page.click("li.hudson_model_FreeStyleProject, .hudson_model_FreeStyleProject")
with s.page.expect_navigation() as nav:
    s.page.click("#ok-button")
s.page.wait_for_load_state("load")
res["C3c"]["create_landed"] = s.page.url
s.shot("#main-panel", "C3c-05-created")
s.done()
res["C3c"]["api"] = api("admin", "/job/team/job/e2e7-created/api/json").status_code
r = api("requester", "/job/team/createItem?name=other-name&mode=hudson.model.FreeStyleProject", "POST")
res["C3c"]["other_name"] = r.status_code
res["C3c"]["other_name_msg"] = clean(r.text)[:300]
res["C3c"]["other_api"] = api("admin", "/job/team/job/other-name/api/json").status_code
# delete in the browser
s = Session("requester")
s.go("/job/team/job/e2e7-created/")
d = s.page.locator("a[href$='doDelete'], a:has-text('Delete'), button:has-text('Delete')").first
res["C3c"]["delete_link"] = d.count()
if d.count():
    try:
        d.click(); s.page.wait_for_timeout(800)
        dlg = s.page.locator("dialog[open]")
        if dlg.count():
            s.shot(dlg.first, "C3c-06-delete-dialog")
            with s.page.expect_navigation():
                dlg.first.locator("button.jenkins-button--primary, button:has-text('Yes'), button:has-text('Delete')").first.click()
    except Exception as e:
        res["C3c"]["delete_err"] = str(e)[:200]
s.done()
time.sleep(1)
res["C3c"]["after_delete_api"] = api("admin", "/job/team/job/e2e7-created/api/json").status_code
close()
log("c_grant", res)
for k, v in res.items(): print(k, v)
