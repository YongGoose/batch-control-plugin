"""e2e-19: item kinds and parameter types the earlier passes had no fixture for (e2e-16 NOT covered list).

usage: python kinds.py [KMCRFT]   rows: out/kinds.jsonl, shots: R19-KIND-*.png   (run r19/arrange.py first)
K  item kinds of the new fixtures on the grant form's item check: a multi-configuration project ("Multi-configuration
   project") and an organization folder ("Organization Folder"), each with its icon (SPEC 8 D-71 "records the item's
   kind"); a matrix configuration r19-mx/A=a is refused at submission (SPEC 8 D-71a)
M  windows on them (SPEC 8 D-71): CREATE on the matrix project and on the organization folder, DELETE on the
   organization folder are refused at submission; a CONFIGURE window on the organization folder confers its configure
   page and its approval page carries the group-configure notice; a DELETE window on the matrix project (a Job) is
   accepted, approved, and lets the requester delete it (DELETE change record by requester); arrange re-creates it
C  credentials parameter through the Request Run page (#111 valuePage) and an approved run: the build receives the
   chosen credential id; the request page shows the id or ******** (the value's own sensitivity decides, SPEC 5); the
   credential's secret is nowhere (page, store, console)
   (coverage inventory G-M1)
R  run parameter through the Request Run page and an approved run: requester picks r19-src #1 (not the first offered);
   the build receives job r19-src and number 1; the request page shows it (G-M2)
F  rerun of an incident whose build was deleted (G-L6): an approved run of r19-delb with a core file and MODE fails; the
   administrator deletes that build; "Request Rerun" on the incident cannot recover the file, so it opens the job's
   Request Run form prefilled with MODE, carrying the incident (validated link back), and asking for UPLOAD again
   (SPEC 11 D-72)
T  the log tail of the seeded batch-failing incident (the build prints its password parameter): masked on the incident
   page and in the stored incident (SPEC 11 R-4)"""
import re
import sys
import time
from urllib.parse import quote

sys.path.insert(0, __file__.rsplit("/", 1)[0])
import lib  # noqa: E402
from lib import (Session, api, gv, check, note, text_of, J, run_sections, next_build, wait_build, wait_executed,
                 browser_decide, fill_run_form, param_box, make_file, loc_id, BASE)  # noqa: E402

lib.LOGNAME[0] = "kinds"
WANT = sys.argv[1] if len(sys.argv) > 1 else "KMCRFT"
U = "requester"


def grant(scope, actions, reason):
    data = [("scopeFullName", scope), ("durationMinutes", "15"), ("reason", reason), ("approvers", "approver-1")]
    data += [("actions", a) for a in actions]
    r = api(U, "/batch-control/grants/create", "POST", data=data)
    return r, loc_id(r)


def sec_K():
    for name, word in (("r19-mx", "Multi-configuration project"), ("r19-org", "Organization Folder")):
        r = api(U, "/batch-control/grants/checkScopeFullName?value=" + quote(name, safe=""), "POST")
        t = text_of(r.text).strip()
        check("K", f"the item check names the kind of {name}: {word}, with its icon (SPEC 8 D-71)",
              r.status_code == 200 and word in t and "<svg" in r.text and "data-batch-control-item-kind" in r.text,
              status=r.status_code, text=t[:160])
    r, gid = grant("r19-mx/A=a", ["CONFIGURE"], "e2e-19 matrix configuration")
    check("K", "a window on a matrix configuration (a sub-item of a job) is refused at submission (SPEC 8 D-71a)",
          r.status_code == 400 and gid is None, status=r.status_code, body=text_of(r.text)[:200])
    s = Session(U)
    s.go("/batch-control/grants/")
    entry = s.page.locator("a, button", has_text=re.compile(r"Request (a )?(Change )?Permission|New request|Request a window", re.I))
    if entry.count():
        entry.first.click()
        s.page.wait_for_timeout(1200)
    field = s.page.locator("input[name=scopeFullName]:visible").first
    if field.count():
        field.fill("r19-org")
        field.blur()
        s.page.wait_for_timeout(1500)
        s.shot("input[name=scopeFullName]", "R19-KIND-K-01-org-kind")
        seen = s.page.locator("[data-batch-control-item-kind]").all_inner_texts()
        check("K", "typing the organization folder's name on the grant form shows its kind", any("Organization Folder" in x for x in seen),
              shown=[x for x in seen if "r19-org" in x][:2])
    else:
        note("K", "grant form field not found on the grants page; the REST item check above stands")
    s.done()


