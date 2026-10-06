"""e2e-17: what security-39 changed, seen through the browser and REST (S-39-01, S-39-02, D-75).

usage: python s39.py [PRDH]     rows: out/s39.jsonl, shots: R17-*.png
P  S-39-01 (RecordLookup, PathCodec.isId): requester, approver-1 and admin each open, in their own browser context, a
   pending run request whose typed values live in requests/run/<id>.values.xml (control: 200), then the detail URLs
   <id>.VALUES/, <id>.Values/, <id>.values/, <ID in upper case>/, x.y/, <id>~1/ and no-such-id.VALUES/: every one 404
   (never 500), Jenkins' own 404 page, nothing of the request or its values on it. The same for a grant request, an
   activation request and an incident (when the shard has one), and POST grants/active/<bad id>/revoke as admin (404,
   no window revoked). Server state: the request stays PENDING with its values file, no ClassCastException or
   "Could not read" warning is logged. Whether <id>.VALUES.xml resolves to the values file on this file system (the
   S-39-01 premise; true on a case-insensitive store, e2e/compose.ci-fs.yml) is recorded with each row.
R  S-39-02 / D-74 (3): w17 holds a CONFIGURE window on r17/rt-a. The administrator renames it twice on the Rename page
   (rt-a -> rt-b -> rt-c): the window names r17/rt-c (store), confers Configure there, the holder's grants list and the
   window page (holder and approver) show the current name, not the earlier ones; new jobs the administrator creates at
   rt-a and rt-b give the holder nothing. Then a rename that changes only the letter case (rt-c -> RT-C), the exception
   S-39-02 makes for the item's own old spelling: if core allows it, the window follows and stays open.
D  S-39-02 / SPEC 8: w17 holds CONFIGURE windows on r17/dc-job and r17-dc-top. The administrator deletes r17/dc-job in the
   browser (the job's Delete page) and creates a new r17/dc-job on the New Item page; r17-dc-top is deleted over REST and
   re-created as R17-DC-TOP (another letter case): the holder gets 403 on both new jobs; each window ended, revoked by
   admin with "its item was deleted" (grant file, GRANT_REVOKE record) and listed under Ended as
   "Revoked (its item was deleted) by admin".
H  D-75 (1), SPEC 8 line 171: w17's window on r17/vis-x; the administrator moves the job into r17-vault (inheritance
   blocked, administrators only). The holder's grants list and window page and the approver's window page must not show
   the new location (r17-vault) and show the approved name with a "moved" note; the administrator sees r17-vault/vis-x.
   Its own step in ci/shard.py: on bd449cd the fix is not in src/main (T-SEC-96/97 red), so this section is expected to
   fail until it is."""
import json
import re
import sys
import time

sys.path.insert(0, __file__.rsplit("/", 1)[0])
import lib  # noqa: E402
from lib import (Session, api, gv, check, note, window, text_of, J, BASE, run_sections, console_ok, changes, revoke_all,
                 st, exists, grant_xml, revoke_records, holder_rows, log_since, now_millis, U, H, UUID)  # noqa: E402

lib.LOGNAME[0] = "s39"
WANT = sys.argv[1] if len(sys.argv) > 1 else "PRDH"
VIEWERS = ("requester", "approver-1", "admin")
INCIDENT_ID = r"\d{8}-\d{6}-[a-z0-9]{6}"


def run_req(user, job, reason, params):
    data = [("reason", reason), ("approvers", "approver-1")] + list(params.items())
    r = api(user, J(job) + "/batch-control/submit", "POST", data=data)
    return r.status_code, lib.loc_id(r)


def act_req(user, job, action, reason):
    r = api(user, J(job) + "/batch-control-activation/submit", "POST",
            data=[("action", action), ("reason", reason), ("approvers", "approver-1")])
    return r.status_code, lib.loc_id(r)


