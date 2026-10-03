"""R4-7: Batch Control tab bar vs core's tab bar on the new build page (new-build-page.flag on for admin only)."""
import json
from lib import Session, close, log, SHOTS
CS = """sel => { const n = document.querySelector(sel); if (!n) return null;
  const cur = n.querySelector('[aria-current=page], [aria-selected=true], .active'); const other = [...n.querySelectorAll('a')].find(a => a !== cur);
  const cs = e => { const c = getComputedStyle(e); const b = getComputedStyle(e, '::before'); return {cls: e.className, bg: c.backgroundColor, color: c.color, radius: c.borderRadius, h: Math.round(e.getBoundingClientRect().height), pad: c.padding, font: c.fontSize + '/' + c.fontWeight, beforeBg: b.backgroundColor, beforeOpacity: b.opacity}; };
  return {tag: n.tagName, cls: n.className, items: [...n.querySelectorAll('a')].map(a => a.innerText.trim()), current: cur && cur.innerText.trim(), curStyle: cur && cs(cur), otherStyle: other && cs(other), navH: Math.round(n.getBoundingClientRect().height)}; }"""
res = {}
s = Session("admin")
s.go("/user/admin/experiments/")
s.page.locator("tr", has_text="new-build-page.flag").locator("select").select_option(label="Enabled")
s.page.click("button[name=Submit]"); s.page.wait_for_load_state("load")
s.go("/job/fast/1/")
res["core"] = s.page.evaluate(CS, ".app-build-tabs")
if res["core"]:
    s.shot(".app-build-tabs", "R7-02-core-build-page-tabs")
s.page.screenshot(path=str(SHOTS / "R7-02b-core-build-page.png"))
s.go("/batch-control/history/")
res["ours"] = s.page.evaluate(CS, "nav[data-batch-control-tabs]")
s.shot("nav[data-batch-control-tabs]", "R7-01-ours-history")
for w in (1280, 1024, 800):
    s.page.set_viewport_size({"width": w, "height": 900})
    s.go("/batch-control/history/")
    res[f"wrap {w}"] = s.page.evaluate("""() => { const n = document.querySelector('nav[data-batch-control-tabs]'); return {navH: Math.round(n.getBoundingClientRect().height),
       rows: new Set([...n.querySelectorAll('a')].map(a => Math.round(a.getBoundingClientRect().top))).size, scrollW: document.documentElement.scrollWidth, overflowX: getComputedStyle(n).overflowX}; }""")
    s.shot("nav[data-batch-control-tabs]", f"R7-03-tabs-{w}")
s.page.set_viewport_size({"width": 1280, "height": 900})
s.go("/user/admin/experiments/")
s.page.locator("tr", has_text="new-build-page.flag").locator("select").select_option(index=0)
s.page.click("button[name=Submit]"); s.page.wait_for_load_state("load")
s.done(); close(); log("s_tabs", res); print(json.dumps(res, indent=1))
