#!/usr/bin/env python3
"""Shard plan and step runner for the CI e2e pass (called by ci/run.sh; usable on its own against a running stack).

    shard.py units                      list the units, their weights and steps
    shard.py plan <k>/<N> [--json]      the units and steps shard k of N runs
    shard.py plan-all <N>               every shard of N with its estimated duration
    shard.py run <k>/<N> --out DIR      run shard k of N against $BC_BASE, write DIR/summary.{json,md}

A *unit* is a group of existing driver invocations that must run in order on one Jenkins (for example the
crawl of one role on both job UIs). Every shard starts from a fresh JENKINS_HOME and first runs the SETUP steps
(the e2e-14 arrangement and seed), then its units in the canonical e2e-14 order; a unit marked `last` replaces the
authorization strategy and therefore runs last in its shard. Units are assigned with a deterministic
longest-processing-time split over the weights below (minutes measured locally, see e2e/README.md), so the same N
always gives the same shards; BC_UNITS=a,b runs just those units (debugging).

A step fails when the driver exits non-zero, or prints one of the drivers' own failure markers: a line starting
with "FAIL " (round3.py, def07.py, the crawl's content checks), `"ok": false` (r15/check.py) or `"EXCEPTION":`
(misc.py catches a section's exception and logs it). The drivers' assertions are not changed here. The observational
drivers (crawl, actions, jobui) log rows for human review; their notable rows are counted in the summary.
"""
import argparse
import json
import os
import re
import subprocess
import sys
import time
from pathlib import Path

E2E = Path(__file__).resolve().parent.parent
ROLES6 = ["admin", "requester", "reqonly", "approver-1", "manager", "nobc"]
DEF07_ROLES = ["approver-1", "admin", "manager"]
ROLE_CRAWL = ["requester", "approver-1", "admin"]


def py(*args):
    return {"argv": list(args)}


def flag(value, *users):
    return py("r14/set_flag.py", value, *users)


# The e2e-14 arrangement (docs/reports/e2e-14.md, Environment) plus the CI-only pieces in ci/.
SETUP = [
    ("arrange-r7", py("r7/arrange.py")),
    ("arrange-r8", py("r8/arrange.py")),
    ("arrange-side", py("r8/arrange_side.py")),
    ("arrange-r12", py("r12/arrange.py")),
    ("arrange-fast", py("r14/arrange_fast.py")),
    ("arrange-ci", py("ci/arrange_ci.py")),
    ("seed-fast", py("r14/seed_fast.py")),
    ("seed", py("r14/seed.py")),
    ("seed-paging", py("r14/seed_paging.py")),
    ("seed-markup", py("ci/seed_markup.py")),
]
SETUP_WEIGHT = 3.3


class Unit:
    def __init__(self, name, weight, steps, last=False, doc=""):
        self.name, self.weight, self.steps, self.last, self.doc = name, weight, steps, last, doc


def crawl_unit(role, weight):
    steps = [
        (f"flag-new-{role}", flag("true", role)),
        (f"crawl-{role}-new", py("r14/crawl.py", role, "new")),
        (f"flag-classic-{role}", flag("false", role)),
        (f"crawl-{role}-classic", py("r14/crawl.py", role, "classic")),
        (f"flag-reset-{role}", flag("true", role)),
    ]
    if role == "admin":  # the admin crawl dismisses the Batch Control monitors on /manage/ (see ci/arrange_ci.py)
        steps.append(("restore-monitors", py("ci/arrange_ci.py", "monitors")))
    return Unit(f"crawl-{role}", weight, steps,
                doc=f"e2e-12/14 crawl (run_crawl.sh) for {role}, new job page then classic, with the content checks")


# jobui.py <ui> [role ...] runs each role on its own; the roles are grouped so that the groups take similar time.
# Measured 2026-10-05 (minutes): new UI requester 9.1, admin 6.6, reqonly+approver-1+manager+nobc 15.9; classic UI
# 7.9, 5.0, 13.6. The last group is split by its entry counts (reqonly has most of them).
JOBUI_GROUPS = {"new": [("requester", 9.1), ("admin", 6.6), ("reqonly", 8.3), ("approver-1 manager nobc", 7.6)],
                "classic": [("requester", 7.9), ("admin", 5.0), ("reqonly", 7.1), ("approver-1 manager nobc", 6.5)]}


