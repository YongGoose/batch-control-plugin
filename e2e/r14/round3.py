"""e2e-14 G2b: the key e2e-11 round-3 checks on the merged main, with explicit assertions.

usage: python round3.py [ABCDEFGHI]   rows: out/round3.jsonl, shots: R3-<sec>-*.png
A folder-only scope (D-65): folder-page dialog as fonly, approve in the browser, then what the window allows,
  incl. the nested delete refusal (ops/sub, ops/mb, ops itself) and a browser save/refusal
B overview badges only (R4-6/D-67): no table/alert, badge counts equal the list rows
C tab look (R4-7): our tab bar vs core's new build page tabs
D model-link (R4-11): dashboard, request detail, activation list/detail; chevron opens core's menu
E dashboard 50 + History links (R4-13)
F Pending/Active/Ended lists (R4-8/D-66) on requests, activations, grants; footers; no horizontal scroll at 1280
G revoke on the window's detail page (R4-12): holder sees no Revoke, manager revokes, holder loses access
H request dialogs from the job page (new: requester, classic: classic) and the folder page; UUID landing
I UUID ids (D-68): new ids are UUIDs, unknown ids 404"""
import json, re, sys
from lib import Session, close, api, groovy, BASE, clean
import lib

WANT = sys.argv[1] if len(sys.argv) > 1 else "ABCDEFGHI"
UUID = r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
J = lambda full: "".join(f"/job/{p}" for p in full.split("/"))  # noqa
KNOWN = ("MIME type ('text/html')", "Jumplist request failed: TypeError: Failed to fetch")
N = {"pass": 0, "fail": 0}
_init = Session.__init__


def _init_with_stack(self, *a, **kw):
    """Records the stack of an uncaught page error as well (e2e-13 U-1: core header.js breadcrumb overflow)."""
    _init(self, *a, **kw)
    self.stacks = []
    self.page.on("pageerror", lambda e: self.stacks.append((e.stack or "")[:400]))


Session.__init__ = _init_with_stack


def check(sec, step, ok, **kw):
    row = dict(sec=sec, step=step, ok=bool(ok), **kw)
    N["pass" if ok else "fail"] += 1
    lib.log("round3", row)
    print(("PASS " if ok else "FAIL ") + json.dumps(row, default=str)[:700])


def console_ok(sec, s, what, expected=()):
    """expected: substrings of responses that the step provoked on purpose (a refused empty submit answers 400).
    A page error whose stack is core's header.js breadcrumb overflow (xmlEscape <- menuItem) is e2e-13 U-1 (core):
    it is recorded as known_core, not as a failure."""
    bad = [b for b in s.bad if not any(e in b for e in expected)]
    codes = {b.split()[0] for b in s.bad if b not in bad}
    stacks = getattr(s, "stacks", [])
    u1 = [st for st in stacks if "xmlEscape" in st and "header.js" in st]
    cons = [c for c in s.console if not any(k in c for k in KNOWN)
            and not any(f"status of {code}" in c for code in codes)
            and not (u1 and "reading 'replace'" in c)]
    check(sec, f"{what}: no console error, no HTTP>=400" + (" (besides the provoked responses)" if expected else ""), not cons and not bad,
          console=cons[:4], bad=bad[:4], provoked=[b for b in s.bad if b not in bad][:2], known_core=u1[:1])


def tick(d, name, value):
    box = d.locator(f"input[name={name}][value={value}]")
    if not box.is_checked():
        box.locator("xpath=following-sibling::label").first.click()


def submit_dialog(s, d, label):
    d.get_by_role("button", name=label).click()
    s.page.wait_for_url(re.compile(r"/batch-control/(grants|requests|activations)/" + UUID + "/$"), timeout=15000)
    s.page.wait_for_load_state("load")
    return s.page.url


def approve_in_browser(user, kind, rid, sec, tag):
    a = Session(user)
    a.go(f"/batch-control/{kind}/{rid}/")
    f = a.page.locator("form[action$='approve'], form[name=approve]").first
    if f.locator("textarea").count():
        f.locator("textarea").first.fill("e2e-14 approve")
    with a.page.expect_navigation():
        f.locator("button[name=Submit], button[type=submit], input[type=submit]").first.click()
    txt = re.sub(r"\s+", " ", a.text())
    a.shot("#main-panel", f"R3-{sec}-{tag}-approved")
    a.done()
    return txt


