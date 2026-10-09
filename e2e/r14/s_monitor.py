"""e2e-12: strategy monitor after Revert (plain strategy): its Install control (Cancel, then OK) and Mark as reviewed.
Restores the Batch Control strategy."""
import re
from lib import Session, close, groovy, BASE, opened, gone, react
import lib
strategy = lambda: groovy("return jenkins.model.Jenkins.get().getAuthorizationStrategy().getClass().simpleName").replace("Result: ", "")  # noqa
row = {"before": strategy()}
s = Session("admin")
s.go("/manage/")
al = s.page.locator(".jenkins-alert", has_text="Batch Control")
row["alerts"] = [re.sub(r"\s+", " ", t)[:220] for t in al.all_inner_texts()]
ctl = s.page.locator("#main-panel form button, #main-panel a[data-url], #main-panel button", has_text=re.compile("Install|Mark as reviewed"))
row["controls"] = [re.sub(r"\s+", " ", t).strip() for t in ctl.all_inner_texts()]
s.shot(".jenkins-alert", "S-monitor-plain")
inst = s.page.locator("#main-panel button, #main-panel a", has_text=re.compile("^\\s*Install"))
if inst.count():
    inst.first.click(); opened(s.page)  # e2e-20: dialog and navigation waits instead of fixed 0.6-3 s
    d = s.page.locator("dialog[open]")
    row["dialog"] = re.sub(r"\s+", " ", d.first.inner_text())[:200] if d.count() else None
    if d.count():
        d.first.locator("button[data-id=cancel]").click(); gone(s.page)
        row["after_cancel"] = strategy()
        inst.first.click(); opened(s.page)
        before = s.page.url
        s.page.locator("dialog[open] button[data-id=ok]").click()
        react(s.page, before, timeout=10000)
    s.page.wait_for_load_state("load")
    row["landing"] = s.page.url.replace(BASE, "")
    row["landing_text"] = re.sub(r"\s+", " ", s.text())[:200]
    row["after_ok"] = strategy()
    s.shot("#main-panel", "S-after-install")
row["console"] = [c for c in s.console if "MIME" not in c][:3]
s.go("/manage/")
row["alerts_after"] = [re.sub(r"\s+", " ", t)[:220] for t in s.page.locator(".jenkins-alert", has_text="Batch Control").all_inner_texts()]
# Mark as reviewed from the monitor, if listed
mr = s.page.locator("a[data-url*='markReviewed']")
row["mark_links"] = mr.count()
if mr.count():
    mr.first.click(); opened(s.page)
    s.page.locator("dialog[open] button[data-id=cancel]").click(); gone(s.page)
    s.go("/manage/"); row["after_mark_cancel"] = s.page.locator("a[data-url*='markReviewed']").count()
    s.page.locator("a[data-url*='markReviewed']").first.click(); opened(s.page)
    before = s.page.url
    s.page.locator("dialog[open] button[data-id=ok]").click(); react(s.page, before, timeout=10000)
    s.page.wait_for_load_state("load")
    row["mark_landing"] = s.page.url.replace(BASE, "")
    row["mark_text"] = re.sub(r"\s+", " ", s.text())[:220]
    row["mark_links_on_page"] = [(a.inner_text().strip()[:30], s.context.request.get(a.evaluate("e=>e.href")).status) for a in s.page.locator("#main-panel a[href]").all() if not (a.get_attribute("href") or "#").startswith("#")][:6]
    s.go("/manage/"); row["after_mark_ok"] = s.page.locator("a[data-url*='markReviewed']").count()
s.done(); close()
if "BatchControl" not in strategy():
    row["restore"] = "manual groovy needed"
lib.log("monitor", row); print(row)
