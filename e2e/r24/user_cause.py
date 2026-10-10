"""e2e-24 #37: the deprecated Cause.UserCause and run approval.

Contract (wave A, frozen 2026-10-10):
  U1  Cause.UserCause counts as a human submission, exactly like Cause.UserIdCause.
  U2  A queue submission of an approval-required, activated job whose only cause is Cause.UserCause is refused, with the
      same refusal and record as a UserIdCause submission.

usage: python user_cause.py [AGSCNFR]   rows: out/user_cause.jsonl, shots: screenshots/run-24/R24-37-*.png
Only code can create the deprecated cause, so the submissions under test are made the two ways an administrator's code
runs: the script console (an HTTP request) and the CLI `groovy` command (jenkins-cli.jar inside the container), each as
`job.scheduleBuild2(0, <cause>)` with the cause as the only one. Every submission is paired with the same submission
carrying `new Cause.UserIdCause()` (the guard, which must keep being refused), and the two are compared: the outcome
(the exception's class and message, or the queue item), what reached the queue or ran within 10 s, and the change
records written for the job (type and detail).

Items (re-created by A): r24-ucause (Freestyle, approval-required, activated through a real ACTIVATE request: the
requester files it, approver-1 approves it), r24-ucause-hold (approval-required, not activated), r24-ucause-free (no
approval required, not activated).

A  arrangement: the items; r24-ucause activated, the others not; run control on; no build.
G  guard premise (browser): the job page of r24-ucause says it is activated (screenshot), approval required.
S  script console on r24-ucause: UserIdCause refused (guard); UserCause refused the same way: same exception class
   and message, nothing queued or built, the same change records.  [U1, U2]
C  CLI groovy on r24-ucause: the same pair through the CLI.  [U1, U2]
N  script console on r24-ucause-hold (approval-required, not activated): UserCause refused the same way as UserIdCause
   (a person's submission is not an unattended run).  [U1, U2]
F  guard, script console on r24-ucause-free (no approval required, not activated): UserIdCause runs, and UserCause
   runs too, exactly once each (a person may start such a job, D-46a).  [U1]
R  guard (browser): the requester files a run request on r24-ucause, approver-1 approves it in the browser; it runs
   exactly once and ends EXECUTED.
cleanup: the requester's leftover PENDING requests on the items cancelled; the items are deleted."""
import json
import re
import sys
import time

sys.path.insert(0, __file__.rsplit("/", 1)[0])
import lib  # noqa: E402
from lib import api, check, note, J, Session  # noqa: E402

lib.LOGNAME[0] = "user_cause"
USER, APPROVER = "requester", "approver-1"
JOB, HOLD, FREE = "r24-ucause", "r24-ucause-hold", "r24-ucause-free"
CAUSES = {"UserIdCause": "new hudson.model.Cause.UserIdCause()", "UserCause": "new hudson.model.Cause.UserCause()"}
WATCH = 10


def submit_script(job, cause, emit="return"):
    """`job.scheduleBuild2(0, cause)` with `cause` as the only cause; the outcome as JSON (returned or printed)."""
    return """def job = jenkins.model.Jenkins.get().getItemByFullName(%s, hudson.model.Job)
def out
try {
  def f = job.scheduleBuild2(0, %s)
  out = [outcome: f == null ? 'refused quietly (scheduleBuild2 returned null)' : 'scheduled', exception: null, message: null]
} catch (Throwable t) {
  out = [outcome: 'refused with an exception', exception: t.getClass().getName(), message: t.getMessage()]
}
%s(groovy.json.JsonOutput.toJson(out))""" % (json.dumps(job), CAUSES[cause], emit)


def via_console(job, cause):
    return lib.facts(submit_script(job, cause))


def via_cli(job, cause):
    rc, out = lib.cli("admin", "groovy", "=", stdin=submit_script(job, cause, emit="println"))
    m = re.search(r"\{.*\}", out, re.S)
    res = json.loads(m.group(0)) if m else {"outcome": "no output", "exception": None, "message": out[-300:]}
    res["cli_exit"] = rc
    return res


def norm(records):
    """Change records of the job, without the fields every record differs in."""
    return sorted((r.get("type"), re.sub(r"UserId?Cause", "<cause>", r.get("detail") or "")) for r in records)


def submit(sec, job, cause, channel):
    """One submission with `cause` as the only cause; returns (result, new builds, queue items, records)."""
    mark = lib.changes_mark()
    before = lib.next_build(job)
    res = via_console(job, cause) if channel == "console" else via_cli(job, cause)
    deadline = time.time() + WATCH
    while time.time() < deadline:
        if lib.queue_items(job) or [b for b in lib.builds(job) if b["number"] >= before]:
            break
        time.sleep(1)
    time.sleep(2)
    new = [b["number"] for b in lib.builds(job) if b["number"] >= before]
    queued = lib.queue_items(job)
    recs = [r for r in lib.changes_since(mark) if r.get("target") == job]
    for n in new:
        lib.wait_build(job, n, timeout=60)
    for q in queued:
        api("admin", f"/queue/cancelItem?id={q['id']}", "POST")
    row = dict(job=job, cause=cause, channel=channel, result=res, new_builds=new, queued=[q.get("id") for q in queued],
               records=[(r.get("type"), (r.get("detail") or "")[:200]) for r in recs])
    note(sec, f"{channel} submission with only {cause}", **row)
    return res, new, queued, recs