def store_files(sub, rid):
    out = gv(f"""def d = new File(jenkins.model.Jenkins.get().rootDir, 'batch-control/{sub}')
return d.exists() ? (d.listFiles().findAll {{ it.name.toLowerCase().startsWith('{rid}'.toLowerCase()) }}*.name.sort().join(',')) : ''""")
    return [x for x in out.split(",") if x]


def resolves(sub, name):
    """Whether batch-control/<sub>/<name> exists on this file system (a case-insensitive one finds another spelling)."""
    return gv(f"""return String.valueOf(new File(jenkins.model.Jenkins.get().rootDir, 'batch-control/{sub}/{name}').exists())""") == "true"


def variants(rid, suffixes=(".VALUES", ".Values", ".values")):
    v = [(f"{rid}{s}/", f"id{s}") for s in suffixes]
    v += [(f"{rid.upper()}/", "ID in upper case"), ("x.y/", "x.y"), (f"{rid}~1/", "id~1 (8.3 short name)"),
          ("no-such-id.VALUES/", "no-such-id.VALUES")]
    return v


def sec_P():
    t0 = now_millis()
    status, rid = run_req("requester", "batch-daily", "e2e-17 S-39-01 values file",
                          {"DATE": "2026-10-06", "MODE": "full", "SECRET": "r17-secret-not-shown"})
    assert rid, ("run request", status)
    files = store_files("requests/run", rid)
    premise = {"values file": f"{rid}.values.xml" in files, "request file": f"{rid}.xml" in files,
               "<id>.VALUES.xml resolves (case-insensitive store)": resolves("requests/run", f"{rid}.VALUES.xml"),
               "<ID>.xml resolves": resolves("requests/run", f"{rid.upper()}.xml")}
    check("P", "premise: the pending run request has its request file and its values file", premise["values file"] and premise["request file"],
          request=rid, files=files, **premise)
    # the other record kinds: a pending grant request (its id is also the window's id), an activation request, an incident
    r, gid = lib.grant_req("requester", "r17/ctl", ["CONFIGURE"], 15, "e2e-17 S-39-01 grant request")
    a_status, aid = act_req("requester", "r17/ctl", "ACTIVATE", "e2e-17 S-39-01 activation request")
    inc = gv("""def d = new File(jenkins.model.Jenkins.get().rootDir, 'batch-control/incidents')
return d.exists() ? (d.listFiles().findAll { it.name ==~ /\\d{8}-\\d{6}-[a-z0-9]{6}\\.xml/ }*.name.sort().take(1).join(',')) : ''""")
    iid = inc[:-4] if inc else None
    kinds = [("requests", rid, variants(rid)), ("grants", gid, variants(gid, (".xml", ".VALUES"))),
             ("activations", aid, variants(aid, (".xml",)))]
    if iid:
        kinds.append(("incidents", iid, variants(iid, (".xml",))))
    else:
        note("P", "no incident in this Jenkins: the incident detail URLs are checked with malformed ids only")
        kinds.append(("incidents", None, [("x.y/", "x.y"), ("ABC~1/", "ABC~1")]))
    rows = {}
    gated = {}  # the incidents section needs ViewHistory: the requester gets the section's 403 whatever the id
    for user in VIEWERS:
        s = Session(user)
        for kind, kid, vs in kinds:
            if kind == "incidents" and user == "requester":
                for path in ([f"{kid}/"] if kid else []) + [p for p, _ in vs]:
                    resp = s.go(f"/batch-control/{kind}/{path}")
                    gated[path] = resp.status if resp else None
                continue
            if kid:
                ctl = s.go(f"/batch-control/{kind}/{kid}/")
                rows[(user, kind, "control")] = ctl.status if ctl else None
            for path, label in vs:
                resp = s.go(f"/batch-control/{kind}/{path}")
                body = s.page.content()
                rows[(user, kind, label)] = resp.status if resp else None
                leaks = [w for w in ("ClassCastException", "Exception", "RunRequestValues", "r17-secret-not-shown", "e2e-17 S-39-01")
                         if w in body]
                if leaks or (resp and resp.status >= 500):
                    rows[(user, kind, label + " leaks")] = leaks
                if kind == "requests" and label == "id.VALUES":
                    s.shot("body", f"R17-P-{user}-values-404")
        console_ok("P", s, f"{user}: detail URLs", expected=("404 ", "403 GET " + BASE + "/batch-control/incidents/"))
        s.done()
    controls = {f"{u}/{k}": v for (u, k, l), v in rows.items() if l == "control"}
    bad = {f"{u}/{k}/{l}": v for (u, k, l), v in rows.items() if l != "control" and not l.endswith(" leaks") and v != 404}
    leaks = {f"{u}/{k}/{l}": v for (u, k, l), v in rows.items() if l.endswith(" leaks")}
    check("P", "control: each viewer opens the run request, the grant request, the activation request (and the incident) by "
          "their real ids (200)", all(v == 200 for v in controls.values()), controls=controls)
    check("P", "requester, approver-1 and admin get 404 (never 500) for <id>.VALUES/, <id>.Values/, <id>.values/, the id in upper "
          "case, x.y/, <id>~1/ and no-such-id.VALUES/ under requests/, and the same shapes under grants/, activations/ and "
          "incidents/; no exception text, request text or value on the 404 pages",
          not bad and not leaks, checked=sum(1 for (_, _, l) in rows if l != "control" and not l.endswith(" leaks")),
          not_404=bad, leaks=leaks, case_insensitive_store=premise["<id>.VALUES.xml resolves (case-insensitive store)"])
    check("P", "the requester (no ViewHistory) gets the incidents section's 403 for the real incident id and for every malformed "
          "one alike, so the answer tells nothing about the id", gated and all(v == 403 for v in gated.values()), statuses=gated)
    # POST to the revoke endpoint with ids that are not window ids: 404, nothing revoked
    revoke = {p: st("admin", f"/batch-control/grants/active/{p}/revoke", "POST")
              for p in ("x.y", f"{gid}.xml", gid.upper(), f"{gid}~1")}
    check("P", "POST grants/active/<bad id>/revoke as admin answers 404 for x.y, <id>.xml, the id in upper case and <id>~1",
          all(v == 404 for v in revoke.values()), statuses=revoke)
    # server state afterwards
    code, status, _ = lib.L16.request_state(rid)
    files_after = store_files("requests/run", rid)
    logged = log_since(t0, ("ClassCastException", "Could not read run request", "Could not read grant", "Could not read activation",
                            "Could not read incident", "RunRequestValues"))
    check("P", "server state: the request is still PENDING with its values file; no ClassCastException or 'Could not read' "
          "warning was logged by these lookups", code == 200 and status == "PENDING" and f"{rid}.values.xml" in files_after
          and not logged, status=status, files=files_after, log=logged[:5])
    # clean up: the requester cancels the run request (its values file is disposed), the grant and activation requests
    cancelled = {kind: lib.decide("requester", kind, kid, "cancel") for kind, kid in (("requests", rid), ("grants", gid), ("activations", aid))}
    # the lists render links through the same lookup as the detail URL: the cancelled request is linked under Ended
    s = Session("requester")
    s.go("/batch-control/requests/")
    link = s.page.locator(f"#main-panel table[data-batch-control-list=ended] a[href*='{rid}']").first
    linked = link.count()
    if linked:
        link.scroll_into_view_if_needed()
        s.shot(link.locator("xpath=ancestor::tr"), "R17-P-requester-ended-row")
        with s.page.expect_navigation() as nav:
            link.click()
        opened = nav.value.status if nav.value else None
    else:
        s.shot("#main-panel", "R17-P-requester-list")
        opened = None
    s.done()
    check("P", "after the requester cancels it, the Run Requests list links the request under Ended and the link opens its page",
          linked > 0 and opened == 200, cancel=cancelled, links=linked, opened=opened,
          request_files=store_files("requests/run", rid))


