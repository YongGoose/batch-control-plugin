"""Summarises out/crawl.jsonl: problems first (pages >=400 or with console errors, broken links, dead hrefs,
buttons that do nothing, dialogs that do not close), then counts per kind/result."""
import json, sys, collections
rows = [json.loads(l) for l in open(sys.argv[1] if len(sys.argv) > 1 else "out/crawl.jsonl")]
flt = sys.argv[2:]  # role/ui filters
rows = [r for r in rows if not flt or r["role"] in flt or r["ui"] in flt]
cnt = collections.Counter()
for r in rows:
    cnt[(r["kind"], str(r.get("result")))] += 1
    bad = False
    if r["kind"] == "page" and ((r.get("status") or 0) >= 400 or r.get("console") or r.get("bad_subrequests")):
        bad = True
    if r.get("result") in ("BROKEN", "DEAD-HREF", "NOTHING", "click-failed", "load-error"):
        bad = True
    d = r.get("detail") or {}
    if isinstance(d, dict) and (d.get("console") or d.get("escape_closes") is False or d.get("cancel_closes") is False):
        bad = True
    if bad:
        print(r["role"], r["ui"], r["page"], r["kind"], r.get("label", r.get("h1")), r.get("target", ""), r.get("status", r.get("status_get", "")), r.get("result", ""), json.dumps({k: r[k] for k in ("console", "bad_subrequests", "detail", "final") if r.get(k)})[:400])
print()
for k, v in sorted(cnt.items()):
    print(v, k)
