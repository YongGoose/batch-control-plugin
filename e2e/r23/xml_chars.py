"""e2e-23 R1-01: characters XML 1.0 cannot store (U+001B in a console tail, U+000B in a comment or a reason) never make a
store entity unreadable. A failed build whose console holds ANSI escapes gets an incident that is listed, opens and is in
incidents.csv and the monthly summary; a comment or reason holding U+000B is refused with a 4xx and changes nothing.

usage: python xml_chars.py [AMKG]   rows: out/xml_chars.jsonl, shots: screenshots/run-23/R23-R1-01-*.png
Items r23-ansi-fail and r23-plain-fail (Freestyle, not approval-required, so the administrator's Build Now runs them at
once; each build fails). approver-1 reads the incident pages (ViewHistory); the requester files the grant requests.

A  arrangement: the two items, run control on, FAILURE in the incident results.
M  r23-ansi-fail prints "ESC[31mERROR ESC[0m failed" and fails: its incident file exists; the stored log tail has no
   character XML 1.0 cannot read (no raw U+001B, no &#x1b; / &#27; reference) and keeps "ERROR"; approver-1's incident
   list (200) shows the incident, its page (200, new browser context) shows the job and "ERROR"; incidents.csv (200)
   names the job and build; summary?month=<now> answers 200.
K  r23-plain-fail's incident: approver-1's acknowledge with a comment holding U+000B answers 4xx, the incident file is
   byte for byte unchanged, the incident still opens (200) and is listed. Guard: a clean acknowledge is accepted.
G  the requester's grant request (CONFIGURE on r23-plain-fail) whose reason holds U+000B answers 4xx and stores nothing;
   the approver's grants page answers 200. Guard: the same request with a clean reason is stored; it is cancelled."""
import json
import re
import sys
import time

sys.path.insert(0, __file__.rsplit("/", 1)[0])
import lib  # noqa: E402
from lib import api, check, note, J, Session  # noqa: E402

lib.LOGNAME[0] = "xml_chars"
ANSI, PLAIN = "r23-ansi-fail", "r23-plain-fail"
VT = "\x0b"
VIEWER, USER = "approver-1", "requester"
BAD_REF = re.compile(r"&#(?:x0*1[bB]|0*27|x0*[bB]|0*11);")
S = {}


def sec_A():
    out = {}
    for job, shell in ((ANSI, "printf '\\033[31mERROR\\033[0m failed\\n'; exit 1"), (PLAIN, "echo 'plain failure'; exit 1")):
        created = lib.ensure_job(job, lib.job_xml("fs", shell=shell))
        out[job] = {"created": created, "prop": lib.set_property(job, approval=False, timer=True, upstream=True)}
    sw = lib.switches()
    results = lib.gv(lib.CFG + "return cfg.incidentResults.collect { it.toString() }.join(',')")
    check("A", "arrangement: r23-ansi-fail and r23-plain-fail not approval-required; run control on; FAILURE opens an "
          "incident", sw["run_control"] is True and "FAILURE" in results
          and all("approvalRequired=false" in v["prop"] for v in out.values()), items=out, switches=sw, results=results)


def incident_of(job, number, timeout=40):
    """(incident id, stored XML text) of build `number` of `job`, found on disk through the script console."""
    script = f"""def d = new File(jenkins.model.Jenkins.get().rootDir, 'batch-control/incidents')
def hit = (d.listFiles() ?: []).findAll {{ it.name.endsWith('.xml') }}.find {{ f -> def t = f.getText('UTF-8')
  t.contains('<jobFullName>{job}</jobFullName>') && (t =~ /<runId>[^<]*\\b{number}<\\/runId>/).find() }}
return hit == null ? '' : groovy.json.JsonOutput.toJson([id: hit.name[0..-5], text: hit.getText('UTF-8')])"""
    found = lib.wait_until(lambda: lib.gv(script), timeout, 1)
    if not found:
        return None, None
    o = json.loads(found)
    return o["id"], o["text"]


def failed_build(job):
    n = lib.next_build(job)
    r = api("admin", J(job) + "/build?delay=0sec", "POST")
    result, console = lib.wait_build(job, n)
    return n, r.status_code, result, console


