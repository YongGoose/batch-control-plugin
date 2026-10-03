"""Item 4: strategy monitor "Mark as reviewed" looks like a button and works with confirmation."""
import re
from lib import Session, close, api, log
res = {}
s = Session("admin")
s.go("/manage/")
mon = s.page.locator("[data-batch-control-monitor], .jenkins-alert", has_text="Batch Control").first
res["monitor_text"] = re.sub(r"\s+", " ", mon.inner_text())[:700] if mon.count() else None
links = s.page.locator("a", has_text="Mark as reviewed")
res["mark_links"] = links.count()
if links.count():
    l = links.first
    st = l.evaluate("""e => { const c = getComputedStyle(e); return {cls: e.className, bg: c.backgroundColor, border: c.borderStyle + ' ' + c.borderWidth,
        radius: c.borderRadius, padding: c.padding, display: c.display, decoration: c.textDecorationLine, color: c.color, h: e.getBoundingClientRect().height}; }""")
    res["mark_style"] = st
    row = l.locator("xpath=ancestor::li[1]")
    res["row_text"] = re.sub(r"\s+", " ", row.inner_text()) if row.count() else None
    s.shot(mon, "86-01-monitor")
    l.click()
    dlg = s.page.locator("dialog[open]").first
    dlg.wait_for()
    res["dialog"] = re.sub(r"\s+", " ", dlg.inner_text())
    s.shot(dlg, "86-02-dialog")
    with s.page.expect_navigation():
        dlg.get_by_role("button", name="Mark as reviewed").click()
    s.page.wait_for_load_state("load")
    res["after_url"] = s.page.url.replace("http://localhost:8080", "")
    res["mark_links_after"] = s.page.locator("a", has_text="Mark as reviewed").count()
    s.shot("#main-panel", "86-03-after")
    res["after_text"] = s.text()[:700].replace("\n", " | ")
    res["after_title"] = s.page.title()
    s.go("/manage/")
    m2 = s.page.locator("[data-batch-control-monitor], .jenkins-alert", has_text="Batch Control").first
    res["monitor_after"] = re.sub(r"\s+", " ", m2.inner_text())[:400] if m2.count() else None
res["monitor_links"] = None
res["records"] = [l for l in api("admin", "/batch-control/history/changes.csv").text.splitlines() if "GUARD_REVIEWED" in l]
s.done()
for u in ["manager", "requester"]:
    res[u + " reviewed GET"] = api(u, res.get("after_url", "/").replace("/jenkins", "", 1)).status_code if res.get("after_url") else None
close()
log("s86", res)
for k, v in res.items(): print(k, v)
