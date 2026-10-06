"""#73: Request Change Permission link from a job reached through a view, classic side panel and new job page menu."""
import re
from lib import Session, close, log, groovy, SHOTS
res = {}
res["arrange"] = groovy('''
import hudson.model.*
def j = jenkins.model.Jenkins.get()
def v = j.getView("nightly")
if (v == null) { v = new ListView("nightly"); j.addView(v) }
v.setRecurse(true); v.add(j.getItemByFullName("batch-daily")); v.add(j.getItemByFullName("team/app-1")); v.save()
return v.getItems()*.fullName
''')
def check(s, tag, open_link):
    for sid, path, full in [("a", "/view/nightly/job/batch-daily/", "batch-daily"), ("b", "/view/nightly/job/team/job/app-1/", "team/app-1")]:
        s.go(path)
        href = open_link(s, f"{tag}{sid}")
        s.page.wait_for_load_state("load")
        url = s.page.url.replace("http://localhost:8080", "")
        st = s.page.locator("[data-batch-control-item-kind]")
        res[f"{tag}{sid} {path}"] = {"href": href, "landed": url,
            "itemKind": st.first.get_attribute("data-batch-control-item-kind") if st.count() else None,
            "scopeFullName": s.page.locator("input[name=scopeFullName]").input_value() if st.count() else None}
        s.shot("form", f"73{tag}{sid}-02-grant-form")
def classic(s, n):
    a = s.page.locator("#tasks a", has_text="Request Change Permission").first
    s.shot("#side-panel", f"73{n}-01-side-panel")
    href = a.get_attribute("href"); a.click(); return href
def newpage(s, n):
    s.page.locator("[data-testid=app-bar-overflow-button]").first.click()
    item = s.page.locator(".jenkins-dropdown a, .tippy-box a", has_text="Request Change Permission").first
    item.wait_for()
    s.shot(".tippy-box, .jenkins-dropdown", f"73{n}-01-menu")
    href = item.get_attribute("href"); item.click(); return href
s = Session("requester")   # classic (new job page Disabled by s72b)
s.go("/user/requester/experiments/")
s.page.locator("tr", has_text="new-job-page.flag").locator("select").select_option(label="Disabled")
s.page.click("button[name=Submit]"); s.page.wait_for_load_state("load")
check(s, "classic-", classic)
s.go("/user/requester/experiments/")
s.page.locator("tr", has_text="new-job-page.flag").locator("select").select_option(index=0)
s.page.click("button[name=Submit]"); s.page.wait_for_load_state("load")
s.done()
n = Session("requester")   # default: new job page on
check(n, "new-", newpage)
n.done(); close()
for k, v in res.items(): print(k, "=>", v)
log("s73", res)
