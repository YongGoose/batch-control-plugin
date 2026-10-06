"""e2e-16 arrangement (script console and REST as admin; arrangement only, no assertion about the plugin).

Accounts: w16 and w16b (Overall/Read, Item/Read, View/Read, BatchControl/RequestGrant; no standing Configure, Create or
Delete): the window holders; vh16 (Overall/Read, Item/Read, View/Read, BatchControl/Request, BatchControl/ViewHistory, no
Item/Build): the incident viewer who may request a rerun (D-72a). Items:
  r16/                 regular folder: job-a, del-16, ren-job, ren-dc, ren-adm, ren-own, kinds-job; folder sub/ with sub/job-b
  r16-renf/            regular folder with inner (a folder a window holder must not rename)
  r16-pipe             Pipeline
  r16-file             Freestyle: core file UPLOAD, password SECRET, string NOTE; prints the sha256 of each
  r16-stash            Pipeline: stashedFile STASHED, base64File B64, password SECRET, string NOTE; withFileParameter
  r16-fail-pw          Freestyle: password SECRET, string MODE; build #1 fails, later builds succeed
  r16-fail-file        Freestyle: core file UPLOAD + MODE; arm-file fail (a core file IS recoverable from the build)
  r16-fail-stash       Pipeline: stashedFile STASHED, string MODE; build #1 fails, later builds succeed
Every job is created while run control is on, so it starts approval-required (D-31). The builds print a sha256 of
the secret, never the secret. Idempotent: an existing item is left alone. Two executors on the built-in node."""
import sys
from xml.sax.saxutils import escape

sys.path.insert(0, __file__.rsplit("/", 1)[0])
from lib import api, groovy, gv, ENV  # noqa: E402

pw = ENV["BC_OTHER_PASSWORD"]
print(groovy(r'''
import jenkins.model.Jenkins
import hudson.model.*
import hudson.security.*
import org.jenkinsci.plugins.matrixauth.*
def j = Jenkins.get()
j.setNumExecutors(2)
def realm = j.getSecurityRealm()
def BC = 'io.jenkins.plugins.batchcontrol.security.BatchControlPermissions.'
def R = ['hudson.model.Hudson.Read', 'hudson.model.Item.Read', 'hudson.model.View.Read']
def s = j.getAuthorizationStrategy()
['w16': [BC + 'RequestGrant'], 'w16b': [BC + 'RequestGrant'], 'vh16': [BC + 'Request', BC + 'ViewHistory']].each { u, extra ->
  if (User.getById(u, false) == null) realm.createAccount(u, "''' + pw + r'''")
  User.getById(u, true).addProperty(new hudson.tasks.Mailer.UserProperty(u + '@e2e.local'))
  (R + extra).each { pid -> s.add(Permission.fromId(pid), new PermissionEntry(AuthorizationType.USER, u)) }
}
j.save()
def F = com.cloudbees.hudson.plugins.folder.Folder
def r16 = j.getItem('r16') ?: j.createProject(F, 'r16')
['job-a', 'del-16', 'ren-job', 'ren-dc', 'ren-adm', 'ren-own', 'kinds-job'].each { n -> if (r16.getItem(n) == null) { def p = r16.createProject(FreeStyleProject, n); p.setDescription('base'); p.save() } }
def sub = r16.getItem('sub') ?: r16.createProject(F, 'sub')
if (sub.getItem('job-b') == null) { def p = sub.createProject(FreeStyleProject, 'job-b'); p.setDescription('base'); p.save() }
def renf = j.getItem('r16-renf') ?: j.createProject(F, 'r16-renf')
if (renf.getItem('inner') == null) renf.createProject(FreeStyleProject, 'inner')
if (j.getItem('r16-pipe') == null) {
  def p = j.createProject(org.jenkinsci.plugins.workflow.job.WorkflowJob, 'r16-pipe')
  p.setDefinition(new org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition("echo 'r16'", true)); p.save()
}
return "r16=${r16.items*.name} sub=${sub.items*.name} renf=${renf.items*.name} executors=${j.numExecutors}"
'''))

PW = '<hudson.model.PasswordParameterDefinition><name>SECRET</name><description>a secret</description><defaultValue></defaultValue></hudson.model.PasswordParameterDefinition>'


def string(name, default=""):
    return f'<hudson.model.StringParameterDefinition><name>{name}</name><defaultValue>{default}</defaultValue><trim>false</trim></hudson.model.StringParameterDefinition>'