def jobui_unit(ui, group, weight):
    roles = group.split()
    name = f"jobui-{ui}-{roles[0] if len(roles) == 1 else 'others'}"
    return Unit(name, weight, [
        (f"flag-{name}", flag("true" if ui == "new" else "false", *roles)),
        (name, py("r14/jobui.py", ui, *roles)),
        (f"flag-reset-{name}", flag("true", *roles)),
    ], doc=f"e2e-12/14 G2c: entry points and dialog cycles, {ui} job UI, {', '.join(roles)}")


# Canonical order = the e2e-14 order. Weights: minutes per unit measured on the local shard runs of 2026-10-05
# (MacBook, Docker Desktop, machine under load from other builds: pessimistic; Playwright Chromium headless).
UNITS = [
    Unit("def07", 0.3, [
        ("flag-def07-new", flag("true", *DEF07_ROLES)),
        ("def07-new", py("r14/def07.py", "new")),
        ("flag-def07-classic", flag("false", *DEF07_ROLES)),
        ("def07-classic", py("r14/def07.py", "classic")),
        ("flag-def07-reset", flag("true", *DEF07_ROLES)),
    ], doc="e2e-14 G1: DEF-07 recheck on both job UIs"),
    crawl_unit("admin", 9.2),
    crawl_unit("requester", 8.7),
    crawl_unit("reqonly", 5.0),
    crawl_unit("approver-1", 1.2),
    crawl_unit("manager", 8.2),
    crawl_unit("nobc", 1.2),
    Unit("actions", 2.3, [("actions", py("r14/actions.py", "all"))],
         doc="e2e-12/14 G2b: every state-changing control per role"),
] + [jobui_unit(ui, group, w) for ui in ("new", "classic") for group, w in JOBUI_GROUPS[ui]] + [
    Unit("misc", 1.1, [("misc", py("r14/misc.py", "CHPBTRMKS"))], doc="e2e-12/14 G2d: targeted checks C H P B T R M K S"),
    Unit("targeted", 0.9, [
        ("errpages", py("r14/errpages.py")),
        ("listpager", py("r14/s_listpager.py")),
        ("incident", py("r14/s_incident.py")),
        ("helpcheck", py("r14/helpcheck.py")),
        ("helpcheck2", py("r14/helpcheck2.py")),
        ("monitor", py("r14/s_monitor.py")),
    ], doc="e2e-12/14 G2d: error pages, list pager, incident, help, strategy monitor"),
    Unit("round3", 0.6, [("round3", py("r14/round3.py", "ABCDEFGHI"))], doc="e2e-14 R3-A..I: the e2e-11 round-3 checks"),
    # e2e-16 (hosting review round 6: D-71..D-73, D-72a/b). Each unit arranges its own items/accounts (r16/arrange.py is
    # idempotent) so it is self-contained on whatever shard it lands on. Drivers exit non-zero and print FAIL lines.
    Unit("r16-items", 4.0, [("r16-arrange", py("r16/arrange.py")), ("r16-items", py("r16/items.py"))],
         doc="e2e-16: one-item windows on job/folder/multibranch (D-71): Configure/Create reach, legacy scope refused, kind+icon"),
    Unit("r16-rename", 4.0, [("r16-arrange", py("r16/arrange.py")), ("r16-rename", py("r16/rename.py"))],
         doc="e2e-16: no rename through a window via UI and every URL form; allowed for admin/own permission (D-71c)"),
    Unit("r16-params", 6.5, [("r16-arrange", py("r16/arrange.py")), ("r16-params", py("r16/params.py"))],
         doc="e2e-16: typed parameters (core file, stashedFile, base64File, password) page+dialog; 413, repeated name, U+0000, disposal (D-72, D-72b)"),
    Unit("r16-rerun", 4.0, [("r16-arrange", py("r16/arrange.py")), ("r16-rerun", py("r16/rerun.py"))],
         doc="e2e-16: incident rerun reuses the secret; stashedFile falls back to the validated prefilled form; resolvedByRunId (D-72, D-72a)"),
    Unit("r16-names", 3.0, [("r16-arrange", py("r16/arrange.py")), ("r16-names", py("r16/names.py"))],
         doc="e2e-16: CREATE name restriction in the #107 optionalBlock (ticks Create before filling); exact and /regex/"),
    Unit("multibranch", 2.8, [
        ("mb-arrange", py("r15/arrange.py")),
        ("mb-check-crawl", py("r15/check.py", "crawl")),
        ("mb-submit-folder", py("r15/check.py", "submit", "true", "/job/team-mb/batch-control-activation/", "requester")),
        ("mb-submit-branch-new", py("r15/check.py", "submit", "true", "/job/team-mb/job/main/batch-control-activation/", "requester")),
        ("mb-submit-branch-classic", py("r15/check.py", "submit", "false", "/job/team-mb/job/feature-1/batch-control-activation/", "requester")),
        ("mb-submit-pipeline", py("r15/check.py", "submit", "true", "/job/batch-pipeline/batch-control-activation/", "requester")),
        ("mb-submit-daily", py("r15/check.py", "submit", "false", "/job/batch-daily/batch-control-activation/", "requester")),
        ("mb-side-dialog", py("r15/side_dialog.py")),
        ("mb-folder-compare", py("r15/folder_compare.py")),
        ("mb-menu", py("r15/menu.py", "ci")),
    ], doc="e2e-15: multibranch activation page and the DEF-08 job pages"),
    Unit("role", 7.2, [
        ("role-setup", py("r14/role/setup.py")),
        ("role-manage", py("r14/role/manage_roles.py")),
        ("role-assign", py("r14/role/assign_roles.py")),
        ("role-overlay", py("r14/role/grant_overlay.py")),
        ("role-endpoints", py("r14/role/endpoints.py")),
        ("flag-role-crawl", flag("true", *ROLE_CRAWL)),
    ] + [(f"role-crawl-{r}", dict(py("r14/crawl.py", r, "new"), env={"BC_CRAWL_LOG": "crawl-role"})) for r in ROLE_CRAWL],
        last=True, doc="e2e-13/14 RS: role-strategy 927 profile, then a crawl under it (replaces the strategy: last)"),
]
BY_NAME = {u.name: u for u in UNITS}
ORDER = {u.name: i for i, u in enumerate(UNITS)}