# ---------------------------------------------------------------- A
def sec_A():
    groovy("""def ops = jenkins.model.Jenkins.get().getItemByFullName('ops')
if (ops.getItem('del-me') == null) ops.createProject(hudson.model.FreeStyleProject, 'del-me')
['fo-new'].each { n -> def i = ops.getItem(n); if (i) i.delete() }
return ops.items*.name""")
    s = Session("fonly")
    s.go("/job/ops/")
    s.page.locator("#tasks a, #tasks button, .jenkins-app-bar a, .jenkins-app-bar button", has_text="Request Change Permission").first.click()
    s.page.wait_for_selector("dialog[open] select[name=scopeType]")
    d = s.page.locator("dialog[open]").first
    url_open = s.page.url
    prefill = (d.locator("select[name=scopeType]").input_value(), d.locator("input[name=scopeFullName]").input_value())
    opts = d.locator("select[name=scopeType] option").all_inner_texts()
    d.locator("select[name=scopeType]").select_option("FOLDER_ONLY")
    s.page.wait_for_timeout(300)
    help_txt = [t.strip() for t in d.locator(".jenkins-form-description, .help").all_inner_texts() if "Folder only" in t][:1]
    for a in ("CREATE", "CONFIGURE", "DELETE"):
        tick(d, "actions", a)
    d.locator("select[name=durationMinutes]").select_option("60")
    d.locator("textarea[name=reason]").fill("e2e-14 folder-only window on ops")
    tick(d, "approvers", "approver-1")
    s.shot("dialog[open]", "R3-A-01-folder-dialog")
    landing = submit_dialog(s, d, "Request Grant")
    gid = re.search(UUID, landing).group(0)
    check("A", "folder page dialog opens in place, pre-filled FOLDER ops, three scope types, lands on UUID detail",
          url_open.rstrip("/").endswith("/job/ops") and prefill == ("FOLDER", "ops") and len(opts) == 3 and gid,
          url_open=url_open.replace(BASE, ""), prefill=prefill, options=opts, help=help_txt, landing=landing.replace(BASE, ""))
    s.shot("#main-panel", "R3-A-02-pending-detail")
    console_ok("A", s, "folder page + dialog")
    s.done()
    txt = approve_in_browser("approver-1", "grants", gid, "A", "02b")
    js = api("admin", f"/batch-control/grants/{gid}/").text
    check("A", "approver-1 approves in the browser", "Approved" in txt or "Open" in txt or "APPROVED" in txt, text=txt[:300])
    st = lambda r: r.status_code  # noqa
    res = {}
    for item in ("ops", "ops/a", "ops/sub", "ops/mb", "ops/sub/b"):
        res[f"configure {item}"] = st(api("fonly", J(item) + "/configure"))
    check("A", "configure: ops, ops/a, ops/sub, ops/mb 200; ops/sub/b 403",
          [res[f"configure {i}"] for i in ("ops", "ops/a", "ops/sub", "ops/mb", "ops/sub/b")] == [200, 200, 200, 200, 403], **res)
    cr = {}
    for parent, name in (("ops", "fo-new"), ("ops/sub", "fo-nested"), ("", "fo-root")):
        base = J(parent) if parent else ""
        r = api("fonly", base + f"/createItem?name={name}&mode=hudson.model.FreeStyleProject", "POST",
                headers={"Content-Type": "application/x-www-form-urlencoded"})
        cr[f"{parent or '(root)'}/{name}"] = (st(r), st(api("admin", base + f"/job/{name}/api/json")))
    check("A", "create: ops/fo-new allowed; ops/sub/fo-nested and root refused",
          cr["ops/fo-new"] == (302, 200) and cr["ops/sub/fo-nested"][1] == 404 and cr["(root)/fo-root"][1] == 404
          and cr["ops/sub/fo-nested"][0] == 403 and cr["(root)/fo-root"][0] == 403, **cr)
    de = {}
    for item in ("ops/del-me", "ops/sub", "ops/mb", "ops", "ops/sub/b"):
        r = api("fonly", J(item) + "/doDelete", "POST")
        de[item] = (st(r), st(api("admin", J(item) + "/api/json")), clean(r.text)[:160] if st(r) >= 400 else "")
    check("A", "delete: ops/del-me deleted; nested folder ops/sub, ops/mb, ops and ops/sub/b refused and still present",
          de["ops/del-me"][:2] == (302, 404) and all(de[i][0] == 403 and de[i][1] == 200 for i in ("ops/sub", "ops/mb", "ops", "ops/sub/b")),
          **{k: v for k, v in de.items()})
    # browser: fonly saves ops/sub configure (allowed), opens ops/sub/b configure (refused); delete ops/sub in the browser
    b = Session("fonly")
    r = b.go("/job/ops/job/sub/configure")
    saved = None
    if r.status == 200:
        b.page.locator("textarea[name=description], textarea[name='_.description']").first.fill("e2e-14 folder-only save ops/sub")
        with b.page.expect_navigation() as nav:
            b.page.locator("button[name=Submit]").first.click()
        saved = (nav.value.status, b.page.url.replace(BASE, ""))
    r2 = b.go("/job/ops/job/sub/job/b/configure")
    b.shot("#main-panel" if b.page.locator("#main-panel").count() else "body", "R3-A-03-nested-configure-refused")
    desc = api("admin", "/job/ops/job/sub/api/json?tree=description").json().get("description")
    check("A", "browser: ops/sub configure saved under the window; ops/sub/b configure refused",
          saved and saved[0] == 200 and desc == "e2e-14 folder-only save ops/sub" and r2.status == 403, saved=saved, nested_status=r2.status, desc=desc)
    # browser delete of the nested folder ops/sub (folder Delete is a confirmation POST from the folder page)
    b.go("/job/ops/job/sub/")
    dl = b.page.locator("#tasks a, #tasks button, a, button", has_text=re.compile(r"^\s*Delete Folder\s*$"))
    info = {"delete_entry": dl.count()}
    if dl.count():
        dl.first.click()
        b.page.wait_for_timeout(800)
        dd = b.page.locator("dialog[open]")
        info["confirm"] = re.sub(r"\s+", " ", dd.first.inner_text())[:160] if dd.count() else None
        if dd.count():
            with b.page.expect_navigation():
                dd.first.locator("button[data-id=ok], button.jenkins-button--primary, button:has-text('Yes'), button:has-text('Delete')").first.click()
            info["landing"] = b.page.url.replace(BASE, "")
            info["text"] = re.sub(r"\s+", " ", b.text())[:300]
            b.shot("#main-panel" if b.page.locator("#main-panel").count() else "body", "R3-A-04-nested-delete-refused")
    info["still_present"] = api("admin", "/job/ops/job/sub/api/json").status_code
    check("A", "browser: Delete Folder on nested ops/sub refused, folder still present",
          info["still_present"] == 200 and (info["delete_entry"] == 0 or "Delete" in info.get("text", "") or "denied" in info.get("text", "").lower()
                                           or "permission" in info.get("text", "").lower()), **info)
    b.done()
    ids = json.loads((lib.HERE / "out" / "ids.json").read_text())
    recs = groovy("""def f = new File(jenkins.model.Jenkins.get().rootDir, 'batch-control/changes/2026-10.jsonl')
return f.readLines().findAll{ it.contains('ops/fo-new') || it.contains('ops/del-me') }.join('\\n')""")
    lines = [json.loads(x) for x in re.findall(r"\{.*?\}", recs)]
    by_fonly = [x for x in lines if x.get("user") == "fonly"]
    newest = {t: max((x for x in by_fonly if x["type"] == t), key=lambda x: x["at"], default=None) for t in ("CREATE", "DELETE")}
    covering = groovy("""def d = new File(jenkins.model.Jenkins.get().rootDir, 'batch-control/grants')
return d.listFiles().findAll{ it.name.endsWith('.xml') }.collect{ new XmlSlurper().parse(it) }.findAll{ g ->
  g.user.text() == 'fonly' && g.scope.type.text() == 'FOLDER_ONLY' && g.scope.fullName.text() == 'ops' }.collect{ g ->
  g.id.text() + ':' + g.grantedAtMillis.text() + ':' + g.expiresAtMillis.text() }.join(',')""").replace("Result: ", "")
    win = {c.split(":")[0]: (int(c.split(":")[1]), int(c.split(":")[2])) for c in covering.split(",") if c}
    ok = all(v and v.get("grantId") in win and win[v["grantId"]][0] <= v["at"] <= win[v["grantId"]][1] for v in newest.values())
    check("A", "this run's change records CREATE ops/fo-new and DELETE ops/del-me by fonly carry a FOLDER_ONLY window of fonly on ops "
          "that was open at that moment (the new one, or an earlier one still open: the first covering window is recorded)",
          ok, newest=newest, new_grant=gid, fonly_folder_only_windows=win)


