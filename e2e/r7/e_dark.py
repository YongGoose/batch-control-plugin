"""E: dark theme (Appearance -> Dark) at 1280 px on the main Batch Control screens."""
from lib import Session, close, log, SHOTS
res = {}
s = Session("approver-1")
s.go("/user/approver-1/appearance/")
s.page.locator("input[data-theme=dark]").check(force=True)
s.page.locator("button[name=Submit]").first.click(); s.page.wait_for_load_state("load")
for p, n in [("/batch-control/", "E-dark-overview"), ("/batch-control/requests/", "E-dark-requests"), ("/batch-control/grants/", "E-dark-grants"),
             ("/batch-control/history/", "E-dark-history"), ("/batch-control/incidents/20261003-123943-z4eeb0/", "E-dark-incident"),
             ("/batch-control/requests/20261003-123921-mf58tt/", "E-dark-detail"), ("/job/batch-daily/", "E-dark-job")]:
    s.go(p)
    s.page.screenshot(path=str(SHOTS / f"{n}.png"), full_page=False)
    res[n] = s.page.evaluate("""() => { const b = getComputedStyle(document.body); const n = document.querySelector('nav[data-batch-control-tabs]');
      const a = document.querySelector('.jenkins-alert'); const t = document.querySelector('.jenkins-table');
      return {bg: getComputedStyle(document.documentElement).getPropertyValue('--background').trim() || b.backgroundColor, text: b.color,
              tabsH: n ? Math.round(n.getBoundingClientRect().height) : null, scrollW: document.documentElement.scrollWidth,
              tableBg: t ? getComputedStyle(t).backgroundColor : null, alert: a ? [getComputedStyle(a).backgroundColor, getComputedStyle(a).color] : null} }""")
s.go("/user/approver-1/appearance/")
s.page.locator("input[data-theme=none]").check(force=True)
s.page.locator("button[name=Submit]").first.click(); s.page.wait_for_load_state("load")
s.done(); close()
log("e_dark", res)
for k, v in res.items(): print(k, v)
