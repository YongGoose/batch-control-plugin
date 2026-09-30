#!/usr/bin/env python3
"""Safeguard for the sharded Surefire run in .github/workflows/build.yml (issue #54).

Usage:
  test-shards.py check
      Lists every test class Surefire would run and checks that each one matches exactly
      one shard in .github/test-shards.txt, and that no shard is empty. On success it
      writes the job matrix to $GITHUB_OUTPUT (key "matrix").
  test-shards.py verify <shard-id>
      Run after a shard's Surefire run. Checks that target/surefire-reports holds a report
      for every class assigned to the shard and for no other class, then writes the
      shard's test counts to $GITHUB_STEP_SUMMARY.

The pattern matching mirrors Surefire's -Dtest handling for patterns without a package
part: the pattern is matched against the simple class name, '*' matches any run of
characters.
"""

import fnmatch
import json
import os
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SHARD_FILE = ROOT / ".github" / "test-shards.txt"
TEST_SRC = ROOT / "src" / "test" / "java"
REPORTS = ROOT / "target" / "surefire-reports"

# Surefire's default includes. A class matching one of these but no shard pattern would
# be silently skipped by the sharded run, so the check treats it as an error.
SUREFIRE_INCLUDES = ["Test*", "*Test", "*Tests", "*TestCase"]

# Generated at build time by maven-hpi-plugin (insert-test goal), not present in src/test.
GENERATED_TESTS = ["io.jenkins.plugins.batch_control.InjectedTest"]

PATTERN_SYNTAX = re.compile(r"^[A-Za-z0-9_*]+$")


def fail(msg):
    print(f"::error::{msg}")
    sys.exit(1)


def load_shards():
    shards = []
    for lineno, raw in enumerate(SHARD_FILE.read_text(encoding="utf-8").splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split()
        if len(parts) != 2:
            fail(f"{SHARD_FILE.name}:{lineno}: expected '<id> <patterns>', got '{line}'")
        shard_id, patterns = parts[0], parts[1].split(",")
        for p in patterns:
            if not PATTERN_SYNTAX.match(p):
                fail(f"{SHARD_FILE.name}:{lineno}: unsupported pattern '{p}'")
        if any(s["id"] == shard_id for s in shards):
            fail(f"{SHARD_FILE.name}:{lineno}: duplicate shard id '{shard_id}'")
        shards.append({"id": shard_id, "patterns": patterns})
    if not shards:
        fail(f"{SHARD_FILE.name} defines no shards")
    return shards


def is_abstract(path, simple):
    # Surefire does not run abstract classes, so they need no shard.
    return re.search(rf"\babstract\s+class\s+{re.escape(simple)}\b", path.read_text(encoding="utf-8")) is not None


def list_test_classes():
    classes = []
    for path in sorted(TEST_SRC.rglob("*.java")):
        simple = path.stem
        if not any(fnmatch.fnmatchcase(simple, inc) for inc in SUREFIRE_INCLUDES):
            continue
        if is_abstract(path, simple):
            continue
        fqcn = ".".join(path.relative_to(TEST_SRC).with_suffix("").parts)
        classes.append(fqcn)
    classes.extend(GENERATED_TESTS)
    return sorted(set(classes))


def assign(shards, classes):
    by_shard = {s["id"]: [] for s in shards}
    errors = []
    for fqcn in classes:
        simple = fqcn.rsplit(".", 1)[-1]
        hits = [s["id"] for s in shards if any(fnmatch.fnmatchcase(simple, p) for p in s["patterns"])]
        if not hits:
            errors.append(f"{fqcn} matches no shard in .github/test-shards.txt")
        elif len(hits) > 1:
            errors.append(f"{fqcn} matches more than one shard: {', '.join(hits)}")
        else:
            by_shard[hits[0]].append(fqcn)
    for shard_id, members in by_shard.items():
        if not members:
            errors.append(f"shard {shard_id} matches no test class")
    return by_shard, errors


def write_output(key, value):
    out = os.environ.get("GITHUB_OUTPUT")
    if out:
        with open(out, "a", encoding="utf-8") as f:
            f.write(f"{key}={value}\n")


def write_summary(text):
    out = os.environ.get("GITHUB_STEP_SUMMARY")
    if out:
        with open(out, "a", encoding="utf-8") as f:
            f.write(text + "\n")


def cmd_check():
    shards = load_shards()
    classes = list_test_classes()
    by_shard, errors = assign(shards, classes)
    if errors:
        for e in errors:
            print(f"::error::{e}")
        sys.exit(1)
    lines = ["| Shard | Patterns | Classes |", "|---|---|---:|"]
    for s in shards:
        lines.append(f"| {s['id']} | `{','.join(s['patterns'])}` | {len(by_shard[s['id']])} |")
    lines.append(f"| total | | {len(classes)} |")
    print("\n".join(lines))
    write_summary("### Test shards\n\n" + "\n".join(lines))
    matrix = {"include": [{"shard": s["id"], "tests": ",".join(s["patterns"])} for s in shards]}
    write_output("matrix", json.dumps(matrix, separators=(",", ":")))
    print(f"OK: {len(classes)} test classes, each in exactly one of {len(shards)} shards")


def cmd_verify(shard_id):
    shards = load_shards()
    by_shard, errors = assign(shards, list_test_classes())
    if errors:
        for e in errors:
            print(f"::error::{e}")
        sys.exit(1)
    if shard_id not in by_shard:
        fail(f"unknown shard '{shard_id}'")
    expected = set(by_shard[shard_id])
    reported = {p.name[len("TEST-"):-len(".xml")] for p in REPORTS.glob("TEST-*.xml")}
    missing = sorted(expected - reported)
    extra = sorted(reported - expected)
    totals = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
    for p in REPORTS.glob("TEST-*.xml"):
        root = ET.parse(p).getroot()
        for k in totals:
            totals[k] += int(root.get(k, "0"))
    summary = (
        f"### Shard {shard_id}\n\n"
        f"{len(reported)} of {len(expected)} classes reported; "
        f"tests {totals['tests']}, failures {totals['failures']}, "
        f"errors {totals['errors']}, skipped {totals['skipped']}"
    )
    print(summary)
    write_summary(summary)
    write_output("tests", totals["tests"])
    for c in missing:
        print(f"::error::shard {shard_id}: no Surefire report for {c}")
    for c in extra:
        print(f"::error::shard {shard_id}: ran {c}, which belongs to another shard")
    if missing or extra:
        sys.exit(1)


def main(argv):
    if len(argv) == 2 and argv[1] == "check":
        cmd_check()
    elif len(argv) == 3 and argv[1] == "verify":
        cmd_verify(argv[2])
    else:
        print(__doc__)
        sys.exit(2)


if __name__ == "__main__":
    main(sys.argv)
