"""DEF-04 monitor + Mark as reviewed dialog, UX-7 dialogs (Cancel request, Revoke), expiry check."""
import re, time
from lib import Session, close, api, log, clean
res = {}
def dialog(s, label, shotname, confirm):
    b = s.page.locator("#main-panel a, #main-panel button", has_text=label).first
    out = {"count": s.page.locator("#main-panel a, #main-panel button", has_text=label).count()}
    if not out["count"]:
        return out
    out["class"] = b.get_attribute("class")
    b.scroll_into_view_if_needed(); b.click(); s.page.wait_for_timeout(700)
    d = s.page.locator("dialog[open]")
    if d.count():
        out["dialog"] = re.sub(r"\s+", " ", d.first.inner_text())[:250]
        out["buttons"] = [x.inner_text().strip() for x in d.first.locator("button").all()]
        s.shot(d.first, shotname)
        if confirm:
            with s.page.expect_navigation():
                d.first.locator("button.jenkins-button--primary, button.jenkins-button--destructive").last.click()
            s.page.wait_for_load_state("load")
            out["after"] = re.sub(r"\s+", " ", s.text())[:250]
        else:
            d.first.locator("button", has_text="Cancel").first.click()
    return out
# DEF-04
s = Session("admin")
s.go("/manage/")
mon = s.page.locator(".jenkins-alert", has_text="Batch Control").first
res["monitor_count"] = s.page.locator(".jenkins-alert", has_text="Batch Control").count()
if mon.count():
    res["monitor_text"] = re.sub(r"\s+", " ", mon.inner_text())[:900]
    res["icons_in_monitor"] = mon.locator("svg").count()
    s.shot(mon, "A3-01-monitor")
    mr = mon.locator("a, button", has_text="Mark as reviewed")
    res["mark_buttons"] = mr.count()
    if mr.count():
        boxes = [x.bounding_box() for x in mr.all()]
        res["mark_xy"] = [(round(b["x"]), round(b["y"])) for b in boxes]
        mr.first.click(); s.page.wait_for_timeout(700)
        d = s.page.locator("dialog[open]")
        if d.count():
            res["mark_dialog"] = re.sub(r"\s+", " ", d.first.inner_text())[:300]
            res["mark_dialog_buttons"] = [x.inner_text().strip() for x in d.first.locator("button").all()]
            s.shot(d.first, "A3-02-mark-dialog")
            with s.page.expect_navigation():
                d.first.locator("button.jenkins-button--primary").last.click()
            s.page.wait_for_load_state("load")
            m2 = s.page.locator(".jenkins-alert", has_text="Batch Control").first
            res["after_mark"] = re.sub(r"\s+", " ", m2.inner_text())[:500] if m2.count() else "monitor gone"
            s.shot(m2 if m2.count() else "#main-panel", "A3-03-after-mark")
s.done()
ch = clean(api("admin", "/batch-control/changes/").text)
res["guard_reviewed"] = re.findall(r"GUARD_REVIEWED \S+ admin .{0,120}", ch)[:2]
# UX-7 cancel request
s = Session("requester")
s.go("/batch-control/requests/20261003-124901-wt5xb1/")
res["cancel"] = dialog(s, "Cancel Request", "A4-01-cancel-dialog", True)
s.done()
# UX-7 revoke (manager)
s = Session("manager")
s.go("/batch-control/grants/")
row = s.page.locator("tr:has(button:has-text('Revoke')), tr:has(a:has-text('Revoke'))", has_text="mover1").first
res["revoke_row"] = row.count()
if row.count():
    b = row.locator("a, button", has_text="Revoke").first
    b.click(); s.page.wait_for_timeout(700)
    d = s.page.locator("dialog[open]")
    if d.count():
        res["revoke_dialog"] = [x.inner_text().strip() for x in d.first.locator("button").all()]
        s.shot(d.first, "A4-02-revoke-dialog")
        with s.page.expect_navigation():
            d.first.locator("button.jenkins-button--primary").last.click()
        s.page.wait_for_load_state("load")
s.shot("#main-panel", "A4-03-after-revoke")
pg = re.findall(r"Page \d+ \([^)]*\)", s.text())
res["pagers"] = pg
s.done(); close()
ch = clean(api("admin", "/batch-control/changes/").text)
res["revoke_record"] = re.findall(r"GRANT_REVOKE .{0,160}", ch)[:1]
res["deep_configure_after"] = (api("requester", "/job/team/job/sub/job/deep-job/configure").status_code, time.strftime("%H:%M:%S"))
log("c_misc", res)
for k, v in res.items(): print(k, v)
