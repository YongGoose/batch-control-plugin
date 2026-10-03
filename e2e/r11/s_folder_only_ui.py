"""R4-15/D-65 in the browser: fonly saves the configuration of ops, ops/sub and ops/mb (allowed), opens ops/sub/b
(refused), and the folder-only window's detail page."""
import re, sys
from lib import Session, close, api, log
res = {}
s = Session("fonly")
for item, n in (("ops", "ops"), ("ops/sub", "sub"), ("ops/mb", "mb"), ("ops/sub/b", "b")):
    path = "".join(f"/job/{p}" for p in item.split("/"))
    r = s.go(path + "/configure")
    out = {"configure_get": r.status}
    if r.status == 200:
        ta = s.page.locator("textarea[name=description], textarea[name='_.description']").first
        ta.fill(f"e2e-11 folder-only save {item}")
        with s.page.expect_navigation() as nav:
            s.page.locator("button[name=Submit]").first.click()
        s.page.wait_for_load_state("load")
        out["save"] = (nav.value.status, s.page.url)
        out["description_now"] = api("admin", path + "/api/json?tree=description").json().get("description")
        if nav.value.status >= 400:
            out["text"] = re.sub(r"\s+", " ", s.text())[:300]
    s.shot("#main-panel" if s.page.locator("#main-panel").count() else "body", f"FO-{n}-configure")
    res[item] = out
s.done(); close(); log("s_folder_only_ui", res)
for k, v in res.items(): print(k, "=>", v)
