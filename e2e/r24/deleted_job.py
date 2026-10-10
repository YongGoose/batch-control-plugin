"""e2e-24 #38: a run request on a deleted job.

Contract (wave A, frozen 2026-10-10):
  E1  Deleting a job (directly, or by deleting a folder above it) INVALIDATES its PENDING run requests, the same way
      activation requests are already invalidated on deletion. The reason must name the deletion.
  E2  Approving a run request whose job no longer exists, or whose job was re-created under the same name after the
      request was made, is refused (4xx with a clear message). No build of any job is scheduled.
  E3  A job re-created under the old name never receives a build from a request made for the deleted job.

usage: python deleted_job.py [AGDRFZ]   rows: out/deleted_job.jsonl, shots: screenshots/run-24/R24-38-*.png
Items (re-created by A on every run): r24-del-keep/daily (guard, in a folder that is never deleted), r24-del-direct,
r24-del-recreate, r24-del-f/sub/daily; every job approval-required. The requester files through the Request Run form
(browser), approver-1 decides in the browser and over REST, the administrator deletes through the item's Delete page
(browser) and re-creates over REST. What a request started is read from the queue and the builds of every job (the
request's approval marker or cause, script console, reading only).

A  arrangement: the items fresh, run control on, approver-1 listed, every job approval-required with no build.
G  guard, filed first: the requester's request on r24-del-keep/daily (PENDING; decided in Z, after every deletion).
D  E1/E2 direct deletion: a run request and an ACTIVATE request on r24-del-direct; the administrator deletes the job.
   Guard: the ACTIVATE request is INVALIDATED (existing behaviour). Run request: INVALIDATED with a reason naming the
   deletion; approver-1's page offers no approval or refuses it and never says the run starts; approver-1's REST
   approve answers 4xx with a clear message and the request does not become APPROVED; nothing is queued or built.
R  E1/E2/E3 re-created under the same name: a request on r24-del-recreate (its shell prints OLD); the job is deleted
   and re-created (shell prints NEW). INVALIDATED naming the deletion; approver-1's browser approval and REST
   approval refused; the re-created job receives no build and nothing carries the request's marker (20 s watched).
F  E1/E2/E3 a folder above the job: a request on r24-del-f/sub/daily; the administrator deletes r24-del-f; then
   r24-del-f/sub/daily is re-created. INVALIDATED naming the deletion; REST approval refused; no build.
Z  guards after the deletions: G's request is still PENDING, approver-1 approves it in the browser, it runs exactly
   once and ends EXECUTED; a new request on the re-created r24-del-recreate is approved and runs exactly once (NEW).
cleanup: the requests this run left PENDING or APPROVED are cancelled by the administrator (or expire), the r24-del-*
items are deleted."""
import re
import sys
import time

sys.path.insert(0, __file__.rsplit("/", 1)[0])
import lib  # noqa: E402
from lib import api, check, note  # noqa: E402

lib.LOGNAME[0] = "deleted_job"
USER, APPROVER = "requester", "approver-1"
KEEP_F, KEEP = "r24-del-keep", "r24-del-keep/daily"
DIRECT, RECRE = "r24-del-direct", "r24-del-recreate"
TOP_F, SUB_F, FJOB = "r24-del-f", "r24-del-f/sub", "r24-del-f/sub/daily"
JOBS = (KEEP, DIRECT, RECRE, FJOB)
STATE = {}
STARTS = "the run starts shortly"
DELETION = re.compile(r"(?i)delet")


def job(tag):
    return lib.job_xml("fs", shell=f'echo "r24-38 {tag}"')


def make(item, tag):
    st = lib.create(item, job(tag))
    prop = lib.set_property(item, approval=True, timer=True, upstream=True) if st == 200 else None
    return st == 200 and prop is not None and "approvalRequired=true" in prop


def file_request(sec, item, shot):
    rid, text = lib.form_request(USER, item, f"e2e-24 #38 {sec} {item}", APPROVER, shot=shot)
    st = lib.stored_request(rid) if rid else {}
    check(sec, f"premise: the requester's Request Run form files a PENDING request on {item}",
          rid is not None and st.get("status") == "PENDING", id=rid, stored=st.get("status"), page=text[:200])
    return rid


def invalidated(sec, rid, item):
    st = lib.wait_status(rid, ("INVALIDATED", "EXPIRED", "REJECTED", "CANCELLED", "EXECUTED"), timeout=10)
    check(sec, f"[#38 E1] the PENDING run request on {item} is INVALIDATED by the deletion",
          st.get("status") == "INVALIDATED", id=rid, status=st.get("status"))
    check(sec, "[#38 E1] the invalidation reason names the deletion", bool(DELETION.search(st.get("comment") or "")),
          id=rid, reason=st.get("comment"))
    return st


