"""Arrange windows for mover1 approved by approver-2 (REST): CREATE on ops (active), DELETE on prod/mv-job
(used for the move, then revoked by admin = ended), and a pending 1-minute CONFIGURE request (item 4)."""
import re
from lib import api, log

def ids(user):
    return set(re.findall(r'grants/(\d{8}-\d{6}-\w+)/', api(user, "/batch-control/grants/").text))

def req(user, name, actions, minutes, approver, approve=True):  # D-71: one item
    before = ids(approver)
    data = [("scopeFullName", name), ("durationMinutes", minutes),
            ("reason", f"e2e-10 {actions} on {name}"), ("approvers", approver)] + [("actions", a) for a in actions.split(",")]
    r = api(user, "/batch-control/grants/create", "POST", data=data)
    new = sorted(ids(approver) - before)
    out = {"scope": name, "actions": actions, "create": r.status_code, "loc": r.headers.get("Location"), "ids": new}
    if approve:
        for i in new:
            out["approve"] = api(approver, f"/batch-control/grants/{i}/approve", "POST", data={"comment": "e2e-10"}).status_code
    print(out); log("setup_grants", out)
    return new[0] if new else None

import sys
step = sys.argv[1]
if step == "a":
    req("mover1", "ops", "CREATE", "60", "approver-2")
    req("mover1", "prod/mv-job", "DELETE", "60", "approver-2")
elif step == "b":
    g = sys.argv[2]
    r = api("admin", f"/batch-control/grants/active/{g}/revoke", "POST", data={"comment": "e2e-10 ended"})
    print("revoke", g, r.status_code); log("setup_grants", {"revoke": g, "status": r.status_code})
    req("mover1", "prod/adm-job", "CONFIGURE", "1", "approver-2", approve=False)
