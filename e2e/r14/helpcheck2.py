"""e2e-12: checkbox help buttons: a click must make one more help block visible (core places it after the checkbox)."""
from lib import Session, close
s = Session("admin")
for page in ["/manage/batch-control-configuration/", "/job/batch-daily/configure"]:
    s.go(page)
    for h in s.page.locator("a.jenkins-help-button[helpurl*='batchcontrol']").all():
        if not h.is_visible():
            h.scroll_into_view_if_needed()
        before = s.page.evaluate("() => [...document.querySelectorAll('.help')].filter(e => e.offsetHeight > 0 && e.innerText.trim().length > 20).length")
        h.click(); s.page.wait_for_timeout(800)
        after = s.page.evaluate("() => [...document.querySelectorAll('.help')].filter(e => e.offsetHeight > 0 && e.innerText.trim().length > 20).length")
        print(page, h.get_attribute("helpurl").split("/")[-1], before, "->", after)
s.done(); close()