# ---------------------------------------------------------------- B
def badge_counts(s):
    out = {}
    for a in s.page.locator("nav[data-batch-control-tabs] a").all():
        t = re.sub(r"\s+", " ", a.inner_text()).strip()
        m = re.match(r"(.*?)\s+(\d+)$", t)
        out[m.group(1) if m else t] = int(m.group(2)) if m else None
    return out


def pending_rows(s, section):
    s.go(f"/batch-control/{section}/")
    foot = s.page.locator("#main-panel").inner_text()
    m = re.search(r"Pending Requests.*?Page \d+ \((\d+) (?:request|item)", foot, re.S)
    if m:
        return int(m.group(1))
    t = s.page.locator("#main-panel table").first
    return t.locator("tbody tr").count() if t.count() else 0


def badge_oracle(user, kind):
    """D-61: pending requests awaiting the viewer's decision (designated approver), else the viewer's own pending
    requests; read from the store files (requests/run, requests/grant, activation-requests)."""
    d = {"run": "requests/run", "grant": "requests/grant", "activation": "activation-requests"}[kind]
    out = groovy("""def dir = new File(jenkins.model.Jenkins.get().rootDir, 'batch-control/%s')
def appr = 0, own = 0
dir.listFiles().findAll{ it.name.endsWith('.xml') }.each { f ->
  def x = new XmlSlurper().parse(f)
  if (x.status.text() != 'PENDING') return
  if (x.approvers.string*.text().contains('%s')) appr++
  if (x.requester.text() == '%s') own++
}
return appr + ',' + own""" % (d, user, user)).replace("Result: ", "")
    appr, own = (int(x) for x in out.split(","))
    return appr if appr else (own or None)


