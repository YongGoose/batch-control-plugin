"""Request a change-permission window over REST and have approver-1 approve it.
usage: grants.py <user> <fullName> <ACTION[,ACTION]> [minutes]   (D-71: one item, no scope type)"""
import re
import sys
from lib import api, log

def ids(user):
    r = api(user, "/batch-control/grants/")
    return set(re.findall(r'grants/([0-9a-f]{8}-[0-9a-f-]{27}|\d{8}-\d{6}-\w+)/', r.text))

def grant(user, name, actions, minutes="60"):
    before = ids("approver-1")
    data = [("scopeFullName", name), ("durationMinutes", minutes),
            ("reason", f"e2e-10 {user} {actions} on {name}"), ("approvers", "approver-1")]
    data += [("actions", a) for a in actions.split(",")]
    r = api(user, "/batch-control/grants/create", "POST", data=data)
    new = ids("approver-1") - before
    out = {"user": user, "scope": name, "actions": actions, "create": r.status_code, "ids": sorted(new)}
    for i in new:
        a = api("approver-1", f"/batch-control/grants/{i}/approve", "POST", data={"comment": "e2e-10"})
        out["approve"] = a.status_code
    log("grants", out)
    print(out)
    return out

if __name__ == "__main__":
    grant(*sys.argv[1:])