def admin_rename(item, new_name, tag):
    """The administrator renames `item` on its Rename page (browser); returns (HTTP status of the result, page text)."""
    s = Session("admin")
    s.go(J(item) + "/confirm-rename")
    box = s.page.locator("input[name=newName]").first
    box.fill(new_name)
    box.blur()
    s.page.wait_for_timeout(800)
    hint = re.sub(r"\s+", " ", s.text())
    s.shot("#main-panel", f"R17-{tag}-rename-page")
    with s.page.expect_navigation() as nav:
        s.page.locator("#main-panel button[name=Submit], #main-panel button.jenkins-button--primary").first.click()
    status = nav.value.status if nav.value else None
    after = re.sub(r"\s+", " ", s.text())
    s.shot("#main-panel", f"R17-{tag}-renamed")
    s.done()
    return status, hint, after


def admin_new_item(parent, name, tag):
    """The administrator creates a Freestyle job `name` in `parent` (None = root) on the New Item page."""
    s = Session("admin")
    s.go((J(parent) if parent else "") + "/newJob")
    s.page.locator("input#name, input[name=name]").first.fill(name)
    radio = s.page.locator("input[name=mode][value='hudson.model.FreeStyleProject']").first
    radio.locator("xpath=..").click()  # the item type's entry, as a person clicks it
    if not radio.is_checked():
        radio.check(force=True)
    s.shot("#main-panel", f"R17-{tag}-new-item")
    with s.page.expect_navigation():
        s.page.locator("#ok-button, button#ok-button, button[type=submit]").first.click()
    url = s.page.url
    s.done()
    return url


