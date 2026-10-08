"""e2e-19 arrangement (REST and script console as admin; arrangement only, no assertion about the plugin beyond the
fixture preconditions at the end). Idempotent: an existing item is left alone; the job properties are re-stated.

Items (every job is created while run control is on, so it starts with the D-31/D-34 lock and not activated):
  r19-gate     Freestyle: DATE string, MODE choice (full|partial), SECRET password; build token r19-tok; prints the
               values and the sha256 of the secret. Approval required (the lock).
  r19-gpipe    Pipeline: X string. Approval required.
  r19-rebuild  Freestyle: P string. Approval required (rebuild plugin's Rebuild on an approved build).
  r19-nag      Freestyle: fails once when armed ($JENKINS_HOME/r19-nag.arm); naginator retries once at once.
  r19-cbn      Freestyle: customize-build-now relabels the build link "Launch batch".
  r19-lock     Freestyle: needs the lockable resource r19-res.
  r19-jch      Freestyle: jobConfigHistory keeps its own history next to the CONFIGURE records.
  r19-ptsrc, r19-ptsrc2   Freestyle, not approval-required (a person starts them): parameterized-trigger to r19-ptdst.
  r19-ptdst    Freestyle: the lock (blockUpstream on), not activated.
  r19-cron     Freestyle: timer every minute; the lock (blockTimer on), not activated.
  r19-life, r19-dis, r19-exp   Freestyle, approval required: request life cycle, disabled job, approved-run expiry.
  r19-mx       multi-configuration project (axis A = a, b); r19-org organization folder (no navigator).
  r19-cred     Freestyle: credentials parameter CRED (default r19-cred-id, a username/password credential in the system
               store) + NOTE; prints the credential id the build received.
  r19-src      Freestyle, not approval-required: the source of the run parameter (two builds).
  r19-run      Freestyle: run parameter SRC on r19-src; prints the job and number it received.
  r19-delb     Freestyle, approval required: core file UPLOAD + MODE; fails when armed (an incident whose build is
               deleted afterwards: the rerun cannot recover the file)."""
import json
import sys

sys.path.insert(0, __file__.rsplit("/", 1)[0])
from lib import (api, gv, check, ensure_job, job_xml, string_p, choice_p, password_p, set_property, J)  # noqa: E402

SECRET_SHA = 'echo "SECRET_SHA=$(printf %s "$SECRET" | sha256sum | cut -c1-64)"'
FILE_P = "<hudson.model.FileParameterDefinition><name>UPLOAD</name><description>a file</description></hudson.model.FileParameterDefinition>"
NAG = ("<com.chikli.hudson.plugin.naginator.NaginatorPublisher><regexpForRerun></regexpForRerun><rerunIfUnstable>false</rerunIfUnstable>"
       "<rerunMatrixPart>false</rerunMatrixPart><checkRegexp>false</checkRegexp><regexpForMatrixStrategy>TestParent</regexpForMatrixStrategy>"
       "<delay class='com.chikli.hudson.plugin.naginator.FixedDelay'><delay>0</delay></delay><maxSchedule>1</maxSchedule>"
       "</com.chikli.hudson.plugin.naginator.NaginatorPublisher>")


def pt(target):
    return ("<hudson.plugins.parameterizedtrigger.BuildTrigger><configs><hudson.plugins.parameterizedtrigger.BuildTriggerConfig>"
            f"<configs class='empty-list'/><projects>{target}</projects><condition>ALWAYS</condition>"
            "<triggerWithNoParameters>true</triggerWithNoParameters><triggerFromChildProjects>false</triggerFromChildProjects>"
            "</hudson.plugins.parameterizedtrigger.BuildTriggerConfig></configs></hudson.plugins.parameterizedtrigger.BuildTrigger>")


ARMED = 'ARM="$JENKINS_HOME/{0}.arm"; if [ -f "$ARM" ]; then rm -f "$ARM"; echo "armed: failing once"; exit 1; fi\n'
LOCK = ("<org.jenkins.plugins.lockableresources.RequiredResourcesProperty><resourceNames>r19-res</resourceNames>"
        "<resourceNamesVar></resourceNamesVar><resourceNumber></resourceNumber><labelName></labelName>"
        "</org.jenkins.plugins.lockableresources.RequiredResourcesProperty>")
