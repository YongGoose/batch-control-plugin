"""SUPERSEDED by D-71 (e2e-16): the FOLDER_ONLY scope type is withdrawn. A window now names exactly one item
(no CREATE+CONFIGURE+DELETE "folder-only" reach): a CONFIGURE window on a folder covers the folder itself only, a
CREATE window creates directly inside it only, and DELETE on a folder is refused at submission. The live, D-71-correct
checks are in e2e/r16/items.py (sec_F folder CONFIGURE/CREATE, sec_D folder DELETE refused) and e2e/r14/round3.py
sec_A. This file is kept for provenance of the e2e-11 (D-65) pass and is not part of the CI shard set.

Original intent (D-65): what a FOLDER_ONLY window on `ops` (CREATE+CONFIGURE+DELETE, holder fonly) allowed, over HTTP
as fonly. fonly holds Item/Read+Move+RequestGrant globally and a standing Item/Create on prod (move destination)."""
from lib import api, log
res = {}
def st(r):
    return r.status_code
J = lambda full: "".join(f"/job/{p}" for p in full.split("/"))
for item in ("ops", "ops/a", "ops/sub", "ops/sub/b", "ops/mb"):
    res[f"GET configure {item}"] = st(api("fonly", J(item) + "/configure"))
    res[f"POST submitDescription {item}"] = st(api("fonly", J(item) + "/submitDescription", "POST", data={"description": "e2e-11 folder-only"}))
for parent, name in (("ops", "fo-new"), ("ops/sub", "fo-nested"), ("", "fo-root")):
    base = J(parent) if parent else ""
    r = api("fonly", base + f"/createItem?name={name}&mode=hudson.model.FreeStyleProject", "POST", headers={"Content-Type": "application/x-www-form-urlencoded"})
    res[f"create {parent or '(root)'}/{name}"] = (st(r), api("admin", (base + f"/job/{name}") + "/api/json").status_code)
for item in ("ops/del-me", "ops/sub", "ops/mb", "ops", "ops/sub/b"):
    r = api("fonly", J(item) + "/doDelete", "POST")
    res[f"delete {item}"] = (st(r), api("admin", J(item) + "/api/json").status_code)
log("s_folder_only", res)
for k, v in res.items(): print(k, "=>", v)
