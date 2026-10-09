"""e2e-22 R2-05: an approved request whose queued run was cancelled does not say "the run starts shortly"; it says the
queued run was cancelled and will not start (the service never submits that run again; D-55 for a disabled job).

usage: python cancelled_notice.py [AQD]   rows: out/cancelled_notice.jsonl, shots: screenshots/run-22/R22-R2-05-*.png
Items r22-cancelq and r22-cancelq-dis (Freestyle, approval-required, restricted to the label r22-no-such-node that no node
carries, so an approved run waits in the queue). The requester files a run request naming approver-1 (REST), approver-1
approves it (REST); the requester's view of the request page is read in a new browser context each time. JCasC sets
approvedRunTimeoutMinutes=1: every check here is made well within the minute after the approval.

A  arrangement: the items (enabled, label, the lock), run control on.
Q  cancelled from the queue: while the run waits, the page says it starts shortly (guard: the notice is there and does
   not mention a cancel); the administrator cancels the queue item (POST queue/cancelItem); then the page does not say
   "starts shortly", has no "approved, awaiting execution" notice (#batch-control-approved-notice) and says the queued
   run was cancelled and will not start; server: the request is still APPROVED, no queue item, no build.
D  cancelled by disabling the job (D-55: core cancels the job's queued items): the same, and the page tells the
   requester to enable the job first. The job is enabled again at the end."""
import re
import sys

sys.path.insert(0, __file__.rsplit("/", 1)[0])
import lib  # noqa: E402
from lib import api, check, note, J, Session  # noqa: E402

lib.LOGNAME[0] = "cancelled_notice"
JOBS = {"Q": "r22-cancelq", "D": "r22-cancelq-dis"}
LABEL = "r22-no-such-node"
USER, APPROVER = "requester", "approver-1"
NOTICE = "#batch-control-approved-notice"
CANCELLED = re.compile(r"queued run was cancelled|run (?:was|has been) cancelled|cancelled[^.]*will not start", re.I)


def page(rid, shot):
    s = Session(USER, fresh=True)
    s.go(f"/batch-control/requests/{rid}/")
    text = s.text()
    notice = s.page.locator(NOTICE).inner_text() if s.page.locator(NOTICE).count() else None
    s.shot("#main-panel", shot)
    s.done()
    return text, notice


def sec_A():
    out = {}
    for job in JOBS.values():
        out[job] = {"created": lib.ensure_job(job, lib.job_xml("fs", shell='echo "never runs: no node has the label"'))}
        out[job]["prop"] = lib.set_property(job, approval=True, timer=True, upstream=True)
        out[job]["state"] = lib.gv(f"""def j = jenkins.model.Jenkins.get(); def p = j.getItemByFullName('{job}')
p.setAssignedLabel(j.getLabel('{LABEL}')); if (p.isDisabled()) p.enable(); p.save()
return 'label=' + p.assignedLabelString + ' disabled=' + p.isDisabled() + ' nodes=' + j.getLabel('{LABEL}').nodes.size()""")
    sw = lib.switches()
    ok = sw["run_control"] is True and all("approvalRequired=true" in v["prop"] and f"label={LABEL} disabled=false nodes=0"
                                           == v["state"] for v in out.values())
    check("A", "arrangement: r22-cancelq and r22-cancelq-dis approval-required, enabled, restricted to a label no node "
          "carries", ok, items=out, switches=sw)


def approved_and_queued(sec, job):
    status, rid, _ = lib.run_req(USER, job, f"e2e-22 R2-05 {sec}", approvers=(APPROVER,))
    st = lib.decide(APPROVER, "requests", rid, "approve", "e2e-22") if rid else None
    queued = lib.wait_queued(job, 30)
    check(sec, "precondition: the request is filed, approved, and its run waits in the queue",
          status == 302 and st in (200, 302, 303) and len(queued) == 1, submit=status, approve=st, id=rid, queue=queued)
    return rid, queued


def after_cancel(sec, job, rid, shot):
    q = lib.wait_until(lambda: not lib.queue_items(job), 20, 1)
    text, notice = page(rid, shot)
    http, status, _ = lib.request_state(rid)
    check(sec, "server: the queue item is gone, no build ran, the request is still APPROVED",
          q and not lib.builds(job) and status == "APPROVED", queue=lib.queue_items(job), builds=lib.builds(job),
          status=status)
    check(sec, "the page does not say that the run starts shortly", "starts shortly" not in text,
          notice=notice)
    check(sec, "no 'approved, awaiting execution' notice (#batch-control-approved-notice) for a run that will not start",
          notice is None, notice=notice)
    m = CANCELLED.search(text)
    check(sec, "the page says the queued run was cancelled and will not start", m is not None,
          found=m.group(0) if m else None, text=re.sub(r"\s+", " ", text)[:600])
    return text


def sec_Q():
    job = JOBS["Q"]
    rid, queued = approved_and_queued("Q", job)
    if not queued:
        return
    text, notice = page(rid, "R22-R2-05-Q-1-waiting")
    check("Q", "guard: while the run waits, the page says it starts shortly and mentions no cancel",
          notice is not None and "starts shortly" in notice and not CANCELLED.search(text), notice=notice)
    r = api("admin", f"/queue/cancelItem?id={queued[0]['id']}", "POST")
    note("Q", "the administrator cancels the queue item", status=r.status_code, item=queued[0]["id"])
    after_cancel("Q", job, rid, "R22-R2-05-Q-2-cancelled")


def sec_D():
    job = JOBS["D"]
    rid, queued = approved_and_queued("D", job)
    if not queued:
        return
    try:
        r = api("admin", J(job) + "/disable", "POST")
        note("D", "the administrator disables the job (core cancels its queued items)", status=r.status_code)
        text = after_cancel("D", job, rid, "R22-R2-05-D-disabled")
        check("D", "the page tells the requester to enable the job first, then submit a new run request",
              re.search(r"enable it first", text, re.I) is not None and "new run request" in text)
    finally:
        r = api("admin", J(job) + "/enable", "POST")
        note("D", "the job is enabled again", status=r.status_code)


if __name__ == "__main__":
    lib.run(sys.argv, {"A": sec_A, "Q": sec_Q, "D": sec_D})
