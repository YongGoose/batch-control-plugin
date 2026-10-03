"""Arrangement: approve grant requests over HTTP as approver-1 (the browser approval is covered in s_approve.py)."""
import sys
from lib import api
for gid in sys.argv[1:]:
    r = api("approver-1", f"/batch-control/grants/{gid}/approve", "POST", data={"comment": "e2e-11 arrange"})
    print(gid, r.status_code, r.headers.get("Location"))