def refused_rest(sec, rid, what):
    code, text = lib.approve_rest(APPROVER, rid, f"e2e-24 #38 {sec}")
    after = lib.stored_request(rid)
    msg = lib.refusal_text(text)
    # A 4xx because an earlier approval already went through ("is APPROVED/EXECUTED and can no longer be approved")
    # is not the refusal the contract asks for.
    check(sec, f"[#38 E2] approver-1's REST approval of the request on {what} is refused with a 4xx and a clear message, "
          "and the request was never approved (not APPROVED, not EXECUTED)",
          400 <= code < 500 and after.get("status") not in ("APPROVED", "EXECUTED") and STARTS not in text
          and not re.search(r"is (APPROVED|EXECUTED) and", msg), id=rid, http=code, status=after.get("status"),
          message=msg)
    return code


def refused_browser(sec, rid, what, shot):
    present, status, text = lib.browser_decide(APPROVER, rid, "approve", f"e2e-24 #38 {sec}", shot=shot)
    after = lib.stored_request(rid)
    ok = (not present or (status is not None and status >= 400)) and STARTS not in text \
        and after.get("status") != "APPROVED"
    check(sec, f"[#38 E2] approver-1's page of the request on {what} offers no approval (or refuses it) and never says "
          "the run starts", ok, id=rid, approve_form=present, http=status, status=after.get("status"),
          starts_shortly=STARTS in text, page=re.sub(r"\s+", " ", text)[:300])


def nothing_runs(sec, rid, item, watch=20):
    """[#38 E2/E3]: no queue item or build carries the request; `item` (when it exists) gets no build."""
    t0 = time.time()
    seen = {"queue": [], "builds": []}
    built = []
    while time.time() - t0 < watch:
        seen = lib.runs_of_request(rid)
        built = lib.builds(item) if lib.exists(item) else []
        if seen["queue"] or seen["builds"] or built:
            break
        time.sleep(2)
    return seen, built


def sec_A():
    for rid in lib.pending_requests("r24-del-"):
        api("admin", f"/batch-control/requests/{rid}/cancel", "POST")
    gone = all(lib.delete(i) for i in (KEEP_F, DIRECT, RECRE, TOP_F))
    made = [lib.create(KEEP_F, lib.FOLDER_XML), lib.create(TOP_F, lib.FOLDER_XML), lib.create(SUB_F, lib.FOLDER_XML)]
    jobs = [make(KEEP, "KEEP"), make(DIRECT, "DIRECT"), make(RECRE, "OLD"), make(FJOB, "FOLDER-OLD")]
    sw = lib.switches()
    empty = {j: len(lib.builds(j)) for j in JOBS}
    check("A", "arrangement: the r24-del-* items fresh, every job approval-required with no build, run control on, "
          "approver-1 listed", gone and made == [200, 200, 200] and all(jobs) and sw["run_control"]
          and APPROVER in sw["approvers"] and not any(empty.values()), folders=made, jobs=jobs, switches=sw, builds=empty)


def sec_G():
    STATE["keep"] = file_request("G", KEEP, "R24-38-G-keep-request")


def sec_D():
    rid = file_request("D", DIRECT, "R24-38-D-1-request")
    st, aid = lib.act_req(USER, DIRECT, "ACTIVATE", "e2e-24 #38 D activation guard")
    note("D", "guard premise: an ACTIVATE request filed on the same job", http=st, id=aid)
    status, text = lib.browser_delete(DIRECT, shot="R24-38-D-2-delete")
    check("D", "premise: the administrator's deletion through the Delete page completes and the job is gone",
          status is not None and status < 400 and not lib.exists(DIRECT), http=status)
    if aid:
        a = api("admin", f"/batch-control/activations/{aid}/")
        m = re.search(r"Status (\w+)", lib.text_of(a.text))
        check("D", "[#38 E1] guard: the ACTIVATE request on the deleted job is INVALIDATED (existing behaviour, SPEC 6a)",
              m is not None and m.group(1) == "INVALIDATED", id=aid, http=a.status_code, status=m.group(1) if m else None)
    if not rid:
        return
    invalidated("D", rid, DIRECT)
    refused_browser("D", rid, "the deleted job", "R24-38-D-3-approver-page")
    refused_rest("D", rid, "the deleted job")
    seen, _ = nothing_runs("D", rid, DIRECT, watch=8)
    check("D", "[#38 E2] no queue item or build of any job carries the request", not seen["queue"] and not seen["builds"],
          id=rid, **seen)


