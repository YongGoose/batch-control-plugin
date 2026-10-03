"""Items 2-4: monitor sentence names the Changes tab; Mark as reviewed lands on /batch-control/reviewed?item=...;
non-admin 403; old monitor URL 404; '1 minute' on a 1-minute grant request detail."""
import re, sys
from lib import Session, close, api, log
res = {}
s = Session("admin")
s.go("/manage/")
mon = s.page.locator("[data-batch-control-monitor], .jenkins-alert", has_text="Batch Control").first
res["monitor_text"] = re.sub(r"\s+", " ", mon.inner_text())[:900] if mon.count() else None
res["monitor_links"] = mon.locator("a").evaluate_all("as => as.map(a => [a.innerText.trim(), a.getAttribute('href')])") if mon.count() else None
s.shot(mon, "86-01-monitor")
l = s.page.locator("a, button", has_text="Mark as reviewed").first
l.click()
dlg = s.page.locator("dialog[open]").first
dlg.wait_for()
res["dialog"] = re.sub(r"\s+", " ", dlg.inner_text())
s.shot(dlg, "86-02-dialog")
with s.page.expect_navigation() as nav:
    dlg.get_by_role("button", name="Mark as reviewed").click()
s.page.wait_for_load_state("load")
res["after_status"] = nav.value.status if nav.value else "n/a (no response object)"
res["after_url"] = s.page.url.replace("http://localhost:8080", "")
res["breadcrumb"] = re.sub(r"\s+", " ", " › ".join(t.strip() for t in s.page.locator("#breadcrumbs li, .jenkins-breadcrumbs__list-item").all_inner_texts() if t.strip()))
res["title"] = s.page.title()
res["after_text"] = s.text()[:600].replace("\n", " | ")
s.page.screenshot(path=str(__import__("lib").SHOTS / "86-03-reviewed-page.png"))
s.go("/manage/")
res["mark_links_after"] = s.page.locator("a, button", has_text="Mark as reviewed").count()
s.done()
res["records"] = [x for x in api("admin", "/batch-control/history/changes.csv").text.splitlines() if "GUARD_REVIEWED" in x]
path = res["after_url"].replace("/jenkins", "", 1)
for u in ["manager", "requester", "mover1", "admin"]:
    res[u + " GET reviewed"] = api(u, path).status_code
res["old monitor URL GET"] = api("admin", "/administrativeMonitor/batch-control-strategy/reviewed?item=ops%2Fmv-job").status_code
res["old monitor URL POST"] = api("admin", "/administrativeMonitor/batch-control-strategy/reviewed?item=ops%2Fmv-job", "POST").status_code
res["markReviewed POST as manager"] = api("manager", "/administrativeMonitor/batch-control-strategy/markReviewed?item=ops%2Fmv-job", "POST").status_code
g = sys.argv[1]
d = Session("mover1")
d.go(f"/batch-control/grants/{g}/")
t = d.text()
res["grant detail duration"] = [ln for ln in t.splitlines() if "minute" in ln.lower() or "Duration" in ln][:4]
d.shot("#main-panel", "4-01-grant-detail-1-minute")
d.done()
close()
log("s86", res)
for k, v in res.items(): print(k, "=>", v)
