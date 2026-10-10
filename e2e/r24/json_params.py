"""e2e-24 #40: a Request Run submission whose json blob carries all, some or none of the job's parameters.

Contract (wave A, frozen 2026-10-10):
  P1  Whatever the submission form (urlencoded, multipart, or a json blob with all, some or none of the parameters),
      the stored request holds a value for EVERY parameter the job defines; missing ones get the job's default at
      request time, stated explicitly.
  P2  The approver sees all of them.
  P3  The approved build runs with exactly the stored values, even if the job's defaults change after the request.

usage: python json_params.py [ASVDBX]   rows: out/json_params.jsonl, shots: screenshots/run-24/R24-40-*.png
Item r24-json (Freestyle, approval-required; re-created by A): DATE (string, default 2026-01-01), MODE (choice
full|delta, default full), DRY (boolean, default true); its shell prints the three. The requester submits to the job's
Request Run endpoint (/job/r24-json/batch-control/submit) over REST and through the browser form; approver-1 reads each
request in the browser; the administrator changes the job's defaults over REST (config.xml) after every request was
made; approver-1 approves; each build's parameters are read over REST (/<n>/api/json).

A  arrangement: the job fresh, approval-required, no build, no pending request of the requester on it.
S  submissions (requester): json blob with all three (guard: J-all), with DATE only (J-some), without a "parameter"
   field (J-none), with "parameter": [] (J-empty), the json blob with DATE only in a multipart body (M-some, the browser's
   encoding), url-encoded without json with DATE only (guard: U-some, the explicit-defaults channel), and the browser's
   Request Run form with DATE typed (guard: B-form). Each is accepted (302) and its stored request holds a value for
   DATE, MODE and DRY: the sent ones, the request-time defaults for the rest.  [P1]
V  approver-1 opens each request in the browser: the Parameters table lists DATE, MODE and DRY with those values, and
   does not say "No parameters.".  [P2]
D  the administrator changes the defaults (DATE 2099-12-31, MODE delta first, DRY false) over REST; premise: the job's
   defaults now read so.
B  approver-1 approves every request (the first in the browser, the rest over REST); each runs exactly once, and the
   build's parameters (REST) are exactly the stored values: the request-time defaults, not the new ones.  [P3]
X  approver-1's view of an executed J-none request still lists the three stored values (screenshot).  [P2]
cleanup: the requester's leftover PENDING requests on r24-json cancelled; the job deleted."""
import json
import re
import sys

import requests

sys.path.insert(0, __file__.rsplit("/", 1)[0])
import lib  # noqa: E402
from lib import api, check, note, J, BASE  # noqa: E402

lib.LOGNAME[0] = "json_params"
USER, APPROVER = "requester", "approver-1"
JOB = "r24-json"
DEFAULTS = {"DATE": "2026-01-01", "MODE": "full", "DRY": "true"}
NEW_DEFAULTS = {"DATE": "2099-12-31", "MODE": "delta", "DRY": "false"}
NAMES = ("DATE", "MODE", "DRY")
REQ = {}   # case -> (request id, expected stored values)
FIRED = {}  # case -> build number


def params_xml(date, modes, dry):
    return (lib.string_p("DATE", date) + lib.choice_p("MODE", modes)
            + "<hudson.model.BooleanParameterDefinition><name>DRY</name>"
            f"<defaultValue>{dry}</defaultValue></hudson.model.BooleanParameterDefinition>")


def job_config(date, modes, dry):
    return lib.job_xml("fs", params=params_xml(date, modes, dry), shell='echo "r24-40 DATE=$DATE MODE=$MODE DRY=$DRY"')


def body(case, params):
    """The json blob as the browser's form builds it; `params` None = no "parameter" field at all."""
    d = {"reason": f"e2e-24 #40 {case}", "approvers": [APPROVER]}
    if params is not None:
        d["parameter"] = [{"name": k, "value": (v if k != "DRY" else v == "true")} for k, v in params.items()]
    return json.dumps(d)


