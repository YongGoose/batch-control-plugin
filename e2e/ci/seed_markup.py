"""CI seed for the crawl's content checks: user text with HTML markup, filed through the plugin's own endpoints.

A run request (requester, batch-pipeline) and a grant request (requester, the job prod/x) whose reason is PROBE, both
rejected by approver-1 with PROBE as the comment, so the text shows up on the detail pages and in the Ended lists
without changing any pending count. Their ids go into r14/out/ids.json as r_markup / g_markup, so r14/crawl.py
visits their detail pages for every role. On every page, the crawl then expects PROBE to read exactly as typed
(no rendered <b>, no double-escaped &lt;/&amp;) and the <img onerror> canary never to run."""
import json
import pathlib
import re
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent.parent / "r14"))
from lib import api  # noqa: E402
import lib  # noqa: E402

PROBE = 'bc-markup-probe <b>b</b> & "q" <img src=x onerror=window.__bcCanary=1>'
UUID = r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"


def created(r, what):
    m = re.search(UUID, r.headers.get("Location", ""))
    assert r.status_code == 302 and m, (what, r.status_code, r.text[:300])
    return m.group(0)


rid = created(api("requester", "/job/batch-pipeline/batch-control/submit", "POST",
                  data=[("reason", PROBE), ("approvers", "approver-1")]), "run request")
r = api("approver-1", f"/batch-control/requests/{rid}/reject", "POST", data={"comment": PROBE})
assert r.status_code in (200, 302), ("reject run request", r.status_code)
# D-71: the grant form names one item (scopeFullName; no scope type field). If the probe cannot be filed, the run request
# alone carries it and a WARN line says so (the grant drivers report the form itself).
fields = [("scopeFullName", "prod/x"), ("actions", "CONFIGURE"), ("durationMinutes", "15"), ("reason", PROBE), ("approvers", "approver-1")]
r = api("requester", "/batch-control/grants/create", "POST", data=fields)
m = re.search(UUID, r.headers.get("Location", ""))
gid = m.group(0) if r.status_code == 302 and m else None
if gid:
    r = api("approver-1", f"/batch-control/grants/{gid}/reject", "POST", data={"comment": PROBE})
    assert r.status_code in (200, 302), ("reject grant", r.status_code)
else:
    print(f"WARN no grant probe: /batch-control/grants/create answered {r.status_code}", flush=True)
f = lib.HERE / "out" / "ids.json"
ids = json.loads(f.read_text())
ids.update(r_markup=rid, **({"g_markup": gid} if gid else {}))
f.write_text(json.dumps(ids, indent=1))
print(json.dumps({"r_markup": rid, "g_markup": gid, "probe": PROBE}))
