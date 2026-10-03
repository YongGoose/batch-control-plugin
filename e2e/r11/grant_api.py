"""Arrangement: file a grant request over HTTP (the dialog itself is covered by s_dialogs/s_classic) and approve it.
usage: grant_api.py <user> <scopeType> <scopeFullName> <minutes> <ACTION>[,<ACTION>...]"""
import re, sys
from lib import api
u, st, sf, mins, acts = sys.argv[1:6]
h = api(u, "/batch-control/grants/dialog?scopeFullName=" + sf).text
action = re.search(r'<form[^>]*action="([^"]+)"', h).group(1).replace("/jenkins", "", 1)
data = [("scopeType", st), ("scopeFullName", sf), ("durationMinutes", mins), ("reason", "e2e-11 arrange"), ("approvers", "approver-1")]
data += [("actions", a) for a in acts.split(",")]
r = api(u, action, "POST", data=data)
loc = r.headers.get("Location", "")
gid = re.search(r"/grants/([^/]+)/", loc)
print("submit", action, r.status_code, loc)
if gid:
    r2 = api("approver-1", f"/batch-control/grants/{gid.group(1)}/approve", "POST", data={"comment": "arrange"})
    print("approve", r2.status_code, gid.group(1))
