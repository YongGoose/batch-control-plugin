"""e2e-12 seed (round E2E-7): realistic Batch Control data, created through the plugin's own POST endpoints
as the real accounts (the script console only arranges: executors, view, the failing/unstable runs' builds).

Run requests: pending (requester, reqonly, opsreq, nested job), executed, rejected, cancelled, expired.
Activations: ACTIVATE approved (batch-cron), HOLD pending (batch-cron), ACTIVATE pending (batch-upstream),
ACTIVATE rejected/cancelled. Grants (one-item windows, D-71; the scope type selector is gone): pending on a job and on
a folder, active on jobs (batch-daily, team/app-1) and on the folders team (Configure+Create) and ops (Configure; the
former folder-only window of D-65, whose Delete is refused at submission since D-71), ended (expired), revoked,
rejected, cancelled. Changes under a window (each on the window's own item), incidents (FAILURE/UNSTABLE), a list view.
Ids are written to out/ids.json for the crawler."""
import json, re, time
from lib import api, groovy
import lib
UUID = r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
ids = {}


def loc_id(r):
    m = re.search(UUID, r.headers.get("Location", ""))
    return m.group(0) if m else None


def run_req(user, job, reason, approvers=("approver-1",), params=None):
    path = "/job/" + "/job/".join(job.split("/")) + "/batch-control/submit"
    data = [("reason", reason)] + [("approvers", a) for a in approvers] + list((params or {}).items())
    r = api(user, path, "POST", data=data)
    assert r.status_code == 302, (job, r.status_code, r.text[:300])
    return loc_id(r)


def act_req(user, job, action, reason, approvers=("approver-1",)):
    path = "/job/" + "/job/".join(job.split("/")) + "/batch-control-activation/submit"
    data = [("action", action), ("reason", reason)] + [("approvers", a) for a in approvers]
    r = api(user, path, "POST", data=data)
    assert r.status_code == 302, (job, action, r.status_code, r.text[:300])
    return loc_id(r)


def grant_req(user, scope, actions, minutes, reason, approvers=("approver-1",)):
    """D-71: a window names one item (scopeFullName); there is no scope type field any more."""
    data = [("scopeFullName", scope), ("durationMinutes", str(minutes)), ("reason", reason)]
    data += [("actions", a) for a in actions] + [("approvers", a) for a in approvers]
    r = api(user, "/batch-control/grants/create", "POST", data=data)
    assert r.status_code == 302, (scope, r.status_code, r.text[:300])
    return loc_id(r)


def decide(user, kind, rid, verb, comment="e2e-12"):
    r = api(user, f"/batch-control/{kind}/{rid}/{verb}", "POST", data={"comment": comment})
    return r.status_code


groovy("jenkins.model.Jenkins.get().setNumExecutors(2)")
# --- run requests
ids["req_executed"] = run_req("requester", "batch-daily", "Month-end settlement rerun", params={"DATE": "2026-09-30", "MODE": "full"})
ids["req_executed_status"] = decide("approver-1", "requests", ids["req_executed"], "approve", "ok, go")
ids["req_pipeline_executed"] = run_req("requester", "batch-pipeline", "Pipeline catch-up")
decide("approver-1", "requests", ids["req_pipeline_executed"], "approve")
ids["req_rejected"] = run_req("requester", "batch-daily", "Rerun with wrong date", params={"DATE": "2025-01-01", "MODE": "partial"})
decide("approver-1", "requests", ids["req_rejected"], "reject", "Wrong date, please check")
ids["req_cancelled"] = run_req("requester", "batch-daily", "Will cancel this one")
decide("requester", "requests", ids["req_cancelled"], "cancel")
ids["req_pending"] = run_req("requester", "batch-daily", "Daily rerun after DB maintenance", ("approver-1", "approver-2"), {"DATE": "2026-10-03", "MODE": "partial"})
ids["req_pending_reqonly"] = run_req("reqonly", "batch-daily", "Request without Build permission", params={"DATE": "2026-10-04"})
ids["req_pending_opsreq"] = run_req("opsreq", "ops/job-a", "Folder-scoped requester run")
ids["req_pending_nested"] = run_req("requester", "team/sub/deep-job", "Nested folder job run", ("approver-1", "admin"))
# expired: hold the queue, approve, let approvedRunTimeoutMinutes=1 pass
groovy("jenkins.model.Jenkins.get().setNumExecutors(0)")
ids["req_expired"] = run_req("requester", "batch-daily", "Will expire in the queue", params={"DATE": "2026-10-01"})
decide("approver-1", "requests", ids["req_expired"], "approve")
t_expire = time.time()

# --- activations
ids["act_approved"] = act_req("requester", "batch-cron", "ACTIVATE", "Enable nightly cron")
decide("approver-1", "activations", ids["act_approved"], "approve")
ids["act_hold_pending"] = act_req("requester", "batch-cron", "HOLD", "Hold cron during DB migration")
ids["act_pending"] = act_req("requester", "batch-upstream", "ACTIVATE", "Enable upstream trigger")
ids["act_rejected"] = act_req("requester", "ops/cron-a", "ACTIVATE", "Enable ops cron")
decide("approver-1", "activations", ids["act_rejected"], "reject", "Not this week")
ids["act_cancelled"] = act_req("requester", "batch-pt-source", "ACTIVATE", "Changed my mind")
decide("requester", "activations", ids["act_cancelled"], "cancel")