CRED_P = ("<com.cloudbees.plugins.credentials.CredentialsParameterDefinition><name>CRED</name><description>a credential</description>"
          "<defaultValue>r19-cred-id</defaultValue>"
          "<credentialType>com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials</credentialType>"
          "<required>false</required></com.cloudbees.plugins.credentials.CredentialsParameterDefinition>")
RUN_P = "<hudson.model.RunParameterDefinition><name>SRC</name><projectName>r19-src</projectName><filter>ALL</filter></hudson.model.RunParameterDefinition>"
MATRIX = ("<?xml version='1.1' encoding='UTF-8'?><matrix-project><description>e2e-19</description><keepDependencies>false</keepDependencies>"
          "<properties/><scm class='hudson.scm.NullSCM'/><canRoam>true</canRoam><disabled>false</disabled><triggers/>"
          "<concurrentBuild>false</concurrentBuild><axes><hudson.matrix.TextAxis><name>A</name><values><string>a</string>"
          "<string>b</string></values></hudson.matrix.TextAxis></axes><builders/><publishers/><buildWrappers/>"
          "<executionStrategy class='hudson.matrix.DefaultMatrixExecutionStrategyImpl'><runSequentially>false</runSequentially>"
          "</executionStrategy></matrix-project>")

print(gv("""import com.cloudbees.plugins.credentials.*
import com.cloudbees.plugins.credentials.domains.Domain
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl
def store = SystemCredentialsProvider.getInstance()
if (!store.credentials.any { it.id == 'r19-cred-id' }) {
  store.getCredentials().add(new UsernamePasswordCredentialsImpl(CredentialsScope.GLOBAL, 'r19-cred-id', 'e2e-19 credential', 'r19-user', 'r19-cred-secret'))
  store.save()
}
def m = org.jenkins.plugins.lockableresources.LockableResourcesManager.get()
if (m.fromName('r19-res') == null) { m.createResource('r19-res'); m.save() }
jenkins.model.Jenkins.get().setNumExecutors(2)
return 'credential r19-cred-id, lockable resource r19-res, 2 executors'"""))

JOBS = {
    "r19-gate": job_xml("fs", params=string_p("DATE", "2026-01-01") + choice_p("MODE", ["full", "partial"]) + password_p("SECRET"),
                        shell='echo "DATE=$DATE"\necho "MODE=$MODE"\n' + SECRET_SHA + "\n", extra="<authToken>r19-tok</authToken>"),
    "r19-gpipe": job_xml("wf", params=string_p("X", "one"), script="echo \"r19-gpipe X=${params.X}\""),
    "r19-rebuild": job_xml("fs", params=string_p("P", "p"), shell='echo "P=$P"'),
    "r19-nag": job_xml("fs", shell='echo "r19-nag ran"\n' + ARMED.format("r19-nag"), publishers=NAG),
    "r19-cbn": job_xml("fs", shell='echo "r19-cbn ran"'),
    "r19-lock": job_xml("fs", props=LOCK, shell='echo "r19-lock ran"'),
    "r19-jch": job_xml("fs", shell='echo "r19-jch ran"'),
    "r19-authz": job_xml("fs", shell='echo "r19-authz ran"'),
    "r19-ptdst": job_xml("fs", shell='echo "r19-ptdst ran"'),
    "r19-ptsrc": job_xml("fs", shell='echo "r19-ptsrc ran"', publishers=pt("r19-ptdst")),
    "r19-ptsrc2": job_xml("fs", shell='echo "r19-ptsrc2 ran"', publishers=pt("r19-ptdst")),
    "r19-cron": job_xml("fs", shell='echo "r19-cron tick"', triggers="<hudson.triggers.TimerTrigger><spec>* * * * *</spec></hudson.triggers.TimerTrigger>"),
    "r19-life": job_xml("fs", params=string_p("DATE", "2026-01-01"), shell='echo "DATE=$DATE"'),
    "r19-dis": job_xml("fs", shell='echo "r19-dis ran"'),
    "r19-exp": job_xml("fs", shell='echo "r19-exp ran"'),
    "r19-cred": job_xml("fs", params=CRED_P + string_p("NOTE", "n"), shell='echo "CRED=$CRED"\necho "NOTE=$NOTE"'),
    "r19-src": job_xml("fs", shell='echo "r19-src ran"'),
    "r19-run": job_xml("fs", params=RUN_P, shell='echo "SRC_JOBNAME=$SRC_JOBNAME"\necho "SRC_NUMBER=$SRC_NUMBER"'),
    "r19-delb": job_xml("fs", params=FILE_P + string_p("MODE", "m"),
                        shell='echo "UPLOAD_SHA=$(sha256sum UPLOAD | cut -c1-64)"\necho "MODE=$MODE"\n' + ARMED.format("r19-delb")),
}
created = []
for name, xml in JOBS.items():
    if ensure_job(name, xml):
        created.append(name)
