"""e2e-25 #42: every run link on History, the Run Dashboard and Incidents resolves (HTTP 200) to the run it names, using
the resolved job's own URL, for a Freestyle job, a job in a folder and a matrix configuration (the links used to be built
from the stored full name, job/mx/job/X%3Da/1/, which answers 404 for a configuration; its real URL is job/mx/X=a/1/).

usage: python run_links.py [ARHDIQ]   rows: out/run_links.jsonl, shots: screenshots/run-25/R25-42-*.png
Items (approval-required): r25-links-fs (Freestyle), r25-links-dir/inner (Freestyle in a folder), r25-links-mx (matrix
project, axis X = a, b). Each build fails except the configuration X=a, so every job also has an incident. The requester
files one run request per job (REST), approver-1 approves it (REST); the matrix project is activated through the ACTIVATE
request flow first (its configurations are children of the parent's run). The viewer is approver-1 (ViewHistory and
Item/Read), one new browser context per page. A Maven module is not covered: maven-plugin is not in e2e/plugins.txt and a
module build needs Maven in the controller.

A  arrangement: the items, the matrix activation (only when not activated yet), one approved run per job, waited for;
   the expected runs (parent, both configurations, the two Freestyle runs) with their own URLs (Jenkins REST `url`), and
   the run records and incidents the plugin stored for them (runs.csv, incidents.csv).
H  History, runs (history/?kind=runs&job=r25-links): each expected run has a link; its href is the run's own URL, a GET
   answers 200 and is that run (api/json url). Contract: the matrix configurations; guards: the Freestyle, folder and
   matrix parent runs. Screen: the configuration X=a link followed in the browser (focus, Enter) lands on its run page.
D  Run Dashboard: the same per run.
I  Incidents (incidents/ and history/?kind=incidents&job=r25-links) and each incident's own page: the run link of each
   incident resolves the same way (contract: the configuration X=b; guards: the others).
Q  guard: the request page of the matrix run request links its executed run (the parent) and the link resolves."""
import re
import sys
from urllib.parse import urljoin

sys.path.insert(0, __file__.rsplit("/", 1)[0])
import lib  # noqa: E402
from lib import api, check, note, J, Session  # noqa: E402

lib.LOGNAME[0] = "run_links"
FS, DIR, INNER, MX = "r25-links-fs", "r25-links-dir", "r25-links-dir/inner", "r25-links-mx"
CONFIGS = ("X=a", "X=b")
USER, APPROVER, VIEWER = "requester", "approver-1", "approver-1"
FILTER = "r25-links"
MATRIX = ("<?xml version='1.1' encoding='UTF-8'?><matrix-project><description>e2e-25 #42</description><keepDependencies>false"
          "</keepDependencies><properties/><scm class='hudson.scm.NullSCM'/><canRoam>true</canRoam><disabled>false</disabled>"
          "<triggers/><concurrentBuild>false</concurrentBuild><axes><hudson.matrix.TextAxis><name>X</name><values>"
          "<string>a</string><string>b</string></values></hudson.matrix.TextAxis></axes><builders><hudson.tasks.Shell>"
          "<command>echo \"r25 configuration X=$X\"; [ \"$X\" = a ]</command></hudson.tasks.Shell></builders><publishers/>"
          "<buildWrappers/><executionStrategy class='hudson.matrix.DefaultMatrixExecutionStrategyImpl'><runSequentially>false"
          "</runSequentially></executionStrategy></matrix-project>")
FAILING = lib.job_xml("fs", shell='echo "r25 run link check"; exit 1')
S = {"runs": {}, "incidents": {}}  # full name#number -> {"url": path of the run's own URL, "contract": bool}
LINKS_JS = """() => [...document.querySelectorAll('#main-panel a[href]')].map(a => {
  const tr = a.closest('tr');
  return {href: a.getAttribute('href'), abs: a.href, text: a.textContent.trim(),
          cells: tr ? [...tr.querySelectorAll('td')].map(td => td.textContent.replace(/\\s+/g, ' ').trim()) : []}; })"""


def path_of(url):
    u = url or ""
    if u.startswith(lib.ORIGIN):
        u = u[len(lib.ORIGIN):]
    return u.split("?")[0].split("#")[0]


def run_url(full, number):
    """The run's own URL path, as Jenkins reports it (REST `url`), e.g. /jenkins/job/r25-links-mx/X=a/3/."""
    if "/X=" in full:
        parent, cfg = full.rsplit("/", 1)
        path = f"{J(parent)}/{cfg}/{number}/api/json?tree=url"
    else:
        path = f"{J(full)}/{number}/api/json?tree=url"
    r = api("admin", path)
    return path_of(r.json().get("url")) if r.status_code == 200 else None


def config_builds(cfg):
    r = api("admin", f"{J(MX)}/{cfg}/api/json?tree=builds[number,result,building]")
    return r.json().get("builds", []) if r.status_code == 200 else []


