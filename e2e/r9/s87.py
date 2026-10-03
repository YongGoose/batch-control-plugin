"""#87 / D-38c: Request only on a folder, only run requests -> activations section 200, no foreign rows."""
import re
from lib import Session, close, log, api
res = {}
r = api("folderreq", "/job/team/job/app-1/batch-control/submit", "POST", data=[("reason", "e2e-09 #87 folderreq run"), ("approvers", "approver-1")])
res["run request"] = (r.status_code, r.headers.get("Location"))
for u in ["folderreq", "opsreq"]:
    s = Session(u)
    resp = s.go("/batch-control/activations/")
    html = s.page.content()
    res[u] = {"status": resp.status, "tabs": [t.strip() for t in s.page.locator("nav[data-batch-control-tabs] a").all_inner_texts()],
              "row ids": sorted(set(re.findall(r"activations/(\d{8}-\d{6}-\w+)/", html))),
              "text": s.text()[:400].replace("\n", " | ")}
    res[u]["foreign detail GET"] = s.go("/batch-control/activations/20261003-175455-hioy7t/").status
    s.go("/batch-control/activations/"); s.shot("#main-panel", f"87-01-{u}-activations")
    s.done()
res["nobc activations"] = api("nobc", "/batch-control/activations/").status_code
close()
for k, v in res.items(): print(k, "=>", v)
log("s87", res)