def sec_M():
    rc, _ = grant("r19-mx", ["CREATE"], "e2e-19 create in a matrix project")
    ro, _ = grant("r19-org", ["CREATE"], "e2e-19 create in an organization folder")
    rd, _ = grant("r19-org", ["DELETE"], "e2e-19 delete an organization folder")
    check("M", "CREATE on the matrix project and the organization folder, DELETE on the organization folder: refused at "
          "submission (SPEC 8 D-71)", rc.status_code == 400 and ro.status_code == 400 and rd.status_code == 400
          and "applies only to a folder" in text_of(rc.text) and "applies only to a job" in text_of(rd.text),
          create_mx=rc.status_code, create_org=ro.status_code, delete_org=rd.status_code)
    before = api(U, J("r19-org") + "/configure").status_code
    r, gid = grant("r19-org", ["CONFIGURE"], "e2e-19 configure the organization folder")
    assert gid, (r.status_code, text_of(r.text)[:200])
    s = Session("approver-1")
    s.go(f"/batch-control/grants/{gid}/")
    notice = s.page.locator("[data-batch-control-notice=group-configure]")
    ntext = notice.first.inner_text() if notice.count() else ""
    s.shot("#main-panel", "R19-KIND-M-01-org-approval")
    s.done()
    check("M", "the approval page of CONFIGURE on the organization folder says the group's settings apply to the items inside "
          "it and that reconfiguring it can create or delete generated items (SPEC 8 D-71a)",
          bool(ntext) and "organization folder" in ntext.lower(), notice=ntext[:300])
    assert lib.decide("approver-1", "grants", gid, "approve") in (200, 302)
    after = api(U, J("r19-org") + "/configure").status_code
    check("M", "the CONFIGURE window confers the organization folder's configure page (G-M7)", before == 403 and after == 200,
          before=before, after=after)
    r, gid = grant("r19-mx", ["DELETE"], "e2e-19 delete the matrix project")
    check("M", "DELETE on the matrix project (a Job) is accepted at submission (SPEC 8 D-71)", gid is not None,
          status=r.status_code, body=text_of(r.text)[:200])
    if not gid:
        return
    assert lib.decide("approver-1", "grants", gid, "approve") in (200, 302)
    mark = lib.changes_mark()
    s = Session(U)
    s.go(J("r19-mx") + "/")
    s.shot("#main-panel", "R19-KIND-M-02-mx-page")
    s.done()
    r = api(U, J("r19-mx") + "/doDelete", "POST")
    gone = api("admin", J("r19-mx") + "/api/json").status_code == 404
    rec = lib.changes_since(mark, lambda x: x.get("type") == "DELETE" and x.get("target") == "r19-mx")
    check("M", "the DELETE window lets the requester delete the matrix project; the DELETE record names requester",
          gone and any(x.get("user") == U for x in rec), status=r.status_code, gone=gone, records=[(x.get("user"), x.get("grantId")) for x in rec])


def submit_page(job, reason, prep):
    s = Session(U)
    s.go(J(job) + "/batch-control/")
    form = s.page.locator("form[name=batch-control-request]").first
    fill_run_form(form, reason, "approver-1")
    prep(form, s)
    s.shot("form[name=batch-control-request]", f"R19-KIND-{job}-form")
    with s.page.expect_navigation(timeout=20000):
        form.locator("button[name=Submit], button[type=submit]").first.click()
    s.page.wait_for_load_state("load")
    m = re.search(r"/batch-control/requests/([0-9a-f-]{36})/", s.page.url)
    t = s.text()
    s.done()
    return (m.group(1) if m else None), t


def detail_params(rid):
    r = api("admin", f"/batch-control/requests/{rid}/")
    rows = dict(re.findall(r"<tr>\s*<td>([^<]+)</td>\s*<td[^>]*>(.*?)</td>\s*</tr>", r.text, re.S))
    return {k.strip(): text_of(v).strip() for k, v in rows.items()}, r.text


