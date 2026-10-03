import re
from lib import Session, close, log, groovy
res = {}
s = Session("requester")
for j in ["/job/batch-daily/", "/job/batch-pipeline/", "/job/team-mb/"]:
    s.go(j)
    res[j + " legacy html"] = s.page.evaluate("""() => { const h=[...document.querySelectorAll('*')].find(e=>e.children.length==0 && e.textContent.trim()=='Legacy'); if(!h) return null; let c=h; for(let i=0;i<4 && c.parentElement;i++){c=c.parentElement; if(c.outerHTML.length>200) break;} return c.outerHTML.replace(/\\s+/g,' ').slice(0,900); }""")
s.go("/user/requester/experiments/")
row = s.page.locator("tr", has_text="new-job-page.flag")
row.locator("select").select_option(label="Disabled")
s.page.click("button[name=Submit]"); s.page.wait_for_load_state("load")
s.go("/job/batch-daily/")
res["classic main"] = s.page.locator("#main-panel").inner_text()[:700].replace("\n", " | ")
res["classic BC card?"] = s.page.locator("#main-panel").get_by_text("Batch Control", exact=True).count()
s.page.screenshot(path=str(__import__("lib").SHOTS / "72b-01-classic-job-page.png"), full_page=True)
# leave classic on for #73 classic step; restore later in s73
s.done(); close()
for k, v in res.items(): print(k, "=>", v)
log("s72b", res)