def sec_R():
    revoke_all(U)
    a, b, c = "r17/rt-a", "r17/rt-b", "r17/rt-c"
    gid = window(U, a, ["CONFIGURE"], reason="e2e-17 rename twice: configure rt-a")
    before = st(U, J(a) + "/configure")
    s1, _, _ = admin_rename(a, "rt-b", "R-1")
    s2, _, _ = admin_rename(b, "rt-c", "R-2")
    g = grant_xml(gid)
    res = {"configure before": before, "first rename": s1, "second rename": s2,
           "renamed": exists(c) and not exists(a) and not exists(b), "stored scope": g.get("scope"),
           "revoked": g.get("revokedAtMillis"), "configure rt-c": st(U, J(c) + "/configure")}
    check("R", "after two renames by the administrator the window names r17/rt-c (store), is still open and confers Configure "
          "there", before == 200 and s1 == 200 and s2 == 200 and res["renamed"] and g.get("scope") == c and not g.get("revokedAtMillis")
          and res["configure rt-c"] == 200, window=gid, **res)
    lst, section, detail, html = holder_rows(gid, "R-holder")
    check("R", "the holder's grants list (Active) and the window page show the current name r17/rt-c and neither earlier name",
          c in lst and section.startswith("Active") and c in detail and a not in detail and b not in detail
          and a not in lst and b not in lst, section=section, list_row=lst[:240], detail=detail[:400])
    _, _, adetail, _ = holder_rows(gid, "R-approver", user="approver-1")
    check("R", "the approver's view of the window page shows r17/rt-c", c in adetail and a not in adetail and b not in adetail,
          detail=adetail[:300])
    api("admin", J("r17") + "/createItem?name=rt-a&mode=hudson.model.FreeStyleProject", "POST", headers=H)
    api("admin", J("r17") + "/createItem?name=rt-b&mode=hudson.model.FreeStyleProject", "POST", headers=H)
    res2 = {"rt-a exists": exists(a), "rt-b exists": exists(b), "configure new rt-a": st(U, J(a) + "/configure"),
            "configure new rt-b": st(U, J(b) + "/configure"), "configure rt-c": st(U, J(c) + "/configure")}
    check("R", "new jobs the administrator creates at the two earlier names give the holder nothing (403); r17/rt-c still 200",
          res2["rt-a exists"] and res2["rt-b exists"] and res2["configure new rt-a"] == 403 and res2["configure new rt-b"] == 403
          and res2["configure rt-c"] == 200, **res2)
    recs = [x for x in changes(lambda x: x.get("type") in ("RENAME", "MOVE") and x.get("target") in (a, b, c))]
    check("R", "two RENAME records by admin (rt-a -> rt-b, rt-b -> rt-c)",
          len([x for x in recs if x.get("user") == "admin" and x.get("type") == "RENAME"]) >= 2,
          records=[{k: x.get(k) for k in ("type", "target", "user", "detail")} for x in recs][-3:])
    # a rename that changes only the letter case
    chk = api("admin", J(c) + "/checkNewName?newName=RT-C")
    s3, hint, after = admin_rename(c, "RT-C", "R-3")
    g3 = grant_xml(gid)
    if exists("r17/RT-C") and not exists("r17/rt-c-missing"):
        renamed = gv("return jenkins.model.Jenkins.get().getItemByFullName('r17/RT-C')?.fullName") == "r17/RT-C"
    else:
        renamed = False
    if renamed:
        check("R", "a rename that only changes the letter case (rt-c -> RT-C) keeps the window open on its item: it names "
              "r17/RT-C and confers Configure there (S-39-02 exempts the item's own old spelling)",
              g3.get("scope") == "r17/RT-C" and not g3.get("revokedAtMillis") and st(U, J("r17/RT-C") + "/configure") == 200,
              stored=g3, rename=s3)
        admin_rename("r17/RT-C", "rt-c", "R-4")  # back
    else:
        note("R", "core refuses a rename that only changes the letter case; nothing to check for the window", rename_status=s3,
             check_new_name=text_of(chk.text)[:200], page=after[:300], stored=g3)
        check("R", "after core refused the case-only rename the window is unchanged (r17/rt-c, open)",
              g3.get("scope") == c and not g3.get("revokedAtMillis"), stored=g3)
    revoke_all(U)