def sec_C():
    job = "r19-cred"
    seen = {}

    def prep(form, s):
        box = param_box(form, "CRED")
        sel = box.locator("select").first
        seen["options"] = sel.locator("option").evaluate_all("os => os.map(o => o.value)") if sel.count() else []
        if sel.count() and "r19-cred-id" in seen["options"]:
            sel.select_option("r19-cred-id")
        box2 = param_box(form, "NOTE")
        box2.locator("input[name=value]").first.fill("cred-note")

    nb = next_build(job)
    rid, t = submit_page(job, "e2e-19 credentials parameter", prep)
    check("C", "the Request Run page renders the credentials parameter with its own value page (a select offering the "
          "credential id) and the request is created", rid is not None and "r19-cred-id" in seen.get("options", []),
          options=seen.get("options"), page=t[:200] if not rid else None)
    if not rid:
        return
    disp, html = detail_params(rid)
    assert lib.decide("approver-1", "requests", rid, "approve") in (200, 302)
    result, console = wait_build(job, nb)
    store = lib._l.store_contains("r19-cred-secret")
    note("C", "how the request page shows the credentials parameter (masked when the value reports itself sensitive)",
         shown=disp.get("CRED"))
    check("C", "the approved build receives the credential id; the request shows the id or the mask, never the credential's "
          "secret, which is on no page, in no stored request, in no console (G-M1, SPEC 5 masking)",
          result == "SUCCESS" and "CRED=r19-cred-id" in console and disp.get("CRED") in ("r19-cred-id", "********")
          and "r19-cred-secret" not in html and "r19-cred-secret" not in console and store == "",
          result=result, display=disp, store_hits=store)


def sec_R():
    job = "r19-run"
    seen = {}

    def prep(form, s):
        box = param_box(form, "SRC")
        sel = box.locator("select").first
        seen["options"] = sel.locator("option").evaluate_all("os => os.map(o => [o.value, o.textContent.trim()])") if sel.count() else []
        target = [v for v, txt in seen["options"] if v.endswith("#1") or txt.strip().endswith("#1")]
        if target:
            sel.select_option(target[0])
        seen["picked"] = target[:1]

    nb = next_build(job)
    rid, t = submit_page(job, "e2e-19 run parameter", prep)
    first_offered = (seen.get("options") or [[None]])[0][0]
    check("R", "the Request Run page offers r19-src's builds for the run parameter; #1 (not the first offered) is picked and "
          "the request is created", rid is not None and seen.get("picked") and first_offered not in seen.get("picked"),
          options=seen.get("options"), picked=seen.get("picked"))
    if not rid:
        return
    disp, _ = detail_params(rid)
    assert lib.decide("approver-1", "requests", rid, "approve") in (200, 302)
    result, console = wait_build(job, nb)
    check("R", "the approved build receives r19-src #1 and the request page shows it (G-M2)",
          result == "SUCCESS" and "SRC_JOBNAME=r19-src" in console and "SRC_NUMBER=1" in console and "r19-src" in disp.get("SRC", "")
          and "1" in disp.get("SRC", ""), result=result, display=disp,
          console=[l for l in console.splitlines() if l.startswith("SRC_")][:2])


def newest_incident(job, after_millis):
    for _ in range(60):
        out = gv(f"""def d=new File(jenkins.model.Jenkins.get().rootDir,'batch-control/incidents'); def o=[]
if(d.exists()) d.listFiles().findAll{{it.name.endsWith('.xml')}}.each{{f-> def x=new XmlSlurper().parse(f)
  if(x.jobFullName.text()=='{job}' && (x.createdAtMillis.text() as long) >= {after_millis}) o<<x.id.text()}}; return o.join(',')""")
        if out:
            return out.split(",")[-1]
        time.sleep(2)
    return None


