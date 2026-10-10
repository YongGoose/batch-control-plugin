"""e2e-26 #36: an activation decision whose state cannot be saved is refused before anything changes, also across a
restart (wave-B contract #36; JenkinsRule rows T-06a-108..110, ActivationStateWriteFailureTest).

usage: python activation_write.py [AHVRG]   rows: out/activation_write.jsonl, shots: screenshots/run-26/R26-36-*.png
Items r26-aw-hold (activated) and r26-aw-act (not activated), Freestyle, deleted and created again by A. The fault is
JENKINS_HOME/batch-control/activations/ (the activation-state files, ARCHITECTURE 5) made unwritable with `docker exec -u
root` (chmod 555 on the directory, 444 on its files, proven by a create refused to the jenkins user) just before
approver-1 presses "Approve" on the request page, and restored right after the answer (exact modes, also on failure).
The request directory stays writable, so only the state write fails. Restarts Jenkins (section R): a `last` unit.

A  arrangement: run control on; activations/ writable; no PENDING r26-aw-* request; r26-aw-hold activated through the
   real flow (requester files, approver-1 approves), r26-aw-act not activated; r26-aw-hold's state file exists.
H  HOLD under the fault: requester's two PENDING HOLD requests on r26-aw-hold (for approver-1 and approver-2);
   approver-1 approves the first in the browser with activations/ unwritable. Contract: a 4xx/5xx answer saying the
   decision "could not be saved", no "Oops!" page; both requests still PENDING; the job page still says activated; the
   state file byte-identical; no HELD record.
V  ACTIVATE under the fault, the same on r26-aw-act with two PENDING ACTIVATE requests: refused as above, both PENDING,
   the job page still says not activated, no ACTIVATED record.
R  restart: the requester reads both job pages in the browser, Jenkins restarts (docker restart), and each job's state
   equals what its page said before (contract: "the job's state after a restart equals what the page said before"),
   which is the old state; the four requests are still PENDING.
G  the store is writable again (guard): approver-1 approves the same HOLD and the same ACTIVATE in the browser: below
   400, no error page, APPROVED; r26-aw-hold no longer activated with one HELD record and its other HOLD INVALIDATED;
   r26-aw-act activated with one ACTIVATED record and its other ACTIVATE INVALIDATED; each state file has changed.

Not here (JenkinsRule rows): the timer gate before and after (T-06a-108..110 assert it with a TimerTriggerCause; a real
cron minute would double this unit's time), notifications under the fault, a job inside a folder, an unreadable rather
than unwritable state file. Run without the fix, H/V fail on the stored status (the request is APPROVED although the
state was not written), R on r26-aw-hold (its page said not activated, after the restart it is activated again) and G
(the request is no longer PENDING, so it cannot be approved again)."""
import re
import sys
import time

sys.path.insert(0, __file__.rsplit("/", 1)[0])
import lib  # noqa: E402
from lib import api, check, note, J, Session  # noqa: E402

lib.LOGNAME[0] = "activation_write"
USER, APPROVER, OTHER = "requester", "approver-1", "approver-2"
HOLD, ACT = "r26-aw-hold", "r26-aw-act"
DIR = f"{lib.STORE}/activations"
S = {}  # request ids, page states and marks shared by the sections


def state_file(job):
    return f"{DIR}/{job}.xml"


def exists(path):
    return lib.sh(f"test -f '{path}'")[0] == 0


def two(job, action):
    """requester's two PENDING requests of `action` on `job`: the first for approver-1, the second for approver-2."""
    return [lib.act_req(USER, job, action, f"e2e-26 #36 {action} {i}", approvers=(a,))[1]
            for i, a in enumerate((APPROVER, OTHER), 1)]


