#!/usr/bin/env python3
"""Summary of the Windows test run in .github/workflows/windows-tests.yml.

Usage:
  surefire-summary.py <reports-dir>
      <reports-dir> holds one directory per test group, named after its artifact
      ("windows-surefire-<group-id>"), with the group's Surefire TEST-*.xml reports.
      Writes to $GITHUB_STEP_SUMMARY (and to stdout) a table of every failing test, with
      its group, class, method and the first line of its message (or the exception type),
      a table of the tests that failed and then passed on a rerun, and per-group totals.
      The groups and their classes come from .github/test-shards.txt (through
      test-shards.py), so a class with no report at all, as left behind by a crashed fork
      or a job that stopped early, is listed too.
      Exits 1 when a test failed or errored, when a class has no report or when there is
      no report at all; 0 otherwise.
"""

import html
import importlib.util
import os
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ARTIFACT_PREFIX = "windows-surefire-"
MESSAGE_MAX = 160
ROWS_MAX = 500


def load_shards():
    path = Path(__file__).resolve().with_name("test-shards.py")
    spec = importlib.util.spec_from_file_location("test_shards", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    groups, _, by_group = module.checked_assignment()
    return [(g["id"], g["label"], set(by_group[g["id"]])) for g in groups]


def cell(text):
    text = html.escape(text, quote=False).replace("|", "\\|")
    return text if len(text) <= MESSAGE_MAX else text[: MESSAGE_MAX - 3] + "..."


def first_line(problem):
    """The exception type and the first line of a <failure>/<error> element's message."""
    full = problem.get("type") or ""
    kind = full.rsplit(".", 1)[-1]
    msg = next((ln.strip() for ln in (problem.get("message") or "").splitlines() if ln.strip()), "")
    if not msg:
        # No message attribute: the stack trace starts with "<type>: <message>" or "<type>".
        msg = next((ln.strip() for ln in (problem.text or "").splitlines() if ln.strip()), "")
        if full and msg.startswith(full):
            msg = msg[len(full):].lstrip(": ")
    if kind and msg.startswith(kind + ":"):
        return msg
    return f"{kind}: {msg}" if kind and msg else (kind or msg or "(no message)")


def parse_group(directory):
    totals = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0, "flaky": 0, "time": 0.0}
    classes, failed, flaky = set(), [], []
    for report in sorted(directory.rglob("TEST-*.xml")):
        classes.add(report.name[len("TEST-"):-len(".xml")])
        root = ET.parse(report).getroot()
        suites = [root] if root.tag == "testsuite" else root.iter("testsuite")
        for suite in suites:
            for k in ("tests", "failures", "errors", "skipped"):
                totals[k] += int(suite.get(k) or 0)
            totals["time"] += float((suite.get("time") or "0").replace(",", ""))
            for case in suite.iter("testcase"):
                cls = (case.get("classname") or suite.get("name") or "?").rsplit(".", 1)[-1]
                name = case.get("name") or "(class)"
                problem = case.find("failure")
                if problem is None:
                    problem = case.find("error")
                if problem is not None:
                    failed.append((cls, name, problem.tag, first_line(problem)))
                    continue
                rerun = next((c for c in case if c.tag in ("flakyFailure", "flakyError")), None)
                if rerun is not None:
                    totals["flaky"] += 1
                    flaky.append((cls, name, rerun.tag, first_line(rerun)))
    return classes, totals, failed, flaky


def main(argv):
    if len(argv) != 2:
        print(__doc__)
        return 2
    reports = Path(argv[1])
    shards = load_shards()
    known = {gid for gid, _, _ in shards}
    dirs = sorted(p for p in reports.glob(ARTIFACT_PREFIX + "*") if p.is_dir()) if reports.is_dir() else []
    found = {p.name[len(ARTIFACT_PREFIX):]: p for p in dirs}
    # An artifact whose group is no longer in test-shards.txt is still reported, at the end.
    groups = shards + [(gid, gid, set()) for gid in sorted(found) if gid not in known]

    failing, flaky_rows, totals_rows, missing_rows = [], [], [], []
    missing_total = 0
    sums = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0, "flaky": 0, "time": 0.0}
    classes_total = 0
    for gid, label, expected in groups:
        if gid in found:
            classes, totals, failed, flaky = parse_group(found[gid])
        else:
            classes, totals, failed, flaky = set(), dict.fromkeys(sums, 0), [], []
        classes_total += len(classes)
        missing = sorted(expected - classes)
        failing += [(label, *row) for row in failed]
        flaky_rows += [(label, *row) for row in flaky]
        missing_total += len(missing)
        if missing and not classes:
            missing_rows.append((label, f"all {len(missing)} classes of the group"))
        else:
            missing_rows += [(label, c.rsplit(".", 1)[-1]) for c in missing]
        for k in sums:
            sums[k] += totals[k]
        status = "no artifact" if gid not in found else ("no reports" if not classes else "")
        totals_rows.append(
            f"| {label} | {len(classes)} | {len(missing) or ''} | {totals['tests']} | "
            f"{totals['failures']} | {totals['errors']} | {totals['skipped']} | "
            f"{totals['flaky'] or ''} | {round(totals['time'])} | {status} |"
        )

    out = ["## Windows test results (JDK 21)", ""]
    bad = len(failing)
    out.append(
        f"{bad} failing test{'s' if bad != 1 else ''}, {missing_total} class"
        f"{'es' if missing_total != 1 else ''} without a report, {sums['flaky']} flaky, "
        f"out of {sums['tests']} tests."
    )
    out.append("")
    if failing:
        out += ["### Failing tests", "", "| Group | Class | Method | Kind | First line |", "|---|---|---|---|---|"]
        for label, cls, name, kind, msg in sorted(failing)[:ROWS_MAX]:
            out.append(f"| {label} | {cell(cls)} | {cell(name)} | {kind} | {cell(msg)} |")
        if len(failing) > ROWS_MAX:
            out.append(f"\n{len(failing) - ROWS_MAX} more rows not shown; see the artifacts.")
        out.append("")
    if missing_rows:
        out += ["### Classes without a report", "",
                "A crashed fork, a build error or the job timeout; see the job log.", "",
                "| Group | Class |", "|---|---|"]
        out += [f"| {label} | {cls} |" for label, cls in missing_rows[:ROWS_MAX]]
        out.append("")
    if flaky_rows:
        out += ["### Flaky tests (failed, then passed on a rerun)", "",
                "| Group | Class | Method | Kind | First line |", "|---|---|---|---|---|"]
        for label, cls, name, kind, msg in sorted(flaky_rows)[:ROWS_MAX]:
            out.append(f"| {label} | {cell(cls)} | {cell(name)} | {kind} | {cell(msg)} |")
        out.append("")
    out += ["### Totals per group", "",
            "| Group | Classes | Without report | Tests | Failures | Errors | Skipped | Flaky | Time (s) | Note |",
            "|---|---:|---:|---:|---:|---:|---:|---:|---:|---|"]
    out += totals_rows
    out.append(
        f"| total | {classes_total} | {missing_total or ''} | {sums['tests']} | {sums['failures']} | "
        f"{sums['errors']} | {sums['skipped']} | {sums['flaky'] or ''} | {round(sums['time'])} | |"
    )
    text = "\n".join(out)
    print(text)
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as f:
            f.write(text + "\n")

    if not classes_total:
        print("::error::no Surefire report was found in any group")
        return 1
    if failing or missing_rows:
        print(f"::error::on Windows: failing tests {len(failing)}, classes without a report {missing_total}")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