def sec_R():
    rid = file_request("R", RECRE, "R24-38-R-1-request")
    status, _ = lib.browser_delete(RECRE, shot="R24-38-R-2-delete")
    remade = make(RECRE, "NEW")
    check("R", "premise: the job is deleted through its Delete page and re-created under the same name (approval-"
          "required, shell prints NEW, no build)", status is not None and status < 400 and remade
          and not lib.builds(RECRE), http=status, remade=remade)
    if not rid:
        return
    invalidated("R", rid, RECRE)
    refused_browser("R", rid, "the re-created job", "R24-38-R-3-approver-page")
    refused_rest("R", rid, "the re-created job")
    seen, built = nothing_runs("R", rid, RECRE)
    check("R", "[#38 E3] the re-created job receives no build, and no queue item or build carries the old request "
          "(20 s watched)", not built and not seen["queue"] and not seen["builds"], id=rid, new_job_builds=built, **seen)
    if built:
        res, console = lib.wait_build(RECRE, built[0]["number"], timeout=60)
        note("R", "the re-created job's build (evidence)", number=built[0]["number"], result=res,
             ran_new_config="r24-38 NEW" in console)


def sec_F():
    rid = file_request("F", FJOB, "R24-38-F-1-request")
    status, _ = lib.browser_delete(TOP_F, shot="R24-38-F-2-delete-folder")
    check("F", "premise: the administrator deletes the folder r24-del-f two levels above the job",
          status is not None and status < 400 and not lib.exists(TOP_F), http=status)
    if not rid:
        return
    invalidated("F", rid, FJOB)
    remade = [lib.create(TOP_F, lib.FOLDER_XML), lib.create(SUB_F, lib.FOLDER_XML)] + [make(FJOB, "FOLDER-NEW")]
    check("F", "premise: r24-del-f/sub/daily re-created under the same name (no build)",
          remade == [200, 200, True] and not lib.builds(FJOB), remade=remade)
    refused_rest("F", rid, "the job under the deleted folder")
    seen, built = nothing_runs("F", rid, FJOB)
    check("F", "[#38 E3] the job re-created under the deleted folder's path receives no build, and nothing carries the "
          "old request (20 s watched)", not built and not seen["queue"] and not seen["builds"], id=rid,
          new_job_builds=built, **seen)


def approved_once(sec, rid, item, word, shot):
    before = lib.next_build(item)
    present, status, text = lib.browser_decide(APPROVER, rid, "approve", f"e2e-24 #38 {sec} guard", shot=shot)
    end = lib.wait_status(rid, ("EXECUTED", "EXPIRED", "INVALIDATED"), timeout=120)
    res, console = lib.wait_build(item, before, timeout=120)
    time.sleep(3)
    nums = [b["number"] for b in lib.builds(item) if b["number"] >= before]
    mine = lib.runs_of_request(rid)
    check(sec, f"[#38 E2/E3] guard: approver-1 approves the request on {item} in the browser; it runs exactly once with "
          "the job's own configuration and ends EXECUTED", present and status is not None and status < 400
          and end.get("status") == "EXECUTED" and nums == [before] and len(mine["builds"]) == 1 and not mine["queue"]
          and res == "SUCCESS" and word in console, id=rid, http=status, status=end.get("status"), new_builds=nums,
          request_builds=mine["builds"], result=res, console_word=word in console)


def sec_Z():
    rid = STATE.get("keep")
    if rid:
        st = lib.stored_request(rid)
        check("Z", "[#38 E1] guard: the request on r24-del-keep/daily is still PENDING after the other deletions",
              st.get("status") == "PENDING", id=rid, status=st.get("status"))
        approved_once("Z", rid, KEEP, "r24-38 KEEP", "R24-38-Z-1-keep-approved")
    if lib.exists(RECRE):
        new = file_request("Z", RECRE, "R24-38-Z-2-recreated-request")
        if new:
            approved_once("Z", new, RECRE, "r24-38 NEW", "R24-38-Z-3-recreated-approved")


def cleanup():
    left = lib.pending_requests("r24-del-")
    for rid in left:
        api("admin", f"/batch-control/requests/{rid}/cancel", "POST")
    gone = all(lib.delete(i) for i in (KEEP_F, DIRECT, RECRE, TOP_F))
    note("cleanup", "the requests left PENDING or APPROVED cancelled, the r24-del-* items deleted", requests=left,
         still_open=lib.pending_requests("r24-del-"), deleted=gone)


if __name__ == "__main__":
    lib.run(sys.argv, {"A": sec_A, "G": sec_G, "D": sec_D, "R": sec_R, "F": sec_F, "Z": sec_Z}, cleanup)