def fp(cls, name):
    return f'<{cls}><name>{name}</name><description>a file</description></{cls}>'


STASHED = "io.jenkins.plugins.file_parameters.StashedFileParameterDefinition"
BASE64 = "io.jenkins.plugins.file_parameters.Base64FileParameterDefinition"
SECRET_SHA = 'echo "SECRET_SHA=$(printf %s "$SECRET" | sha256sum | cut -c1-64)"'


def params(*defs):
    return ('<properties><hudson.model.ParametersDefinitionProperty><parameterDefinitions>' + "".join(defs)
            + '</parameterDefinitions></hudson.model.ParametersDefinitionProperty></properties>')


def freestyle(defs, shell):
    return ("<?xml version='1.1' encoding='UTF-8'?><project><description>e2e-16</description><keepDependencies>false</keepDependencies>"
            + params(*defs) + "<scm class='hudson.scm.NullSCM'/><canRoam>true</canRoam><disabled>false</disabled>"
            + f"<builders><hudson.tasks.Shell><command>{escape(shell)}</command></hudson.tasks.Shell></builders>"
            + "<publishers/><buildWrappers/></project>")


def pipeline(defs, script):
    return ("<?xml version='1.1' encoding='UTF-8'?><flow-definition><description>e2e-16</description><keepDependencies>false</keepDependencies>"
            + params(*defs) + "<definition class='org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition'>"
            + f"<script>{escape(script)}</script><sandbox>true</sandbox></definition><triggers/><disabled>false</disabled></flow-definition>")


JOBS = {
    "r16-file": freestyle([fp("hudson.model.FileParameterDefinition", "UPLOAD"), PW, string("NOTE", "n")],
                          'echo "UPLOAD_SHA=$(sha256sum UPLOAD | cut -c1-64)"\n' + SECRET_SHA + '\necho "NOTE=$NOTE"\n'),
    "r16-stash": pipeline([fp(STASHED, "STASHED"), fp(BASE64, "B64"), PW, string("NOTE", "n")],
                          "node {\n"
                          "  withFileParameter('STASHED') { sh 'echo \"STASHED_SHA=$(sha256sum \"$STASHED\" | cut -c1-64)\"' }\n"
                          "  withFileParameter('B64') { sh 'echo \"B64_SHA=$(sha256sum \"$B64\" | cut -c1-64)\"' }\n"
                          "  sh '" + SECRET_SHA.replace("'", "\\'") + "'\n"
                          "  echo \"NOTE=${params.NOTE}\"\n}\n"),
    "r16-fail-pw": freestyle([PW, string("MODE", "m")],
                             SECRET_SHA + '\necho "MODE=$MODE"\n'
                             'ARM="$JENKINS_HOME/r16-fail-pw.arm"; if [ -f "$ARM" ]; then rm -f "$ARM"; echo "armed: failing once"; exit 1; fi\n'),
    "r16-fail-file": freestyle([fp("hudson.model.FileParameterDefinition", "UPLOAD"), string("MODE", "m")],
                               'echo "UPLOAD_SHA=$(sha256sum UPLOAD | cut -c1-64)"\necho "MODE=$MODE"\n'
                               'ARM="$JENKINS_HOME/r16-fail-file.arm"; if [ -f "$ARM" ]; then rm -f "$ARM"; echo "armed: failing once"; exit 1; fi\n'),
    "r16-fail-stash": pipeline([fp(STASHED, "STASHED"), string("MODE", "m")],
                               "node {\n"
                               "  withFileParameter('STASHED') { sh 'echo \"STASHED_SHA=$(sha256sum \"$STASHED\" | cut -c1-64)\"' }\n"
                               "  echo \"MODE=${params.MODE}\"\n"
                               "  def armed = sh(returnStatus: true, script: 'ARM=\"$JENKINS_HOME/r16-fail-stash.arm\"; if [ -f \"$ARM\" ]; then rm -f \"$ARM\"; exit 1; fi') != 0\n"
                               "  if (armed) { error 'armed: failing once' }\n}\n"),
}
for name, xml in JOBS.items():
    if api("admin", f"/job/{name}/api/json").status_code == 200:
        print(name, "exists")
        continue
    r = api("admin", f"/createItem?name={name}", "POST", data=xml.encode("utf-8"), headers={"Content-Type": "application/xml"})
    print(name, "created", r.status_code)
    assert r.status_code == 200, (name, r.status_code, r.text[:300])
