"""e2e-23 R2-02 and R2-01 (D-82): the configurations of a matrix project follow the parent's run control. An approved run
of an activated, approval-required matrix project runs its configurations (they used to abort at the activation gate),
a direct build of a configuration is refused like the parent's (it used to bypass the approval), and a configuration
offers no activation notice or run request of its own but points to its parent.

usage: python matrix_children.py [ARUD]   rows: out/matrix_children.jsonl, shots: screenshots/run-23/R23-R2-02-*.png
Item r23-mx (matrix-project, axis X = a, b; each configuration echoes its value), approval-required. The requester files
the ACTIVATE and run requests (REST), approver-1 approves them (REST); nobc holds Overall/Read, Job/Read and Job/Build
and no Batch Control permission. Needs matrix-project (pinned in e2e/plugins.txt).

A  arrangement: r23-mx with both configurations, approval-required, activated through the ACTIVATE request flow (only
   when it is not activated yet, so a rerun on the same Jenkins reuses it); run control on.
R  R2-02: the requester's run request, approved by approver-1, runs r23-mx: the parent and both configuration runs end
   SUCCESS, each configuration's console shows its value, and no TRIGGER_BLOCKED record names r23-mx or a configuration;
   screen: the parent run's console (admin, new browser context) shows both configurations completed with SUCCESS.
U  D-82 (c): the requester's page of r23-mx/X=a says neither "not activated" nor "on hold", offers no "Request Run",
   links to no batch-control-activation page and says it is part of r23-mx with a link to the parent; the
   configuration's batch-control-activation/ and batch-control/ URLs answer 404.
D  R2-01: nobc's direct build of r23-mx/X=a (after the parent has run, so matrix-project itself would accept it) is
   refused (not 2xx) with the same answer as nobc's build of the parent; no queue item and no new run of the
   configuration follow."""
import re
import sys
import time

sys.path.insert(0, __file__.rsplit("/", 1)[0])
import lib  # noqa: E402
from lib import api, check, note, J, Session  # noqa: E402

lib.LOGNAME[0] = "matrix_children"
MX = "r23-mx"
CONFIGS = ("X=a", "X=b")
USER, APPROVER, OTHER = "requester", "approver-1", "nobc"
MATRIX = ("<?xml version='1.1' encoding='UTF-8'?><matrix-project><description>e2e-23</description><keepDependencies>false"
          "</keepDependencies><properties/><scm class='hudson.scm.NullSCM'/><canRoam>true</canRoam><disabled>false</disabled>"
          "<triggers/><concurrentBuild>false</concurrentBuild><axes><hudson.matrix.TextAxis><name>X</name><values>"
          "<string>a</string><string>b</string></values></hudson.matrix.TextAxis></axes><builders><hudson.tasks.Shell>"
          "<command>echo \"r23 configuration X=$X\"</command></hudson.tasks.Shell></builders><publishers/><buildWrappers/>"
          "<executionStrategy class='hudson.matrix.DefaultMatrixExecutionStrategyImpl'><runSequentially>false"
          "</runSequentially></executionStrategy></matrix-project>")
S = {}


def config_builds(cfg):
    r = api("admin", f"{J(MX)}/{cfg}/api/json?tree=builds[number,result,building]")
    return r.json().get("builds", []) if r.status_code == 200 else []


def config_queue(cfg):
    """Queue items of the configuration (its URL is job/r23-mx/<cfg>/, not job/r23-mx/job/<cfg>/)."""
    r = api("admin", "/queue/api/json?tree=items[id,task[url],why]")
    items = r.json().get("items", []) if r.status_code == 200 else []
    return [i for i in items if f"{J(MX)}/{cfg}/" in ((i.get("task") or {}).get("url") or "")]


def sec_A():
    created = lib.ensure_job(MX, MATRIX)
    configs = lib.wait_until(lambda: all(api("admin", f"{J(MX)}/{c}/api/json").status_code == 200 for c in CONFIGS), 30, 1)
    prop = lib.set_property(MX, approval=True, timer=True, upstream=True)
    state = lib.activation(MX)
    act = None
    if state != "activated":
        st, aid = lib.act_req(USER, MX, "ACTIVATE", "e2e-23 R2-02 activate the matrix project")
        dec = lib.decide(APPROVER, "activations", aid, "approve", "e2e-23") if aid else None
        act = {"submit": st, "id": aid, "approve": dec}
        state = lib.wait_until(lambda: lib.activation(MX) == "activated" and "activated", 20, 1) or lib.activation(MX)
    sw = lib.switches()
    check("A", "arrangement: r23-mx (configurations X=a, X=b) approval-required and activated through the request flow; "
          "run control on", bool(configs) and "approvalRequired=true" in prop and state == "activated"
          and sw["run_control"] is True, created=created, prop=prop, state=state, activate=act)


