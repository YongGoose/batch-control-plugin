"""e2e-14 G1: DEF-07 recheck in Chrome. History "Filter", Changes "Show" and Incidents "Show" must produce URLs with
only the filter parameters (no Jenkins-Crumb, no json), list the right rows, and the pager and CSV/JSON export
links must keep the filter. Run once per job page UI (the flag is set per account by set_flag.py first).

usage: python def07.py <new|classic> [role ...]     rows: out/def07.jsonl, shots: D7-<ui>-<role>-<step>.png"""
import csv, io, json, re, sys
from urllib.parse import urlparse, parse_qsl
from lib import Session, close, BASE
import lib
import datetime

# The controller's current/previous month and a 30-day range ending today (the original run used the literals
# 2026-10, 2026-09 and 2026-09-05..2026-10-04 of its day, 2026-10-04).
MONTH, PREV = lib.month(), lib.month(-1)
TO = lib.jenkins_today()
FROM = TO - datetime.timedelta(days=29)

UI = sys.argv[1]
ROLES = sys.argv[2:] or ["approver-1", "admin", "manager"]
FORBIDDEN = {"Jenkins-Crumb", "json", ".crumb"}
KNOWN = ("MIME type ('text/html')", "Jumplist request failed: TypeError: Failed to fetch")  # D-70; core model-link prefetch aborted by navigation
results = []


def q(url):
    return parse_qsl(urlparse(url).query, keep_blank_values=True)


def check(role, step, ok, **kw):
    row = dict(ui=UI, role=role, step=step, ok=bool(ok), **kw)
    results.append(row)
    lib.log("def07", row)
    print(("PASS " if ok else "FAIL ") + json.dumps(row)[:600])


def rows(s):
    return s.page.locator("#main-panel table").first.locator("tbody tr")


def submit(s, button_text, how="click", field=None):
    form = s.page.locator("form[data-batch-control-get-form]")
    hidden_before = form.first.evaluate("f => Array.from(f.querySelectorAll('input[type=hidden]')).map(i => i.name)")
    with s.page.expect_navigation():
        if how == "enter":
            s.page.locator(field).press("Enter")
        else:
            form.first.locator("button[type=submit]", has_text=button_text).click()
    s.page.wait_for_load_state("load")
    return hidden_before


def clean_url(url, allowed):
    keys = [k for k, _ in q(url)]
    return not (set(keys) & FORBIDDEN) and set(keys) <= set(allowed), keys


def fetch(s, href):
    r = s.context.request.get(urljoin(s.page.url, href))
    return r.status, r.headers.get("content-type", ""), r.text()


def urljoin(base, href):
    from urllib.parse import urljoin as j
    return j(base, href)


HKEYS = ["kind", "from", "to", "job", "user", "result", "status", "page"]


