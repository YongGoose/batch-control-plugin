#!/usr/bin/env python3
"""Self-test of the crawl's content checks (r14/crawl.py), no Jenkins needed: runs the real CONTENT_JS and the real
content_checks() (taken from crawl.py's source, its module code is not executed) in headless Chromium against two
synthetic pages. The "defects" page plants one instance of every finding the checks must report, next to look-alikes
that must stay silent (text in <pre>, a URL path, an optional empty column); the "clean" page renders the markup probe
correctly and must produce no defect finding. The raw-enum INFO rows (e2e-16) are compared too (EXPECTED_ENUM). Exit 0 when both pages give exactly the expected findings.

    python e2e/ci/selftest_content.py          (BC_BROWSER_CHANNEL as for the drivers; default chromium here)
"""
import ast
import html
import json
import os
import re
import sys
from pathlib import Path

from playwright.sync_api import sync_playwright

CRAWL = Path(__file__).resolve().parent.parent / "r14" / "crawl.py"
WANTED = {"CONTENT_JS", "EXPECTED_COLUMNS", "PROBE_AS_TYPED", "_content_seen", "content_checks"}


def load():
    tree = ast.parse(CRAWL.read_text())
    nodes = [n for n in tree.body if (isinstance(n, ast.Assign) and any(getattr(t, "id", None) in WANTED for t in n.targets))
             or (isinstance(n, ast.FunctionDef) and n.name in WANTED)]
    rows = []
    # emit() rows are collected; the FAIL lines that content_checks() prints are swallowed (the verdict is this script's)
    ns = {"json": json, "re": re, "ROLE": "selftest", "UI": "page", "emit": rows.append, "print": lambda *a, **k: None}
    exec(compile(ast.Module(body=nodes, type_ignores=[]), str(CRAWL), "exec"), ns)
    missing = WANTED - set(ns)
    if missing:
        raise SystemExit(f"crawl.py no longer defines {sorted(missing)}")
    return ns, rows


PROBE = 'bc-markup-probe <b>b</b> & "q" <img src=x onerror=window.__bcCanary=1>'
PROBE_HTML = html.escape(PROBE, quote=False)
DEFECTS = """<div id="main-panel"><h2>Requests</h2>
<table><thead><tr><th>ID</th><th>Job</th><th>Comment</th></tr></thead>
<tbody><tr><td>r-1</td><td></td><td></td></tr></tbody></table>
<table><tbody><tr><th>Requester</th><td> </td></tr><tr><th>Decided by</th><td></td></tr></tbody></table>
<p>Store: /var/jenkins_home/batch-control/requests</p>
<p>failed: StoreLocation=batch-control</p>
<p>name &amp;lt;b&amp;gt; shown escaped twice</p>
<p>&lt;script&gt;alert(1)&lt;/script&gt;</p>
<p>bc-markup-probe <b>b</b> &amp; "q" <img src=x onerror=window.__bcCanary=1></p>
<pre>/var/log/in-a-pre-block-is-fine</pre>
<p>Grant g-1 for user 'u' (ITEM:a/b) ended; type GRANT_REVOKE, status PENDING</p>
<table><tbody><tr><th>Scope</th><td>FOLDER_ONLY: team</td></tr></tbody></table>
<p><a href="/jenkins/job/batch-daily/">/jenkins/job/batch-daily/</a></p>
</div>"""
CLEAN = f"""<div id="main-panel"><h2>Requests</h2>
<table><thead><tr><th>ID</th><th>Job</th><th>Comment</th></tr></thead>
<tbody><tr><td>r-1</td><td>batch-daily</td><td></td></tr></tbody></table>
<p>Reason: {PROBE_HTML}</p><p>Comment: {html.escape(PROBE[:30], quote=False)}…</p>
<p>Jobs under /jenkins/job/ops/ and the folder ops/sub; 3 &lt; 5 &amp; 7 &gt; 2</p>
<code>/tmp/in-code-is-fine GRANT_REVOKE ITEM:a/b</code>
<p>Status: Approved; Folder: team; JOB_NAME is not shown as code here but MIXED_case and lower_snake are not constants</p></div>"""
EXPECTED_DEFECTS = {("empty-cell", "Requests / Job"), ("empty-cell", "Requests / Requester"),
                    ("server-path", "/var/jenkins_home/batch-control/requests"), ("server-path", "StoreLocation="),
                    ("double-escaped", "&lt;"), ("markup-text", "<script"),
                    ("markup-live", "probe <img onerror> rendered as markup"), ("markup-live", "probe text not as typed"),
                    ("legacy-scope", "FOLDER_ONLY:")}
EXPECTED_INFO = {"Comment", "Decided by"}
# raw-enum INFO rows (e2e-16): planted on the defects page; the clean page has look-alikes that must stay silent except
# JOB_NAME, a real UPPER_SNAKE token in visible text (the check cannot tell a parameter name from an enum: INFO only).
EXPECTED_ENUM = {"defects": {"ITEM:", "GRANT_REVOKE", "PENDING", "FOLDER_ONLY"}, "clean": {"JOB_NAME"}}


def main():
    channel = os.environ.get("BC_BROWSER_CHANNEL", "chromium")
    ok = True
    with sync_playwright() as pw:
        b = pw.chromium.launch(channel=None if channel in ("", "chromium") else channel)
        for name, html, want, want_info in (("defects", DEFECTS, EXPECTED_DEFECTS, EXPECTED_INFO), ("clean", CLEAN, set(), {"Comment"})):
            ns, rows = load()
            page = b.new_page()
            page.set_content(html)
            page.wait_for_timeout(300)  # let the probe's <img onerror> fire
            ns["content_checks"](type("S", (), {"page": page})(), "/batch-control/requests/", "/batch-control/requests/")
            got = {(r["check"], r["key"]) for r in rows if r["result"] == "DEFECT"}
            info = {r["key"] for r in rows if r["check"] == "empty-optional"}
            enums = {r["key"] for r in rows if r["check"] == "raw-enum"}
            errors = [r for r in rows if r["result"] == "ERROR"]
            good = got == want and info == want_info and enums == EXPECTED_ENUM[name] and not errors
            ok &= good
            print(f"{'PASS' if good else 'FAIL'} selftest {name}: {len(got)} defect finding(s), empty-optional {sorted(info)}, "
                  f"raw-enum {sorted(enums)}")
            for x in sorted(EXPECTED_ENUM[name] ^ enums):
                print(f"   raw-enum mismatch: {x}")
            for x in sorted(want - got):
                print(f"   missed:     {x}")
            for x in sorted(got - want):
                print(f"   unexpected: {x}")
            for r in errors:
                print(f"   error:      {r}")
            page.close()
        b.close()
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
