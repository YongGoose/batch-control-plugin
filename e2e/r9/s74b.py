import re
from lib import api, clean, log
res = {}
before = api("admin", "/batch-control/changes/").text
for d in ["/ops", "/no-such-folder", "/team"]:
    r = api("moverd", "/job/prod/job/x/move/move", "POST", data={"destination": d})
    res[d] = {"status": r.status_code, "location": r.headers.get("Location"), "bc_wording": bool(re.search(r"Batch Control|permission window", r.text)), "body": clean(r.text)[:260]}
res["x at source"] = api("admin", "/job/prod/job/x/api/json").status_code
res["x at ops"] = api("admin", "/job/ops/job/x/api/json").status_code
after = api("admin", "/batch-control/changes/").text
ids = lambda h: set(re.findall(r"changes/([\w-]+)/", h))
res["new change records"] = sorted(ids(after) - ids(before))
for k, v in res.items(): print(k, "=>", v)
log("s74b", res)
