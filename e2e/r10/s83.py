"""#83: Move refusal page offers pre-filled Delete/Create window links per missing permission; links open the grants form pre-filled."""
import re
from lib import Session, close, log, api, SHOTS
res = {}
s = Session("mover1")
r = s.go("/job/prod/job/y/move/")
res["move page"] = r.status
res["options"] = s.page.locator('select[name="destination"] option').all_inner_texts()
res["move page hints"] = [t for t in s.text().split("\n") if re.search(r"window|Delete|Create|permission", t)][:6]
s.shot("#main-panel", "83-01-move-page")
s.page.select_option('select[name="destination"]', "/ops")
with s.page.expect_navigation() as nav:
    s.page.click('button[name="Submit"]')
s.page.wait_for_load_state("load")
res["refusal status"] = nav.value.status
res["refusal text"] = s.text()[:900].replace("\n", " | ")
links = s.page.locator("#main-panel a", has_text=re.compile("window", re.I))
res["window links"] = links.evaluate_all("els => els.map(e => [e.innerText.trim(), e.getAttribute('href')])")
s.shot("#main-panel", "83-02-refusal")
refusal = s.page.url
for i, (txt, href) in enumerate(res["window links"]):
    s.page.goto(refusal) if i else None
    if i == 0:
        s.page.locator("#main-panel a", has_text=txt).first.click(); s.page.wait_for_load_state("load")
    else:
        s.go(href.replace("/jenkins", "", 1))
    f = s.page.locator("select[name=scopeType]")
    res[f"link {txt}"] = {"landed": s.page.url.replace("http://localhost:8080", ""),
        "scopeType": f.input_value() if f.count() else None,
        "scopeFullName": s.page.locator("input[name=scopeFullName]").input_value() if f.count() else None,
        "checked actions": s.page.locator("input[name=actions]:checked").evaluate_all("els => els.map(e => e.value)")}
    s.shot("form", f"83-03-{i}-grant-form")
# both missing: a destination the select does not offer (no Create), posted from the same browser session
s.go("/job/prod/job/y/move/")
s.page.evaluate("""() => { const sel = document.querySelector('select[name=destination]'); const o = document.createElement('option'); o.value = '/team'; o.text = 'team (crafted)'; sel.appendChild(o); sel.value = '/team'; }""")
with s.page.expect_navigation() as nav:
    s.page.click('button[name="Submit"]')
s.page.wait_for_load_state("load")
res["team refusal status"] = nav.value.status
res["team refusal text"] = s.text()[:700].replace("\n", " | ")
res["team window links"] = s.page.locator("#main-panel a", has_text=re.compile("window", re.I)).evaluate_all("els => els.map(e => [e.innerText.trim(), e.getAttribute('href')])")
s.shot("#main-panel", "83-04-refusal-both")
for i, (txt, href) in enumerate(res["team window links"]):
    s.go(href.replace("/jenkins", "", 1))
    f = s.page.locator("select[name=scopeType]")
    res[f"team link {txt}"] = {"scopeType": f.input_value() if f.count() else None,
        "scopeFullName": s.page.locator("input[name=scopeFullName]").input_value() if f.count() else None,
        "checked actions": s.page.locator("input[name=actions]:checked").evaluate_all("els => els.map(e => e.value)")}
    s.shot("form", f"83-05-{i}-grant-form")
s.done(); close()
res["mv-job still at source"] = api("admin", "/job/prod/job/y/api/json").status_code
for k, v in res.items(): print(k, "=>", v)
log("s83", res)
