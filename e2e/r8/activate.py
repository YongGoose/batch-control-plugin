"""Activate jobs through the real ACTIVATE request flow over REST: requester submits, approver-1 approves.
usage: activate.py <full name> [...]"""
import re, sys
from lib import api, log

def path(full):
    return "".join(f"/job/{p}" for p in full.split("/"))

def activate(full, user="requester", action="ACTIVATE"):
    r = api(user, path(full) + "/batch-control-activation/submit", "POST",
            data=[("action", action), ("reason", f"e2e-08 {action} {full}"), ("approvers", "approver-1")])
    m = re.search(r"activations/([^/]+)/", r.headers.get("Location", ""))
    out = {"job": full, "submit": r.status_code, "location": r.headers.get("Location")}
    if m:
        a = api("approver-1", f"/batch-control/activations/{m.group(1)}/approve", "POST", data={"comment": "e2e-08"})
        out.update(id=m.group(1), approve=a.status_code)
    log("activate", out); print(out)
    return out

if __name__ == "__main__":
    for j in sys.argv[1:]:
        activate(j)
