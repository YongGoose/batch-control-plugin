"""e2e-16: one-item permission windows (D-71, SPEC item 8), through the browser form and REST.

usage: python items.py [JFMKLD]     rows: out/items.jsonl, shots: R16-ITEM-*.png
J  CONFIGURE window on a job (r16/job-a): the browser form shows no scope type selector, the name check shows the
   kind (Freestyle) with its icon and data-batch-control-item-kind; the window configures that job only, nothing else
F  CONFIGURE window on a folder (r16): configures the folder itself only (not its children); CREATE window on the
   folder creates directly inside it only, never in a nested folder; the detail page shows the kind + icon
M  a multibranch project (team-mb) and (if present) an organization folder: a CONFIGURE window names it; CREATE and
   DELETE are refused at submission (a computed folder is not a modifiable group and is not a job)
K  the item check / kind badge for each kind (Folder, Freestyle, Pipeline, Multibranch) and a hostile item name:
   the answer carries the descriptor id, an icon, the exact text, and no injected attribute
L  a request stored with a legacy scope type (JOB/FOLDER/FOLDER_ONLY) cannot be approved (D-69/D-71)
D  DELETE: refused at submission on a folder, a multibranch project (item groups); allowed on a job; a job Delete
   window deletes that job"""
import re
import sys
from urllib.parse import quote

sys.path.insert(0, __file__.rsplit("/", 1)[0])
import lib  # noqa: E402
from lib import (Session, api, gv, check, note, grant_req, window, decide, grant_files, text_of, J, BASE,
                 run_sections, console_ok, tick, KIND_RE, loc_id, revoke_all)  # noqa: E402

lib.LOGNAME[0] = "items"
WANT = sys.argv[1] if len(sys.argv) > 1 else "JFMKLD"
U = "w16"


def st(user, path, method="GET", **kw):
    return api(user, path, method, **kw).status_code


def open_grant_dialog(s):
    """Open the Request Change Permission dialog from the current job/folder page, via the app bar, the side panel,
    or the new job page's overflow ('More actions') menu."""
    entry = s.page.locator(".jenkins-app-bar a, .jenkins-app-bar button, #tasks a, #tasks button",
                           has_text="Request Change Permission")
    if entry.count() == 0 or not entry.first.is_visible():
        ov = s.page.locator("[data-testid=app-bar-overflow-button], button[aria-label='More actions']")
        if ov.count():
            ov.first.click(); s.page.wait_for_timeout(800)
            entry = s.page.locator(".tippy-box a, .tippy-box button, .jenkins-dropdown a, .jenkins-dropdown button",
                                   has_text="Request Change Permission")
    entry.first.click()
    s.page.wait_for_selector("dialog[open] input[name=scopeFullName]")
    return s.page.locator("dialog[open]").first


def dialog_kind(d):
    k = d.locator("[data-batch-control-item-kind]")
    try:
        k.first.wait_for(timeout=6000)
    except Exception:
        return None, None, False
    return (k.first.get_attribute("data-batch-control-item-kind"), re.sub(r"\s+", " ", k.first.inner_text()).strip(),
            k.first.locator("svg").count() > 0)


def sec_J():
    revoke_all(U)  # D-71: assert the job window confers on the job only, independent of other sections/leftovers
    s = Session(U)
    s.go("/job/r16/job/job-a/")
    d = open_grant_dialog(s)
    selector = d.locator("select[name=scopeType]").count()
    scope = d.locator("input[name=scopeFullName]").input_value()
    kind, kind_text, icon = dialog_kind(d)
    s.shot("dialog[open]", "R16-ITEM-J-01-job-dialog")
    tick(d, "actions", "CONFIGURE")
    d.locator("select[name=durationMinutes]").select_option("30")
    d.locator("textarea[name=reason]").fill("e2e-16 configure job-a")
    tick(d, "approvers", "approver-1")
    d.get_by_role("button", name="Request Grant").click()
    s.page.wait_for_url(re.compile(r"/grants/[0-9a-f-]{36}/$"), timeout=15000)
    gid = re.search(r"/grants/([0-9a-f-]{36})/", s.page.url).group(1)
    console_ok("J", s, "job grant dialog")
    s.done()
    check("J", "job page dialog: no scope type selector; prefilled job-a; kind Freestyle with icon and descriptor id",
          selector == 0 and scope == "r16/job-a" and kind and re.search(KIND_RE["Freestyle"], kind) and icon and "Freestyle" in (kind_text or ""),
          scope_type_selector=selector, scope=scope, kind=kind, kind_text=kind_text, icon=icon)
    assert decide("approver-1", "grants", gid, "approve") in (200, 302)
    res = {"configure r16/job-a": st(U, "/job/r16/job/job-a/configure"),
           "configure r16 (parent)": st(U, "/job/r16/configure"),
           "configure r16/kinds-job (sibling)": st(U, "/job/r16/job/kinds-job/configure"),
           "configure r16/sub/job-b (nested)": st(U, "/job/r16/job/sub/job/job-b/configure")}
    check("J", "a CONFIGURE window on the job confers Configure on that job only (200) and on nothing else (403)",
          res["configure r16/job-a"] == 200 and all(v == 403 for k, v in res.items() if k != "configure r16/job-a"), **res)