def approve_in_browser(rid, shot, comment, fault):
    """approver-1 presses "Approve" on the request page in a new browser context; with `fault`, activations/ is
    unwritable from just before the click until the answer has arrived. Returns (status, page text, fault proof)."""
    s = Session(APPROVER, fresh=True)
    proof = None
    try:
        s.go(f"/batch-control/activations/{rid}/")
        form = s.page.locator("form[name=approve]")
        if form.count() == 0:  # the request is no longer PENDING: the page offers no decision
            s.shot("#main-panel", f"{shot}-1-no-approve-form")
            return None, re.sub(r"\s+", " ", s.page.locator("body").inner_text()), None
        form.locator("textarea[name=comment]").fill(comment)
        s.shot("#main-panel", f"{shot}-1-request")
        try:
            if fault:
                proof = lib.make_unwritable(DIR)
            with s.page.expect_response(lambda r: r.request.method == "POST" and r.url.endswith(f"/{rid}/approve")) as ri:
                form.locator("button[name=Submit]").click()
            resp = ri.value
        finally:
            if fault:
                proof = dict(proof or {}, restored=lib.restore(DIR))
        s.page.wait_for_load_state("load")
        text = re.sub(r"\s+", " ", s.page.locator("body").inner_text())
        s.shot("body", f"{shot}-2-answer")
        return resp.status, text, proof
    finally:
        s.done()


def refused_section(sec, job, action, want_state, record):
    ids = two(job, action)
    before_state = lib.activation(job)
    check(sec, f"precondition: two PENDING {action} requests on {job} and its page says '{want_state}'",
          all(ids) and [lib.act_status(i) for i in ids] == ["PENDING"] * 2 and before_state == want_state,
          ids=ids, job_page=before_state)
    digest = lib.sha(state_file(job)) if exists(state_file(job)) else "absent"
    S["digest " + job] = digest
    mark = lib.now_ms()
    status, text, proof = approve_in_browser(ids[0], f"R26-36-{sec}", f"e2e-26 #36 {action} with activations/ unwritable",
                                             fault=True)
    check(sec, "fault: activations/ refused a new file to the jenkins user during the approval and was restored after it",
          bool(proof) and proof.get("create_refused") and proof.get("restored"), **(proof or {}))
    err = lib.ERROR_PAGE.search(text)
    unsaved = "could not be saved" in text.lower()
    check(sec, f"contract #36 (refusal): approving the {action} while its state cannot be written answers 4xx/5xx and "
          "says the decision could not be saved, not the 'Oops!' page",
          status is not None and 400 <= status < 600 and unsaved and not err,
          http=status, could_not_be_saved=unsaved, error_page=err.group(0) if err else None, text=text[:400])
    stored = [lib.act_status(i) for i in ids]
    check(sec, f"contract #36 (request stays PENDING): the refused {action} request and the job's other one are still "
          "PENDING", stored == ["PENDING", "PENDING"], stored=stored)
    after_state = lib.activation(job)
    check(sec, f"contract #36 (memory keeps the old state): {job}'s page still says '{want_state}'",
          after_state == want_state, job_page=after_state)
    after_digest = lib.sha(state_file(job)) if exists(state_file(job)) else "absent"
    check(sec, f"contract #36 (file keeps the old state): {job}'s state file is unchanged",
          after_digest == digest and digest is not None, before=digest, after=after_digest)
    recs = lib.records_since(mark, (record,), job)
    check(sec, f"contract #36 (no record): no {record} record for {job} after the refused approval", not recs,
          records=[r.get("id") for r in recs])
    return ids


# ---------------------------------------------------------------- sections
def sec_A():
    writable = lib.restore(DIR)
    sw = lib.switches()
    for rid in lib.pending_activations("r26-aw-"):
        api("admin", f"/batch-control/activations/{rid}/cancel", "POST")
    gone = all(lib.delete(j) for j in (HOLD, ACT))
    made = [lib.create(j, lib.job_xml("fs", shell="echo r26")) for j in (HOLD, ACT)]
    live = lib.activate(HOLD)
    states = {j: lib.activation(j) for j in (HOLD, ACT)}
    has_file = exists(state_file(HOLD))
    left = lib.pending_activations("r26-aw-")
    check("A", "arrangement: run control on, activations/ writable, no PENDING r26-aw-* request; r26-aw-hold activated "
          "(state file present), r26-aw-act not activated",
          writable and sw["run_control"] and gone and made == [200, 200] and live and has_file and not left
          and states == {HOLD: "activated", ACT: "not activated"},
          writable=writable, switches=sw, deleted=gone, created=made, states=states, state_file=has_file, pending=left,
          container=lib.CONTAINER)