def parse_shard(s):
    m = re.fullmatch(r"(\d+)/(\d+)", s or "")
    if not m or not (1 <= int(m.group(1)) <= int(m.group(2))):
        raise SystemExit(f"shard must be <k>/<N> with 1 <= k <= N, got {s!r}")
    return int(m.group(1)), int(m.group(2))


def split(n):
    """Deterministic LPT: heaviest unit first onto the least loaded shard (ties: lower shard index)."""
    if n > len(UNITS):
        raise SystemExit(f"at most {len(UNITS)} shards (one unit each)")
    bins = [[] for _ in range(n)]
    load = [SETUP_WEIGHT] * n
    for u in sorted(UNITS, key=lambda u: (-u.weight, ORDER[u.name])):
        candidates = [i for i in range(n) if not (u.last and any(x.last for x in bins[i]))]
        i = min(candidates, key=lambda i: (load[i], i))
        bins[i].append(u)
        load[i] += u.weight
    for b in bins:
        b.sort(key=lambda u: (u.last, ORDER[u.name]))
    # shard 1 is the one holding the first unit in canonical order, and so on: stable numbering
    order = sorted(range(n), key=lambda i: min(ORDER[u.name] for u in bins[i]) if bins[i] else 99)
    return [bins[i] for i in order], [load[i] for i in order]