def sec_F():
    # D-71: assert folder-only reach; start from a clean window state so a job window from sec_J does not leak in,
    # and remove any item left by an earlier run of this section (createItem of an existing name answers 400)
    revoke_all(U)
    for full in ("r16/f-direct", "r16/sub/f-nested", "f-root"):
        if st("admin", J(full) + "/api/json") == 200:
            api("admin", J(full) + "/doDelete", "POST")
    gconf = window(U, "r16", ["CONFIGURE"], reason="e2e-16 configure the folder r16")
    res = {"configure r16 (the folder itself)": st(U, "/job/r16/configure"),
           "configure r16/job-a (child)": st(U, "/job/r16/job/job-a/configure"),
           "configure r16/sub (child folder)": st(U, "/job/r16/job/sub/configure"),
           "configure r16/sub/job-b (nested)": st(U, "/job/r16/job/sub/job/job-b/configure")}
    check("F", "a CONFIGURE window on the folder confers Configure on the folder itself only (200), on no child (403)",
          res["configure r16 (the folder itself)"] == 200 and all(v == 403 for k, v in res.items() if "itself" not in k),
          window=gconf, **res)
    gcreate = window(U, "r16", ["CREATE"], reason="e2e-16 create directly in r16")
    H = {"Content-Type": "application/x-www-form-urlencoded"}
    cr = {}
    for parent, name in (("r16", "f-direct"), ("r16/sub", "f-nested"), ("", "f-root")):
        base = J(parent) if parent else ""
        r = api(U, base + f"/createItem?name={name}&mode=hudson.model.FreeStyleProject", "POST", headers=H)
        cr[f"{parent or '(root)'}/{name}"] = (r.status_code, st("admin", base + f"/job/{name}/api/json"))
    check("F", "a CREATE window on the folder creates directly inside it only (f-direct 302/200), never in a nested folder "
          "(f-nested) or at the root (f-root)",
          cr["r16/f-direct"] == (302, 200) and cr["r16/sub/f-nested"][0] == 403 and cr["r16/sub/f-nested"][1] == 404
          and cr["(root)/f-root"][0] == 403 and cr["(root)/f-root"][1] == 404, **{k: str(v) for k, v in cr.items()})
    # detail page shows the kind with its icon
    s = Session("approver-1")
    s.go(f"/batch-control/grants/{gconf}/")
    el = s.page.locator("#main-panel [data-batch-control-item-kind]").first
    s.shot("#main-panel", "R16-ITEM-F-02-detail-kind")
    detail_kind = el.get_attribute("data-batch-control-item-kind") if el.count() else None
    ok = detail_kind and re.search(KIND_RE["Folder"], detail_kind) and el.locator("svg").count()
    s.done()
    check("F", "the grant detail page shows the item with its kind (Folder) and icon", bool(ok), kind=detail_kind)
    api("admin", "/job/r16/job/f-direct/doDelete", "POST")


COMPUTED = []


def sec_M():
    global COMPUTED
    COMPUTED = [c for c in ("team-mb",) if st("admin", J(c) + "/api/json") == 200]
    org = gv("""def j=jenkins.model.Jenkins.get(); def c=j.pluginManager.getPlugin('cloudbees-folder'); return j.items.findAll{ it.class.name.contains('OrganizationFolder') }*.fullName.join(',')""")
    if org:
        COMPUTED += [o for o in org.split(",") if o]
    note("M", "computed folders present", items=COMPUTED)
    if not COMPUTED:
        check("M", "a computed folder (multibranch/organization folder) is present to test", False, note="none found")
        return
    for mb in COMPUTED:
        gid = window(U, mb, ["CONFIGURE"], reason=f"e2e-16 configure {mb}")
        conf = st(U, J(mb) + "/configure")
        kind = [g for g in grant_files(U) if g[4] == mb]
        check("M", f"a CONFIGURE window names the computed folder {mb} (kind recorded) and confers Configure on it",
              gid and conf == 200 and kind and re.search(KIND_RE["Multibranch"] + "|OrganizationFolder", kind[0][5]),
              window=gid, configure=conf, recorded_kind=kind[0][5] if kind else None)
        rc, _ = grant_req(U, mb, ["CREATE"], reason=f"e2e-16 create on {mb}")
        rd, _ = grant_req(U, mb, ["DELETE"], reason=f"e2e-16 delete on {mb}")
        check("M", f"CREATE and DELETE on the computed folder {mb} are refused at submission (not a modifiable group, not a job)",
              rc.status_code == 400 and rd.status_code == 400 and "applies only to a folder" in text_of(rc.text)
              and "applies only to a job" in text_of(rd.text), create=rc.status_code, delete=rd.status_code)


HOSTILE = "r16-x\"onmouseover='alert(1)' data-injected=\"1"