# e2e-16: the dialog scenarios (items J, params D) need the new job page; turn it on for the accounts they use
print(gv("""import jenkins.model.experimentalflags.UserExperimentalFlagsProperty
['w16','w16b','requester','admin'].each { id -> def u = hudson.model.User.getById(id, false); if (u != null) {
  def m = new HashMap(); m.put('new-job-page.flag', 'true')
  u.addProperty(new UserExperimentalFlagsProperty(m)); u.save() } }
return 'new-job-page on for the e2e-16 accounts'"""))
print(gv("""def j = jenkins.model.Jenkins.get()
def cl = j.pluginManager.uberClassLoader.loadClass('io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty')
return ['r16-file', 'r16-stash', 'r16-fail-pw', 'r16-fail-file', 'r16-fail-stash'].collect { n -> def p = j.getItem(n).getProperty(cl)
  n + ':approvalRequired=' + (p?.approvalRequired) + ',params=' + j.getItem(n).getProperty(hudson.model.ParametersDefinitionProperty)?.parameterDefinitionNames }.join('; ')"""))

# Fixture preconditions (e2e-16): what the r16 drivers assume, asserted here so a wrong fixture fails this step. Each unit
# starts without windows of the r16 accounts (an earlier unit on the same shard may have left some), so the standing
# permissions are what is checked.
import json  # noqa: E402
from lib import revoke_all  # noqa: E402

import os  # noqa: E402

if os.environ.get("R16_KEEP_WINDOWS") == "1":  # r16/durable.py re-arranges after a restart and keeps its windows
    print("leftover windows kept (R16_KEEP_WINDOWS=1)")
else:
    print("revoked leftover windows:", {u: revoke_all(u) for u in ("w16", "w16b", "vh16")})

state = json.loads(gv("""import hudson.model.*
def j = jenkins.model.Jenkins.get()
def cl = j.pluginManager.uberClassLoader.loadClass('io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty')
def jobs = ['r16-file', 'r16-stash', 'r16-fail-pw', 'r16-fail-file', 'r16-fail-stash'].collectEntries { n -> def p = j.getItem(n)
  [n, [approvalRequired: p?.getProperty(cl)?.approvalRequired,
       params: p?.getProperty(ParametersDefinitionProperty)?.parameterDefinitions?.collect { it.name + ':' + it.class.simpleName }]] }
def perms = [:]
['w16', 'w16b', 'vh16'].each { id -> def a = User.getById(id, false)?.impersonate2(); def f = j.getItem('r16')
  perms[id] = a == null ? null : [read: f.getACL().hasPermission2(a, Item.READ), configure: f.getACL().hasPermission2(a, Item.CONFIGURE),
    create: f.getACL().hasPermission2(a, Item.CREATE), delete: f.getACL().hasPermission2(a, Item.DELETE),
    build: j.getItem('r16-fail-pw').getACL().hasPermission2(a, Item.BUILD)] }
return groovy.json.JsonOutput.toJson([jobs: jobs, perms: perms, fileParameters: j.pluginManager.getPlugin('file-parameters')?.isActive()])"""))
WANT = {"r16-file": {"UPLOAD:FileParameterDefinition", "SECRET:PasswordParameterDefinition", "NOTE:StringParameterDefinition"},
        "r16-stash": {"STASHED:StashedFileParameterDefinition", "B64:Base64FileParameterDefinition", "SECRET:PasswordParameterDefinition"},
        "r16-fail-pw": {"SECRET:PasswordParameterDefinition", "MODE:StringParameterDefinition"},
        "r16-fail-file": {"UPLOAD:FileParameterDefinition", "MODE:StringParameterDefinition"},
        "r16-fail-stash": {"STASHED:StashedFileParameterDefinition", "MODE:StringParameterDefinition"}}
bad = []
if not state["fileParameters"]:
    bad.append("file-parameters plugin not active")
for n, want in WANT.items():
    j = state["jobs"].get(n) or {}
    if j.get("approvalRequired") is not True or not want <= set(j.get("params") or []):
        bad.append(f"{n}: {j}")
for u, p in state["perms"].items():
    if p is None or not p["read"] or p["configure"] or p["create"] or p["delete"] or p["build"]:
        bad.append(f"{u}: {p} (wants Item/Read only, no Configure/Create/Delete/Build)")
print(("PASS " if not bad else "FAIL ") + json.dumps({"check": "r16 fixture preconditions", "problems": bad})[:1500])
sys.exit(1 if bad else 0)