def history(role):
    s = Session(role)
    tag = f"D7-{UI}-{role}"
    # H1: Filter by job with the button
    s.go("/batch-control/history/?kind=runs")
    s.page.fill("#filter-job", "fast")
    hidden = submit(s, "Filter")
    ok, keys = clean_url(s.page.url, HKEYS)
    jobs = sorted(set(t.strip() for t in s.page.locator("#main-panel table").first.locator("tbody tr td:nth-child(2)").all_inner_texts()))
    s.shot(["#main-panel form[data-batch-control-get-form]", "#main-panel table"], f"{tag}-H1-filter-job")
    check(role, "H1 history Filter job=fast: clean URL", ok and dict(q(s.page.url)).get("job") == "fast",
          url=s.page.url.replace(BASE, ""), keys=keys, hidden_before_submit=hidden)
    check(role, "H1 rows are only fast", jobs == ["fast"] and rows(s).count() > 0, jobs=jobs, rows=rows(s).count())
    # pager keeps the filter
    older = s.page.locator("#main-panel a[href*='page=2']")
    hrefs = [a.get_attribute("href") for a in older.all()]
    if older.count():
        with s.page.expect_navigation():
            older.first.click()
        jobs2 = sorted(set(t.strip() for t in s.page.locator("#main-panel table").first.locator("tbody tr td:nth-child(2)").all_inner_texts()))
        d = dict(q(s.page.url))
        check(role, "H1 pager (page 2) keeps job=fast", d.get("job") == "fast" and d.get("page") == "2" and jobs2 == ["fast"]
              and not (set(d) & FORBIDDEN), url=s.page.url.replace(BASE, ""), jobs=jobs2, pager_hrefs=hrefs, rows=rows(s).count())
        newer = s.page.locator("#main-panel a", has_text=re.compile("Newer|Previous"))
        if newer.count():
            with s.page.expect_navigation():
                newer.first.click()
            d = dict(q(s.page.url))
            check(role, "H1 pager back (Newer) keeps job=fast", d.get("job") == "fast" and not (set(d) & FORBIDDEN),
                  url=s.page.url.replace(BASE, ""))
        s.go("/batch-control/history/?kind=runs&job=fast")
    else:
        check(role, "H1 pager present for 60 fast runs", False, pager_hrefs=hrefs)
    # kind buttons keep the filter
    kinds = {a.inner_text().strip(): a.get_attribute("href") for a in s.page.locator("#main-panel .jenkins-buttons-row a.jenkins-button").all()
             if a.inner_text().strip() in ("Runs", "Incidents", "Changes", "Requests")}
    check(role, "H1 kind buttons keep job=fast", all("job=fast" in (h or "") for h in kinds.values()) and len(kinds) == 4, kinds=kinds)
    # CSV exports keep the filter and contain only fast
    csvs = {a.get_attribute("href"): None for a in s.page.locator("#main-panel a[href*='.csv']").all()}
    for href in list(csvs):
        st, ct, body = fetch(s, href)
        data = list(csv.reader(io.StringIO(body)))
        hdr = data[0] if data else []
        jcol = next((i for i, h in enumerate(hdr) if h in ("job", "jobFullName", "target")), None)
        vals = sorted(set(r[jcol] for r in data[1:] if jcol is not None and len(r) > jcol))
        csvs[href] = {"status": st, "type": ct.split(";")[0], "rows": len(data) - 1, "jobs": vals[:5], "header": hdr[:6]}
    run_csv = next((v for k, v in csvs.items() if k.startswith("runs.csv")), None)
    check(role, "H1 CSV links keep job=fast; runs.csv only fast", csvs and all("job=fast" in k for k in csvs)
          and run_csv and run_csv["status"] == 200 and run_csv["jobs"] == ["fast"] and run_csv["type"] == "text/csv", csvs=csvs)

    # H2: Enter in a text field, user + result
    s.go("/batch-control/history/?kind=runs")
    s.page.fill("#filter-result", "FAILURE")
    s.page.fill("#filter-user", "admin")
    hidden = submit(s, "Filter", how="enter", field="#filter-user")
    ok, keys = clean_url(s.page.url, HKEYS)
    res = sorted(set(t.strip() for t in s.page.locator("#main-panel table").first.locator("tbody tr td:nth-child(6)").all_inner_texts()))
    s.shot(["#main-panel form[data-batch-control-get-form]", "#main-panel table"], f"{tag}-H2-enter-result")
    check(role, "H2 Enter submits; clean URL; result=FAILURE rows only", ok and res in (["FAILURE"], []) and dict(q(s.page.url)).get("result") == "FAILURE",
          url=s.page.url.replace(BASE, ""), keys=keys, results=res, rows=rows(s).count(), hidden_before_submit=hidden)

    # H3: Requests kind with a status filter
    s.go("/batch-control/history/?kind=requests")
    s.page.fill("#filter-status", "PENDING")
    submit(s, "Filter")
    ok, keys = clean_url(s.page.url, HKEYS)
    txt = s.page.locator("#main-panel table").first.inner_text() if s.page.locator("#main-panel table").count() else ""
    hdr = [t.strip() for t in s.page.locator("#main-panel table thead th").all_inner_texts()]
    si = next((i for i, h in enumerate(hdr) if h.lower() in ("status", "state")), None)
    sts = sorted(set(t.strip() for t in s.page.locator("#main-panel table").first.locator(f"tbody tr td:nth-child({(si or 0) + 1})").all_inner_texts())) if si is not None else []
    csvl = [a.get_attribute("href") for a in s.page.locator("#main-panel a[href*='requests.csv']").all()]
    pager = [a.get_attribute("href") for a in s.page.locator("#main-panel a[href*='page=']").all()]
    s.shot(["#main-panel form[data-batch-control-get-form]", "#main-panel table"], f"{tag}-H3-requests-status")
    check(role, "H3 requests status=PENDING: clean URL, rows PENDING, CSV/pager keep status",
          ok and dict(q(s.page.url)).get("kind") == "requests" and sts == ["PENDING"] and all("status=PENDING" in h for h in csvl + pager) and csvl,
          url=s.page.url.replace(BASE, ""), statuses=sts, rows=rows(s).count(), csv=csvl, pager=pager[:3])
    if pager:
        with s.page.expect_navigation():
            s.page.locator("#main-panel a[href*='page=2']").first.click()
        sts2 = sorted(set(t.strip() for t in s.page.locator("#main-panel table").first.locator(f"tbody tr td:nth-child({(si or 0) + 1})").all_inner_texts()))
        check(role, "H3 requests page 2 keeps status=PENDING", dict(q(s.page.url)).get("status") == "PENDING" and sts2 == ["PENDING"],
              url=s.page.url.replace(BASE, ""), statuses=sts2)

    # H4: "Monthly summary" and "Monthly aggregate as JSON" links from the filtered page
    s.go(f"/batch-control/history/?kind=runs&job=fast&from={FROM}&to={TO}")
    links = {a.inner_text().strip(): a.get_attribute("href") for a in s.page.locator("#main-panel a").all()
             if a.inner_text().strip() in ("Monthly summary", "Monthly aggregate as JSON")}
    info = {"links": links}
    if "Monthly aggregate as JSON" in links:
        st, ct, body = fetch(s, links["Monthly aggregate as JSON"])
        info.update(status=st, type=ct.split(";")[0], body=body[:200])
    s.shot(["#main-panel a:has-text('Monthly aggregate as JSON')", "#main-panel a:has-text('Monthly summary')"], f"{tag}-H4-links")
    check(role, "H4 JSON export 200 application/json for the filtered month (month-level aggregate, by design not per job)",
          info.get("status") == 200 and "json" in info.get("type", "") and f"month={MONTH}" in links.get("Monthly aggregate as JSON", ""), **info)
    if "Monthly summary" in links:
        with s.page.expect_navigation():
            s.page.locator("#main-panel a", has_text="Monthly summary").first.click()
        mon = {"url": s.page.url.replace(BASE, ""), "nav": [a.get_attribute("href") for a in s.page.locator("#main-panel a[href*='month=']").all()][:6]}
        prev = s.page.locator(f"#main-panel a[href*='month={PREV}']")
        if prev.count():
            with s.page.expect_navigation():
                prev.first.click()
            mon["after_prev"] = s.page.url.replace(BASE, "")
        s.shot("#main-panel", f"{tag}-H4-monthly")
        check(role, "H4 monthly page and its month navigation", "month=" in mon["url"] and not (set(dict(q(s.page.url))) & FORBIDDEN), **mon)
    cons = [c for c in s.console if not any(k in c for k in KNOWN)]
    check(role, "H console/HTTP>=400 on history pages", not cons and not s.bad, console=cons[:5], bad=s.bad[:5])
    s.done()


