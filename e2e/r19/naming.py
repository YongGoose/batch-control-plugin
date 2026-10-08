"""e2e-19 G-27 (SPEC 8 line 172, T-08-168): a CREATE window never bypasses role-strategy's RoleBasedProjectNamingStrategy,
and the D-40 name restriction applies on top. Runs inside the `role` unit (Batch Control role-strategy variant).

usage: python naming.py     rows: out/naming.jsonl, shots: R19-NAMING-*.png
Arrangement: casc/profile-role-naming-window.yaml, RoleBasedProjectNamingStrategy, change control on, regular folder nf.
role-strategy 927 grants Item/Create on every folder to a user holding any project role with Create while its naming
strategy is installed (RoleMap.AclImpl), and its naming strategy admits a name only through such a role. So a user the
strategy admits a name for already holds Create on nf, and a window-only user is admitted no name at all:
  role  requester (project role Create on nf/teamA-.*) with an approved CREATE window: on the New Item page zz-ui shows
        the strategy's message and teamA-ui is created through the page; over REST teamA-1 is created and zz-1 refused
        (4xx, the strategy's message, no item): the window does not loosen the naming strategy
  win   g27 (no Create role; Create on nf only from windows): an unrestricted window creates nothing, teamA-w1 is refused
        by the strategy; with the restriction /teamA-r.*/ teamA-r1 (restriction admits) is refused by the strategy, no
        item; teamA-x (restriction refuses) is refused, no item, one GRANT_VIOLATION shown on the Changes screen
At the end the default naming strategy, the change-control switch and profile-role.yaml are restored."""
import sys

sys.path.insert(0, __file__.rsplit("/", 1)[0])
import lib  # noqa: E402
from lib import Session, api, gv, check, note, decide, text_of, J, ENV, run_sections  # noqa: E402

lib.LOGNAME[0] = "naming"
U1, U2 = "requester", "g27"
H = {"Content-Type": "application/x-www-form-urlencoded"}
NAMES = ("teamA-ui", "zz-ui", "teamA-1", "zz-1", "teamA-w1", "teamA-r1", "teamA-x")
CC = "io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration.get()"
SEL = ".validation-error-area, .input-validation-message, .error, #itemname-invalid"


def exists(name):
    return api("admin", J("nf/" + name) + "/api/json").status_code == 200


def cleanup():
    for n in NAMES:
        if exists(n):
            api("admin", J("nf/" + n) + "/doDelete", "POST")


def revoke_all(user):
    ids = gv("""def now=System.currentTimeMillis(); def d=new File(jenkins.model.Jenkins.get().rootDir,'batch-control/grants'); def o=[]
if(d.exists()) d.listFiles().findAll{it.name.endsWith('.xml')}.each{f-> def g=new XmlSlurper().parse(f)
  if(g.user.text()=='%s' && g.revokedAtMillis.text()=='' && (g.expiresAtMillis.text() as long) > now) o<<g.id.text()}
return o.join(',')""" % user)
    for gid in [x for x in ids.split(",") if x]:
        api("admin", f"/batch-control/grants/{gid}/revoke", "POST")


def window(user, pattern=None):
    revoke_all(user)
    data = [("scopeFullName", "nf"), ("durationMinutes", "30"), ("reason", "e2e-19 G-27"), ("actions", "CREATE"),
            ("approvers", "approver-1")]
    if pattern:
        data.append(("createNamePattern", pattern))
    r = api(user, "/batch-control/grants/create", "POST", data=data)
    gid = lib.loc_id(r)
    assert gid, (r.status_code, text_of(r.text)[:300])
    assert decide("approver-1", "grants", gid, "approve") in (200, 302)
    return gid


def create(user, name):
    r = api(user, J("nf") + f"/createItem?name={name}&mode=hudson.model.FreeStyleProject", "POST", headers=H)
    return r.status_code, text_of(r.text)


def violations(user, name):
    return len(lib.changes(lambda r: r.get("type") == "GRANT_VIOLATION" and r.get("target") == "nf/" + name
                           and r.get("user") == user))


def create_on_nf(user):
    return gv("def u=hudson.model.User.getById('%s', true).impersonate2(); "
              "return jenkins.model.Jenkins.get().getItem('nf').getACL().hasPermission2(u, hudson.model.Item.CREATE)" % user)


