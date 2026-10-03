"""Regression: dark theme on the reworked screens (approver-1) and the strategy monitor (non-Batch-Control strategy)."""
import re
from lib import Session, close, api, groovy, log, SHOTS
res = {}
s = Session("approver-1", dark=False)
s.go("/user/approver-1/appearance/")
s.page.locator("input[data-theme=dark]").check(force=True)
s.page.locator("button[name=Submit]").first.click(); s.page.wait_for_load_state("load")
for p, n in (("/batch-control/", "overview"), ("/batch-control/grants/", "grants"), ("/batch-control/requests/", "requests"), ("/batch-control/dashboard/", "dashboard")):
    s.go(p)
    s.page.screenshot(path=str(SHOTS / f"DK-{n}.png"))
    res[n] = s.page.evaluate("""() => { const n = document.querySelector('nav[data-batch-control-tabs]'); const b = getComputedStyle(document.body);
      const cur = n && n.querySelector('[aria-current=page]'); return {bodyBg: b.backgroundColor, text: b.color, tabsH: n ? Math.round(n.getBoundingClientRect().height) : null,
      curColor: cur ? getComputedStyle(cur).color : null, curBefore: cur ? getComputedStyle(cur, '::before').backgroundColor : null, scrollW: document.documentElement.scrollWidth}; }""")
s.go("/job/batch-daily/")
s.page.locator("[data-testid=app-bar-overflow-button]").first.locator("xpath=..").locator("a, button", has_text="Request Run").count()
s.go("/batch-control/grants/")
s.page.locator("#main-panel a, #main-panel button", has_text="Request Change Permission").count()
s.go("/user/approver-1/appearance/")
s.page.locator("input[data-theme=none]").check(force=True)
s.page.locator("button[name=Submit]").first.click(); s.page.wait_for_load_state("load")
s.done()
# monitor
res["set_plain"] = groovy("""def j = jenkins.model.Jenkins.get(); def s = new hudson.security.GlobalMatrixAuthorizationStrategy(); s.add(jenkins.model.Jenkins.ADMINISTER, new org.jenkinsci.plugins.matrixauth.PermissionEntry(org.jenkinsci.plugins.matrixauth.AuthorizationType.USER, 'admin')); j.setAuthorizationStrategy(s); j.save(); return j.authorizationStrategy.class.simpleName""")
a = Session("admin")
a.go("/manage/")
al = [re.sub(r"\s+", " ", t)[:300] for t in a.page.locator(".jenkins-alert, .alert").all_inner_texts() if "Batch Control" in t]
res["monitor"] = al
a.shot(a.page.locator(".jenkins-alert, .alert", has_text="Batch Control").first if al else "#main-panel", "MON-01-strategy-monitor")
a.done()
res["restore"] = groovy("io.jenkins.plugins.casc.ConfigurationAsCode.get().configure('/var/jenkins_casc/jenkins.yaml'); return jenkins.model.Jenkins.get().authorizationStrategy.class.simpleName")
close(); log("s_dark_monitor", res)
for k, v in res.items(): print(k, "=>", v)