def sec_R():
    mark = lib.changes_mark()
    n = lib.next_build(MX)
    before = {c: {b["number"] for b in config_builds(c)} for c in CONFIGS}
    st, rid, _ = lib.run_req(USER, MX, "e2e-23 R2-02 run the matrix project", approvers=(APPROVER,))
    dec = lib.decide(APPROVER, "requests", rid, "approve", "e2e-23") if rid else None
    check("R", "precondition: the run request is filed and approved", st in (302, 303) and dec in (200, 302, 303),
          submit=st, id=rid, approve=dec)
    result, console = lib.wait_build(MX, n, timeout=240)
    S["parent"] = n
    check("R", f"the approved run r23-mx #{n} ends SUCCESS", result == "SUCCESS", result=result,
          console=" | ".join(console.strip().splitlines()[-8:])[:600])
    for c in CONFIGS:
        new = [b for b in config_builds(c) if b["number"] not in before[c]]
        last = new[0] if new else None
        con = api("admin", f"{J(MX)}/{c}/{last['number']}/consoleText").text if last else ""
        check("R", f"the configuration {c} ran in it and ended SUCCESS, its console shows its value",
              last is not None and last.get("result") == "SUCCESS" and f"r23 configuration {c}" in con,
              run=last, console=" | ".join(con.strip().splitlines()[-4:])[:300])
    blocked = lib.changes_since(mark, lambda r: r.get("type") == "TRIGGER_BLOCKED" and MX in str(r.get("target")))
    check("R", "no TRIGGER_BLOCKED record names r23-mx or one of its configurations", not blocked,
          records=[(r.get("target"), r.get("detail") or r.get("message")) for r in blocked][:3])
    s = Session("admin", fresh=True)
    s.go(f"{J(MX)}/{n}/console")
    page = s.text()
    s.shot("#main-panel", "R23-R2-02-1-parent-console")
    s.done()
    done = re.findall(r"completed with result SUCCESS", page)
    check("R", "screen: the parent run's console shows both configurations completed with SUCCESS and Finished: SUCCESS",
          len(done) >= 2 and "Finished: SUCCESS" in page, completed=len(done),
          text=re.sub(r"\s+", " ", page)[-500:])


def sec_U():
    s = Session(USER, fresh=True)
    s.go(f"{J(MX)}/X=a/")
    page = s.text()
    links = s.page.eval_on_selector_all("a[href]", "els => els.map(e => e.getAttribute('href'))")
    s.shot("#main-panel", "R23-R2-02-2-configuration-page")
    s.done()
    parent_link = [h for h in links if re.search(rf"/job/{MX}/?$", h or "")]
    check("U", "screen: the configuration page says neither 'not activated' nor 'on hold' and offers no 'Request Run' "
          "and no batch-control-activation link", not re.search(r"(?i)not activated|on hold", page)
          and "Request Run" not in page and not [h for h in links if "batch-control-activation" in (h or "")],
          text=re.sub(r"\s+", " ", page)[:500])
    check("U", "screen: the configuration page says it is part of r23-mx and links to the parent",
          re.search(r"(?i)part of", page) is not None and bool(parent_link), links=parent_link[:3],
          text=re.sub(r"\s+", " ", page)[:500])
    codes = {p: api(USER, f"{J(MX)}/X=a/{p}/").status_code for p in ("batch-control-activation", "batch-control")}
    check("U", "the configuration's own activation form and request action answer 404", set(codes.values()) == {404},
          codes=codes)


def sec_D():
    before = {b["number"] for b in config_builds("X=a")}
    parent = api(OTHER, f"{J(MX)}/build?delay=0sec", "POST")
    r = api(OTHER, f"{J(MX)}/X=a/build?delay=0sec", "POST")
    check("D", "nobc's direct build of r23-mx/X=a is refused (not 2xx) with the same answer as nobc's build of the parent",
          not 200 <= r.status_code < 300 and r.status_code == parent.status_code, configuration=r.status_code,
          parent=parent.status_code, location=r.headers.get("Location"), message=lib.text_of(r.text)[:200])
    seen = {"queue": [], "runs": []}
    t0 = time.time()
    while time.time() - t0 < 15:
        seen["queue"] = seen["queue"] or config_queue("X=a")
        seen["runs"] = [b for b in config_builds("X=a") if b["number"] not in before]
        if seen["runs"]:
            break
        time.sleep(1)
    check("D", "no queue item and no new run of the configuration follow (15 s)", not seen["queue"] and not seen["runs"],
          **seen)


if __name__ == "__main__":
    lib.run(sys.argv, {"A": sec_A, "R": sec_R, "U": sec_U, "D": sec_D})
