"""R4-6 (overview: badges only), R4-7 (tab bar vs core's new run page tabs, 1280 px), R4-8 (Pending -> Active -> Ended,
no form on top, footers, row links), R4-11 (model-link on job/run links), R4-13 (dashboard <= 50 runs + History link)."""
import re, sys, json
from lib import Session, close, api, log, SHOTS
res = {}
UUIDL = re.compile(r"/batch-control/(grants|requests|activations)/[0-9a-f-]{36}/$")
JS_LAYOUT = """() => {
  const mp = document.querySelector('#main-panel');
  const heads = [...mp.querySelectorAll('h1,h2,h3')].map(h => h.tagName + ':' + h.innerText.trim());
  const firstH2 = mp.querySelector('h2');
  const formsBefore = [...mp.querySelectorAll('form')].filter(f => !firstH2 || (f.compareDocumentPosition(firstH2) & Node.DOCUMENT_POSITION_FOLLOWING)).map(f => f.name || f.action);
  const tables = [...mp.querySelectorAll('table')].map(t => ({rows: t.tBodies[0] ? t.tBodies[0].rows.length : 0,
     heads: [...t.querySelectorAll('thead th')].map(th => th.innerText.trim()),
     section: (() => { let e = t; while (e && e !== mp) { let p = e.previousElementSibling; while (p) { if (/^H[23]$/.test(p.tagName)) return p.innerText.trim(); const h = p.querySelector && p.querySelector('h2,h3'); if (h) return h.innerText.trim(); p = p.previousElementSibling; } e = e.parentElement; } return null; })(),
     rowLinks: t.tBodies[0] ? [...t.tBodies[0].rows].slice(0, 3).map(r => [...r.querySelectorAll('a')].map(a => a.getAttribute('href') + (a.classList.contains('model-link') ? ' [model-link]' : ''))) : []}));
  const footers = [...mp.querySelectorAll('p, div, span, nav')].map(e => e.childElementCount < 4 ? e.innerText.trim() : '').filter(t => /^Page \\d+/.test(t));
  return {heads, formsBefore, tables, footers: [...new Set(footers)].slice(0, 6), scrollW: document.documentElement.scrollWidth};
}"""
for user in ("admin", "requester", "approver-1"):
    s = Session(user)
    s.go("/batch-control/")
    nav = s.page.locator("nav[data-batch-control-tabs]")
    res[f"{user} tabs"] = [re.sub(r"\s+", " ", t).strip() for t in nav.locator("a").all_inner_texts()]
    res[f"{user} overview"] = {"text": re.sub(r"\s+", " ", s.text())[:300], "tables": s.page.locator("#main-panel table").count(),
        "alerts": [re.sub(r"\s+", " ", t)[:120] for t in s.page.locator("#main-panel .jenkins-alert, #main-panel .alert, #main-panel .jenkins-notice").all_inner_texts()]}
    s.shot("#main-panel", f"R6-{user}-overview")
    for p in ("grants", "requests", "activations"):
        s.go(f"/batch-control/{p}/")
        res[f"{user} {p}"] = s.page.evaluate(JS_LAYOUT)
        s.page.screenshot(path=str(SHOTS / f"R8-{user}-{p}.png"), full_page=True)
    if user == "admin":
        s.go("/batch-control/dashboard/")
        res["dashboard"] = s.page.evaluate(JS_LAYOUT)
        res["dashboard_history_links"] = [(a.inner_text().strip(), a.get_attribute("href")) for a in s.page.locator("#main-panel a", has_text=re.compile("History", re.I)).all()][:4]
        res["dashboard_intro"] = re.sub(r"\s+", " ", s.text())[:400]
        s.page.screenshot(path=str(SHOTS / "R13-dashboard.png"), full_page=True)
        # tab bar at 1280 (admin = 8 tabs)
        st = s.page.evaluate("""() => { const n = document.querySelector('nav[data-batch-control-tabs]'); const r = n.getBoundingClientRect();
          const cur = n.querySelector('[aria-current=page]'); const other = [...n.querySelectorAll('a')].find(a => a !== cur);
          const cs = e => { const c = getComputedStyle(e); return {bg: c.backgroundColor, color: c.color, radius: c.borderRadius, h: Math.round(e.getBoundingClientRect().height), weight: c.fontWeight}; };
          return {navClass: n.className, navH: Math.round(r.height), navW: Math.round(r.width), tops: [...new Set([...n.querySelectorAll('a')].map(a => Math.round(a.getBoundingClientRect().top)))],
                  current: cur && cur.innerText.trim(), curStyle: cur && cs(cur), otherStyle: other && cs(other), scrollW: document.documentElement.scrollWidth}; }""")
        res["tabbar_dashboard"] = st
        s.shot("nav[data-batch-control-tabs]", "R7-01-tabbar-admin-1280")
        # core's new run page tab bar
        s.go("/job/fast/1/")
        res["core_run_page"] = s.page.evaluate("""() => { const n = document.querySelector('.app-build-tabs, nav[class*=tabs], .tabBar, [role=tablist]');
          if (!n) return null; const cur = n.querySelector('[aria-current=page], .active, [aria-selected=true]'); const other = [...n.querySelectorAll('a')].find(a => a !== cur);
          const cs = e => { const c = getComputedStyle(e); return {bg: c.backgroundColor, color: c.color, radius: c.borderRadius, h: Math.round(e.getBoundingClientRect().height), weight: c.fontWeight}; };
          return {navTag: n.tagName, navClass: n.className, items: [...n.querySelectorAll('a')].map(a => a.innerText.trim()), current: cur && cur.innerText.trim(), curStyle: cur && cs(cur), otherStyle: other && cs(other), linkClass: n.querySelector('a') && n.querySelector('a').className}; }""")
        nsel = ".app-build-tabs, nav[class*=tabs], .tabBar, [role=tablist]"
        s.shot(nsel if s.page.locator(nsel).count() else "#main-panel", "R7-02-core-run-page-tabs")
        # model-link on request detail / lists
        for p in ("/batch-control/requests/", "/batch-control/history/"):
            s.go(p)
            res[f"model-link {p}"] = s.page.evaluate("""() => [...document.querySelectorAll('#main-panel a[href*="/job/"]')].slice(0, 8).map(a => a.innerText.trim().slice(0,30) + ' | ' + a.getAttribute('href') + ' | ' + a.className)""")
    s.done()
close(); log("s_pages", res)
print(json.dumps(res, indent=1)[:12000])