def post(case, **kw):
    s = requests.Session()
    s.auth = (USER, lib.pw(USER))
    c = s.get(BASE + "/crumbIssuer/api/json").json()
    r = s.post(BASE + J(JOB) + "/batch-control/submit", headers={c["crumbRequestField"]: c["crumb"]},
               allow_redirects=False, **kw)
    return r.status_code, lib.loc_id(r), lib.text_of(r.text)


def norm(v):
    return str(v).lower() if isinstance(v, bool) else ("" if v is None else str(v))


def expect(sent):
    out = dict(DEFAULTS)
    out.update(sent)
    return out


def file_case(case, sent, **kw):
    code, rid, text = post(case, **kw)
    stored = lib.stored_request(rid).get("parameters", {}) if rid else {}
    want = expect(sent)
    check("S", f"[#40 P1] {case}: accepted, and the stored request holds a value for every parameter (sent values, "
          "request-time defaults for the rest)", code == 302 and rid is not None and stored == want, id=rid, http=code,
          stored=stored, expected=want, refusal=None if rid else text[-200:])
    if rid:
        REQ[case] = (rid, want)


def sec_A():
    for rid in lib.pending_requests(JOB):
        api("admin", f"/batch-control/requests/{rid}/cancel", "POST")
    gone = lib.delete(JOB)
    made = lib.create(JOB, job_config(DEFAULTS["DATE"], ["full", "delta"], "true"))
    prop = lib.set_property(JOB, approval=True)
    check("A", "arrangement: r24-json fresh (DATE, MODE, DRY with their defaults), approval-required, no build",
          gone and made == 200 and "approvalRequired=true" in prop and not lib.builds(JOB), created=made, prop=prop)


def sec_S():
    file_case("J-all (guard)", {"DATE": "2026-10-10", "MODE": "delta", "DRY": "false"},
              data={"json": body("J-all", {"DATE": "2026-10-10", "MODE": "delta", "DRY": "false"})})
    file_case("J-some", {"DATE": "2026-10-11"}, data={"json": body("J-some", {"DATE": "2026-10-11"})})
    file_case("J-none", {}, data={"json": body("J-none", None)})
    file_case("J-empty", {}, data={"json": body("J-empty", {})})
    file_case("M-some", {"DATE": "2026-10-13"}, files={"json": (None, body("M-some", {"DATE": "2026-10-13"}))})
    file_case("U-some (guard)", {"DATE": "2026-10-14"},
              data={"reason": "e2e-24 #40 U-some", "approvers": APPROVER, "DATE": "2026-10-14"})

    def fill(form):
        lib.L19.param_box(form, "DATE").locator("input[name=value]:visible").first.fill("2026-10-15")
    rid, text = lib.form_request(USER, JOB, "e2e-24 #40 B-form", APPROVER, shot="R24-40-S-browser-request", fill=fill)
    stored = lib.stored_request(rid).get("parameters", {}) if rid else {}
    want = expect({"DATE": "2026-10-15"})
    check("S", "[#40 P1] B-form (guard): the browser's Request Run form with DATE typed is accepted and stores every "
          "parameter", rid is not None and stored == want, id=rid, stored=stored, expected=want)
    if rid:
        REQ["B-form (guard)"] = (rid, want)


def table_of(text):
    """{name: value} of the request page's Parameters table (inner text rows 'NAME<tab>value')."""
    m = re.search(r"Parameters\n(.*?)\nRecent Runs of This Job", text, re.S)
    rows = {}
    if m:
        for line in m.group(1).splitlines():
            parts = line.split("\t")
            if len(parts) == 2 and parts[0] in NAMES:
                rows[parts[0]] = parts[1].strip()
    return rows, bool(m and "No parameters." in m.group(1))


def sec_V():
    for i, (case, (rid, want)) in enumerate(REQ.items()):
        s = lib.Session(APPROVER, fresh=True)
        s.go(f"/batch-control/requests/{rid}/")
        text = s.page.locator("#main-panel").inner_text()
        s.shot("#main-panel", f"R24-40-V-{i + 1}-{re.sub(r'[^A-Za-z-]', '', case.split(' ')[0])}")
        s.done()
        rows, none = table_of(text)
        check("V", f"[#40 P2] {case}: approver-1's request page lists DATE, MODE and DRY with the stored values",
              rows == want and not none, id=rid, shown=rows, no_parameters=none, expected=want)