def sec_B():
    for user in ("admin", "requester", "approver-1", "manager", "reqonly"):
        s = Session(user)
        s.go("/batch-control/")
        tabs = badge_counts(s)
        ov = {"tables": s.page.locator("#main-panel table").count(),
              "alerts": s.page.locator("#main-panel .jenkins-alert, #main-panel .alert, #main-panel .jenkins-notice").count(),
              "text": re.sub(r"\s+", " ", s.text())[:220]}
        s.shot("#main-panel", f"R3-B-{user}-overview")
        cmp = {}
        for tab, kind in (("Run Requests", "run"), ("Activations", "activation"), ("Grants", "grant")):
            if tab in tabs:
                cmp[tab] = (tabs[tab], badge_oracle(user, kind))
        check("B", f"{user}: overview has no table/alert; badges equal the D-61 count", ov["tables"] == 0 and ov["alerts"] == 0
              and all(a == b for a, b in cmp.values()), tabs=tabs, badge_vs_expected=cmp, **ov)
        console_ok("B", s, f"{user} overview + lists")
        s.done()


# ---------------------------------------------------------------- C
CS = """sel => { const n = document.querySelector(sel); if (!n) return null;
  const cur = n.querySelector('[aria-current=page], [aria-selected=true], .active') || [...n.querySelectorAll('a.jenkins-button')].find(a => !a.classList.contains('jenkins-button--tertiary')); const other = [...n.querySelectorAll('a')].find(a => a !== cur);
  const cs = e => { const c = getComputedStyle(e); const b = getComputedStyle(e, '::before'); return {bg: c.backgroundColor, color: c.color, radius: c.borderRadius, h: Math.round(e.getBoundingClientRect().height), pad: c.padding, font: c.fontSize + '/' + c.fontWeight, beforeBg: b.backgroundColor}; };
  return {cls: n.className, items: [...n.querySelectorAll('a')].map(a => a.innerText.trim()), current: cur && cur.innerText.trim(), curStyle: cur && cs(cur), otherStyle: other && cs(other), navH: Math.round(n.getBoundingClientRect().height), scrollW: document.documentElement.scrollWidth}; }"""


