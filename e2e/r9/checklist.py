"""Reviewer checklist quick pass (HTML/REST): items 4,5,7,8,9,11,16,19 and the 2026-09-27 spot checks."""
import re, json
from lib import api, log, clean
res = {}
cfg = api("admin", "/manage/configure").text
m = api("admin", "/manage/").text
res["5 manage link -> settings subpage"] = re.findall(r'href="([^"]*batch-control[^"]*)"', m)[:3]
bc = api("admin", "/manage/batch-control/") ; res["5 subpage GET"] = bc.status_code
res["5 subpage breadcrumbs"] = re.findall(r'breadcrumbs__list-item[^>]*>(?:<a[^>]*>)?([^<]+)', bc.text)[:4]
res["9 Apply button on subpage"] = bool(re.search(r'name="Apply"|>Apply<', bc.text))
res["8 alert with leading <p>"] = len(re.findall(r'class="jenkins-alert[^"]*"[^>]*>\s*<p', m))
sec = api("admin", "/manage/configureSecurity/").text
res["4/7 strategy names"] = sorted(set(re.findall(r"Batch Control:[^<\"]{0,60}Strateg[^<\"]*", sec)))
res["7 legacy strategy present"] = "BatchControlAuthorizationStrategy\"" in sec
cm = api("admin", "/batch-control/contextMenu"); res["11 contextMenu"] = (cm.status_code, [i.get("displayName") for i in cm.json().get("items", [])] if cm.status_code == 200 else None)
job = api("requester", "/job/batch-daily/").text
res["16 Direct Build href"] = re.findall(r'href="([^"]*build[^"]*)"[^>]*>[^<]*(?:<[^>]+>)*\s*Direct Build', job)[:1] or re.findall(r'"[^"]*/build\?delay=0sec[^"]*"', job)[:1]
res["16 build form GET"] = api("requester", "/job/batch-daily/build?delay=0sec").status_code
act = api("requester", "/batch-control/activations/20261003-175454-ko5ri1/").text
res["19 destructive buttons"] = re.findall(r'class="[^"]*jenkins-button--destructive[^"]*"[^>]*>\s*([^<]*)', act)[:3] or re.findall(r'data-destructive[^>]*', act)[:2]
pages = ["/batch-control/", "/batch-control/requests/", "/batch-control/activations/", "/batch-control/grants/", "/batch-control/changes/", "/batch-control/history/", "/batch-control/dashboard/", "/batch-control/incidents/"]
spec, small, back = [], [], []
for p in pages:
    h = api("admin", p).text
    if re.search(r"SPEC (item|§)|SPEC\s+\d", clean(h)): spec.append(p)
    if "<table" in h and "jenkins-table--small" not in h: small.append(p)
    if re.search(r">\s*(Back to|&larr;|←)", h): back.append(p)
res["27 'SPEC item' text"] = spec; res["27 tables without jenkins-table--small"] = small; res["27 back links"] = back
res["27 doIndex (GET /batch-control/index)"] = api("admin", "/batch-control/index").status_code
for u in ["nobc", "requester", "approver-1", "auditor"]:
    h = api(u, "/").text
    res[f"27 sidebar link {u}"] = "/batch-control/" in h
f = api("admin", "/job/team/configure").text
res["27 folder matrix property on folder config"] = "AuthorizationMatrixProperty" in f or "Enable project-based security" in f
log("checklist", res)
for k, v in res.items(): print(k, "=>", v)