def admin_delete_in_browser(item, tag):
    """The administrator deletes `item` on its Delete page (core's confirmation page) in the browser."""
    s = Session("admin")
    r = s.go(J(item) + "/delete")
    status = r.status if r else None
    s.shot("#main-panel", f"R17-{tag}-delete-page")
    with s.page.expect_navigation() as nav:
        s.page.locator("#main-panel form button[name=Submit], #main-panel form button.jenkins-button--primary, "
                       "#main-panel form input[type=submit]").first.click()
    res = nav.value.status if nav.value else None
    s.done()
    return status, res


def sec_D():
    revoke_all(U)
    job, top = "r17/dc-job", "r17-dc-top"
    for n in (job, top):
        if not exists(n):
            parent, _, name = n.rpartition("/")
            api("admin", (J(parent) if parent else "") + f"/createItem?name={name}&mode=hudson.model.FreeStyleProject", "POST", headers=H)
    g1 = window(U, job, ["CONFIGURE"], reason="e2e-17 delete and re-create: configure r17/dc-job")
    g2 = window(U, top, ["CONFIGURE"], reason="e2e-17 delete and re-create: configure r17-dc-top")
    pre = {"dc-job": st(U, J(job) + "/configure"), "dc-top": st(U, J(top) + "/configure")}
    page, deleted = admin_delete_in_browser(job, "D-1")
    url = admin_new_item("r17", "dc-job", "D-2")
    d2 = api("admin", J(top) + "/doDelete", "POST").status_code
    c2 = api("admin", "/createItem?name=R17-DC-TOP&mode=hudson.model.FreeStyleProject", "POST", headers=H).status_code
    res = {"before": pre, "delete page": page, "delete": deleted, "re-created r17/dc-job": exists(job), "new item page url": url.replace(BASE, ""),
           "configure new r17/dc-job": st(U, J(job) + "/configure"), "REST delete r17-dc-top": d2, "create R17-DC-TOP": c2,
           "configure R17-DC-TOP": st(U, "/job/R17-DC-TOP/configure")}
    check("D", "after the administrator deletes a windowed job and creates a new one at its name (New Item page; and at the root "
          "under another letter case, R17-DC-TOP), the holder gets 403 on both new jobs",
          pre == {"dc-job": 200, "dc-top": 200} and res["re-created r17/dc-job"] and res["configure new r17/dc-job"] == 403
          and c2 in (200, 302) and res["configure R17-DC-TOP"] == 403, **res)
    for gid, name, tag in ((g1, job, "D-job"), (g2, top, "D-top")):
        g = grant_xml(gid)
        rec = revoke_records(gid)
        lst, section, detail, _ = holder_rows(gid, tag)
        check("D", f"the window on {name} ended: revoked by admin with 'its item was deleted' (grant file and GRANT_REVOKE record), "
              "listed under Ended as 'Revoked (its item was deleted) by admin'",
              g.get("revokedReason") == "its item was deleted" and g.get("revokedBy") == "admin" and rec and rec[-1].get("user") == "admin"
              and section.startswith("Ended") and "Revoked (its item was deleted) by admin" in lst,
              window=gid, stored={k: g.get(k) for k in ("scope", "revokedBy", "revokedReason")},
              record=[x.get("detail") for x in rec][-1:], section=section, list_row=lst[:240])