def units_for(k, n):
    if os.environ.get("BC_UNITS"):
        names = [x.strip() for x in os.environ["BC_UNITS"].split(",") if x.strip()]
        unknown = [x for x in names if x not in BY_NAME]
        if unknown:
            raise SystemExit(f"unknown units {unknown}; known: {list(BY_NAME)}")
        return sorted((BY_NAME[x] for x in names), key=lambda u: (u.last, ORDER[u.name]))
    return split(n)[0][k - 1]


def steps_for(units):
    steps = [("setup:" + name, spec) for name, spec in SETUP]
    if os.environ.get("BC_SKIP_SETUP") == "1":
        steps = []
    for u in units:
        steps += [(f"{u.name}:{name}", spec) for name, spec in u.steps]
    return steps


# ------------------------------------------------------------------ verdicts
FAIL_LINE = re.compile(r"^FAIL ", re.M)
OK_FALSE = re.compile(r'"ok": false')
EXCEPTION = re.compile(r'"EXCEPTION": ')
PASS_LINE = re.compile(r"^PASS ", re.M)


def r15_rows(before):
    """check.jsonl rows that a r15/check.py step appended (the file had `before` lines when the step started)."""
    f = E2E / "r15" / "out" / "check.jsonl"
    if not f.exists():
        return []
    return [json.loads(l) for l in f.read_text().splitlines()[before:] if l.strip()]


# r15/check.py writes "ok": false as a hint for the reviewer (e2e-15 judged each row). The runner judges a row from its
# data, leaving out exactly these known or provoked items (everything else still fails):
BUILD_HISTORY = re.compile(r"^(Success|Failed|Unstable|Aborted|Not built|In progress|Pending)$")


def r15_problems(row):
    left = []
    for p in row.get("problems", []):
        if p.get("kind", "").startswith("known_core"):
            continue  # r15/check.py's own classification (core GET 405/404, e2e-15 Known)
        if p.get("kind") == "duplicate" and p.get("region") == "side" and BUILD_HISTORY.match(p.get("text", "")):
            continue  # the classic side panel's build history widget: one status label per build
        left.append(p)
    provoked = row.get("step") == "submit"  # the empty submit first: the form re-renders with errors as HTTP 400
    bad = [b for b in row.get("bad_responses", []) if not (provoked and re.match(r"400 POST .*/batch-control-activation/submit", b))]
    # "Refused to execute script from": core's DialogEvent MIME line (D-70, e2e-11 UX 2), r15's own KNOWN_CONSOLE.
    # "reading 'replace'": core header.js breadcrumb overflow (xmlEscape <- menuItem), e2e-13 U-1, intermittent; e2e-14
    # confirmed it by stack (round3.py's known_core). r15 records no stack, but the plugin's own scripts
    # (ui/dialogOpener.js, ui/getForm.js) call no replace(), so the message cannot come from plugin code.
    cons = [c for c in row.get("console", []) if not (provoked and "status of 400" in c)
            and "Refused to execute script from" not in c and "Cannot read properties of undefined (reading 'replace')" not in c]
    if row.get("step") == "submit" and row.get("form") and not (row.get("uuid") and row.get("server_detail_status") == 200):
        left.append({"kind": "submit did not land on a stored activation request"})
    return left + [{"kind": "response", "detail": b} for b in bad] + [{"kind": "console", "detail": c} for c in cons]


def verdict(rc, text, timed_out, extra=None):
    reasons = []
    if timed_out:
        reasons.append("timeout")
    elif rc != 0:
        reasons.append(f"exit {rc}")
    lines = text.splitlines()
    fails = [l for l in lines if l.startswith("FAIL ")]
    if fails:
        reasons.append(f"{len(fails)} FAIL line(s)")
    okf = [] if extra is not None else [l for l in lines if OK_FALSE.search(l) and not l.startswith(("FAIL ", "PASS "))]
    if okf:
        reasons.append(f'{len(okf)} "ok": false row(s)')
    exc = [l for l in lines if EXCEPTION.search(l)]
    if exc:
        reasons.append(f"{len(exc)} EXCEPTION row(s)")
    if extra:
        reasons.append(f"{len(extra)} r15 problem(s)")
    return ("FAIL" if reasons else "PASS"), reasons, (fails + okf + exc + (extra or []))[:30], len(PASS_LINE.findall(text))