def sec_C():
    s = Session("admin")
    s.go("/user/admin/experiments/")
    s.page.locator("tr", has_text="new-build-page.flag").locator("select").select_option(label="Enabled")
    s.page.click("button[name=Submit]"); s.page.wait_for_load_state("load")
    s.go("/job/fast/1/")
    try:
        s.page.wait_for_selector(".app-build-tabs", timeout=8000)
    except Exception:
        s.go("/job/fast/1/")
    core = s.page.evaluate(CS, ".app-build-tabs")
    if core:
        s.shot(".app-build-tabs", "R3-C-core-build-tabs")
    s.go("/batch-control/history/")
    ours = s.page.evaluate(CS, "nav[data-batch-control-tabs]")
    s.shot("nav[data-batch-control-tabs]", "R3-C-ours-history")
    same = core and ours and core["curStyle"] and all(core["curStyle"][k] == ours["curStyle"][k] for k in ("radius", "h", "font", "beforeBg", "color"))
    other = core and ours and core["otherStyle"] and all(core["otherStyle"][k] == ours["otherStyle"][k] for k in ("radius", "h", "beforeBg"))
    check("C", "current and other tabs look like core's new build page tabs (radius, height, font, colour, ::before); "
          "inline padding 12px vs core's 16px is the documented tabs.jelly tweak (DEF-05 fix, one row at 1280)",
          same and other and ours["current"] == "History" and ours["navH"] <= 40 and ours["scrollW"] <= 1280, core=core, ours=ours)
    s.go("/user/admin/experiments/")
    s.page.locator("tr", has_text="new-build-page.flag").locator("select").select_option(index=0)
    s.page.click("button[name=Submit]"); s.page.wait_for_load_state("load")
    s.done()


# ---------------------------------------------------------------- D, E
def chevron_menu(s, link):
    link.hover(); s.page.wait_for_timeout(700)
    chev = [c for c in s.page.locator(".jenkins-menu-dropdown-chevron").all() if c.is_visible()]
    if not chev:
        return None
    chev[0].click(); s.page.wait_for_timeout(1200)
    m = [t.strip()[:160] for t in s.page.locator(".tippy-box").all_inner_texts()][:1]
    s.page.keyboard.press("Escape")
    return m


def sec_D():
    ids = json.loads((lib.HERE / "out" / "ids.json").read_text())
    s = Session("admin")
    for p, name in (("/batch-control/dashboard/", "dashboard"), (f"/batch-control/requests/{ids['req_executed']}/", "request-detail"),
                    ("/batch-control/activations/", "activation-list"), (f"/batch-control/activations/{ids['act_approved']}/", "activation-detail"),
                    ("/batch-control/requests/", "requests-list"), ("/batch-control/grants/", "grants-list")):
        s.go(p)
        jl = s.page.locator("#main-panel a[href*='/job/']")
        ml = s.page.locator("#main-panel a.model-link[href*='/job/']")
        menu = chevron_menu(s, ml.first) if ml.count() else None
        if ml.count():
            s.shot(ml.first.locator("xpath=.."), f"R3-D-{name}")
        expect_links = name not in ("requests-list", "grants-list")
        check("D", f"{name}: job/run links carry model-link and the chevron opens core's menu",
              (jl.count() == ml.count() and ml.count() > 0 and menu) if expect_links else jl.count() == ml.count(),
              job_links=jl.count(), model_links=ml.count(), menu=menu, note="" if expect_links else "list without job links is UX 5 of e2e-11")
    console_ok("D", s, "model-link pages")
    s.done()


def sec_E():
    s = Session("approver-1")
    s.go("/batch-control/dashboard/")
    t = s.page.locator("#main-panel table").first
    rows = t.locator("tbody tr").count()
    first = [x.strip() for x in t.locator("tbody tr td:first-child").all_inner_texts()][:4]
    hist = [(a.inner_text().strip(), a.get_attribute("href")) for a in s.page.locator("#main-panel a", has_text=re.compile("History"))
            .all() if "history" in (a.get_attribute("href") or "")]
    intro = re.sub(r"\s+", " ", s.text())[:260]
    s.shot("#main-panel", "R3-E-dashboard")
    landing = None
    if hist:
        with s.page.expect_navigation() as nav:
            s.page.locator("#main-panel a[href*='history']").first.click()
        landing = (nav.value.status, s.page.url.replace(BASE, ""))
    total = api("admin", "/batch-control/history/runs.csv?from=2026-09-01&to=2026-10-31").text.count("\n") - 1
    check("E", "dashboard shows exactly 50 runs (more exist), 2 History links, link lands on History", rows == 50 and total > 50 and len(hist) == 2
          and landing and landing[0] == 200 and "/batch-control/history/" in landing[1], rows=rows, runs_total=total, first=first, history_links=hist,
          landing=landing, intro=intro)
    console_ok("E", s, "dashboard")
    s.done()


