#!/usr/bin/env python3
"""Markdown for the GitHub job summary from the JUnit XML that ci/run.sh's pytest run writes (no third-party action,
so it works with the read-only token of fork pull requests).

    python e2e/ci/junit_summary.py e2e/ci/out/<k>/junit.xml >> "$GITHUB_STEP_SUMMARY"
"""
import sys
import xml.etree.ElementTree as ET


def main(path):
    root = ET.parse(path).getroot()
    suites = [root] if root.tag == "testsuite" else root.findall("testsuite")
    rows, failed, skipped, total, secs = [], [], [], 0, 0.0
    for s in suites:
        for c in s.findall("testcase"):
            total += 1
            secs += float(c.get("time") or 0)
            name = c.get("name", "").removeprefix("test_step[").removesuffix("]")
            props = {p.get("name"): p.get("value") for p in c.findall("properties/property")}
            f = c.find("failure")
            if f is None:
                f = c.find("error")
            if f is not None:
                failed.append((name, (f.get("message") or "").splitlines()[0][:300], props))
            elif c.find("skipped") is not None:
                skipped.append((name, (c.find("skipped").get("message") or "")[:200]))
        rows.append(s.get("name"))
    out = [f"### JUnit: {', '.join(r for r in rows if r)}", "",
           f"{total} tests: {total - len(failed) - len(skipped)} passed, {len(failed)} failed, {len(skipped)} skipped "
           f"({secs / 60:.1f} min).", ""]
    if failed:
        out += ["| Failed step | Reason | Log | Traces |", "|---|---|---|---|"]
        out += [f"| `{n}` | {m.replace('|', '/')} | `{p.get('log', '')}` | {('`' + p['traces'] + '`') if p.get('traces') else ''} |"
                for n, m, p in failed]
        out.append("")
    if skipped:
        out += ["Skipped: " + ", ".join(f"`{n}` ({m})" for n, m in skipped), ""]
    print("\n".join(out))


if __name__ == "__main__":
    main(sys.argv[1])
