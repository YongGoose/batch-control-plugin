"""#76: tab badges equal the expected pending counts (designated approver, requester, bystander)."""
import re
from lib import api, log, Session, close
res = {}
# arrange two pending grant requests by requester: one to approver-1, one to approver-2
for ap in ["approver-1", "approver-2"]:
    r = api("requester", "/batch-control/grants/create", "POST", data=[("scopeFullName", "batch-daily"),
        ("durationMinutes", "30"), ("reason", f"e2e-09 #76 to {ap}"), ("approvers", ap), ("actions", "CONFIGURE")])
    res[f"grant to {ap}"] = (r.status_code, r.headers.get("Location"))
def badges(u):
    h = api(u, "/batch-control/").text
    out = {}
    for tab, body in re.findall(r'data-batch-control-tab="(\w+)"[^>]*>(.*?)</a>', h, re.S):
        m = re.search(r'tooltip="([^"]*)"[^>]*>(\d+)<', body)
        out[tab] = (int(m.group(2)), m.group(1)) if m else 0
    return out
# independent expectation: count PENDING rows on the admin's section lists by requester / approvers
def rows(section):
    h = api("admin", f"/batch-control/{section}/").text
    ids = sorted(set(re.findall(r'href="[^"]*?(\d{8}-\d{6}-\w+)/"', h)))
    out = []
    for i in ids:
        t = re.sub(r"<[^>]+>", " ", api("admin", f"/batch-control/{section}/{i}/").text)
        t = re.sub(r"\s+", " ", t)
        st = re.search(r"Status (\w+)", t); rq = re.search(r"Requester ([\w-]+)", t); ap = re.search(r"Approvers ([\w\-, ]+?) (?:Created|Reason|Requested|Scope|Duration|Job|Action)", t)
        out.append((i, st and st.group(1), rq and rq.group(1), ap and [a.strip() for a in ap.group(1).split(",")]))
    return out
state = {s: rows(s) for s in ["requests", "activations", "grants"]}
res["pending state"] = {s: [r for r in v if r[1] == "PENDING"] for s, v in state.items()}
approvers = {"approver-1", "approver-2", "approver-unlisted", "approver-disc", "admin"}
def expect(u, s):
    d = m = 0
    for i, st, rq, ap in state[s]:
        if st != "PENDING": continue
        if u in approvers and ap and u in ap: d += 1
        elif rq == u: m += 1
    return d + m
tabs = {"requests": "requests", "activations": "activations", "grants": "grants"}
for u in ["approver-1", "approver-2", "requester", "reqonly", "approver-unlisted", "auditor", "admin"]:
    b = badges(u)
    exp = {s: expect(u, s) for s in tabs}
    got = {s: (b.get(s)[0] if isinstance(b.get(s), tuple) else b.get(s)) for s in tabs}
    res[u] = {"badges": b, "expected": exp, "match": all((got[s] or 0) == exp[s] for s in tabs if s in b)}
# section-level awaiting count for approver-1 on Activations (same predicate)
s = Session("approver-1"); s.go("/batch-control/activations/")
res["approver-1 activations section text"] = re.findall(r"[^\n]*(?:awaiting|Awaiting)[^\n]*", s.text())[:3]
s.shot("nav[data-batch-control-tabs]", "76-01-approver1-tabs"); s.shot("#main-panel", "76-02-approver1-activations")
s.done()
for u in ["requester", "approver-unlisted"]:
    x = Session(u); x.go("/batch-control/"); x.shot("nav[data-batch-control-tabs]", f"76-03-{u}-tabs"); x.done()
close()
for k, v in res.items(): print(k, "=>", v)
log("s76", res)
