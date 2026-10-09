"""e2e-21 arrangement (script console and REST as admin; arrangement only, no assertion about the plugin besides the
fixture check at the end).

Item r21-reject (Freestyle, created here when missing; like every job the script console creates under run control it
starts not activated): the target of the pending activation and grant requests. The run request goes to the seeded
batch-daily. Accounts are the seeded ones (requester files, approver-1 decides). Idempotent: an existing item is left
alone; requests of earlier runs stay pending and are not looked at."""
import json
import sys

sys.path.insert(0, __file__.rsplit("/", 1)[0])
from lib import groovy, gv, check, JOB  # noqa: E402

print(groovy(r'''
import jenkins.model.Jenkins
import hudson.model.*
def j = Jenkins.get()
if (j.getItem("''' + JOB + r'''") == null) { def p = j.createProject(FreeStyleProject, "''' + JOB + r'''"); p.setDescription('e2e-21 reject colour'); p.save() }
return "batch-daily=" + (j.getItem('batch-daily') != null) + " ''' + JOB + r'''=" + (j.getItem("''' + JOB + r'''") != null)
'''))

facts = json.loads(gv(r'''
import jenkins.model.Jenkins
import hudson.model.*
def j = Jenkins.get()
def c = io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration.get()
def BCP = io.jenkins.plugins.batchcontrol.security.BatchControlPermissions
def has = { uid, obj, p -> def u = User.getById(uid, false); u == null ? null : obj.getACL().hasPermission2(u.impersonate2(), p) }
def job = j.getItem("''' + JOB + r'''")
def daily = j.getItem('batch-daily')
return groovy.json.JsonOutput.toJson([
  run_control: c.runControlEnabled, change_control: c.changeControlEnabled,
  job: job != null, job_disabled: job?.isDisabled(), daily: daily != null, daily_disabled: daily?.isDisabled(),
  requester_request: has('requester', j, BCP.REQUEST), requester_request_grant: has('requester', j, BCP.REQUEST_GRANT),
  requester_read_job: job == null ? null : has('requester', job, Item.READ),
  approver_approve: has('approver-1', j, BCP.APPROVE), approver_read_job: job == null ? null : has('approver-1', job, Item.READ)])'''))
ok = (facts.get("run_control") is True and facts.get("change_control") is True and facts.get("job") is True
      and facts.get("daily") is True and facts.get("daily_disabled") is False
      and all(facts.get(k) is True for k in ("requester_request", "requester_request_grant", "requester_read_job",
                                              "approver_approve", "approver_read_job")))
check("arrange", "r21 fixture preconditions (switches on, r21-reject and an enabled batch-daily, requester may request, "
      "approver-1 may approve)", ok, **facts)
sys.exit(0 if ok else 1)
