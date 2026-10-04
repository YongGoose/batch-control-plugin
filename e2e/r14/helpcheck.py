"""e2e-12: every help '?' button on the Batch Control forms: its helpURL answers 200 with text, and a click
expands the help area with that text. Pages: configuration, job configure (job property), request forms."""
import json, re
from lib import Session, close, BASE
import lib
PAGES = ["/manage/batch-control-configuration/", "/job/batch-daily/configure", "/job/batch-daily/batch-control/",
         "/job/batch-cron/batch-control-activation/", "/batch-control/grants/new", "/job/ops/job/mb/configure"]
s = Session("admin")
for page in PAGES:
    s.go(page)
    helps = s.page.locator("a.jenkins-help-button, a[helpurl], .jenkins-help-button")
    rows = []
    for i in range(helps.count()):
        h = helps.nth(i)
        url = h.get_attribute("helpurl") or h.get_attribute("helpURL")
        if not url or "batchcontrol" not in url.lower() and "batch" not in url.lower():
            continue
        r = s.context.request.get(BASE.split("/jenkins")[0] + url if url.startswith("/") else url)
        body = re.sub(r"<[^>]+>", " ", r.text())
        clicked = None
        try:
            if h.is_visible():
                h.click(); s.page.wait_for_timeout(700)
                area = h.locator("xpath=ancestor::*[contains(@class,'jenkins-form-item') or contains(@class,'setting-main') or self::tr][1]").locator(".help-area .help, .help")
                clicked = area.first.is_visible() if area.count() else "no-help-area"
        except Exception as e:  # noqa
            clicked = "click-error " + str(e)[:60]
        rows.append({"url": url.replace("/jenkins", ""), "status": r.status, "chars": len(body.strip()), "expands": clicked})
    lib.log("help", {"page": page, "helps": rows, "console": [c for c in s.console if "MIME" not in c][:3]})
    print(page, len(rows), [x for x in rows if x["status"] != 200 or x["chars"] < 20 or x["expands"] not in (True,)])
s.done(); close()