if api("admin", "/job/r19-mx/api/json").status_code != 200:
    r = api("admin", "/createItem?name=r19-mx", "POST", data=MATRIX.encode(), headers={"Content-Type": "application/xml"})
    assert r.status_code == 200, ("r19-mx", r.status_code)
    created.append("r19-mx")
print(gv("""def j = jenkins.model.Jenkins.get()
if (j.getItem('r19-org') == null) j.createProject(jenkins.branch.OrganizationFolder, 'r19-org')
def cbn = j.getItem('r19-cbn')
def cl = j.pluginManager.uberClassLoader.loadClass('org.jenkinsci.plugins.customizebuildnow.BuildNowTextProperty')
if (cbn.getProperty(cl) == null) { cbn.addProperty(cl.getConstructor(String).newInstance('Launch batch')); cbn.save() }
return 'r19-org ' + j.getItem('r19-org')?.class?.simpleName + ', r19-cbn label ' + cbn.getProperty(cl)?.labels?.alternateBuildNow"""))
print("created", created)

# Job properties (arrangement): the not-approval-required jobs a person starts directly; the rest keep their lock.
for job in ("r19-ptsrc", "r19-ptsrc2", "r19-src"):
    print(job, set_property(job, approval=False, timer=False, upstream=False))
# r19-src needs two builds for the run parameter
# (two quick submissions would be merged into one queue item: wait for each build)
print(gv("""def j = jenkins.model.Jenkins.get(); def job = j.getItem('r19-src')
while (job.builds.size() < 2) { def f = job.scheduleBuild2(0, new hudson.model.Cause.UserIdCause()); if (f == null) break; f.get(60, java.util.concurrent.TimeUnit.SECONDS) }
return 'r19-src builds ' + job.builds*.number"""))

state = json.loads(gv("""def j = jenkins.model.Jenkins.get()
def cl = j.pluginManager.uberClassLoader.loadClass('io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty')
def out = [:]
j.getAllItems(hudson.model.Job).findAll { it.fullName.startsWith('r19-') && !(it.parent instanceof hudson.matrix.MatrixProject) }.each { x ->
  def p = x.getProperty(cl); out[x.fullName] = [p?.approvalRequired, p?.blockTimer, p?.blockUpstream] }
out['r19-org'] = j.getItem('r19-org')?.class?.name
out['r19-mx'] = j.getItem('r19-mx')?.class?.name
out['src-builds'] = j.getItem('r19-src').builds.size()
return groovy.json.JsonOutput.toJson(out)"""))
bad = []
for job in ("r19-gate", "r19-gpipe", "r19-rebuild", "r19-nag", "r19-cbn", "r19-lock", "r19-jch", "r19-life", "r19-dis", "r19-exp",
            "r19-cred", "r19-run", "r19-delb"):
    if (state.get(job) or [None])[0] is not True:
        bad.append(f"{job} not approval-required: {state.get(job)}")
for job in ("r19-ptsrc", "r19-ptsrc2", "r19-src"):
    if (state.get(job) or [True])[0] is not False:
        bad.append(f"{job} approval-required: {state.get(job)}")
if state.get("r19-org") != "jenkins.branch.OrganizationFolder" or state.get("r19-mx") != "hudson.matrix.MatrixProject":
    bad.append(f"kinds: {state.get('r19-org')} {state.get('r19-mx')}")
if state.get("src-builds", 0) < 2:
    bad.append("r19-src has fewer than 2 builds")
check("arrange", "r19 fixture preconditions", not bad, problems=bad, state=state)
sys.exit(1 if bad else 0)