# ---------------------------------------------------------------- F
LAYOUT = """() => { const mp = document.querySelector('#main-panel');
  const heads = [...mp.querySelectorAll('h2')].map(h => h.innerText.trim());
  const firstH2 = mp.querySelector('h2');
  const formsBefore = [...mp.querySelectorAll('form')].filter(f => firstH2 && (f.compareDocumentPosition(firstH2) & Node.DOCUMENT_POSITION_FOLLOWING)).length;
  const footers = [...new Set([...mp.querySelectorAll('*')].filter(e => e.childElementCount < 4 && /^Page \\d+ \\(/.test((e.innerText || '').trim())).map(e => e.innerText.trim()))];
  const tables = [...mp.querySelectorAll('table')].map(t => ({rows: t.tBodies[0] ? t.tBodies[0].rows.length : 0,
     idLinks: t.tBodies[0] ? [...t.tBodies[0].rows].slice(0, 3).map(r => { const a = r.cells[0] && r.cells[0].querySelector('a'); return a ? a.getAttribute('href') : null; }) : []}));
  return {heads, formsBefore, footers, tables, scrollW: document.documentElement.scrollWidth}; }"""


def sec_F():
    for user in ("admin", "approver-1", "requester", "fonly"):
        s = Session(user)
        for sec in ("requests", "activations", "grants"):
            r = s.go(f"/batch-control/{sec}/")
            if r.status != 200:
                check("F", f"{user} {sec}: refused as expected", (user, sec) in (("fonly", "requests"),), status=r.status)
                continue
            lay = s.page.evaluate(LAYOUT)
            if not lay["tables"] and not lay["heads"]:
                empty = re.sub(r"\s+", " ", s.text())
                check("F", f"{user} {sec}: nothing to list, the page says so and how to file one", "No " in empty and "To file one" in empty,
                      text=empty[:260])
                continue
            s.page.screenshot(path=str(lib.SHOTS / f"R3-F-{user}-{sec}.png"), full_page=True)
            order_ok = [h for h in lay["heads"] if h in ("Pending Requests", "Active", "Approved", "Ended")]
            ids_ok = all(h is None or re.search(UUID + "/$", h) for t in lay["tables"] for h in t["idLinks"])
            check("F", f"{user} {sec}: H2 Pending -> Active -> Ended, no form above, ID links to UUID detail, no h-scroll at 1280",
                  len(order_ok) >= 3 and order_ok[0] == "Pending Requests" and order_ok[-1] == "Ended" and lay["formsBefore"] == 0
                  and ids_ok and lay["scrollW"] <= 1280, **lay)
        console_ok("F", s, f"{user} lists", expected=(("403 GET " + BASE + "/batch-control/requests/"),) if user == "fonly" else ())
        s.done()


