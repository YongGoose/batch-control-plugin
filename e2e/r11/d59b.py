"""Item 5 (D-59b): Batch Control role strategy + role-based project naming strategy. namer has Move+Delete on prod/*,
Create on folder team limited by the naming pattern team/team-.*. Moves into team: prod/x refused (naming rule),
prod/team-ok allowed; with change control off the move of prod/x2 behaves as in plain Jenkins."""
import re, sys
from lib import groovy, api, log, ENV, Session, close, SHOTS
step = sys.argv[1]
def viol():
    return [l for l in api("admin", "/batch-control/history/changes.csv").text.splitlines() if "GRANT_VIOLATION" in l]
if step == "arrange":
    print(groovy(r'''
import jenkins.model.Jenkins
import hudson.model.*
def j = Jenkins.get()
if (User.getById('namer', false) == null) j.getSecurityRealm().createAccount('namer', "''' + ENV["BC_OTHER_PASSWORD"] + r'''")
def prod = j.getItemByFullName('prod')
['team-ok', 'x2'].each { n -> if (prod.getItem(n) == null) { def p = prod.createProject(FreeStyleProject, n); p.save() } }
io.jenkins.plugins.casc.ConfigurationAsCode.get().configure('/var/jenkins_casc/profile-role-naming.yaml')
j.setProjectNamingStrategy(new org.jenkinsci.plugins.rolestrategy.RoleBasedProjectNamingStrategy(false))
j.save()
def u = User.getById('namer', true).impersonate2()
def chk = { n -> try { j.getProjectNamingStrategy().checkName('team', n); 'ok' } catch (e) { e.message } }
return "strategy=${j.authorizationStrategy.class.simpleName} naming=${j.projectNamingStrategy.class.simpleName} " +
  "namer create on team=${j.getItemByFullName('team').getACL().hasPermission2(u, Item.CREATE)} delete prod/x=${prod.getItem('x').getACL().hasPermission2(u, Item.DELETE)} " +
  "move prod/x=${prod.getItem('x').getACL().hasPermission2(u, com.cloudbees.hudson.plugins.folder.relocate.RelocationAction.RELOCATE)} " +
  "changeControl=${io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration.get().isChangeControlEnabled()}"
'''))
elif step == "viol":
    v = viol(); print(len(v)); [print(x) for x in v[-3:]]
elif step == "cc":
    print(groovy("def c = io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration.get(); c.setChangeControlEnabled(%s); c.save(); return c.isChangeControlEnabled()" % sys.argv[2]))
elif step == "create":
    # control: what the naming strategy says to namer on the New Item path
    s = Session("namer")
    out = {}
    for n in ["x", "team-new"]:
        r = api("namer", "/job/team/createItem", "POST", data={"name": n, "mode": "hudson.model.FreeStyleProject"})
        out[n] = (r.status_code, re.sub(r"\s+", " ", re.sub(r"<[^>]+>", " ", r.text))[:200] if r.status_code >= 400 else r.headers.get("Location"))
    s.done(); close(); print(out); log("d59b", {"create control": out})
