"""Item 3 follow-up: More actions on freestyle jobs without builds; build page Rebuild link."""
import re
from lib import Session, close, api, log
res = {}
for user, job, tag in [("requester", "/job/ops/job/job-a/", "4"), ("admin", "/job/ops/job/job-a/", "5-admin"), ("admin", "/job/batch-failing/", "6-admin-uncontrolled")]:
    s = Session(user)
    s.go(job)
    s.page.locator("[data-testid=app-bar-overflow-button]").first.click()
    s.page.wait_for_timeout(1500)
    res[f"{user} {job}"] = {"menu": [re.sub(r"\s+", " ", i.inner_text()).strip() for i in s.page.locator(".tippy-box a, .tippy-box button").all()],
                            "console": [c[:140] for c in s.console if "MIME" not in c],
                            "builds": api("admin", job + "api/json?tree=builds%5Bnumber%5D").json().get("builds")}
    s.shot(s.page.locator(".tippy-box").first if s.page.locator(".tippy-box").count() else "#main-panel", f"R{tag}-01-menu")
    s.done()
for user in ["requester", "admin"]:
    s = Session(user)
    r = s.go("/job/batch-daily/1/")
    links = [(re.sub(r"\s+", " ", a.inner_text()).strip(), a.get_attribute("href")) for a in s.page.locator("a").all() if "rebuild" in (a.get_attribute("href") or "").lower()]
    res[f"{user} build page #1"] = {"status": r.status, "rebuild_links": links, "rebuild_url": api(user, "/job/batch-daily/1/rebuild/").status_code}
    s.shot("#main-panel", f"R7-{user}-build-page")
    s.done()
close()
log("rebuild2", res)
for k, v in res.items(): print(k, v)