def observations():
    """Rows the observational drivers logged that a reviewer should look at (not a verdict)."""
    obs = {}
    out = E2E / "r14" / "out"

    def rows(name):
        f = out / name
        if not f.exists():
            return []
        res = []
        for line in f.read_text().splitlines():
            try:
                res.append(json.loads(line))
            except ValueError:
                pass
        return res

    for log in ("crawl.jsonl", "crawl-role.jsonl"):
        r = rows(log)
        if r:
            obs[log] = {
                "page_loads": sum(1 for x in r if x.get("kind") == "page"),
                "control_checks": sum(1 for x in r if x.get("kind") not in ("page", "content")),
                "dialog_cycles": sum(1 for x in r if x.get("kind") == "click" and x.get("result") == "dialog"),
                "nothing_or_click_failed": sum(1 for x in r if x.get("result") in ("NOTHING", "click-failed")),
                "broken_links": sum(1 for x in r if x.get("result") == "BROKEN"),
                "pages_with_console_errors": sum(1 for x in r if x.get("kind") == "page" and x.get("console")),
                "content_defects": sum(1 for x in r if x.get("kind") == "content" and x.get("result") == "DEFECT"),
                "empty_optional_columns": sorted({f'{x.get("pattern")} {x.get("key")}' for x in r if x.get("check") == "empty-optional"})[:80],
            }
    a = rows("actions.jsonl")
    if a:
        obs["actions.jsonl"] = {"rows": len(a), "exception": sum(1 for x in a if x.get("exception")),
                                "status_ge_400": sum(1 for x in a if (x.get("status") or 0) >= 400)}
    j = rows("jobui.jsonl")
    if j:
        obs["jobui.jsonl"] = {"entries": sum(1 for x in j if x.get("kind") == "entry"),
                              "exception": sum(1 for x in j if x.get("exception")),
                              "nothing": sum(1 for x in j if x.get("result") == "NOTHING")}
    return obs


def run(k, n, outdir):
    outdir = Path(outdir)
    logs = outdir / "logs"
    logs.mkdir(parents=True, exist_ok=True)
    units = units_for(k, n)
    steps = steps_for(units)
    pyexe = os.environ.get("PY", sys.executable)
    timeout = int(os.environ.get("BC_STEP_TIMEOUT", "2700"))
    results = []
    t_all = time.time()
    setup_failed = False
    print(f"shard {k}/{n}: units {[u.name for u in units]} ({len(steps)} steps)", flush=True)
    for idx, (name, spec) in enumerate(steps, 1):
        log = logs / f"{idx:02d}-{name.replace(':', '-').replace('/', '_')}.log"
        if setup_failed:
            results.append({"step": name, "verdict": "BLOCKED", "reasons": ["setup failed"], "seconds": 0, "log": log.name})
            continue
        env = dict(os.environ, PYTHONUNBUFFERED="1", **spec.get("env", {}))
        is_r15 = spec["argv"][0] == "r15/check.py"
        r15_before = len(r15_rows(0)) if is_r15 else 0
        argv = [pyexe] + spec["argv"]
        t0 = time.time()
        timed_out = False
        with open(log, "w") as fh:
            fh.write("$ " + " ".join(spec["argv"]) + "\n")
            fh.flush()
            try:
                p = subprocess.run(argv, cwd=E2E, env=env, stdout=fh, stderr=subprocess.STDOUT, timeout=timeout)
                rc = p.returncode
            except subprocess.TimeoutExpired:
                rc, timed_out = -1, True
        secs = round(time.time() - t0, 1)
        text = log.read_text(errors="replace")
        extra = None
        if is_r15:
            extra = [json.dumps({"user": r.get("user"), "flag": r.get("flag"), "page": r.get("page"), "problem": p})[:400]
                     for r in r15_rows(r15_before) for p in r15_problems(r)]
            extra += [json.dumps({"page": r.get("page"), "status": r.get("status"), "expected": 404})
                      for r in r15_rows(r15_before) if "expected" in r and r.get("status") != r["expected"]]
        v, reasons, lines, passes = verdict(rc, text, timed_out, extra)
        results.append({"step": name, "verdict": v, "reasons": reasons, "seconds": secs, "log": log.name,
                        "pass_lines": passes, "failure_lines": lines})
        print(f"[{time.strftime('%H:%M:%S')}] {v:4} {secs:7.1f}s {name} {'; '.join(reasons)}", flush=True)
        if v != "PASS" and name.startswith("setup:"):
            setup_failed = True
    total = round(time.time() - t_all, 1)
    summary = {"shard": f"{k}/{n}", "units": [u.name for u in units], "seconds": total,
               "steps": results, "observations": observations(),
               "failed": [r["step"] for r in results if r["verdict"] != "PASS"]}
    (outdir / "summary.json").write_text(json.dumps(summary, indent=1))
    (outdir / "summary.md").write_text(markdown(summary))
    return 1 if summary["failed"] else 0