def sec_role():
    note("role", "requester's own Create on nf (role-strategy: a project Create role grants Create on folders while "
         "RoleBasedProjectNamingStrategy is installed)", create_on_nf=create_on_nf(U1))
    gid = window(U1)
    s = Session(U1)
    s.go("/job/nf/newJob")
    name = s.page.locator("input#name, input[name=name]").first
    name.fill("zz-ui")
    name.press("Tab")
    s.page.wait_for_timeout(1500)
    msg_bad = " ".join(t.strip() for t in s.page.locator(SEL).all_inner_texts() if t.strip())
    s.shot("#main-panel", "R19-NAMING-role-01-zz-ui")
    name.fill("teamA-ui")
    name.press("Tab")
    s.page.wait_for_timeout(1500)
    msg_ok = " ".join(t.strip() for t in s.page.locator(SEL).all_inner_texts() if t.strip())
    radio = s.page.locator("input[name=mode][value='hudson.model.FreeStyleProject']").first
    radio.locator("xpath=..").click()
    if not radio.is_checked():
        radio.check(force=True)
    s.shot("#main-panel", "R19-NAMING-role-02-teamA-ui")
    with s.page.expect_navigation(timeout=15000):
        s.page.locator("#ok-button").first.click()
    s.page.wait_for_load_state("load")
    landed = s.page.url
    s.shot("#main-panel", "R19-NAMING-role-03-created")
    s.done()
    check("role", "New Item page with a CREATE window under RoleBasedProjectNamingStrategy: zz-ui shows the strategy's "
          "message naming the pattern, teamA-ui is created through the page",
          "does not match the job name convention" in msg_bad and "nf/teamA-.*" in msg_bad and "convention" not in msg_ok
          and exists("teamA-ui") and not exists("zz-ui") and "teamA-ui" in landed,
          window=gid, zz_ui_message=msg_bad[:200], teamA_ui_message=msg_ok[:200], landed=landed)
    ok = create(U1, "teamA-1")
    bad = create(U1, "zz-1")
    check("role", "over REST with the window: teamA-1 created; zz-1 refused by the strategy (4xx, its message, no item)",
          ok[0] in (200, 302) and exists("teamA-1") and bad[0] >= 400 and not exists("zz-1")
          and "does not match the job name convention" in bad[1],
          admitted=ok[0], refused=bad[0], refused_text=bad[1][:240])
    note("role", "GRANT_VIOLATION records for the naming-strategy refusal (not required by SPEC)", zz_1=violations(U1, "zz-1"))
    revoke_all(U1)


def sec_win():
    premise = create_on_nf(U2)
    gid = window(U2)
    free = create(U2, "teamA-w1")
    check("win", "g27 (no Create role, no Create on nf of its own) with an unrestricted CREATE window: teamA-w1 refused "
          "by the naming strategy (4xx, its message, no item): the window does not bypass the strategy",
          premise == "false" and free[0] >= 400 and not exists("teamA-w1") and "Create" in free[1],
          own_create=premise, window=gid, status=free[0], text=free[1][:240])
    gid = window(U2, "/teamA-r.*/")
    ok = create(U2, "teamA-r1")
    v0 = violations(U2, "teamA-x")
    bad = create(U2, "teamA-x")
    v1 = violations(U2, "teamA-x")
    check("win", "with the restriction /teamA-r.*/: teamA-r1 (restriction admits) refused by the naming strategy, no item; "
          "teamA-x (restriction refuses) refused, no item, one GRANT_VIOLATION",
          ok[0] >= 400 and not exists("teamA-r1") and bad[0] >= 400 and not exists("teamA-x") and v1 - v0 == 1,
          window=gid, matching=ok[0], matching_text=ok[1][:240], restricted=bad[0], restricted_text=bad[1][:240],
          violations=v1 - v0)
    s = Session("approver-1")
    s.go("/batch-control/history/?kind=changes")
    page = s.page.locator("#main-panel").inner_text()
    s.shot("#main-panel", "R19-NAMING-win-01-changes")
    s.done()
    check("win", "the Changes screen shows the GRANT_VIOLATION for nf/teamA-x by g27",
          "GRANT_VIOLATION" in page and "nf/teamA-x" in page and "g27" in page, excerpt=page[:400])
    revoke_all(U2)


if __name__ == "__main__":
    prev = gv(f"""def j=jenkins.model.Jenkins.get(); def prev=j.getProjectNamingStrategy().getClass().simpleName + ' cc=' + {CC}.isChangeControlEnabled()
if (hudson.model.User.getById('{U2}', false) == null) j.getSecurityRealm().createAccount('{U2}', '{ENV["BC_OTHER_PASSWORD"]}')
if (j.getItem('nf') == null) j.createProject(com.cloudbees.hudson.plugins.folder.Folder, 'nf')
io.jenkins.plugins.casc.ConfigurationAsCode.get().configure('/var/jenkins_casc/profile-role-naming-window.yaml')
j.setProjectNamingStrategy(new org.jenkinsci.plugins.rolestrategy.RoleBasedProjectNamingStrategy(false)); j.save()
def c={CC}; c.setChangeControlEnabled(true); c.save()
return prev + ' -> ' + j.authorizationStrategy.class.simpleName + ' ' + j.projectNamingStrategy.class.simpleName + ' cc=' + c.isChangeControlEnabled()""")
    note("arrange", "naming strategy and change control before -> after", value=prev)
    try:
        cleanup()
        run_sections(["role", "win"], {"role": sec_role, "win": sec_win})
    finally:
        cleanup()
        was_cc = "cc=true" in prev.split(" -> ")[0]
        restored = gv(f"""def j=jenkins.model.Jenkins.get(); j.setProjectNamingStrategy(jenkins.model.ProjectNamingStrategy.DEFAULT_NAMING_STRATEGY); j.save()
def c={CC}; c.setChangeControlEnabled({str(was_cc).lower()}); c.save()
io.jenkins.plugins.casc.ConfigurationAsCode.get().configure('/var/jenkins_casc/profile-role.yaml')
return j.projectNamingStrategy.class.simpleName + ' cc=' + c.isChangeControlEnabled() + ' ' + j.authorizationStrategy.class.simpleName""")
        note("restore", "naming strategy, change control and profile-role.yaml restored", value=restored)
    lib.close()