def run_once(job):
    n = lib.next_build(job)
    st, rid, _ = lib.run_req(USER, job, f"e2e-25 #42 run {job}", approvers=(APPROVER,))
    dec = lib.decide(APPROVER, "requests", rid, "approve", "e2e-25 #42") if rid else None
    result, _ = lib.wait_build(job, n, timeout=240)
    return {"request": rid, "submit": st, "approve": dec, "number": n, "result": result}


def csv_rows(name):
    r = api("admin", f"/batch-control/history/{name}?job={FILTER}")
    text = r.content.decode("utf-8-sig", errors="replace")
    import csv
    import io
    return list(csv.DictReader(io.StringIO(text))) if r.status_code == 200 else []


def sec_A():
    created = {FS: lib.ensure_job(FS, FAILING), MX: lib.ensure_job(MX, MATRIX)}
    if api("admin", J(DIR) + "/api/json").status_code != 200:
        lib.gv(f"jenkins.model.Jenkins.get().createProject(com.cloudbees.hudson.plugins.folder.Folder, '{DIR}'); return 'ok'")
    created[INNER] = lib.ensure_job(INNER, FAILING)
    lib.wait_until(lambda: all(api("admin", f"{J(MX)}/{c}/api/json").status_code == 200 for c in CONFIGS), 30, 1)
    props = {j: lib.set_property(j, approval=True, timer=True, upstream=True) for j in (FS, INNER, MX)}
    state = lib.activation(MX)
    act = None
    if state != "activated":
        st, aid = lib.act_req(USER, MX, "ACTIVATE", "e2e-25 #42 activate the matrix project")
        dec = lib.decide(APPROVER, "activations", aid, "approve", "e2e-25") if aid else None
        act = {"submit": st, "id": aid, "approve": dec}
        state = lib.wait_until(lambda: lib.activation(MX) == "activated" and "activated", 20, 1) or lib.activation(MX)
    before = {c: {b["number"] for b in config_builds(c)} for c in CONFIGS}
    runs = {j: run_once(j) for j in (FS, INNER, MX)}
    S["request_mx"] = runs[MX]["request"]
    S["runs"] = {f"{FS}#{runs[FS]['number']}": {"contract": False},
                 f"{INNER}#{runs[INNER]['number']}": {"contract": False},
                 f"{MX}#{runs[MX]['number']}": {"contract": False}}
    lib.wait_until(lambda: all(not b.get("building") for c in CONFIGS for b in config_builds(c)
                               if b["number"] not in before[c]) and all(
        [b for b in config_builds(c) if b["number"] not in before[c]] for c in CONFIGS), 60, 2)
    for c in CONFIGS:
        new = sorted(b["number"] for b in config_builds(c) if b["number"] not in before[c])
        if new:
            S["runs"][f"{MX}/{c}#{new[-1]}"] = {"contract": True}
    for rid, v in S["runs"].items():
        full, num = rid.rsplit("#", 1)
        v["url"] = run_url(full, int(num))
    recs = {r.get("jobFullName") + "#" + r.get("number", "") for r in csv_rows("runs.csv")}
    incs = csv_rows("incidents.csv")
    S["incidents"] = {r["runId"]: r["id"] for r in incs if r.get("runId") in S["runs"]}
    for rid, v in S["runs"].items():
        v["recorded"] = rid in recs
    note("A", "expected runs, their own URLs, run records and incidents", runs=S["runs"], incidents=S["incidents"])
    ok = (all(p.startswith("approvalRequired=true") for p in props.values()) and state == "activated"
          and runs[FS]["result"] == "FAILURE" and runs[INNER]["result"] == "FAILURE" and runs[MX]["result"] == "FAILURE"
          and len(S["runs"]) == 5 and all(v["url"] for v in S["runs"].values())
          and all(v["recorded"] for v in S["runs"].values()))
    check("A", "arrangement: one approved run each of r25-links-fs, r25-links-dir/inner and r25-links-mx (configurations "
          "X=a and X=b ran); all five runs have their own URL and a run record", ok, created=created, state=state,
          activate=act, runs=runs, expected={k: v.get("url") for k, v in S["runs"].items()},
          recorded={k: v.get("recorded") for k, v in S["runs"].items()})
    check("A", "arrangement: the failed runs (r25-links-fs, r25-links-dir/inner, r25-links-mx and its configuration X=b) "
          "have incidents", all(any(k == rid for k in S["incidents"]) for rid in S["runs"]
                                if not rid.startswith(f"{MX}/X=a#")), incidents=S["incidents"])


def page_links(path, shot, user=VIEWER):
    """Opens `path` as `user` in a new context; returns (status, links with their row cells, session)."""
    s = Session(user, fresh=True)
    r = s.go(path)
    links = s.page.evaluate(LINKS_JS) if r and r.status == 200 else []
    s.shot("#main-panel table", shot)
    return (r.status if r else None), links, s