def sec_F():
    job = "r19-delb"
    gv(f"new File(jenkins.model.Jenkins.get().rootDir, '{job}.arm').text = 'x'; return 'armed'")
    t0 = lib.now_ms()
    up, _ = make_file("r19-delb.bin", 1500, "r19-delb")
    nb = next_build(job)
    s = Session(U)
    s.go(J(job) + "/batch-control/")
    form = s.page.locator("form[name=batch-control-request]").first
    fill_run_form(form, "e2e-19 run that fails, build deleted later", "approver-1", {"MODE": "m-deleted"}, {"UPLOAD": str(up)})
    with s.page.expect_navigation(timeout=20000):
        form.locator("button[name=Submit], button[type=submit]").first.click()
    m = re.search(r"/batch-control/requests/([0-9a-f-]{36})/", s.page.url)
    s.done()
    assert m, "initial request"
    assert lib.decide("approver-1", "requests", m.group(1), "approve") in (200, 302)
    result, _ = wait_build(job, nb)
    iid = newest_incident(job, t0)
    check("F", "the approved run fails and an incident is registered", result == "FAILURE" and iid, result=result, incident=iid)
    if not iid:
        return
    r = api("admin", J(job) + f"/{nb}/doDelete", "POST")
    gone = api("admin", J(job) + f"/{nb}/api/json").status_code == 404
    check("F", "the administrator deleted the failed build", gone, status=r.status_code)
    s = Session("admin")
    s.go(f"/batch-control/incidents/{iid}/")
    f = s.page.locator("form[name=rerun]").first
    box = f.locator("input[name=approvers][value='approver-1']")
    if box.count() and not box.is_checked():
        box.locator("xpath=following-sibling::label").first.click()
    with s.page.expect_navigation(timeout=20000):
        f.locator("button[name=Submit], button[type=submit], input[type=submit]").first.click()
    s.page.wait_for_load_state("load")
    url = s.page.url
    notice = s.page.locator("[data-batch-control-notice=rerun]")
    ntext = re.sub(r"\s+", " ", notice.first.inner_text()) if notice.count() else ""
    link = s.page.locator(f"[data-batch-control-notice=rerun] a[href*='incidents/{iid}']").count() > 0
    mode = param_box(s.page.locator("form[name=batch-control-request]").first, "MODE").locator("input[name=value]").first.input_value() \
        if s.page.locator("form[name=batch-control-request]").count() else None
    s.shot("#main-panel", "R19-KIND-F-01-fallback-form")
    s.done()
    check("F", "the rerun of a deleted build opens the prefilled Request Run form: MODE carried, a validated link to the "
          "incident, UPLOAD asked for again; no request created (SPEC 11 D-72, G-L6)",
          "/batch-control/" in url and "/requests/" not in url and mode == "m-deleted" and link and "UPLOAD" in ntext,
          landing=url.replace(BASE, ""), mode=mode, link=link, notice=ntext[:300])


def sec_T():
    """batch-failing (seeded by r14/seed.py, built by it) prints its password parameter TOKEN on purpose."""
    token = "tok-e2e-secret-4711"
    iid = gv("""def d=new File(jenkins.model.Jenkins.get().rootDir,'batch-control/incidents'); def o=''
if(d.exists()) d.listFiles().findAll{it.name.endsWith('.xml')}.sort{it.name}.each{f-> def x=new XmlSlurper().parse(f)
  if(x.jobFullName.text()=='batch-failing') o=x.id.text()}; return o""")
    if not iid:
        check("T", "an incident of batch-failing exists (setup seed)", False)
        return
    s = Session("approver-1")
    s.go(f"/batch-control/incidents/{iid}/")
    t = s.text()
    s.shot("#main-panel", "R19-KIND-T-01-logtail")
    s.done()
    stored = gv(f"""def f=new File(jenkins.model.Jenkins.get().rootDir,'batch-control/incidents/{iid}.xml'); return f.text.contains('{token}') ? 'plaintext' : 'masked'""")
    check("T", "the incident's log tail shows the build's password parameter masked, on the page and in the store (SPEC 11 R-4)",
          "batch-failing: token=" in t and token not in t and stored == "masked", stored=stored,
          line=[l for l in t.splitlines() if "token=" in l][:1])


run_sections(WANT, {"K": sec_K, "M": sec_M, "C": sec_C, "R": sec_R, "F": sec_F, "T": sec_T})