# ---------------------------------------------------------------- G
def sec_G():
    r = api("requester", "/batch-control/grants/create", "POST", data=[("scopeType", "JOB"), ("scopeFullName", "prod/ok-move"), ("actions", "CONFIGURE"),
                                                                     ("durationMinutes", "15"), ("reason", "e2e-14 revoke test"), ("approvers", "approver-1")])
    gid = re.search(UUID, r.headers.get("Location", "")).group(0)
    api("approver-1", f"/batch-control/grants/{gid}/approve", "POST", data={"comment": "ok"})
    before = api("requester", "/job/prod/job/ok-move/configure").status_code
    h = Session("requester")
    h.go(f"/batch-control/grants/{gid}/")
    hb = [b.inner_text().strip() for b in h.page.locator("#main-panel button, #main-panel a.jenkins-button").all() if b.inner_text().strip()]
    h.shot("#main-panel", "R3-G-01-holder-detail")
    h.done()
    m = Session("manager")
    m.go(f"/batch-control/grants/{gid}/")
    rv = m.page.locator("#main-panel a, #main-panel button", has_text=re.compile(r"^\s*Revoke\s*$"))
    info = {"holder_buttons": hb, "manager_revoke": rv.count(), "before": before}
    if rv.count():
        rv.first.click(); m.page.wait_for_timeout(700)
        d = m.page.locator("dialog[open]")
        info["confirm"] = re.sub(r"\s+", " ", d.first.inner_text())[:200] if d.count() else None
        m.shot("dialog[open]", "R3-G-02-confirm")
        d.first.locator("button", has_text="Cancel").click(); m.page.wait_for_timeout(500)
        info["after_cancel"] = api("requester", "/job/prod/job/ok-move/configure").status_code
        rv.first.click(); m.page.wait_for_timeout(700)
        with m.page.expect_navigation():
            m.page.locator("dialog[open]").first.locator("button[data-id=ok], button.jenkins-button--primary, button.jenkins-button--destructive").first.click()
        info["state_text"] = re.search(r"State (.{0,40})", re.sub(r"\s+", " ", m.text())).group(0)
        m.shot("#main-panel", "R3-G-03-revoked")
    info["after"] = api("requester", "/job/prod/job/ok-move/configure").status_code
    console_ok("G", m, "manager revoke")
    m.done()
    check("G", "holder sees no Revoke; manager's Cancel keeps the window; Revoke ends it; configure 200 -> 403",
          "Revoke" not in hb and info["manager_revoke"] == 1 and info.get("after_cancel") == 200 and before == 200 and info["after"] == 403
          and "Revoked by manager" in info.get("state_text", ""), **info)
    r = api("requester", f"/batch-control/grants/{gid}/revoke", "POST")
    check("G", "holder's direct POST to revoke refused", r.status_code in (403, 404), status=r.status_code)


# ---------------------------------------------------------------- H
def run_dialog(s, where, tag):
    s.page.locator(where, has_text="Request Run").first.click()
    s.page.wait_for_selector("dialog[open] textarea[name=reason]")
    url_open = s.page.url
    d = s.page.locator("dialog[open]").first
    d.get_by_role("button", name="Submit Request").click()
    s.page.wait_for_timeout(1500)
    stays = s.page.locator("dialog[open]").count() == 1
    errs = [t.strip() for t in s.page.locator("dialog[open] .error, dialog[open] .jenkins-alert").all_inner_texts() if t.strip()][:3]
    d = s.page.locator("dialog[open]").first
    d.locator("textarea[name=reason]").fill(f"e2e-14 {tag} run dialog")
    tick(d, "approvers", "approver-1")
    s.shot("dialog[open]", f"R3-H-{tag}-run-dialog")
    landing = submit_dialog(s, d, "Submit Request")
    return url_open, stays, errs, landing


def grant_dialog(s, where, tag):
    s.page.locator(where, has_text="Request Change Permission").first.click()
    s.page.wait_for_selector("dialog[open] select[name=scopeType]")
    url_open = s.page.url
    d = s.page.locator("dialog[open]").first
    pre = (d.locator("select[name=scopeType]").input_value(), d.locator("input[name=scopeFullName]").input_value())
    tick(d, "actions", "CONFIGURE")
    d.locator("select[name=durationMinutes]").select_option("15")
    d.locator("textarea[name=reason]").fill(f"e2e-14 {tag} grant dialog")
    tick(d, "approvers", "approver-2")
    s.shot("dialog[open]", f"R3-H-{tag}-grant-dialog")
    landing = submit_dialog(s, d, "Request Grant")
    return url_open, pre, landing