def pair(sec, job, channel, refusal_expected=True):
    gid, g_new, g_q, g_recs = submit(sec, job, "UserIdCause", channel)
    uc, u_new, u_q, u_recs = submit(sec, job, "UserCause", channel)
    if refusal_expected:
        check(sec, f"[#37 U2] guard ({channel}): the submission with only UserIdCause is refused (an exception with the "
              "approval message), nothing is queued or built", gid.get("exception") and not g_new and not g_q,
              result=gid, builds=g_new, queued=len(g_q))
        check(sec, f"[#37 U1/U2] ({channel}): the submission with only the deprecated UserCause is refused: nothing is "
              "queued or built", not u_new and not u_q and uc.get("outcome") != "scheduled", result=uc, builds=u_new,
              queued=len(u_q))
        same = (uc.get("exception"), uc.get("message")) == (gid.get("exception"), gid.get("message"))
        check(sec, f"[#37 U2] ({channel}): the UserCause refusal is the same as the UserIdCause refusal (exception class "
              "and message)", same and gid.get("exception") is not None, user_cause=[uc.get("exception"), uc.get("message")],
              user_id_cause=[gid.get("exception"), gid.get("message")])
        check(sec, f"[#37 U2] ({channel}): the UserCause submission writes the same change records as the UserIdCause "
              "submission", norm(u_recs) == norm(g_recs), user_cause=norm(u_recs), user_id_cause=norm(g_recs))
    else:
        check(sec, f"[#37 U1] guard ({channel}): on a job without approvalRequired, the submission with only UserIdCause "
              "is scheduled and runs exactly once", gid.get("outcome") == "scheduled" and len(g_new) == 1, result=gid,
              builds=g_new)
        check(sec, f"[#37 U1] guard ({channel}): the submission with only UserCause is scheduled and runs exactly once, "
              "like UserIdCause", uc.get("outcome") == "scheduled" and len(u_new) == 1, result=uc, builds=u_new)


def sec_A():
    for rid in lib.pending_requests("r24-ucause"):
        api("admin", f"/batch-control/requests/{rid}/cancel", "POST")
    gone = all(lib.delete(j) for j in (JOB, HOLD, FREE))
    made = [lib.create(j, lib.job_xml("fs", shell=f'echo "r24-37 {j}"')) for j in (JOB, HOLD, FREE)]
    props = [lib.set_property(JOB, approval=True), lib.set_property(HOLD, approval=True),
             lib.set_property(FREE, approval=False)]
    st, aid = lib.act_req(USER, JOB, "ACTIVATE", "e2e-24 #37 arrangement")
    dec = lib.decide(APPROVER, "activations", aid, "approve", "e2e-24 #37") if aid else None
    acts = {j: lib.activation(j) for j in (JOB, HOLD, FREE)}
    sw = lib.switches()
    check("A", "arrangement: r24-ucause approval-required and activated (ACTIVATE request approved by approver-1), "
          "r24-ucause-hold approval-required and not activated, r24-ucause-free without approval and not activated, "
          "run control on, no build", gone and made == [200, 200, 200] and "approvalRequired=true" in props[0]
          and "approvalRequired=true" in props[1] and "approvalRequired=false" in props[2]
          and acts == {JOB: "activated", HOLD: "not activated", FREE: "not activated"} and sw["run_control"]
          and not any(lib.builds(j) for j in (JOB, HOLD, FREE)), activation_request=[st, aid, dec], activation=acts,
          props=props)


def sec_G():
    s = Session(USER, fresh=True)
    s.go(J(JOB) + "/")
    t = s.text()
    s.shot("#main-panel", "R24-37-G-job-page-activated")
    s.done()
    check("G", "premise (browser, requester): the job page of r24-ucause says it is activated",
          "this job is activated" in t, text=re.sub(r"\s+", " ", t)[:300])


def sec_S():
    pair("S", JOB, "console")


def sec_C():
    pair("C", JOB, "cli")


def sec_N():
    pair("N", HOLD, "console")


def sec_F():
    pair("F", FREE, "console", refusal_expected=False)


def sec_R():
    before = lib.next_build(JOB)
    rid, text = lib.form_request(USER, JOB, "e2e-24 #37 guard run", APPROVER, shot="R24-37-R-1-request")
    present, status, page = lib.browser_decide(APPROVER, rid, "approve", "e2e-24 #37 guard",
                                               shot="R24-37-R-2-approved") if rid else (False, None, "")
    end = lib.wait_status(rid, ("EXECUTED", "EXPIRED", "INVALIDATED"), timeout=120) if rid else {}
    res, console = lib.wait_build(JOB, before, timeout=120)
    time.sleep(3)
    new = [b["number"] for b in lib.builds(JOB) if b["number"] >= before]
    mine = lib.runs_of_request(rid) if rid else {"builds": [], "queue": []}
    check("R", "guard (browser): the requester's run request on r24-ucause, approved by approver-1, runs exactly once and "
          "ends EXECUTED", rid and present and status is not None and status < 400 and end.get("status") == "EXECUTED"
          and new == [before] and len(mine["builds"]) == 1 and res == "SUCCESS", id=rid, http=status,
          status=end.get("status"), new_builds=new, request_builds=mine["builds"], result=res)


def cleanup():
    left = lib.pending_requests("r24-ucause")
    for rid in left:
        api("admin", f"/batch-control/requests/{rid}/cancel", "POST")
    for q in lib.all_queue():
        if "/job/r24-ucause" in (q[1] or ""):
            api("admin", f"/queue/cancelItem?id={q[0]}", "POST")
    gone = all(lib.delete(j) for j in (JOB, HOLD, FREE))
    note("cleanup", "leftover requests cancelled, the r24-ucause* items deleted", requests=left, deleted=gone)


if __name__ == "__main__":
    lib.run(sys.argv, {"A": sec_A, "G": sec_G, "S": sec_S, "C": sec_C, "N": sec_N, "F": sec_F, "R": sec_R}, cleanup)
