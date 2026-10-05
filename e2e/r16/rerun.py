"""e2e-16: incident rerun reuses the failed run's own typed values (D-72, D-72a; SPEC item 11).

usage: python rerun.py [PFS]    rows: out/rerun.jsonl, shots: R16-RERUN-*.png
P  password recoverable: an approved run of r16-fail-pw (SECRET password + MODE) fails -> incident. "Request Rerun"
   on the incident reuses the build's own values (the original secret is recovered from the build) and creates the
   request DIRECTLY; the rerun request masks the secret (********, no plaintext stored); the approved rerun build
   succeeds with the ORIGINAL secret (sha), and the incident records resolvedByRunId
S  stashedFile not recoverable: an approved run of r16-fail-stash (stashedFile + MODE) fails -> incident. "Request
   Rerun" cannot recover the stashed file, so it does NOT create the request: it lands on the job's Request Run form
   prefilled, carrying the incident id (fromRerun) with a validated link back to the incident, and asking for the file
   again. Re-provide the file and submit (as a ViewHistory holder): the request is linked to the incident, the approved
   build succeeds, and the incident records resolvedByRunId
F  core `file` recoverable: an approved run of r16-fail-file (core file UPLOAD + MODE) fails -> incident. "Request
   Rerun" recovers the core file from the build and creates the request DIRECTLY; the approved rerun build receives the
   exact original file bytes (sha), and the incident records resolvedByRunId"""
import re
import sys
import time

sys.path.insert(0, __file__.rsplit("/", 1)[0])
import lib  # noqa: E402
from lib import (Session, api, gv, check, note, decide, text_of, J, BASE, SECRET, INCIDENT_ID, run_sections,
                 console_ok, store_contains, next_build, wait_build, sha, make_file, fill_run_form, param_box)  # noqa: E402

lib.LOGNAME[0] = "rerun"
WANT = sys.argv[1] if len(sys.argv) > 1 else "PFS"
# The rerun is submitted from the Incidents screen (ViewHistory) and needs Request + Item/Read on the job; admin holds
# all three, and its ViewHistory lets D-72a link the fallback request to the incident.
HIST = "admin"
MASK = "********"


def incident_ids(job):
    return gv(f"""def d=new File(jenkins.model.Jenkins.get().rootDir,'batch-control/incidents'); def o=[]
if(d.exists()) d.listFiles().findAll{{it.name.endsWith('.xml')}}.each{{f-> def x=new XmlSlurper().parse(f)
  if(x.jobFullName.text()=={lib.json.dumps(job)}) o<<(x.id.text()+':'+x.createdAtMillis.text())}}; return o.join(',')""")


def newest_incident(job, after_millis):
    for _ in range(90):
        rows = [r for r in incident_ids(job).split(",") if ":" in r]
        fresh = sorted([r for r in rows if int(r.split(":")[1]) >= after_millis], key=lambda r: int(r.split(":")[1]))
        if fresh:
            return fresh[-1].split(":")[0]
        time.sleep(2)
    return None


def incident_field(iid, field):
    return gv(f"""def f=new File(jenkins.model.Jenkins.get().rootDir,'batch-control/incidents/{iid}.xml')
if(!f.exists()) return ''; def x=new XmlSlurper().parse(f); return x.{field}.text()""")


def submit_run(job, reason, params=None, files=None, user="requester"):
    """Submit a run request on the full page as `user`; returns the request id or None."""
    s = Session(user)
    s.go(J(job) + "/batch-control/")
    scope = s.page.locator("form[name=batch-control-request]").first
    fill_run_form(scope, reason, "approver-1", params, files)
    with s.page.expect_navigation(timeout=20000):
        scope.locator("button[name=Submit], button[type=submit]").first.click()
    s.page.wait_for_load_state("load")
    m = re.search(r"/batch-control/requests/([0-9a-f-]{36})/", s.page.url)
    s.done()
    return m.group(1) if m else None


def arm(job):
    """Arm the job so its next build fails exactly once (the build removes the marker), independent of build number."""
    gv(f"new File(jenkins.model.Jenkins.get().rootDir, {lib.json.dumps(job + '.arm')}).text = 'x'; return 'armed'")


