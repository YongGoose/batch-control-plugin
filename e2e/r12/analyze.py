"""Condensed view of out/crawl.jsonl without the known false positives:
core help '?' links (checked by helpcheck*.py), POST-only core links answering 405 on GET (build, rebuild,
toggleCollapse), pages forced from the start list that the role may not open (listed separately)."""
import json, sys, collections, re
rows = [json.loads(l) for l in open("out/crawl.jsonl")]
FP405 = re.compile(r"/build\b|/build\?|rebuild|toggleCollapse|buildWithParameters")
forced = collections.defaultdict(list)
issues = collections.OrderedDict()
for r in rows:
    k = None
    if r["kind"] == "page":
        st = r.get("status") or 0
        if st >= 400:
            forced[(r["page"].split("?")[0])].append(f'{r["role"]}/{r["ui"]}:{st}')
            continue
        cons = [c for c in r.get("console", []) if "403" not in c and "404" not in c]
        if cons or r.get("bad_subrequests"):
            k = ("page-console", r["page"], json.dumps(cons or r.get("bad_subrequests"))[:200])
    elif r.get("result") == "DEAD-HREF":
        if r.get("label") in ("?",):
            continue
        k = ("dead-href", r["page"].split("?")[0], r.get("label"), r.get("region"))
    elif r.get("result") == "BROKEN":
        if r.get("status") == 405 and FP405.search(r.get("target", "")):
            continue
        k = ("broken-link", re.sub(r"[0-9a-f-]{36}", "{id}", r["page"]), r.get("label"), re.sub(r"[0-9a-f-]{36}", "{id}", r.get("target", "")), r.get("status"))
    elif r["kind"] == "click":
        d = r.get("detail") or {}
        cons = [c for c in d.get("console", []) if "Jumplist request failed" not in c] if isinstance(d, dict) else []
        if r.get("result") in ("NOTHING", "click-failed") or cons or (isinstance(d, dict) and (d.get("escape_closes") is False or d.get("cancel_closes") is False)):
            k = ("click", r["page"].split("?")[0], r.get("label"), r.get("result"), json.dumps({x: d.get(x) for x in ("console", "escape_closes", "cancel_closes", "err") if isinstance(d, dict) and d.get(x) is not None})[:200])
    elif r["kind"] == "post-form" and r.get("result") == "BROKEN":
        k = ("post-form", r["page"], r.get("label"), r.get("target"), r.get("status_get"))
    if k:
        issues.setdefault(k, set()).add(f'{r["role"]}/{r["ui"]}')
for k, v in issues.items():
    print(" | ".join(str(x) for x in k), "<-", ",".join(sorted(v)))
print("\nFORCED (start-list pages the role cannot open):")
for k, v in forced.items():
    print(" ", k, v[:8])
c = collections.Counter()
for r in rows:
    c[(r["kind"], r.get("result") if r["kind"] != "page" else ("ok" if (r.get("status") or 0) < 400 else "refused"))] += 1
print(c)