def month_page(role, section):
    s = Session(role)
    tag = f"D7-{UI}-{role}-{section}"
    s.go(f"/batch-control/{section}/")
    n0 = rows(s).count()
    s.page.fill("#main-panel input[name=month]", MONTH)
    hidden = submit(s, "Show")
    keys = [k for k, _ in q(s.page.url)]
    n1 = rows(s).count()
    s.shot(["#main-panel form[data-batch-control-get-form]", "#main-panel table"], f"{tag}-show")
    check(role, f"{section} Show month={MONTH}: URL only month", keys == ["month"] and dict(q(s.page.url))["month"] == MONTH,
          url=s.page.url.replace(BASE, ""), keys=keys, hidden_before_submit=hidden)
    check(role, f"{section} rows after Show equal the default month", n1 == n0 and n1 > 0, rows_default=n0, rows_shown=n1)
    pager = [a.get_attribute("href") for a in s.page.locator("#main-panel a[href*='page=']").all()]
    if pager:
        first = s.page.locator("#main-panel table tbody tr").first.inner_text()[:60]
        with s.page.expect_navigation():
            s.page.locator("#main-panel a[href*='page=2']").first.click()
        d = dict(q(s.page.url))
        second = s.page.locator("#main-panel table tbody tr").first.inner_text()[:60]
        check(role, f"{section} pager keeps month", d.get("month") == MONTH and d.get("page") == "2" and first != second and not (set(d) & FORBIDDEN),
              url=s.page.url.replace(BASE, ""), pager=pager)
    else:
        check(role, f"{section} pager (not shown: fewer rows than a page)", True, rows=n1, note="no pager on this month")
    # previous month via the input and via the link
    s.go(f"/batch-control/{section}/")
    s.page.fill("#main-panel input[name=month]", PREV)
    submit(s, "Show")
    keys = [k for k, _ in q(s.page.url)]
    check(role, f"{section} Show month={PREV} clean", keys == ["month"], url=s.page.url.replace(BASE, ""), rows=rows(s).count())
    cons = [c for c in s.console if not any(k in c for k in KNOWN)]
    check(role, f"{section} console/HTTP>=400", not cons and not s.bad, console=cons[:5], bad=s.bad[:5])
    s.done()


for r in ROLES:
    history(r)
    month_page(r, "changes")
    month_page(r, "incidents")
close()
print("SUMMARY", UI, sum(r["ok"] for r in results), "pass /", sum(not r["ok"] for r in results), "fail")
