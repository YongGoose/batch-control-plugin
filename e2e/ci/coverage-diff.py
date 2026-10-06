#!/usr/bin/env python3
"""Changed-line e2e coverage: which lines that a branch changed under src/main/java did no e2e scenario execute?

    coverage-diff.py --exec e2e/ci/out/*/jacoco-*.exec --base <base ref or sha> [--head HEAD] [--repo DIR]
                     [--classes target/classes] [--sources src/main/java] [--out DIR] [--fail-under PCT]

The signal: a changed executable line that no e2e scenario executed means "a scenario must be added" (or a reason
why not). The heavy lifting is done by proven tools; this file only glues them and adds what they lack:

1. JaCoCo CLI (pinned in ci/jacoco.env, sha256-verified before use, no network): `merge` the exec files of all
   shards, `execinfo`, `report` -> jacoco.xml (+ HTML). JaCoCo decides which lines are executable: a line is
   reported only if it carries instructions (`<line nr mi ci>`); comments, blank lines and declarations never are.
2. Fail loudly on a class mismatch (exit 3): JaCoCo matches execution data to class files by a checksum of the
   class bytes, so an exec recorded from an hpi built from another checkout silently reports those classes as not
   covered. Any "[WARN] Execution data for class ... does not match" from the report, any class in the exec that
   the given classes do not have, or no matched execution data at all stops the script with a message.
3. diff-cover (Bachmann1234/diff_cover, pinned in ci/requirements.txt) computes the changed-line coverage from the
   JaCoCo XML and `git diff -U0 <base>...<head> -- <sources>` (three dots: changes since the merge base).
4. Output in --out: merged.exec, jacoco.xml, html/, changed.diff, diff-cover.json/.md and summary.md (per file:
   changed executable lines, covered, NOT covered with line numbers; overall e2e coverage per package; changed files
   that JaCoCo cannot measure: Jelly, JavaScript, properties, help HTML under src/main/resources and src/main/webapp).
   With annotations on (default when GITHUB_ACTIONS=true): GitHub `::warning file=...,line=...` lines for the not
   covered ranges (at most --max-annotations, then a summary notice), and summary.md appended to
   $GITHUB_STEP_SUMMARY.

Exit codes: 0 report written (whatever the coverage: non-blocking); 1 --fail-under given and the changed-line
coverage is below it; 2 usage or tool error; 3 execution data does not match the classes (see 2.).

Runs on any branch and against any local ref: --repo points at the checkout (a worktree of another branch works),
--base at a branch name or sha it has, --head defaults to HEAD; --worktree also counts uncommitted edits.
"""
import argparse
import glob
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

CI = Path(__file__).resolve().parent
EXIT_OK, EXIT_UNDER, EXIT_TOOL, EXIT_MISMATCH = 0, 1, 2, 3
NON_MEASURABLE = ("src/main/resources", "src/main/webapp")
MESSAGE = "not executed by any e2e scenario"
ANNOTATE = False


class Fail(Exception):
    def __init__(self, code, msg):
        super().__init__(msg)
        self.code = code


def esc_data(s):
    return s.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


def esc_prop(s):
    return esc_data(s).replace(":", "%3A").replace(",", "%2C")


def pins():
    out = {}
    for line in (CI / "jacoco.env").read_text().splitlines():
        if "=" in line and not line.lstrip().startswith("#"):
            k, v = line.split("=", 1)
            out[k.strip()] = v.strip()
    return out