def markdown(s):
    out = [f"### e2e shard {s['shard']}: {'FAIL' if s['failed'] else 'PASS'}", "",
           f"Units: {', '.join(s['units'])}. Steps: {len(s['steps'])}, failed: {len(s['failed'])}, "
           f"driver time {s['seconds'] / 60:.1f} min.", "", "| Step | Result | Seconds | Notes |", "|---|---|---|---|"]
    for r in s["steps"]:
        notes = "; ".join(r["reasons"]) or (f"{r['pass_lines']} PASS lines" if r.get("pass_lines") else "")
        out.append(f"| `{r['step']}` | {r['verdict']} | {r['seconds']} | {notes} |")
    fails = [r for r in s["steps"] if r.get("failure_lines")]
    if fails:
        out += ["", "#### Failure lines (first 30 per step)", ""]
        for r in fails:
            out.append(f"`{r['step']}` (`logs/{r['log']}`):")
            out.append("```")
            out += [l[:400] for l in r["failure_lines"]]
            out.append("```")
    if s.get("observations"):
        out += ["", "#### Observational drivers (for review, not a verdict)", "", "```", json.dumps(s["observations"], indent=1), "```"]
    return "\n".join(out) + "\n"


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("units")
    p = sub.add_parser("plan")
    p.add_argument("shard")
    p.add_argument("--json", action="store_true")
    p = sub.add_parser("plan-all")
    p.add_argument("n", type=int)
    p = sub.add_parser("run")
    p.add_argument("shard")
    p.add_argument("--out", required=True)
    a = ap.parse_args()
    if a.cmd == "units":
        print(f"setup ({SETUP_WEIGHT} min, every shard): " + ", ".join(n for n, _ in SETUP))
        for u in UNITS:
            print(f"{u.name:16} {u.weight:5.1f} min{' (last)' if u.last else ''}  {u.doc}")
            for name, spec in u.steps:
                print(f"    {name:26} {' '.join(spec['argv'])}{'  ' + str(spec['env']) if spec.get('env') else ''}")
    elif a.cmd == "plan":
        k, n = parse_shard(a.shard)
        units = units_for(k, n)
        steps = steps_for(units)
        if a.json:
            print(json.dumps({"shard": a.shard, "units": [u.name for u in units],
                              "steps": [{"step": s, "argv": spec["argv"]} for s, spec in steps]}, indent=1))
        else:
            print(f"shard {k}/{n}: {[u.name for u in units]}")
            for s, spec in steps:
                print(f"  {s:34} {' '.join(spec['argv'])}")
    elif a.cmd == "plan-all":
        bins, loads = split(a.n)
        for i, (b, l) in enumerate(zip(bins, loads), 1):
            print(f"shard {i}/{a.n}: ~{l:.0f} min drivers (incl. {SETUP_WEIGHT:.0f} setup): {[u.name for u in b]}")
    elif a.cmd == "run":
        k, n = parse_shard(a.shard)
        sys.exit(run(k, n, a.out))


if __name__ == "__main__":
    main()