def admin_move(item, dest, tag):
    s = Session("admin")
    r = s.go(J(item) + "/move/")
    status = None
    if r and r.status == 200:
        sel = s.page.locator("select[name=destination]").first
        val = [o.get_attribute("value") for o in sel.locator("option").all()
               if (o.get_attribute("value") or "").rstrip("/").endswith(dest)]
        sel.select_option(val[0] if val else "/" + dest)
        s.shot("#main-panel", f"R17-{tag}-move-page")
        with s.page.expect_navigation() as nav:
            s.page.locator("#main-panel button[name=Submit], #main-panel button.jenkins-button--primary, #main-panel input[type=submit]").first.click()
        status = nav.value.status if nav.value else None
    s.done()
    return (r.status if r else None), status


def sec_H():
    revoke_all(U)
    if not exists("r17/vis-x"):
        if exists("r17-vault/vis-x"):
            api("admin", "/job/r17-vault/job/vis-x/doDelete", "POST")
        api("admin", J("r17") + "/createItem?name=vis-x&mode=hudson.model.FreeStyleProject", "POST", headers=H)
    gid = window(U, "r17/vis-x", ["CONFIGURE"], reason="e2e-17 D-75 (1): configure r17/vis-x")
    page, moved = admin_move("r17/vis-x", "r17-vault", "H")
    g = grant_xml(gid)
    check("H", "precondition: the administrator moved r17/vis-x into r17-vault (Move page) and the window followed it (store)",
          exists("r17-vault/vis-x") and not exists("r17/vis-x") and g.get("scope") == "r17-vault/vis-x" and not g.get("revokedAtMillis"),
          move_page=page, move=moved, stored=g)
    lst, section, detail, html = holder_rows(gid, "H-holder")
    _, _, adetail, ahtml = holder_rows(gid, "H-approver", user="approver-1")
    _, _, mdetail, _ = holder_rows(gid, "H-admin", user="admin")
    raw_list = lst
    check("H", "D-75 (1): the holder (no Read on r17-vault) sees neither the new location nor the new full name on the grants list "
          "and the window page, but the approved name r17/vis-x with a 'moved' note",
          "r17-vault" not in html and "r17-vault" not in raw_list and "r17/vis-x" in detail and "moved" in detail.lower(),
          vault_in_page_html="r17-vault" in html, list_row=raw_list[:240], detail=detail[:400])
    check("H", "D-75 (1): the approver (no Read on r17-vault) does not see r17-vault on the window page",
          "r17-vault" not in ahtml, detail=adetail[:300])
    check("H", "control: the administrator sees the current name r17-vault/vis-x", "r17-vault/vis-x" in mdetail, detail=mdetail[:300])
    revoke_all(U)
    api("admin", "/job/r17-vault/job/vis-x/doDelete", "POST")
    api("admin", J("r17") + "/createItem?name=vis-x&mode=hudson.model.FreeStyleProject", "POST", headers=H)


if __name__ == "__main__":
    run_sections(WANT, {"P": sec_P, "R": sec_R, "D": sec_D, "H": sec_H})
