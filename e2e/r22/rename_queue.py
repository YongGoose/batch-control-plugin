"""e2e-22 R2-04: a rename that invalidates an approved request drops its queued run, also when the renaming user can no
longer read the job afterwards (the listener used to read the queue as that user, whose Item/Read filter hid the item).

usage: python rename_queue.py [ACR]       rows: out/rename_queue.jsonl, shots: screenshots/run-22/R22-R2-04-*.png
Arrangement (script console as admin; restored at the end): the authorization strategy is saved (XStream, to
$JENKINS_HOME/r22-r2-04-strategy.xml) and replaced through JCasC by "Batch Control: Role-Based Strategy" in which
configurer holds Overall/Read and, through the item role r22-team-a-.*, Job/Read, Configure and Build only on items
matching that pattern (the PoC's r2_04_role.yaml); requester requests (Request + the same item role), approver-1 approves
(Job/Read + Approve). Change control is switched off for the check (otherwise Configure needs a permission window, which
also confers Item/Read and would hide the effect); run control stays on. Each item is approval-required and restricted to
the label r22-r2-04-node, which no node carries until the end of section R, so the approved run waits in the queue.
At the end the strategy, the change-control switch and the built-in node's labels are restored as they were.

A  arrangement as above; preconditions: the role strategy is active, configurer reads and configures r22-team-a-*.
C  control: requester requests r22-team-a-ctrl, approver-1 approves (queued), admin renames it to r22-ctrl-old: the
   request is INVALIDATED and the queued run is gone (the listener's own path; passes without the fix too).
R  requester requests r22-team-a-nightly, approver-1 approves (queued), configurer renames it to r22-nightly-old (POST
   confirmRename) and can no longer read it (404): the request is INVALIDATED and no queued run of the job remains (the
   bug: it stayed); then the built-in node gets the label: no build of r22-nightly-old runs within 20 s."""
import json
import sys

sys.path.insert(0, __file__.rsplit("/", 1)[0])
import lib  # noqa: E402
from lib import api, check, note, gv, J  # noqa: E402

lib.LOGNAME[0] = "rename_queue"
LABEL = "r22-r2-04-node"
PAIRS = {"C": ("r22-team-a-ctrl", "r22-ctrl-old", "admin"), "R": ("r22-team-a-nightly", "r22-nightly-old", "configurer")}
ROLE_YAML = """jenkins:
  authorizationStrategy:
    batchControlRoleBased:
      roles:
        global:
          - name: admin
            permissions: [Overall/Administer]
            entries: [{user: admin}]
          - name: bc-requester
            permissions: [Overall/Read, BatchControl/Request]
            entries: [{user: requester}]
          - name: bc-approver
            permissions: [Overall/Read, Job/Read, BatchControl/Approve, BatchControl/ViewHistory]
            entries: [{user: approver-1}]
          - name: reader
            permissions: [Overall/Read]
            entries: [{user: configurer}]
        items:
          - name: r22-team-a
            pattern: "r22-team-a-.*"
            permissions: [Job/Read, Job/Configure, Job/Build]
            entries: [{user: configurer}, {user: requester}]
"""
STATE = {}


def sec_A():
    out = json.loads(gv(r"""import jenkins.model.Jenkins
def j = Jenkins.get()
def cfg = io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration.get()
def snap = new File(j.rootDir, 'r22-r2-04-strategy.xml')
def prev = new File(j.rootDir, 'r22-r2-04-previous.json')
def role = j.authorizationStrategy.getClass().simpleName.contains('RoleBased')
// a run that died before its restore left the snapshot of the original state: keep it, never snapshot our own profile
if (!(role && snap.exists() && prev.exists())) {
  snap.setText(Jenkins.XSTREAM2.toXML(j.authorizationStrategy), 'UTF-8')
  prev.setText(groovy.json.JsonOutput.toJson([changeControl: cfg.changeControlEnabled, labels: j.labelString,
    executors: j.numExecutors, strategy: j.authorizationStrategy.getClass().name]), 'UTF-8')
}
def yaml = new File(j.rootDir, 'r22-r2-04-role.yaml')
yaml.setText(""" + json.dumps(ROLE_YAML) + r""", 'UTF-8')
io.jenkins.plugins.casc.ConfigurationAsCode.get().configure(yaml.absolutePath)
cfg.setChangeControlEnabled(false); cfg.save()
if (j.numExecutors < 1) { j.setNumExecutors(1) }
return groovy.json.JsonOutput.toJson([previous: new groovy.json.JsonSlurper().parse(prev),
  strategy: j.authorizationStrategy.getClass().simpleName, change_control: cfg.changeControlEnabled,
  run_control: cfg.runControlEnabled, executors: j.numExecutors])"""))
    STATE.update(out)
    items = {}
    for sec, (old, new, _) in PAIRS.items():
        items[old] = gv(f"""import jenkins.model.Jenkins
def j = Jenkins.get()
['{old}', '{new}'].each {{ n -> j.getItemByFullName(n)?.delete() }}
def p = j.createProject(hudson.model.FreeStyleProject, '{old}')
p.setAssignedLabel(j.getLabel('{LABEL}'))
p.getBuildersList().add(new hudson.tasks.Shell('echo r22 R2-04 ran'))
p.save()
return 'label=' + p.assignedLabelString + ' nodes=' + j.getLabel('{LABEL}').nodes.size()""")
        items[old] += " " + lib.set_property(old, approval=True, timer=True, upstream=True)
    old = PAIRS["R"][0]
    perms = {p: lib.has_permission("configurer", old, f"hudson.model.Item.{p}") for p in ("READ", "CONFIGURE")}
    perms["requester_read"] = lib.has_permission("requester", old, "hudson.model.Item.READ")
    ok = ("RoleBased" in out["strategy"] and out["change_control"] is False and out["run_control"]
          is True and out["executors"] >= 1 and all(perms.values())
          and all(f"label={LABEL} nodes=0" in v and "approvalRequired=true" in v for v in items.values()))
    check("A", "arrangement: the role-based profile is active (strategy saved), change control off, run control on, "
          "configurer reads and configures r22-team-a-nightly, the items wait for a label no node has", ok,
          state=out, items=items, perms=perms)