def sec_H():
    S["hold"] = refused_section("H", HOLD, "HOLD", "activated", "HELD")


def sec_V():
    S["act"] = refused_section("V", ACT, "ACTIVATE", "not activated", "ACTIVATED")


def sec_R():
    said = {j: lib.page_state(USER, j, f"R26-36-R-1-before-restart-{j}") for j in (HOLD, ACT)}
    note("R", "the job pages before the restart, as the requester reads them", **said)
    secs, rc = lib.restart()
    check("R", "precondition: Jenkins restarted and the plugin is active", secs is not None and rc == 0, seconds=secs, rc=rc)
    now = {j: lib.page_state(USER, j, f"R26-36-R-2-after-restart-{j}") for j in (HOLD, ACT)}
    check("R", "contract #36 (restart): each job's state after the restart equals what its page said before it",
          now == said, before=said, after=now)
    check("R", "contract #36 (restart): that state is the old one (r26-aw-hold activated, r26-aw-act not activated)",
          now == {HOLD: "activated", ACT: "not activated"}, after=now)
    ids = S.get("hold", []) + S.get("act", [])
    stored = [lib.act_status(i) for i in ids]
    check("R", "contract #36 (restart): the four requests are still PENDING after the restart",
          len(ids) == 4 and stored == ["PENDING"] * 4, stored=stored)
    sw = lib.switches()
    check("R", "precondition: run control is on after the restart (JCasC)", sw["run_control"], switches=sw)


def approved_section(job, ids, action, want_state, record, shot):
    if not ids or not all(ids):
        check("G", f"precondition: the {action} requests of H/V exist", False, ids=ids)
        return
    mark = lib.now_ms()
    status, text, _ = approve_in_browser(ids[0], shot, f"e2e-26 #36 {action} with a writable store", fault=False)
    err = lib.ERROR_PAGE.search(text)
    check("G", f"contract #36 (later approval works): approver-1's approval of the same {action} with a writable store "
          "answers below 400, no error page, and the page says APPROVED",
          status is not None and status < 400 and not err and "APPROVED" in text,
          http=status, error_page=err.group(0) if err else None,
          approve_form=status is not None, text=text[:300])
    stored = [lib.act_status(i) for i in ids]
    state = lib.activation(job)
    recs = lib.records_since(mark, (record,), job)
    check("G", f"contract #36 (later approval works): the {action} is APPROVED and applied ({job} '{want_state}'), the "
          f"other request INVALIDATED, one {record} record",
          stored == ["APPROVED", "INVALIDATED"] and state == want_state and len(recs) == 1,
          stored=stored, job_page=state, records=len(recs))
    digest = lib.sha(state_file(job)) if exists(state_file(job)) else "absent"
    check("G", f"guard of the 'file keeps the old state' check: {job}'s state file now records the decision (changed)",
          digest not in (None, "absent") and digest != S.get("digest " + job), before=S.get("digest " + job), after=digest)


def sec_G():
    writable = lib.restore(DIR)
    check("G", "precondition: activations/ is writable", writable)
    approved_section(HOLD, S.get("hold", []), "HOLD", "on hold", "HELD", "R26-36-G1")
    approved_section(ACT, S.get("act", []), "ACTIVATE", "activated", "ACTIVATED", "R26-36-G2")
    shown = lib.page_state(USER, HOLD, "R26-36-G-3-hold-page")
    check("G", "guard: the requester's page of r26-aw-hold says it is no longer activated after the approved HOLD",
          shown in ("on hold", "not activated"), job_page=shown)


def cleanup():
    ok = lib.restore(DIR)
    for rid in lib.pending_activations("r26-aw-"):
        api("admin", f"/batch-control/activations/{rid}/cancel", "POST")
    note("cleanup", "activations/ writable, no PENDING r26-aw-* request left", writable=ok,
         pending=lib.pending_activations("r26-aw-"))
    if not ok:
        raise RuntimeError("activations/ is still unwritable")


if __name__ == "__main__":
    lib.run(sys.argv, {"A": sec_A, "H": sec_H, "V": sec_V, "R": sec_R, "G": sec_G}, cleanup)