def sec_M():
    n, st, result, console = failed_build(ANSI)
    iid, text = incident_of(ANSI, n)
    check("M", "precondition: r23-ansi-fail failed with ANSI escapes in its console and an incident file was written",
          st in (200, 201, 302) and result == "FAILURE" and "\x1b[31m" in console and iid is not None,
          build=n, http=st, result=result, incident=iid)
    S["ansi"] = (n, iid)
    tail = re.search(r"<logTail>(.*?)</logTail>", text or "", re.S)
    tail = tail.group(1) if tail else ""
    check("M", "the stored log tail holds no character XML 1.0 cannot read (no raw U+001B, no &#x1b; reference) and keeps "
          "'ERROR'", "\x1b" not in (text or "") and not BAD_REF.search(text or "") and "ERROR" in tail,
          refs=sorted(set(re.findall(r"&#x?[0-9a-fA-F]+;", text or "")))[:6], tail=tail[-160:])
    lst = api(VIEWER, "/batch-control/incidents/")
    check("M", "approver-1's incident list answers 200 and lists the incident", lst.status_code == 200 and iid in lst.text,
          http=lst.status_code, incident=iid)
    det = api(VIEWER, f"/batch-control/incidents/{iid}/")
    check("M", "the incident page answers 200", det.status_code == 200, http=det.status_code,
          text=lib.text_of(det.text)[:200] if det.status_code != 200 else "")
    csv = api(VIEWER, "/batch-control/history/incidents.csv")
    check("M", "incidents.csv answers 200 and names r23-ansi-fail and the build",
          csv.status_code == 200 and re.search(rf"{ANSI}.{{0,60}}\b{n}\b", csv.text) is not None, http=csv.status_code,
          tail=csv.text[-240:].replace("\n", " | "))
    month = lib.gv("return java.time.YearMonth.now().toString()")
    summ = api(VIEWER, f"/batch-control/history/summary?month={month}")
    check("M", f"summary?month={month} answers 200", summ.status_code == 200, http=summ.status_code,
          text=lib.text_of(summ.text)[:200] if summ.status_code != 200 else "")
    s = Session(VIEWER, fresh=True)
    s.go(f"/batch-control/incidents/{iid}/")
    page = s.text()
    s.shot("#main-panel", "R23-R1-01-1-incident-page")
    s.done()
    check("M", "screen: approver-1's incident page shows r23-ansi-fail and the console line 'ERROR ... failed'",
          ANSI in page and re.search(r"ERROR\W{0,12}failed", page) is not None, text=re.sub(r"\s+", " ", page)[:400])
    s = Session(VIEWER, fresh=True)
    s.go("/batch-control/incidents/")
    page = s.text()
    s.shot("#main-panel", "R23-R1-01-2-incident-list")
    s.done()
    check("M", "screen: the incident list shows r23-ansi-fail", ANSI in page, text=re.sub(r"\s+", " ", page)[:300])


def stored(iid):
    return lib.gv(f"""def f = new File(jenkins.model.Jenkins.get().rootDir, 'batch-control/incidents/{iid}.xml')
return f.exists() ? f.getText('UTF-8') : ''""")


def sec_K():
    n, st, result, _ = failed_build(PLAIN)
    iid, before = incident_of(PLAIN, n)
    opens = api(VIEWER, f"/batch-control/incidents/{iid}/").status_code if iid else None
    check("K", "precondition: r23-plain-fail failed, its incident exists and opens", result == "FAILURE" and iid is not None
          and opens == 200, build=n, result=result, incident=iid, http=opens)
    r = api(VIEWER, f"/batch-control/incidents/{iid}/acknowledge", "POST", data={"comment": f"r23 a{VT}b acknowledged"})
    after = stored(iid)
    check("K", "an acknowledge comment holding U+000B is refused with a 4xx and the incident file is unchanged",
          400 <= r.status_code < 500 and after == before, http=r.status_code, unchanged=after == before,
          message=lib.text_of(r.text)[:200])
    det = api(VIEWER, f"/batch-control/incidents/{iid}/")
    lst = api(VIEWER, "/batch-control/incidents/")
    check("K", "the incident still opens (200) and is still listed", det.status_code == 200 and iid in lst.text,
          detail=det.status_code, listed=iid in lst.text)
    ok = api(VIEWER, f"/batch-control/incidents/{iid}/acknowledge", "POST", data={"comment": "r23 acknowledged"})
    s = Session(VIEWER, fresh=True)
    s.go(f"/batch-control/incidents/{iid}/")
    page = s.text()
    s.shot("#main-panel", "R23-R1-01-3-incident-acknowledged")
    s.done()
    check("K", "guard: a clean acknowledge is accepted; the page says acknowledged",
          ok.status_code in (200, 302, 303) and re.search(r"(?i)acknowledged", page) is not None
          and "<status>ACKNOWLEDGED</status>" in stored(iid), http=ok.status_code, text=re.sub(r"\s+", " ", page)[:300])


def grant_files(marker):
    return [x for x in lib.gv(f"""def d = new File(jenkins.model.Jenkins.get().rootDir, 'batch-control/requests/grant')
return (d.listFiles() ?: []).findAll {{ it.name.endsWith('.xml') && it.getText('UTF-8').contains({json.dumps(marker)}) }}
  .collect {{ it.name[0..-5] }}.join(',')""").split(",") if x]


def grant_req(reason):
    return api(USER, "/batch-control/grants/create", "POST",
               data=[("scopeFullName", PLAIN), ("actions", "CONFIGURE"), ("durationMinutes", "15"),
                     ("reason", reason), ("approvers", VIEWER)])


def sec_G():
    marker = f"r23-r101-{int(time.time())}"
    r = grant_req(f"{marker} fix{VT}cron")
    files = grant_files(marker)
    check("G", "a grant request whose reason holds U+000B is refused with a 4xx and stores nothing",
          400 <= r.status_code < 500 and not files, http=r.status_code, stored=files, message=lib.text_of(r.text)[:200])
    page = api(VIEWER, "/batch-control/grants/")
    check("G", "the approver's grants page answers 200", page.status_code == 200, http=page.status_code)
    clean = grant_req(f"{marker} clean")
    gid = lib.loc_id(clean)
    files = grant_files(marker)
    check("G", "guard: the same request with a clean reason is stored", gid is not None and files == [gid],
          http=clean.status_code, id=gid, stored=files)
    if gid:
        c = api(USER, f"/batch-control/grants/{gid}/cancel", "POST")
        note("G", "the clean grant request is cancelled", http=c.status_code)


if __name__ == "__main__":
    lib.run(sys.argv, {"A": sec_A, "M": sec_M, "K": sec_K, "G": sec_G})
