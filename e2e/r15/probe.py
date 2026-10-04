"""e2e-15 diagnostic: the text-less main-panel anchor, the app-bar build control on job sub-pages (core's own
/changes sub-page as the comparison), and on which pages core's MIME console line appears (new job page user)."""
import json
from lib import Session, close, log
s = Session("requester")
for path in ["/job/batch-pipeline/batch-control-activation/", "/job/batch-pipeline/changes", "/job/batch-pipeline/batch-control/",
             "/job/batch-pipeline/", "/job/team-mb/job/main/batch-control-activation/", "/job/team-mb/job/main/changes", "/job/team-mb/job/main/",
             "/job/team-mb/batch-control-activation/"]:
    s.console.clear(); s.bad.clear()
    s.go(path); s.page.wait_for_timeout(800)
    info = s.page.evaluate("""() => ({
      noHref: [...document.querySelectorAll('#main-panel a:not([href])')].map(a => a.outerHTML.slice(0, 200)),
      build: [...document.querySelectorAll('a, button')].filter(a => /Build Now|Direct Build/.test(a.innerText)).map(a => a.outerHTML.slice(0, 400))})""")
    row = {"page": path, **info, "console": list(s.console), "bad": list(s.bad)}
    btn = s.page.locator("#main-panel a, #main-panel button, .jenkins-app-bar a").filter(has_text="Direct Build")
    if not btn.count():
        btn = s.page.locator("#main-panel a, #main-panel button").filter(has_text="Build Now")
    if btn.count() and "batch-control" in path or (btn.count() and path.endswith("changes")):
        before = s.page.url
        s.bad.clear()
        btn.first.click(); s.page.wait_for_timeout(2500)
        row["build_click"] = {"url": s.page.url, "url_changed": s.page.url != before, "dialog": s.page.locator("dialog[open]").count(),
                              "toast": s.page.locator("#notification-bar, .jenkins-notification").first.inner_text() if s.page.locator("#notification-bar, .jenkins-notification").count() else None,
                              "bad": list(s.bad)}
        s.shot(["#main-panel"], "P-build-click-" + path.strip('/').replace('/', '_'))
    log("probe", row)
    print(json.dumps(row)[:1800])
s.done(); close()