def sha256(p):
    h = hashlib.sha256()
    with open(p, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def run(argv, cwd=None, check=True):
    p = subprocess.run(argv, cwd=cwd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    if check and p.returncode != 0:
        raise Fail(EXIT_TOOL, f"command failed ({p.returncode}): {' '.join(map(str, argv))}\n{p.stdout[-3000:]}")
    return p.stdout


def ranges(lines):
    out = []
    for n in sorted(lines):
        if out and n == out[-1][1] + 1:
            out[-1][1] = n
        else:
            out.append([n, n])
    return out


def fmt_ranges(lines):
    return ", ".join(f"{a}" if a == b else f"{a}-{b}" for a, b in ranges(lines))


def counters(el):
    return {c.get("type"): (int(c.get("covered")), int(c.get("missed"))) for c in el.findall("counter")}


def pct(cov, miss):
    return f"{100.0 * cov / (cov + miss):.1f}%" if cov + miss else "n/a"


def jacoco(args, out):
    p = pins()
    cli = Path(args.jacococli)
    if not cli.is_file():
        raise Fail(EXIT_TOOL, f"JaCoCo CLI not found at {cli}: run e2e/ci/fetch-jacoco.sh")
    if sha256(cli) != p["JACOCO_CLI_SHA256"]:
        raise Fail(EXIT_TOOL, f"{cli} does not have the sha256 pinned in ci/jacoco.env ({p['JACOCO_VERSION']}): re-run fetch-jacoco.sh")
    java = args.java or (str(Path(os.environ["JAVA_HOME"]) / "bin" / "java") if os.environ.get("JAVA_HOME") else "java")
    execs = []
    for pattern in args.exec:
        hits = sorted(glob.glob(pattern)) if any(ch in pattern for ch in "*?[") else [pattern]
        execs += [h for h in hits if Path(h).is_file()]
    if not execs:
        raise Fail(EXIT_TOOL, f"no exec file found for {args.exec}")
    merged = out / "merged.exec"
    run([java, "-jar", str(cli), "merge", *execs, "--destfile", str(merged), "--quiet"])
    info = run([java, "-jar", str(cli), "execinfo", str(merged)])
    exec_classes = {m.group(2) for m in re.finditer(r"^([0-9a-f]{16})\s+\d+\s+of\s+\d+\s+(\S+)$", info, re.M)}
    sessions = re.findall(r'^Session "([^"]*)"', info, re.M)
    rep = [java, "-jar", str(cli), "report", str(merged), "--classfiles", str(args.classes), "--sourcefiles", str(args.sources),
           "--name", "batch-control e2e", "--xml", str(out / "jacoco.xml")]
    if not args.no_html:
        rep += ["--html", str(out / "html")]
    report_log = run(rep)
    (out / "jacoco-report.log").write_text(report_log)
    nomatch = re.findall(r"Execution data for class (\S+) does not match", report_log)
    root = ET.parse(out / "jacoco.xml").getroot()
    report_classes = {c.get("name") for c in root.iter("class")}
    methods = {c.get("name"): {m.get("name") for m in c.iter("method")} for c in root.iter("class")}

    def generated(name):
        # Groovy call-site classes <Owner>$<method>[$<n>] that Groovy defines at runtime when a script calls plugin
        # code (compose.coverage.yml excludes their class loaders; exec files recorded without that still have them)
        m = re.fullmatch(r"(.+?)\$([A-Za-z_]\w*?)(?:\$\d+)?", name)
        return bool(m) and m.group(2) in methods.get(m.group(1), ())

    unknown = sorted(c for c in exec_classes if c not in report_classes and c.startswith(args.package.replace(".", "/"))
                     and not generated(c))
    total = counters(root)
    problems = []
    if nomatch:
        problems.append(f"{len(nomatch)} class(es) in the execution data do not match the class files, e.g. {', '.join(nomatch[:5])}")
    if unknown:
        problems.append(f"{len(unknown)} class(es) in the execution data are missing from {args.classes}, e.g. {', '.join(unknown[:5])}")
    if not problems and total.get("INSTRUCTION", (0, 0))[0] == 0:
        problems.append("no execution data matches any class (wrong exec files, or the agent's includes do not cover the plugin)")
    if problems:
        raise Fail(EXIT_MISMATCH, "e2e coverage cannot be reported: " + "; ".join(problems) + ". JaCoCo needs the class files of "
                   "the very build whose hpi ran in the e2e Jenkins: build target/batch-control.hpi and target/classes from the same "
                   "checkout (ci/run.sh records the hpi's sha256 in out/<k>/build-info.txt).")
    return root, execs, sessions


def git_diffs(args, repo, sources_rel):
    if args.worktree:
        base = run(["git", "merge-base", args.base, args.head], cwd=repo).strip()
        rng = [base]
        label = f"{args.base} (merge base {base[:10]}) .. working tree"
    else:
        rng = [f"{args.base}...{args.head}"]
        base = run(["git", "merge-base", args.base, args.head], cwd=repo).strip()
        label = f"{args.base}...{args.head} (merge base {base[:10]})"
    diff = run(["git", "diff", "--no-color", "--no-ext-diff", "-U0", *rng, "--", sources_rel], cwd=repo)
    names = run(["git", "diff", "--no-color", "--name-status", "-M", *rng, "--", "src/main"], cwd=repo)
    changed = []
    for line in names.splitlines():
        parts = line.split("\t")
        if len(parts) >= 2:
            changed.append((parts[0][0], parts[-1]))
    return diff, changed, label


def main():
    global ANNOTATE
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--exec", nargs="+", required=True, help="JaCoCo exec files or globs (one per shard)")
    ap.add_argument("--base", required=True, help="base ref or sha (PR: the base sha); changes since the merge base count")
    ap.add_argument("--head", default="HEAD")
    ap.add_argument("--repo", default=".", help="checkout to diff (default: the current directory's repository)")
    ap.add_argument("--classes", help="class files of the build that ran (default <repo>/target/classes; a jar or the hpi works too)")
    ap.add_argument("--sources", help="default <repo>/src/main/java")
    ap.add_argument("--package", default="io.jenkins.plugins.batchcontrol")
    ap.add_argument("--worktree", action="store_true", help="also count uncommitted changes of --repo")
    ap.add_argument("--out", default=str(CI / "out" / "coverage"))
    ap.add_argument("--jacococli", default=str(CI / ".cache" / "jacoco" / "jacococli.jar"))
    ap.add_argument("--java", help="java executable (default $JAVA_HOME/bin/java, else java on PATH)")
    ap.add_argument("--diff-cover", dest="diff_cover", help="diff-cover executable (default: next to this python, else PATH)")
    ap.add_argument("--fail-under", type=float, help="exit 1 when the changed-line coverage is below this percentage")
    ap.add_argument("--max-annotations", type=int, default=50)
    ap.add_argument("--annotations", choices=("auto", "on", "off"), default="auto")
    ap.add_argument("--no-html", action="store_true")
    ap.add_argument("--title", default="E2E coverage of the changed lines")
    args = ap.parse_args()
    ANNOTATE = args.annotations == "on" or (args.annotations == "auto" and os.environ.get("GITHUB_ACTIONS") == "true")
    try:
        sys.exit(work(args))
    except Fail as f:
        msg = str(f)
        print(msg, file=sys.stderr)
        if ANNOTATE:
            print(f"::error title=e2e coverage::{esc_data(msg)}")
        sys.exit(f.code)


def work(args):
    repo = Path(run(["git", "rev-parse", "--show-toplevel"], cwd=args.repo).strip())
    args.classes = Path(args.classes or repo / "target" / "classes").resolve()
    args.sources = Path(args.sources or repo / "src" / "main" / "java").resolve()
    for what, p in (("classes", args.classes), ("sources", args.sources)):
        if not p.exists():
            raise Fail(EXIT_TOOL, f"--{what} {p} does not exist")
    out = Path(args.out).resolve()
    # --out is emptied first (also because `jacococli merge` appends to an existing destfile): never over the inputs
    inputs = [Path(h).resolve() for p in args.exec for h in (glob.glob(p) if any(ch in p for ch in "*?[") else [p])]
    if any(out == i or out in i.parents for i in inputs):
        raise Fail(EXIT_TOOL, f"--out {out} holds exec files given with --exec and would be deleted: choose another --out")
    if out.exists():
        shutil.rmtree(out)
    out.mkdir(parents=True)
    root, execs, sessions = jacoco(args, out)

    sources_rel = os.path.relpath(args.sources, repo)
    diff, changed, label = git_diffs(args, repo, sources_rel)
    (out / "changed.diff").write_text(diff)
    dc = args.diff_cover or shutil.which("diff-cover", path=str(Path(sys.executable).parent)) or shutil.which("diff-cover")
    if not dc:
        raise Fail(EXIT_TOOL, "diff-cover not found: pip install -r e2e/ci/requirements.txt")
    run([dc, str(out / "jacoco.xml"), "--diff-file", str(out / "changed.diff"), "--compare-branch", args.base,
         "--src-roots", sources_rel, "--format", f"json:{out / 'diff-cover.json'},markdown:{out / 'diff-cover.md'}", "-q"], cwd=repo)
    dcj = json.loads((out / "diff-cover.json").read_text())
    stats = dcj.get("src_stats", {})

    # ---------------- summary
    tot = counters(root)
    files = []
    for path, st in sorted(stats.items()):
        miss, cov = st.get("violation_lines", []), st.get("covered_lines", [])
        files.append((path, len(miss) + len(cov), len(cov), miss))
    n_exec = sum(f[1] for f in files)
    n_cov = sum(f[2] for f in files)
    n_miss = n_exec - n_cov
    changed_java = [(s, p) for s, p in changed if p.startswith(sources_rel + "/") and p.endswith(".java") and s != "D"]
    no_exec = [p for s, p in changed_java if p not in stats]
    non_measurable = [(s, p) for s, p in changed if p.startswith(NON_MEASURABLE) or (p.startswith(sources_rel + "/") and not p.endswith(".java"))]
    changed_pct = 100.0 * n_cov / n_exec if n_exec else None

    md = [f"## {args.title}", ""]
    md.append(f"Diff `{label}` in `{repo.name}`; {len(execs)} exec file(s) merged ({len(sessions)} JaCoCo session(s)); classes `{os.path.relpath(args.classes, repo) if str(args.classes).startswith(str(repo)) else args.classes}` match the execution data.")
    md.append("")
    li, ins, br, me = (tot.get(k, (0, 0)) for k in ("LINE", "INSTRUCTION", "BRANCH", "METHOD"))
    md.append(f"**Overall e2e coverage of `{args.package}`:** lines {pct(*li)} ({li[0]}/{li[0] + li[1]}), instructions {pct(*ins)}, "
              f"branches {pct(*br)}, methods {pct(*me)}.")
    md.append("")
    if n_exec:
        md.append(f"**Changed executable lines:** {n_exec}; executed by e2e: {n_cov} ({changed_pct:.1f}%); **not executed: {n_miss}**"
                  + (" — each one needs an e2e scenario (or a reason why not)." if n_miss else "."))
        md += ["", "| File | Changed executable lines | Covered | Not covered (lines) |", "|---|---:|---:|---|"]
        for path, ne, nc, miss in files:
            md.append(f"| `{path}` | {ne} | {nc} | {fmt_ranges(miss) if miss else '-'} |")
    else:
        md.append("**Changed executable lines:** none under `" + sources_rel + "` (nothing for JaCoCo to measure).")
    if no_exec:
        md += ["", "Changed Java files without an executable changed line (comments, imports, declarations, or code JaCoCo "
               "does not instrument such as interfaces without default methods):", ""] + [f"- `{p}`" for p in no_exec]
    if non_measurable:
        md += ["", "**Changed files JaCoCo cannot measure** (Jelly views, JavaScript, properties, help pages): check that an e2e "
               "scenario renders or exercises them:", ""] + [f"- `{p}` ({s})" for s, p in non_measurable]
    md += ["", "<details><summary>Overall e2e line coverage per package</summary>", "", "| Package | Lines | Covered |", "|---|---:|---:|"]
    for pkg in sorted(root.findall("package"), key=lambda p: p.get("name")):
        c, m = counters(pkg).get("LINE", (0, 0))
        md.append(f"| `{pkg.get('name').replace('/', '.')}` | {c + m} | {pct(c, m)} |")
    md += ["", "</details>", "", "Artefacts: `jacoco.xml`, `html/index.html` (JaCoCo report), `diff-cover.json`, `merged.exec`.", ""]
    summary = "\n".join(md)
    (out / "summary.md").write_text(summary)
    (out / "summary.json").write_text(json.dumps({
        "diff": label, "exec_files": execs, "sessions": sessions,
        "overall": {k: {"covered": v[0], "missed": v[1]} for k, v in tot.items()},
        "changed_executable_lines": n_exec, "changed_covered": n_cov, "changed_not_covered": n_miss,
        "changed_percent": changed_pct, "files": {p: {"executable": ne, "covered": nc, "not_covered": miss} for p, ne, nc, miss in files},
        "no_executable_change": no_exec, "not_measurable": [p for _, p in non_measurable]}, indent=1))
    print(summary)

    if ANNOTATE:
        emitted, all_ranges = 0, [(p, a, b) for p, _, _, miss in files for a, b in ranges(miss)]
        for path, a, b in all_ranges[:args.max_annotations]:
            where = f"file={esc_prop(path)},line={a}" + (f",endLine={b}" if b != a else "")
            what = f"Changed line {a} {MESSAGE}" if a == b else f"Changed lines {a}-{b} {MESSAGE}"
            print(f"::warning {where},title=e2e coverage::{esc_data(what)}")
            emitted += 1
        if n_exec:
            note = f"{n_cov} of {n_exec} changed executable lines executed by e2e ({changed_pct:.1f}%); {n_miss} not executed"
            if len(all_ranges) > emitted:
                note += f"; {len(all_ranges) - emitted} more range(s) not annotated, see the job summary"
            print(f"::notice title=e2e changed-line coverage::{esc_data(note)}")
        if os.environ.get("GITHUB_STEP_SUMMARY"):
            with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as fh:
                fh.write(summary + "\n")
    if args.fail_under is not None and changed_pct is not None and changed_pct < args.fail_under:
        print(f"changed-line coverage {changed_pct:.1f}% is below --fail-under {args.fail_under}", file=sys.stderr)
        return EXIT_UNDER
    return EXIT_OK


if __name__ == "__main__":
    main()
