"""Direct Build (needs approval) on a parameterless approval-required job (new job page): what the user sees.
Plus the activation page's 'Permalinks' control and the multibranch activation page's collapse links."""
import re
from lib import Session, close, BASE, shot
s = Session("requester")
s.go("/job/ops/job/job-a/")
b = s.page.locator("button, a", has_text="Direct Build (needs approval)").first
print("tag", b.evaluate("e => e.outerHTML.slice(0,300)"))
b.click(); s.page.wait_for_timeout(2500)
print("url", s.page.url.replace(BASE, ""))
print("notif", [t for t in s.page.locator(".jenkins-notification, #notification-bar, [role=alert], .tippy-box").all_inner_texts() if t.strip()])
print("dialog", s.page.locator("dialog[open]").count(), s.page.locator("dialog[open]").first.inner_text()[:300] if s.page.locator("dialog[open]").count() else "")
print("bad", s.bad[-3:])
s.page.screenshot(path=str(s.shot.__self__ and __import__('lib').SHOTS / "P-direct-build-parameterless.png"))
s.go("/job/batch-daily/batch-control-activation/")
p = s.page.locator("text=Permalinks").first
print("permalinks", p.evaluate("e => e.outerHTML.slice(0,200)") if p.count() else None)
s.page.screenshot(path=str(__import__('lib').SHOTS / "P-activation-page.png"), full_page=True)
s.go("/job/ops/job/mb/batch-control-activation/")
for a in s.page.locator("a[href*='toggleCollapse']").all():
    print("collapse", a.get_attribute("href"), a.is_visible())
s.go("/job/ops/job/mb/")
for a in s.page.locator("a[href*='toggleCollapse']").all():
    print("mb page collapse", a.get_attribute("href"))
s.done(); close()
