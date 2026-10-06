"""Probe: which checkbox does each action label toggle in the job-page / folder-page / grants-page dialog."""
from lib import Session, close
for page, link in (("/job/batch-pipeline/", "#tasks a"), ("/job/ops/", "#tasks a"), ("/batch-control/grants/", "#main-panel a, #main-panel button")):
    for v in ("CREATE", "CONFIGURE", "DELETE"):
        s = Session("classic")
        s.go(page)
        s.page.locator(link, has_text="Request Change Permission").first.click()
        s.page.wait_for_selector("dialog[open] input[name=scopeFullName]")
        s.page.wait_for_timeout(500)
        d = s.page.locator("dialog[open]").first
        info = d.evaluate("""d => [...d.querySelectorAll('input[name=actions]')].map(i => i.value + ' id=' + i.id + ' for=' + (i.nextElementSibling && i.nextElementSibling.getAttribute('for')) + ' dupIds=' + document.querySelectorAll('[id=\"' + i.id + '\"]').length)""")
        d.locator(f"input[name=actions][value={v}]").locator("xpath=following-sibling::label").first.click()
        s.page.wait_for_timeout(200)
        st = {x: d.locator(f"input[name=actions][value={x}]").is_checked() for x in ("CREATE", "CONFIGURE", "DELETE")}
        print(page, "click", v, "->", st, info if v == "CREATE" else "")
        s.done()
close()
