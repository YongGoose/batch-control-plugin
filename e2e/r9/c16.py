import re
from lib import Session, close, log, api
res = {}
s = Session("requester")
s.go("/job/batch-daily/")
bar = s.page.locator("[data-testid=app-bar-overflow-button]").first.locator("xpath=..")
d = bar.locator("a, button", has_text="Direct Build").first
res["direct build attrs"] = d.evaluate("e => [e.tagName, e.getAttribute('href'), e.getAttribute('data-href') || e.getAttribute('data-url')]")
d.click(); s.page.wait_for_timeout(2500)
res["after click url"] = s.page.url.replace("http://localhost:8080", "")
res["dialog/form"] = [re.sub(r"\s+", " ", t)[:200] for t in s.page.locator("dialog[open], form[name=parameters]").all_inner_texts()]
s.shot("dialog[open], form[name=parameters], #main-panel", "C16-01-direct-build-params")
s.go("/")
res["dashboard bc links"] = s.page.locator("a[href*='batch-control']").evaluate_all("els => els.map(e => e.getAttribute('href') + ' ' + e.innerText.trim())")[:5]
s.done(); close()
for k, v in res.items(): print(k, "=>", v)
log("c16", res)
