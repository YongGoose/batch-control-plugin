"""R4-12: the revoke endpoint behind the detail page's Revoke button, POSTed by the holder (no Manage) and by an approver."""
import re, sys
from lib import api, log, clean
gid = sys.argv[1]
h = api("manager", f"/batch-control/grants/{gid}/").text
btn = re.search(r'<a [^>]*confirmation-link[^>]*>', h)
tag = btn.group(0) if btn else None
url = re.search(r'data-url="([^"]+)"', tag or "")
res = {"button_tag": (tag or "")[:300], "endpoint": url.group(1) if url else None}
ep = res["endpoint"]
if ep:
    ep = ep.replace("/jenkins", "", 1) if ep.startswith("/jenkins") else f"/batch-control/grants/{gid}/" + ep
    for u in ("classic", "approver-1", "requester"):
        r = api(u, ep, "POST")
        res[f"POST {u}"] = (r.status_code, clean(r.text)[:160] if r.status_code >= 400 else r.headers.get("Location"))
    res["GET manager"] = api("manager", ep).status_code
res["holder_configure_still"] = api("classic", "/job/batch-pipeline/configure").status_code
log("s_revoke_holder", res)
for k, v in res.items(): print(k, "=>", v)