def approved_and_queued(sec, job):
    status, rid, _ = lib.run_req("requester", job, f"e2e-22 R2-04 {sec}", approvers=("approver-1",))
    st = lib.decide("approver-1", "requests", rid, "approve", "e2e-22") if rid else None
    queued = lib.wait_queued(job, 30)
    check(sec, f"precondition: the request on {job} is filed, approved, and its run waits in the queue",
          status == 302 and st in (200, 302, 303) and len(queued) == 1, submit=status, approve=st, id=rid,
          queue=[q.get("id") for q in queued])
    return rid, queued


def rename_and_check(sec):
    old, new, who = PAIRS[sec]
    rid, queued = approved_and_queued(sec, old)
    if not queued:
        return None
    r = api(who, J(old) + "/confirmRename", "POST", data={"newName": new})
    readable = api(who, J(new) + "/api/json").status_code
    exists = api("admin", J(new) + "/api/json").status_code
    check(sec, f"{who} renames {old} to {new} (POST confirmRename)", r.status_code in (200, 302) and exists == 200,
          rename=r.status_code, location=(r.headers.get("Location") or "").replace(lib.BASE, ""), admin_reads_new=exists)
    if who != "admin":
        check(sec, f"precondition of the bug: {who} can no longer read {new} after the rename", readable == 404,
              status=readable)
    status = lib.wait_until(lambda: lib.request_state(rid)[1] == "INVALIDATED", 15, 1)
    check(sec, "the approved request is INVALIDATED by the rename", status, status=lib.request_state(rid)[1], id=rid)
    gone = lib.wait_until(lambda: not lib.queue_items(old) and not lib.queue_items(new), 15, 1)
    check(sec, "no queued run of the renamed job remains (administrator's view of the queue)", gone,
          queue_old=lib.queue_items(old), queue_new=lib.queue_items(new))
    return new


def sec_C():
    rename_and_check("C")


def sec_R():
    new = rename_and_check("R")
    if new is None:
        return
    s = lib.Session("admin", fresh=True)
    s.go("/batch-control/")
    s.shot("#main-panel", "R22-R2-04-R-after-rename")
    s.done()
    labels = gv(f"""def j = jenkins.model.Jenkins.get(); j.setLabelString((j.labelString + ' {LABEL}').trim()); j.save()
j.getLabel('{LABEL}').reset(); j.getQueue().scheduleMaintenance()
return j.getLabel('{LABEL}').nodes.size() + ' ' + j.labelString""")
    note("R", "the built-in node now carries the label", labels=labels)
    ran = lib.wait_until(lambda: lib.builds(new), 20, 2)
    check("R", f"no build of {new} runs once a node carries the label (the invalidated approval does not run)",
          not ran and labels.startswith("1 "), builds=lib.builds(new), labels=labels)


def cleanup():
    jobs = [n for pair in PAIRS.values() for n in pair[:2]]
    out = gv(r"""import jenkins.model.Jenkins
def j = Jenkins.get()
def q = j.getQueue()
q.getItems().findAll { it.task instanceof hudson.model.Item && it.task.fullName in """ + json.dumps(jobs) + r""" }.each { q.cancel(it) }
def snap = new File(j.rootDir, 'r22-r2-04-strategy.xml')
def prev = new File(j.rootDir, 'r22-r2-04-previous.json')
if (!snap.exists() || !prev.exists()) return 'no snapshot'
def p = new groovy.json.JsonSlurper().parse(prev)
j.setAuthorizationStrategy(Jenkins.XSTREAM2.fromXML(snap.getText('UTF-8')))
j.setLabelString(p.labels ?: ''); j.setNumExecutors(p.executors as int); j.save()
def cfg = io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration.get()
cfg.setChangeControlEnabled(p.changeControl as boolean); cfg.save()
def ok = j.authorizationStrategy.getClass().name == p.strategy && cfg.changeControlEnabled == p.changeControl && j.labelString == (p.labels ?: '')
if (ok) { snap.delete(); prev.delete(); new File(j.rootDir, 'r22-r2-04-role.yaml').delete() }
return (ok ? 'restored ' : 'NOT restored ') + j.authorizationStrategy.getClass().simpleName + ' changeControl=' + cfg.changeControlEnabled + ' labels=' + j.labelString""")
    check("cleanup", "the authorization strategy, the change-control switch and the built-in node's labels are restored",
          out.startswith("restored "), result=out, previous=STATE.get("previous"))


if __name__ == "__main__":
    lib.run(sys.argv, {"A": sec_A, "C": sec_C, "R": sec_R}, cleanup)