# --- grants
ids["g_active_job"] = grant_req("requester", "batch-daily", ["CONFIGURE"], 60, "Fix the DATE default")
decide("approver-1", "grants", ids["g_active_job"], "approve")
ids["g_active_folder"] = grant_req("requester", "team", ["CONFIGURE", "CREATE"], 60, "Team folder clean-up")
decide("approver-1", "grants", ids["g_active_folder"], "approve")
# D-71: the former folder-only window (D-65) on ops asked for Delete as well; Delete on a folder is refused at submission now
ids["g_fonly_delete_refused"] = api("fonly", "/batch-control/grants/create", "POST", data=[
    ("scopeFullName", "ops"), ("actions", "CREATE"), ("actions", "CONFIGURE"), ("actions", "DELETE"), ("durationMinutes", "60"),
    ("reason", "Ops folder work incl. delete"), ("approvers", "approver-1")]).status_code
assert ids["g_fonly_delete_refused"] == 400, ("Delete on the folder ops must be refused at submission", ids["g_fonly_delete_refused"])
# Configure only: with Create as well fonly would hold every permission a window can carry on ops, and the folder
# page offers no "Request Change Permission" to such a user (FolderGrantRequestAction), which round3.py A opens
ids["g_active_fonly"] = grant_req("fonly", "ops", ["CONFIGURE"], 60, "Ops folder configuration")
decide("approver-1", "grants", ids["g_active_fonly"], "approve")
# D-71: a folder window covers the folder only, so the job edit below needs its own window (Mark as reviewed, misc.py M)
ids["g_active_app1"] = grant_req("requester", "team/app-1", ["CONFIGURE"], 60, "Fix app-1 description")
decide("approver-1", "grants", ids["g_active_app1"], "approve")
ids["g_expired"] = grant_req("requester", "prod/x", ["CONFIGURE"], 1, "Short window that will expire")
decide("approver-1", "grants", ids["g_expired"], "approve")
ids["g_revoked"] = grant_req("requester", "prod/y", ["CONFIGURE"], 60, "Window to be revoked")
decide("approver-1", "grants", ids["g_revoked"], "approve")
ids["g_revoked_status"] = decide("manager", "grants", ids["g_revoked"], "revoke")
ids["g_rejected"] = grant_req("requester", "prod/z", ["DELETE"], 15, "Delete old job")
decide("approver-1", "grants", ids["g_rejected"], "reject", "Keep it")
ids["g_cancelled"] = grant_req("requester", "team/app-1", ["CONFIGURE"], 15, "Not needed after all")
decide("requester", "grants", ids["g_cancelled"], "cancel")
ids["g_pending_job"] = grant_req("requester", "batch-pipeline", ["CONFIGURE"], 30, "Pipeline script fix", ("approver-1", "approver-2"))
ids["g_pending_folder"] = grant_req("requester", "prod", ["CREATE"], 15, "New prod job")

# --- changes under windows (a change record per save; each on the item its window names, D-71)
r = api("requester", "/job/batch-daily/submitDescription", "POST", data={"description": "Daily batch (edited under window e2e-12)"})
ids["change_desc_status"] = r.status_code
r = api("requester", "/job/team/job/app-1/submitDescription", "POST", data={"description": "app-1 edited under its own window"})
ids["change_desc2_status"] = r.status_code
# a folder's submitDescription edits its view (View/Configure), so the folder's own configuration goes through config.xml
x = api("requester", "/job/team/config.xml").text
x = re.sub(r"<description>.*?</description>|<description/>", "<description>team folder edited under the folder window</description>", x, count=1, flags=re.S)
r = api("requester", "/job/team/config.xml", "POST", data=x.encode("utf-8"), headers={"Content-Type": "application/xml"})
ids["change_desc_folder_status"] = r.status_code
r = api("admin", "/job/ops/job/a/submitDescription", "POST", data={"description": "admin edit"})
ids["change_admin_status"] = r.status_code
assert ids["change_desc_status"] in (200, 302) and ids["change_desc2_status"] in (200, 302) \
    and ids["change_desc_folder_status"] in (200, 302), ("saves under the windows", ids)

# --- incidents: failing/unstable uncontrolled jobs built by admin; a view

print(groovy(r'''
import hudson.model.*
def j = jenkins.model.Jenkins.get()
if (j.getView('batch-view') == null) {
  def v = new ListView('batch-view', j); j.addView(v)
  v.add(j.getItem('batch-daily')); v.add(j.getItem('batch-pipeline')); v.setRecurse(true)
  v.add(j.getItemByFullName('ops/job-a')); v.save()
}
return j.views*.viewName
'''))
while time.time() - t_expire < 80:
    time.sleep(5)
groovy("jenkins.model.Jenkins.get().setNumExecutors(2)")
for job in ("batch-failing", "batch-unstable"):
    ids["build_" + job] = api("admin", f"/job/{job}/build?delay=0sec", "POST").status_code
    ids["buildp_" + job] = api("admin", f"/job/{job}/buildWithParameters?delay=0sec", "POST").status_code
time.sleep(20)
ids["req_expired_state"] = api("admin", f"/batch-control/requests/{ids['req_expired']}/").status_code
(lib.HERE / "out" / "ids.json").write_text(json.dumps(ids, indent=1))
print(json.dumps(ids, indent=1))
