"""Item 3 (DEF-06): rebuild plugin + new job page. More actions on an approval-required job with a completed
build (batch-daily) and on one without (batch-pipeline); run Rebuild / Rebuild Last; check refusal + record."""
import re, sys, time
from lib import Session, close, api, log
res = {}
import atexit
atexit.register(lambda: [print(k, v) for k, v in res.items()])

def menu(user, job, tag):
    s = Session(user)
    s.go(job)
    s.page.locator("[data-testid=app-bar-overflow-button]").first.click()
    s.page.wait_for_timeout(1500)
    items = [re.sub(r"\s+", " ", i.inner_text()).strip() for i in s.page.locator(".tippy-box a, .tippy-box button").all()]
    rec = {"menu": items, "console": [c[:140] for c in s.console if "MIME" not in c]}
    s.shot(s.page.locator(".tippy-box").first if s.page.locator(".tippy-box").count() else "#main-panel", f"R{tag}-01-menu")
    return s, rec

def nb(job):
    return api("admin", job + "api/json?tree=nextBuildNumber,inQueue").json()

def blocked_records():
    t = api("admin", "/batch-control/history/changes.csv").text
    return [l for l in t.splitlines() if ",TRIGGER_BLOCKED," in l and "REBUILD" in l]

# with a completed build
before = nb("/job/batch-daily/"); rb0 = len(blocked_records())
for entry in ["Rebuild Last", "Rebuild"]:
    s, rec = menu("requester", "/job/batch-daily/", "1-" + entry.replace(" ", ""))
    e = s.page.locator(".tippy-box a, .tippy-box button").filter(has_text=re.compile("^\\s*" + entry + "\\s*$")).first
    rec["entry_found"] = e.count()
    if e.count():
        rec["href"] = e.get_attribute("href")
        try:
            with s.page.expect_navigation(timeout=8000):
                e.click()
            s.page.wait_for_load_state("load")
        except Exception as ex:
            rec["nav"] = "no navigation: " + str(ex)[:60]
            s.page.wait_for_timeout(2500)
        rec["landed"] = s.page.url.replace("http://localhost:8080", "")
        rec["text"] = re.sub(r"\s+", " ", s.text())[:400]
        s.shot("#main-panel" if s.page.locator("#main-panel").count() else "body", f"R1-{entry.replace(' ', '')}-02-after-click")
        # submit the rebuild parameters page if one opened
        btn = s.page.locator("button[name=Submit], #yui-gen1-button, button:has-text('Rebuild')").last
        if "rebuild" in s.page.url and btn.count():
            try:
                with s.page.expect_navigation(timeout=10000) as nav:
                    btn.click()
                s.page.wait_for_load_state("load")
                rec["submit_status"] = nav.value.status
            except Exception as ex:
                rec["submit"] = "no navigation: " + str(ex)[:60]
                s.page.wait_for_timeout(2000)
            rec["after_submit_url"] = s.page.url.replace("http://localhost:8080", "")
            rec["after_submit_text"] = re.sub(r"\s+", " ", s.text())[:400]
            s.shot("#main-panel" if s.page.locator("#main-panel").count() else "body", f"R1-{entry.replace(' ', '')}-03-after-submit")
        rec["toasts"] = s.page.locator(".jenkins-notification, #notification-bar").all_inner_texts()
    rec["console"] = [c[:140] for c in s.console if "MIME" not in c]
    res["with_build " + entry] = rec
    s.done()
time.sleep(2)
after = nb("/job/batch-daily/")
res["batch-daily nextBuildNumber"] = (before, after)
res["new REBUILD records"] = blocked_records()[: max(0, len(blocked_records()) - rb0)] if len(blocked_records()) > rb0 else []
# without a completed build
s, rec = menu("requester", "/job/batch-pipeline/", "2")
res["no_build batch-pipeline"] = rec
s.done()
s, rec = menu("admin", "/job/batch-pipeline/", "3-admin")
res["no_build batch-pipeline admin"] = rec
s.done()
close()
log("rebuild", res)