def failing_build(job, params, files=None):
    """Create an approved run that fails and returns (incident_id, build_number)."""
    arm(job)
    t0 = int(gv("return System.currentTimeMillis()"))
    nb = next_build(job)
    rid = submit_run(job, f"e2e-16 initial failing run of {job}", params, files)
    assert rid, ("initial request", job)
    assert decide("approver-1", "requests", rid, "approve") in (200, 302)
    result, _ = wait_build(job, nb)
    assert result == "FAILURE", (job, "build should fail", result)
    iid = newest_incident(job, t0)
    assert iid and re.fullmatch(INCIDENT_ID, iid), ("incident", iid)
    return iid, nb


def detail_params(rid):
    r = api("admin", f"/batch-control/requests/{rid}/")
    rows = dict(re.findall(r"<tr>\s*<td>([^<]+)</td>\s*<td[^>]*>(.*?)</td>\s*</tr>", r.text, re.S))
    return {k.strip(): text_of(v).strip() for k, v in rows.items()}, r.text


def rerun_on_incident(iid):
    """Click 'Request Rerun' on the incident page as HIST; returns (session, landing_url)."""
    s = Session(HIST)
    s.go(f"/batch-control/incidents/{iid}/")
    f = s.page.locator("form[name=rerun]").first
    # one approver is pre-checked when there is a single candidate; ensure approver-1
    box = f.locator("input[name=approvers][value='approver-1']")
    if box.count() and not box.is_checked():
        box.locator("xpath=following-sibling::label").first.click()
    with s.page.expect_navigation(timeout=20000):
        f.locator("button[name=Submit], button[type=submit], input[type=submit]").first.click()
    s.page.wait_for_load_state("load")
    return s, s.page.url


def sec_P():
    job = "r16-fail-pw"
    iid, nb1 = failing_build(job, {"SECRET": SECRET, "MODE": "first"})
    nb2 = next_build(job)
    s, url = rerun_on_incident(iid)
    s.shot("#main-panel", "R16-RERUN-P-01-rerun-created")
    console_ok("P", s, "rerun submit")
    s.done()
    m = re.search(r"/batch-control/requests/([0-9a-f-]{36})/", url)
    if not check("P", "a password-only rerun is created directly (recovered from the build) and lands on the new request",
                 m is not None, landing=url.replace(BASE, "")):
        return
    rid = m.group(1)
    disp, html = detail_params(rid)
    assert decide("approver-1", "requests", rid, "approve") in (200, 302)
    result, console = wait_build(job, nb2)
    cv = dict(re.findall(r"^(\w+)=(.*)$", console, re.M))
    resolved = incident_field(iid, "resolvedByRunId")
    checks = {
        "rerun request masks the secret": disp.get("SECRET") == MASK,
        "no plaintext secret on the rerun detail page": SECRET not in html,
        "no plaintext secret in the store": store_contains(SECRET) == "",
        "the approved rerun build SUCCESS": result == "SUCCESS",
        "the rerun build received the ORIGINAL secret (sha)": cv.get("SECRET_SHA") == sha(SECRET.encode()),
        "the incident records resolvedByRunId after success": bool(resolved),
    }
    check("P", "the password rerun reused the original secret (masked everywhere, never stored in plaintext), the approved "
          "build got the original secret, and the incident recorded resolvedByRunId",
          all(checks.values()), rerun_request=rid, build=result, resolvedByRunId=resolved, display=disp,
          **{k: v for k, v in checks.items() if not v})