def defaults_now():
    return lib.facts(f"""def j = jenkins.model.Jenkins.get().getItemByFullName({json.dumps(JOB)})
def p = j.getProperty(hudson.model.ParametersDefinitionProperty)
return groovy.json.JsonOutput.toJson(p.parameterDefinitions.collectEntries {{ d -> [d.name, String.valueOf(d.defaultParameterValue?.value)] }})""")


def sec_D():
    xml = api("admin", J(JOB) + "/config.xml").text
    new_params = params_xml(NEW_DEFAULTS["DATE"], ["delta", "full"], NEW_DEFAULTS["DRY"])
    xml2 = re.sub(r"<parameterDefinitions>.*</parameterDefinitions>", f"<parameterDefinitions>{new_params}</parameterDefinitions>",
                  xml, count=1, flags=re.S)
    r = api("admin", J(JOB) + "/config.xml", "POST", data=xml2.encode("utf-8"), headers={"Content-Type": "application/xml"})
    now = defaults_now()
    states = {c: lib.stored_request(rid).get("status") for c, (rid, _) in REQ.items()}
    check("D", "premise: the administrator changed the job's defaults after the requests (DATE 2099-12-31, MODE delta, "
          "DRY false) and every request is still PENDING", r.status_code == 200 and now == NEW_DEFAULTS
          and set(states.values()) == {"PENDING"}, http=r.status_code, defaults=now, statuses=states)


def sec_B():
    first = True
    for case, (rid, want) in REQ.items():
        before = lib.next_build(JOB)
        if first:
            present, status, _ = lib.browser_decide(APPROVER, rid, "approve", "e2e-24 #40", shot="R24-40-B-approved")
            ok = present and status is not None and status < 400
            first = False
        else:
            status = lib.decide(APPROVER, "requests", rid, "approve", "e2e-24 #40")
            ok = status in (200, 302, 303)
        end = lib.wait_status(rid, ("EXECUTED", "EXPIRED", "INVALIDATED", "REJECTED"), timeout=120)
        mine = lib.runs_of_request(rid)
        num = int(mine["builds"][0].rsplit("#", 1)[1]) if mine["builds"] else None
        res, console = lib.wait_build(JOB, num, timeout=120) if num else (None, "")
        got = {k: norm(v) for k, v in (lib.build_params(JOB, num) or {}).items()} if num else None
        check("B", f"[#40 P3] {case}: approved, it runs exactly once, and the build's parameters (REST) are exactly the "
              "stored values (request-time defaults, not the new ones)", ok and end.get("status") == "EXECUTED"
              and len(mine["builds"]) == 1 and got == want, id=rid, approve=status, status=end.get("status"),
              build=num, build_params=got, expected=want, result=res, first_build_number=before)
        if num:
            FIRED[case] = num


def sec_X():
    case = "J-none"
    if case not in REQ:
        check("X", "[#40 P2] the J-none request exists", False)
        return
    rid, want = REQ[case]
    s = lib.Session(APPROVER, fresh=True)
    s.go(f"/batch-control/requests/{rid}/")
    text = s.page.locator("#main-panel").inner_text()
    s.shot("#main-panel", "R24-40-X-executed-none")
    s.done()
    rows, none = table_of(text)
    check("X", "[#40 P2] after the run, approver-1's view of the J-none request still lists the three stored values and "
          "the build", rows == want and not none and (f"#{FIRED.get(case)}" in text if FIRED.get(case) else False),
          id=rid, shown=rows, expected=want, build=FIRED.get(case))


def cleanup():
    left = lib.pending_requests(JOB)
    for rid in left:
        api("admin", f"/batch-control/requests/{rid}/cancel", "POST")
    for q in lib.queue_items(JOB):
        api("admin", f"/queue/cancelItem?id={q['id']}", "POST")
    gone = lib.delete(JOB)
    note("cleanup", "leftover requests cancelled, r24-json deleted", requests=left, deleted=gone)


if __name__ == "__main__":
    lib.run(sys.argv, {"A": sec_A, "S": sec_S, "V": sec_V, "D": sec_D, "B": sec_B, "X": sec_X}, cleanup)
