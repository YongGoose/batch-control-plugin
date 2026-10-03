"""#72: new job page notices in a 'Batch Control' card (not Legacy); classic page unchanged. #73 also uses this context."""
import re
from lib import Session, close, log
res = {}
s = Session("requester")
s.go("/job/batch-daily/")
# collect card headings on the new job page
heads = s.page.evaluate("""() => [...document.querySelectorAll('h2, h3, .jenkins-card__title, [class*=card] [class*=title]')].map(e => e.innerText.trim()).filter(Boolean)""")
res["new headings"] = heads[:30]
card = s.page.locator("section, .jenkins-card, div[class*=card]").filter(has_text=re.compile("Batch Control")).last
res["bc card text"] = card.inner_text()[:500].replace("\n", " | ") if card.count() else None
leg = s.page.locator("section, .jenkins-card, div[class*=card]").filter(has_text=re.compile("^\\s*Legacy")).last
res["legacy card"] = leg.inner_text()[:300].replace("\n", " | ") if leg.count() else None
s.page.screenshot(path=str(__import__("lib").SHOTS / "72a-01-new-job-page-full.png"), full_page=True)
if card.count(): s.shot(card, "72a-02-batch-control-card")
s.done()
c = Session("requester")
c.go("/user/requester/experiments/")
sels = c.page.locator("form[name=config] select")
labels = c.page.locator("form[name=config]").inner_text()
res["experiments"] = labels[:400].replace("\n", " | ")
log("s72", res)
for k, v in res.items(): print(k, "=>", v)
c.done(); close()
