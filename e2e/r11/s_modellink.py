"""R4-11: hovering a job/run link shows core's context-menu chevron (model-link) and opens the menu."""
from lib import Session, close, log
res = {}
s = Session("admin")
for p, name in (("/batch-control/dashboard/", "dashboard"), ("/batch-control/requests/6a3f9004-2302-4f5e-b8bb-6efda6bb9c34/", "request-detail")):
    s.go(p)
    a = s.page.locator("#main-panel a.model-link").first
    a.hover(); s.page.wait_for_timeout(700)
    chev = s.page.locator(".jenkins-menu-dropdown-chevron, button.jenkins-menu-dropdown-chevron, .model-link--open, .jenkins-dropdown-chevron")
    res[name] = {"link": a.inner_text(), "chevrons_visible": sum(1 for c in chev.all() if c.is_visible())}
    s.shot(a.locator("xpath=.."), f"R11-{name}-hover")
    c = next((c for c in chev.all() if c.is_visible()), None)
    if c:
        c.click(); s.page.wait_for_timeout(1200)
        res[name]["menu"] = [t.strip()[:200] for t in s.page.locator(".tippy-box").all_inner_texts()][:1]
        s.page.screenshot(path=str(s.page.context.pages[0].url and __import__('lib').SHOTS / f"R11-{name}-menu.png"))
s.done(); close(); log("s_modellink", res); print(res)