def sec_K():
    # a hostile-named job to confirm the name check escapes it
    gv(f"""def j=jenkins.model.Jenkins.get(); if (j.getItem({lib.json.dumps(HOSTILE)}) == null) {{ def p=j.createProject(hudson.model.FreeStyleProject, {lib.json.dumps(HOSTILE)}); p.save() }}; return 'ok'""")
    cases = {"r16": "Folder", "r16/job-a": "Freestyle", "r16-pipe": "Pipeline"}
    if COMPUTED:
        cases[COMPUTED[0]] = "Multibranch"
    cases[HOSTILE] = "Freestyle"
    parser = Session(None)
    for name, word in cases.items():
        r = api(U, "/batch-control/grants/checkScopeFullName?value=" + quote(name, safe=""), "POST")
        html = r.text
        text = text_of(html)
        # Parse the answer as HTML in a real browser and check that nothing hostile became an attribute or element.
        parser.page.set_content("<div id=bc-probe>" + html + "</div>")
        parsed = parser.page.evaluate(r"""() => {
          const root = document.getElementById('bc-probe');
          return {kind: root.querySelector('[data-batch-control-item-kind]') ? root.querySelector('[data-batch-control-item-kind]').getAttribute('data-batch-control-item-kind') : null,
                  svg: root.querySelectorAll('svg').length,
                  injected: root.querySelectorAll('[onmouseover], [data-injected]').length,
                  text: (root.innerText || root.textContent || '').replace(/\s+/g, ' ').trim()};
        }""")
        exact = parsed["text"].endswith(f"'{name}'") and parsed["text"].startswith(word.split()[0])
        check("K", f"item check for {name!r}: kind badge ({word}) with descriptor id and an svg, exact text parsed as HTML, no injected attribute",
              r.status_code == 200 and parsed["kind"] and parsed["svg"] >= 1 and parsed["injected"] == 0 and exact,
              status=r.status_code, parsed_text=parsed["text"][:140], injected=parsed["injected"], kind=parsed["kind"])
    parser.done()


def sec_L():
    for legacy in ("JOB", "FOLDER", "FOLDER_ONLY"):
        r, gid = grant_req(U, "r16/job-a", ["CONFIGURE"], reason=f"e2e-16 legacy {legacy}")
        assert gid, (legacy, r.status_code)
        rewritten = gv(f"""def f = new File(jenkins.model.Jenkins.get().rootDir, 'batch-control/requests/grant/{gid}.xml')
def t = f.text; f.text = t.replace('<type>ITEM</type>', '<type>{legacy}</type>'); return f.text.contains('<type>{legacy}</type>')""")
        appr = api("approver-1", f"/batch-control/grants/{gid}/approve", "POST", data={"comment": "x"})
        detail = api("approver-1", f"/batch-control/grants/{gid}/")
        status = re.search(r"Status (\w+)", text_of(detail.text))
        check("L", f"a request stored with the legacy scope type {legacy} cannot be approved (D-69/D-71): 4xx, never APPROVED, "
              "detail page below 500",
              rewritten == "true" and 400 <= appr.status_code < 500 and detail.status_code < 500
              and (status is None or status.group(1) != "APPROVED"),
              rewrite=rewritten, approve=appr.status_code, detail=detail.status_code, status=status.group(1) if status else None,
              conferred=st(U, "/job/r16/job/job-a/configure"))


def sec_D():
    # refused at submission: folder, multibranch (item groups)
    rf, _ = grant_req(U, "r16", ["DELETE"], reason="e2e-16 delete the folder r16")
    check("D", "DELETE on a folder is refused at submission (4xx, nothing stored), with the explanation",
          rf.status_code == 400 and "applies only to a job" in text_of(rf.text), status=rf.status_code)
    if COMPUTED:
        rm, _ = grant_req(U, COMPUTED[0], ["DELETE"], reason="e2e-16 delete a multibranch")
        check("D", f"DELETE on the computed folder {COMPUTED[0]} is refused at submission",
              rm.status_code == 400 and "applies only to a job" in text_of(rm.text), status=rm.status_code)
    # allowed on a job: a Delete window deletes it
    if st("admin", "/job/r16/job/del-16/api/json") != 200:
        api("admin", "/job/r16/createItem?name=del-16&mode=hudson.model.FreeStyleProject", "POST",
            headers={"Content-Type": "application/x-www-form-urlencoded"}, data=b"")
    gid = window(U, "r16/del-16", ["DELETE"], reason="e2e-16 delete the job del-16")
    r = api(U, "/job/r16/job/del-16/doDelete", "POST")
    gone = st("admin", "/job/r16/job/del-16/api/json") == 404
    check("D", "DELETE on a job is allowed at submission and the window deletes that job",
          gid and r.status_code in (302, 200) and gone, window=gid, delete=r.status_code, gone=gone)


if __name__ == "__main__":
    run_sections(WANT, {"J": sec_J, "F": sec_F, "M": sec_M, "K": sec_K, "L": sec_L, "D": sec_D})