def sec_S():
    job = "r16-fail-stash"
    st1, _ = make_file("rerun-stash-1.bin", 2048, "r16-rerun-stash-1")
    iid, nb1 = failing_build(job, {"MODE": "first"}, files={"STASHED": str(st1)})
    s, url = rerun_on_incident(iid)
    s.shot("#main-panel", "R16-RERUN-S-01-fallback-form")
    # the rerun could not recover the stashed file -> the job's Request Run form, carrying the incident id
    on_form = "/batch-control/" in url and "/requests/" not in url
    notice = s.page.locator("[data-batch-control-notice=rerun]")
    notice_text = re.sub(r"\s+", " ", notice.first.inner_text()) if notice.count() else ""
    link_to_incident = s.page.locator(f"[data-batch-control-notice=rerun] a[href*='incidents/{iid}']").count() > 0
    from_rerun_field = s.page.locator("input[name=fromRerun]").get_attribute("value") if s.page.locator("input[name=fromRerun]").count() else None
    check("S", "a stashedFile rerun cannot recover the file, so it lands on the job's Request Run form carrying the "
          "incident id (fromRerun) with a validated link back to the incident, asking for the file again",
          on_form and notice.count() and link_to_incident and from_rerun_field == iid
          and ("provide" in notice_text.lower() or "again" in notice_text.lower()),
          landing=url.replace(BASE, ""), on_form=on_form, fromRerun=from_rerun_field, link=link_to_incident,
          notice=notice_text[:200])
    # re-provide the stashed file and submit from this prefilled form (as HIST, so D-72a links it)
    nb2 = next_build(job)
    st2, st2b = make_file("rerun-stash-2.bin", 3000, "r16-rerun-stash-2")
    scope = s.page.locator("form[name=batch-control-request]").first
    fill_run_form(scope, "e2e-16 rerun with the file re-provided", "approver-1", files={"STASHED": str(st2)})
    with s.page.expect_navigation(timeout=20000):
        scope.locator("button[name=Submit], button[type=submit]").first.click()
    s.page.wait_for_load_state("load")
    m = re.search(r"/batch-control/requests/([0-9a-f-]{36})/", s.page.url)
    s.shot("#main-panel", "R16-RERUN-S-02-request-created")
    s.done()
    if not check("S", "the re-provided stashedFile rerun is submitted and creates a request", m is not None,
                 landing=s.page.url.replace(BASE, "") if 's' in dir() else None):
        return
    rid = m.group(1)
    linked_before = incident_field(iid, "rerunRequestIds")
    assert decide("approver-1", "requests", rid, "approve") in (200, 302)
    result, console = wait_build(job, nb2)
    cv = dict(re.findall(r"^(\w+)=(.*)$", console, re.M))
    resolved = incident_field(iid, "resolvedByRunId")
    checks = {
        "the request is linked to the incident (rerunRequestIds)": rid in incident_field(iid, "rerunRequestIds"),
        "the approved rerun build SUCCESS": result == "SUCCESS",
        "withFileParameter read the re-provided file (sha)": cv.get("STASHED_SHA") == sha(st2b),
        "the incident records resolvedByRunId after success": bool(resolved),
    }
    check("S", "the fallback rerun request is linked to the incident; the approved build read the re-provided stashed file; "
          "the incident records resolvedByRunId",
          all(checks.values()), rerun_request=rid, build=result, resolvedByRunId=resolved,
          **{k: v for k, v in checks.items() if not v})


def sec_F():
    job = "r16-fail-file"
    up, upb = make_file("rerun-file-1.bin", 2560, "r16-rerun-file-1")
    iid, nb1 = failing_build(job, {"MODE": "first"}, files={"UPLOAD": str(up)})
    nb2 = next_build(job)
    s, url = rerun_on_incident(iid)
    s.shot("#main-panel", "R16-RERUN-F-01-rerun-created")
    s.done()
    m = re.search(r"/batch-control/requests/([0-9a-f-]{36})/", url)
    if not check("F", "a core-file rerun recovers the file from the build and creates the request directly (no fallback form)",
                 m is not None, landing=url.replace(BASE, "")):
        return
    rid = m.group(1)
    assert decide("approver-1", "requests", rid, "approve") in (200, 302)
    result, console = wait_build(job, nb2)
    cv = dict(re.findall(r"^(\w+)=(.*)$", console, re.M))
    resolved = incident_field(iid, "resolvedByRunId")
    checks = {
        "linked to the incident": rid in incident_field(iid, "rerunRequestIds"),
        "the approved rerun build SUCCESS": result == "SUCCESS",
        "the rerun build received the exact recovered file (sha)": cv.get("UPLOAD_SHA") == sha(upb),
        "the incident records resolvedByRunId": bool(resolved),
    }
    check("F", "the core-file rerun reused the file recovered from the build (exact bytes), was linked to the incident, and "
          "the incident recorded resolvedByRunId",
          all(checks.values()), rerun_request=rid, build=result, resolvedByRunId=resolved,
          **{k: v for k, v in checks.items() if not v})


if __name__ == "__main__":
    run_sections([c for c in WANT if c in "PFS"], {"P": sec_P, "F": sec_F, "S": sec_S})