def resolve(s, link, expected):
    """GETs a link in the viewer's context: (status, the run's own URL according to the page it answers)."""
    href = link["abs"]
    r = s.context.request.get(href)
    j = s.context.request.get(urljoin(href if href.endswith("/") else href + "/", "api/json?tree=url,fullDisplayName"))
    try:
        url = path_of(j.json().get("url")) if j.status == 200 else None
    except Exception:  # noqa
        url = None
    return r.status, url


def check_links(sec, where, links, s, find):
    """One check per expected run: its link on this page is the run's own URL and resolves to it."""
    if not S["runs"]:
        check(sec, f"precondition: the expected runs are known (section A) for {where}", False)
        return
    for rid, v in S["runs"].items():
        if find(rid, None) is False:
            continue
        cands = [lk for lk in links if find(rid, lk)]
        lk = cands[0] if cands else None
        status, landed = resolve(s, lk, v["url"]) if lk else (None, None)
        kind = "#42 contract (matrix configuration)" if v["contract"] else "guard"
        check(sec, f"{kind}: on {where} the link of {rid} is the run's own URL and answers 200 with that run",
              lk is not None and path_of(lk["abs"]) == v["url"] and status == 200 and landed == v["url"],
              href=lk and lk["href"], expected=v["url"], status=status, landed=landed)


def by_row(rid, lk):
    """History/dashboard rows: the Run cell links '#N' and the Job cell holds the full name."""
    full, num = rid.rsplit("#", 1)
    return lk is None or (lk["text"] == f"#{num}" and full in lk["cells"])


def by_run_id(rid, lk):
    """Incident rows and pages: the link text is the run id 'full#N'."""
    return lk is None or lk["text"] == rid


def sec_H():
    status, links, s = page_links(f"/batch-control/history/?kind=runs&job={FILTER}", "R25-42-1-history-runs")
    check("H", "guard: History (runs, job filter r25-links) opens for approver-1", status == 200, status=status)
    check_links("H", "History", links, s, by_row)
    # Screen: the configuration X=a link, clicked as a person does.
    cfg = next((rid for rid in S["runs"] if f"{MX}/X=a#" in rid), None)
    if cfg:
        num = cfg.rsplit("#", 1)[1]
        row = s.page.locator("#main-panel table tbody tr").filter(has_text=f"{MX}/X=a").filter(
            has=s.page.locator(f"a:text-is('#{num}')")).first
        landed = None
        if row.count():
            # Core's hover chevron of a model-link overlays the short "#N" text, so a pointer click lands on the
            # chevron; the link is followed as a keyboard user does (focus, Enter).
            link = row.locator(f"a:text-is('#{num}')").first
            link.focus()
            with s.page.expect_navigation(timeout=20000):
                s.page.keyboard.press("Enter")
            s.page.wait_for_load_state("load")
            landed = {"url": path_of(s.page.url), "title": s.page.title()}
        s.shot("#main-panel, body", "R25-42-2-history-config-click")
        check("H", "#42 contract (screen): following the History link of r25-links-mx/X=a lands on its run page",
              landed is not None and landed["url"] == S["runs"][cfg]["url"] and "404" not in landed["title"]
              and "X=a" in landed["title"], landed=landed, expected=S["runs"][cfg]["url"])
    s.done()


def sec_D():
    status, links, s = page_links("/batch-control/dashboard/", "R25-42-3-dashboard")
    check("D", "guard: the Run Dashboard opens for approver-1", status == 200, status=status)
    check_links("D", "the Run Dashboard", links, s, by_row)
    s.done()


def sec_I():
    failing = {rid for rid in S["runs"] if rid in S["incidents"]}

    def only_failing(fn):
        return lambda rid, lk: (False if rid not in failing else fn(rid, lk))
    for path, where, shot in (("/batch-control/incidents/", "Incidents", "R25-42-4-incidents"),
                              (f"/batch-control/history/?kind=incidents&job={FILTER}", "History (incidents)",
                               "R25-42-5-history-incidents")):
        status, links, s = page_links(path, shot)
        check("I", f"guard: {where} opens for approver-1", status == 200, status=status)
        check_links("I", where, links, s, only_failing(by_run_id))
        s.done()
    for rid, iid in S["incidents"].items():
        status, links, s = page_links(f"/batch-control/incidents/{iid}/", f"R25-42-6-incident-{iid}")
        check_links("I", f"the incident page {iid}", links, s, lambda r, lk, rid=rid: (False if r != rid else by_run_id(r, lk)))
        s.done()


def sec_Q():
    rid = S.get("request_mx")
    parent = next((k for k in S["runs"] if k.startswith(f"{MX}#")), None)
    if not rid or not parent:
        check("Q", "precondition: the matrix run request and its run are known", False, request=rid, parent=parent)
        return
    status, links, s = page_links(f"/batch-control/requests/{rid}/", "R25-42-7-request-executed-run")
    check_links("Q", f"the request page {rid}", links, s, lambda r, lk: (False if r != parent else by_run_id(r, lk)))
    s.done()


if __name__ == "__main__":
    lib.run(sys.argv, {"A": sec_A, "H": sec_H, "D": sec_D, "I": sec_I, "Q": sec_Q})
