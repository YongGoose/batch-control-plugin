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
    s.shot(mon, "M1-01-monitor")
    l.click()
    dlg = s.page.locator("dialog[open]").first
    dlg.wait_for()
    res["dialog"] = re.sub(r"\s+", " ", dlg.inner_text())
    s.shot(dlg, "M1-02-dialog")
    with s.page.expect_navigation():
        dlg.get_by_role("button", name="Mark as reviewed").click()
    s.page.wait_for_load_state("load")
    res["after_url"] = s.page.url.replace("http://localhost:8080", "")
    res["mark_links_after"] = s.page.locator("a", has_text="Mark as reviewed").count()
    s.shot("#main-panel", "M1-03-after")
res["records"] = [l for l in api("admin", "/batch-control/history/changes.csv").text.splitlines() if "GUARD_REVIEWED" in l]
s.done(); close()
log("monitor", res)
for k, v in res.items(): print(k, v)
