"""e2e-15: the new job page app-bar "More actions" menu on the job-subpage activation pages vs the same menu on the
job's own page (requester, admin; new job page on). Items: text, tag, href; duplicates; empty-href anchors; each
URL-less item clicked (Permalinks must open its submenu, Request Change Permission its dialog).
Usage: menu.py [tag]   -> out/menu.jsonl, run-15/M-<tag>-<user>-<page>.png"""
import json, sys
from urllib.parse import urljoin
from lib import Session, close, log, groovy, gone, react, until
TAG = sys.argv[1] if len(sys.argv) > 1 else "a"
JOBS = ["/job/team-mb/job/main/", "/job/team-mb/job/feature-1/", "/job/batch-pipeline/", "/job/batch-daily/"]
ITEMS = """() => [...document.querySelectorAll('.tippy-box .jenkins-dropdown__item')].map(i => ({text: i.innerText.trim(),
  tag: i.tagName.toLowerCase(), href: i.getAttribute('href'), cls: i.className}))"""
print(groovy("""import jenkins.model.experimentalflags.*
['requester','admin'].each { id -> def u = hudson.model.User.getById(id, true); def m = new HashMap(); m.put('new-job-page.flag', 'true')
u.addProperty(new UserExperimentalFlagsProperty(m)); u.save() }; return 'ok'"""))


def open_menu(s):
    p = s.page
    btn = p.get_by_test_id("app-bar-overflow-button")
    try:
        btn.first.wait_for(state="visible", timeout=10000)
    except Exception:  # noqa: BLE001
        return None
    p.wait_for_load_state("networkidle")
    btn.first.click()
    try:
        p.wait_for_selector(".tippy-box .jenkins-dropdown__item", timeout=6000)
    except Exception:  # noqa: BLE001
        return []
    return p.evaluate(ITEMS)


for user in ("requester", "admin"):
    s = Session(user)
    for job in JOBS:
        for page in (job, job + "batch-control-activation/"):
            s.console.clear(); s.bad.clear()
            s.go(page)  # e2e-20: open_menu() waits for the button and the network (a fixed 0.6 s was here)
            items = open_menu(s)
            row = {"user": user, "page": page, "items": items}
            if items:
                texts = [i["text"] for i in items]
                row["duplicates"] = sorted({t for t in texts if texts.count(t) > 1})
                row["empty_href_anchors"] = [i["text"] for i in items if i["tag"] == "a" and not (i["href"] or "").strip()]
                row["status"] = {}
                for i in items:
                    if i["href"]:
                        row["status"][i["text"]] = s.page.request.get(urljoin(s.page.url, i["href"]), max_redirects=5).status
                s.shot(".tippy-box", f"M-{TAG}-{user}-{page.strip('/').replace('job/', '').replace('/', '_')}")
                row["clicks"] = {}
                for t in [i["text"] for i in items if not i["href"]]:
                    s.page.keyboard.press("Escape"); gone(s.page, ".tippy-box")
                    s.go(page)
                    again = open_menu(s)
                    before_boxes = s.page.locator(".tippy-box").count()
                    hover = None
                    try:
                        s.page.locator(".tippy-box .jenkins-dropdown__item").filter(has_text=t).first.hover(timeout=5000)
                        until(s.page, lambda: s.page.locator(".tippy-box").count() > before_boxes, 1000)  # a submenu, if any
                        hover = {"boxes": s.page.locator(".tippy-box").count(),
                                 "items": s.page.evaluate(ITEMS)[len(items):]}
                    except Exception:  # noqa: BLE001
                        pass
                    try:
                        s.page.locator(".tippy-box .jenkins-dropdown__item").filter(has_text=t).first.click(timeout=5000)
                    except Exception:  # noqa: BLE001
                        row["clicks"][t] = {"not_found_on_reopen": [i["text"] for i in (again or [])]}
                        continue
                    react(s.page, timeout=1200, menus=".tippy-box", menus_before=before_boxes)
                    row["clicks"][t] = {"dialog": s.page.locator("dialog[open]").count(), "boxes_before": before_boxes,
                                        "boxes_after": s.page.locator(".tippy-box").count(),
                                        "submenu_items": s.page.locator(".tippy-box .jenkins-dropdown__item").count() - len(items),
                                        "url": s.page.url.replace(s.page.url.split('/jenkins')[0], ''), "hover": hover}
            row["console"] = list(s.console); row["bad"] = list(s.bad)
            log("menu", row)
            print(row['user'], row['page'], len(row['items'] or []))
    s.done()
close()
