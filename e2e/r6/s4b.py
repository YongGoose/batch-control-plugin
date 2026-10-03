"""Scenario 4b: new job page, Direct Build (needs approval) -> parameters dialog -> Build -> pre-filled Request Run."""
import sys
from lib import Session, close, api, log, SHOTS
user = sys.argv[1] if len(sys.argv) > 1 else "requester"
tag = sys.argv[2] if len(sys.argv) > 2 else "S4b"
r = {"user": user}
q0 = api("admin", "/queue/api/json").json()["items"]
b0 = api("admin", "/job/batch-daily/api/json?tree=nextBuildNumber").json()["nextBuildNumber"]
s = Session(user)
navs = []
s.page.on("framenavigated", lambda f: navs.append(f.url) if f == s.page.main_frame else None)
s.go("/job/batch-daily/")
btn = s.page.locator("a, button", has_text="Direct Build").first
r["button"] = btn.inner_text().strip() if btn.count() else None
btn.click()
s.page.wait_for_timeout(1500)
d = s.page.locator("dialog[open]")
r["dialog"] = d.count()
if d.count():
    dd = d.first
    r["dialog_text"] = dd.inner_text()[:500]
    t = dd.locator("input[name='value']")
    print("inputs:", [(i.get_attribute("name"), i.get_attribute("type")) for i in dd.locator("input, select").all()])
    texts = dd.locator("input[type=text][name=value], input:not([type])[name=value]")
    if texts.count():
        texts.first.fill("2026-10-03")
    sel = dd.locator("select[name=value]")
    if sel.count():
        opts = sel.first.locator("option").all_inner_texts()
        r["mode_options"] = opts
        sel.first.select_option(label=opts[-1])
        r["mode_chosen"] = opts[-1]
    pw = dd.locator("input[type=password]")
    if pw.count():
        pw.first.fill("s3cret-e2e")
    s.shot(dd, f"{tag}-01-parameters-dialog")
    dd.locator("button", has_text="Build").last.click()
    s.page.wait_for_timeout(2500)
    s.page.wait_for_load_state("load")
r["final_url"] = s.page.url
r["navigations"] = navs
d2 = s.page.locator("dialog[open]")
r["dialog_still_open"] = d2.count()
if d2.count():
    r["dialog_after_text"] = d2.first.inner_text()[:600]
    s.shot(d2.first, f"{tag}-02-dialog-after-build")
else:
    r["heading"] = s.page.locator("h1").first.inner_text() if s.page.locator("h1").count() else None
    vals = {}
    for i in s.page.locator("#main-panel input, #main-panel select, #main-panel textarea").all():
        n = i.get_attribute("name")
        try:
            vals[n] = i.input_value()
        except Exception:
            pass
    r["form_values"] = {k: v for k, v in vals.items() if k and k not in ("Jenkins-Crumb", "json")}
    r["classic_build_form"] = s.page.locator("form[action='build'], form[action$='/build']").count()
    r["text"] = s.text()[:700]
    s.page.screenshot(path=str(SHOTS / f"{tag}-02-after-build-page.png"), full_page=True)
    s.shot("#main-panel", f"{tag}-02-after-build")
q1 = api("admin", "/queue/api/json").json()["items"]
b1 = api("admin", "/job/batch-daily/api/json?tree=nextBuildNumber").json()["nextBuildNumber"]
r["queue_before_after"] = (len(q0), len(q1))
r["nextBuildNumber_before_after"] = (b0, b1)
r["console"] = s.console
r["bad"] = s.bad
s.done(); close()
log("s4b", r)
for k, v in r.items():
    print(f"{k}: {v}")
