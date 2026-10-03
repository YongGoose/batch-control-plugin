"""e2e-14 diagnostic: side-panel tasks on Batch Control job sub-pages for a new-job-page user vs a classic user
(the crawl saw "Permalinks"/"Delete Pipeline" entries with an empty href that do nothing when clicked)."""
import json, sys
from lib import Session, close, log, groovy
JS = """() => [...document.querySelectorAll('#side-panel a, #side-panel button')].filter(a => /Permalinks|Delete/.test(a.innerText))
  .map(a => ({text: a.innerText.trim(), href: a.getAttribute('href'), cls: a.className, data: Object.assign({}, a.dataset)}))"""
for flag in ("true", "false"):
    groovy("""def u = hudson.model.User.getById('admin', true); def m = new HashMap(); m.put('new-job-page.flag', '%s')
u.addProperty(new jenkins.model.experimentalflags.UserExperimentalFlagsProperty(m)); u.save(); return 'ok'""" % flag)
    s = Session("admin")
    for p in ("/job/batch-pipeline/batch-control-activation/", "/job/batch-pipeline/batch-control/", "/job/batch-daily/batch-control-activation/"):
        s.go(p)
        els = s.page.evaluate(JS)
        row = {"new_job_page": flag, "page": p, "entries": els}
        perm = s.page.locator("#side-panel a", has_text="Permalinks")
        if perm.count():
            before = s.page.url
            perm.first.click(); s.page.wait_for_timeout(1500)
            row["permalinks_click"] = {"url_changed": s.page.url != before, "dialog": s.page.locator("dialog[open]").count(),
                                       "menu": s.page.locator(".tippy-box").count()}
            s.shot("#side-panel", f"SP-{flag}-{p.strip('/').replace('/', '_')}")
        log("sidepanel", row)
        print(json.dumps(row)[:900])
    s.done()
close()
