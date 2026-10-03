"""D-59 move check: move.py <id> <user> <source full name> <destination folder> [expect: refused|moved]"""
import re
import sys
from lib import Session, close, api, log, SHOTS

def path(full):
    return "".join(f"/job/{p}" for p in full.split("/"))

def move(sid, user, src, dest, expect):
    name = src.split("/")[-1]
    s = Session(user)
    r = s.go(path(src) + "/move/")
    s.shot("#main-panel", f"{sid}-01-move-page")
    opts = s.page.locator('select[name="destination"] option').all_inner_texts() if r.status == 200 else []
    resp = None
    if r.status == 200:
        s.page.select_option('select[name="destination"]', "/" + dest if dest else "/")
        with s.page.expect_navigation() as nav:
            s.page.click('button[name="Submit"]')
        resp = nav.value
        s.page.wait_for_load_state("load")
    status = resp.status if resp else r.status
    body = s.text()
    layout = bool(s.page.locator("#page-header, .page-header, #breadcrumbBar").count())
    s.shot("#main-panel" if s.page.locator("#main-panel").count() else "body", f"{sid}-02-result")
    s.page.screenshot(path=str(SHOTS / f"{sid}-02b-result-page.png"))
    dst_full = (dest + "/" if dest else "") + name
    at_src = api("admin", path(src) + "/api/json").status_code
    at_dst = api("admin", path(dst_full) + "/api/json").status_code
    ch = api("admin", "/batch-control/changes/").text
    text = re.sub(r"<[^>]+>", " ", ch)
    text = re.sub(r"\s+", " ", text)
    viol = [m.group(0) for m in re.finditer(r".{0,160}(GRANT_VIOLATION|Grant violation|grant violation).{0,200}", text)]
    out = {"id": sid, "user": user, "src": src, "dest": dest, "options": opts, "move_page": r.status,
           "result_status": status, "result_url": s.page.url, "standard_layout": layout,
           "result_text": body[:600], "src_api": at_src, "dst_api": at_dst,
           "violations_mentioning_item": [v for v in viol if name in v][-2:], "console": s.console, "bad": s.bad}
    ok = (expect == "refused" and status == 403 and at_src == 200 and at_dst == 404) or \
         (expect == "moved" and at_src == 404 and at_dst == 200)
    out["verdict"] = "PASS" if ok else "CHECK"
    log("move", out)
    for k, v in out.items():
        print(f"{k}: {v}")
    s.done()
    close()

if __name__ == "__main__":
    a = sys.argv[1:]
    move(a[0], a[1], a[2], a[3], a[4] if len(a) > 4 else "refused")
