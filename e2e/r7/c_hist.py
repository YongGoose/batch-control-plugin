"""C4/C5/D/E: history family gates + CSV, mails, earlier-round spot checks, dark theme at 1280."""
import re, time
from lib import Session, close, api, log, clean, mails
res = {}
pages = ["/batch-control/history/", "/batch-control/dashboard/", "/batch-control/incidents/", "/batch-control/changes/"]
csvs = ["/batch-control/history/export.csv", "/batch-control/changes/export.csv", "/batch-control/incidents/export.csv", "/batch-control/requests/export.csv"]
# find CSV links as auditor
h = api("auditor", "/batch-control/history/").text
res["csv_links_history"] = sorted(set(re.findall(r'href="([^"]*\.csv[^"]*)"', h)))
for u in ["auditor", "approver-1", "reqonly", "requester", "nobc"]:
    res[f"gate_{u}"] = {p: api(u, p).status_code for p in pages}
links = set()
for p in pages:
    links |= set(re.findall(r'href="(/jenkins/batch-control/[^"]*csv[^"]*)"', api("auditor", p).text))
res["csv_links"] = sorted(links)
for l in sorted(links):
    rel = l.replace("/jenkins", "", 1)
    a = api("auditor", rel); b = api("reqonly", rel)
    res[f"csv {rel}"] = (a.status_code, a.headers.get("Content-Type"), a.text.splitlines()[0][:140] if a.text else "", len(a.text.splitlines()), b.status_code)
# MARKER/TRIGGER in changes.csv
c = api("auditor", "/batch-control/changes/export.csv")
res["changes_csv_types"] = sorted(set(l.split(",")[1] for l in c.text.splitlines()[1:] if "," in l))[:20] if c.status_code == 200 else c.status_code
# mails
ms = mails()
res["mail_subjects"] = sorted(set(re.sub(r"\d{8}-\d{6}-\w+", "<id>", m["Subject"]) for m in ms))
# D spot checks
d = {}
d["nobc_root"] = api("nobc", "/batch-control/").status_code
d["nobc_sub"] = api("nobc", "/batch-control/requests/").status_code
d["auditor_requests"] = api("auditor", "/batch-control/requests/").status_code
d["reqonly_grants"] = api("reqonly", "/batch-control/grants/").status_code
allhtml = ""
for p in ["/batch-control/", "/batch-control/requests/", "/batch-control/grants/", "/batch-control/activations/"] + pages + ["/manage/batch-control-configuration/", "/job/batch-daily/batch-control/"]:
    allhtml += api("admin", p).text
t = clean(allhtml)
d["spec_item_text"] = bool(re.search(r"SPEC item|SPEC section|\bD-\d\d\b", t))
d["back_links"] = len(re.findall(r">\s*(Back|&laquo; Back|← Back)[^<]{0,20}<", allhtml))
d["table_small"] = allhtml.count("jenkins-table--small"), allhtml.count("jenkins-table ")
d["ionicons_symbols"] = sorted(set(re.findall(r"symbol-[a-z-]+ plugin-ionicons-api", allhtml)))[:8]
d["jenkins_select_in_grants"] = "jenkins-select" in api("requester", "/batch-control/grants/").text
d["permission_ids"] = re.findall(r"BatchControl/\w+|io\.jenkins\.plugins\.batchcontrol\.security\.BatchControlPermissions\.\w+", api("admin", "/manage/configureSecurity/").text)[:3]
mon = api("admin", "/manage/").text
d["admin_monitor_ids"] = sorted(set(re.findall(r'administrativeMonitor/(batch-control[\w-]*)', mon)))
# unchanged save writes no CONFIGURE record (plugin-version noise)
before = clean(api("admin", "/batch-control/changes/").text).count("CONFIGURE batch-cron")
x = api("admin", "/job/batch-cron/config.xml").text
x2 = re.sub(r'plugin="([\w-]+)@[^"]+"', r'plugin="\1@0.0-e2e"', x)
d["unchanged_post"] = api("admin", "/job/batch-cron/config.xml", "POST", data=x2.encode(), headers={"Content-Type": "application/xml"}).status_code
time.sleep(1)
d["configure_records_added"] = clean(api("admin", "/batch-control/changes/").text).count("CONFIGURE batch-cron") - before
res["D"] = d
# cron timing
b = api("admin", "/job/batch-cron/api/json?tree=builds[number,timestamp,actions[causes[shortDescription]]]").json()["builds"]
res["cron_first"] = (b[-1]["number"], time.strftime("%H:%M:%S", time.localtime(b[-1]["timestamp"] / 1000)), b[-1]["actions"][0].get("causes", [{}])[0].get("shortDescription") if b[-1]["actions"] else None, len(b))
# dark theme at 1280
s = Session("approver-1", dark=True)
s.go("/user/approver-1/appearance/")
try:
    s.page.locator("input[value=dark]").first.check(force=True)
    s.page.click("button[name=Submit]"); s.page.wait_for_load_state("load")
except Exception as e:
    res["theme_err"] = str(e)[:120]
for p, n in [("/batch-control/requests/", "E-dark-requests"), ("/batch-control/grants/", "E-dark-grants"), ("/batch-control/history/", "E-dark-history"), ("/batch-control/requests/20261003-123921-mf58tt/", "E-dark-detail"), ("/job/batch-daily/", "E-dark-job")]:
    s.go(p)
    s.page.screenshot(path=str(__import__("lib").SHOTS / f"{n}.png"), full_page=False)
    res[n] = s.page.evaluate("() => { const b = getComputedStyle(document.body); const n = document.querySelector('nav[data-batch-control-tabs]'); return [b.backgroundColor, b.color, n ? n.getBoundingClientRect().height : null, document.documentElement.scrollWidth] }")
s.done(); close()
log("c_hist", res)
for k, v in res.items(): print(k, v)