def sec_H():
    # new job page: requester, app bar Request Run, overflow menu Request Change Permission
    s = Session("requester")
    s.go("/job/batch-daily/")
    u, stays, errs, landing = run_dialog(s, ".jenkins-app-bar a, .jenkins-app-bar button, [data-testid] button, a, button", "new")
    st = api("requester", re.sub(r"^.*?/batch-control/", "/batch-control/", landing)).status_code
    check("H", "new job page: Request Run dialog in place, empty submit stays with errors, full submit lands on UUID detail (200)",
          u.endswith("/job/batch-daily/") and stays and errs and re.search(UUID, landing) and st == 200, url_open=u.replace(BASE, ""), errors=errs,
          landing=landing.replace(BASE, ""), server=st)
    s.go("/job/batch-pipeline/")
    ov = s.page.locator("[data-testid=app-bar-overflow-button], button[aria-label='More actions'], button:has-text('More actions')")
    info = {"overflow": ov.count()}
    if ov.count():
        ov.first.click(); s.page.wait_for_timeout(1000)
        u, pre, landing = grant_dialog(s, ".tippy-box a, .tippy-box button, .jenkins-dropdown a, .jenkins-dropdown button", "new")
        info.update(url_open=u.replace(BASE, ""), prefill=pre, landing=landing.replace(BASE, ""))
    check("H", "new job page: More actions -> Request Change Permission dialog pre-filled JOB batch-pipeline, lands on UUID grant",
          info.get("prefill") == ("JOB", "batch-pipeline") and re.search("/grants/" + UUID, info.get("landing", "")), **info)
    console_ok("H", s, "new job page dialogs", expected=("400 POST",))
    s.done()
    # classic: user classic
    groovy("""def u = hudson.model.User.getById('classic', true); def m = new HashMap(); m.put('new-job-page.flag', 'false')
u.addProperty(new jenkins.model.experimentalflags.UserExperimentalFlagsProperty(m)); u.save(); return 'ok'""")
    s = Session("classic")
    s.go("/job/batch-pipeline/")
    u, stays, errs, landing = run_dialog(s, "#tasks a, #tasks button", "classic")
    check("H", "classic side panel: Request Run dialog, validation, UUID landing", stays and errs and re.search("/requests/" + UUID, landing),
          url_open=u.replace(BASE, ""), errors=errs, landing=landing.replace(BASE, ""))
    s.go("/job/batch-daily/")
    u, pre, landing = grant_dialog(s, "#tasks a, #tasks button", "classic")
    check("H", "classic side panel: Request Change Permission pre-filled JOB batch-daily, UUID landing",
          pre == ("JOB", "batch-daily") and re.search("/grants/" + UUID, landing), url_open=u.replace(BASE, ""), prefill=pre, landing=landing.replace(BASE, ""))
    console_ok("H", s, "classic dialogs", expected=("400 POST",))
    s.done()
    # folder page: requester on team (FOLDER)
    s = Session("requester")
    s.go("/job/team/")
    u, pre, landing = grant_dialog(s, "#tasks a, #tasks button, .jenkins-app-bar a, .jenkins-app-bar button", "folder")
    check("H", "folder page team: Request Change Permission pre-filled FOLDER team, UUID landing",
          pre == ("FOLDER", "team") and re.search("/grants/" + UUID, landing), url_open=u.replace(BASE, ""), prefill=pre, landing=landing.replace(BASE, ""))
    console_ok("H", s, "folder dialog")
    s.done()


# ---------------------------------------------------------------- I
def sec_I():
    ids = json.loads((lib.HERE / "out" / "ids.json").read_text())
    uu = {k: v for k, v in ids.items() if isinstance(v, str) and k[0] in "rag" and "_" in k}
    check("I", "every seeded request/activation/grant id is a UUID", all(re.fullmatch(UUID, v) for v in uu.values()), n=len(uu))
    res = {}
    for p in ("/batch-control/grants/00000000-0000-4000-8000-000000000000/", "/batch-control/grants/no-such-id/",
              "/batch-control/requests/00000000-0000-4000-8000-000000000000/", "/batch-control/activations/00000000-0000-4000-8000-000000000000/",
              f"/batch-control/requests/{ids['g_active_job']}/", f"/batch-control/grants/{ids['req_executed']}/",
              "/batch-control/grants/..%2F..%2Fconfig.xml/", "/batch-control/requests/20261004-000000-abcdef/"):
        res[p] = api("admin", p).status_code
    check("I", "unknown ids, an id under the wrong kind, traversal and the old timestamp format: 404", all(v in (404, 400) for v in res.values()), **res)
    xml = groovy("""def d = new File(jenkins.model.Jenkins.get().rootDir, 'batch-control')
return ['grants','requests/run','requests/grant','requests/activation'].collect{ s -> def f = new File(d, s); s + '=' + (f.exists() ? f.list().findAll{it.endsWith('.xml')}.take(2) : 'missing') }.join('; ')""")
    check("I", "store file names are UUIDs (ARCHITECTURE storage layout)", re.search(UUID + r"\.xml", xml) is not None, files=xml)


for sec in WANT:
    try:
        globals()["sec_" + sec]()
    except Exception as e:  # noqa
        check(sec, "section raised", False, error=repr(e)[:400])
close()
print("SUMMARY round3", N)
