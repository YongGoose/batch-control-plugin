"""e2e-19: the self-grant guard as the window holder meets it, with matrix-auth's real project-security form (SPEC 2 D-35b,
D-48, D-58).

usage: python guard.py [XF]   rows: out/guard.jsonl, shots: R19-GUARD-*.png   (run r19/arrange.py first)
requester (no standing Configure) holds an approved CONFIGURE window on r19-guard.
X  a config.xml POST that changes the description and adds a project-matrix entry (Job/Build for nobc): HTTP 403 with
   the plain message naming the item, "were not kept", "temporary permission window", "Your other changes were saved"
   and "ask an administrator"; the description is saved, no authorization entry is kept, nobc still cannot build it
   (403 on the build endpoint is not the check: the stored property is), and a GRANT_VIOLATION record names requester
F  the same in the browser: the configure page, matrix-auth's "Enable project-based security", "Add user" nobc, Job/Build
   ticked, a new description, Save: the answer is Jenkins' standard page "Authorization entries not kept" (HTTP 403)
   with the same sentences and, since requester may open it, a link to the job's Batch Control page; the description
   is saved, the entry is not kept, one more GRANT_VIOLATION record; the form lists the plugin's permissions under the
   group "Batch Control" (SPEC 1)"""
import re
import sys
import time

sys.path.insert(0, __file__.rsplit("/", 1)[0])
import lib  # noqa: E402
from lib import (Session, api, gv, check, note, text_of, J, run_sections, loc_id, changes_mark, changes_since,
                 ensure_job, job_xml, BASE)  # noqa: E402

lib.LOGNAME[0] = "guard"
WANT = sys.argv[1] if len(sys.argv) > 1 else "XF"
JOB = "r19-guard"
U = "requester"
ENTRY = ("<hudson.security.AuthorizationMatrixProperty><inheritanceStrategy "
         "class='org.jenkinsci.plugins.matrixauth.inheritance.InheritParentStrategy'/>"
         "<permission>USER:hudson.model.Item.Build:nobc</permission></hudson.security.AuthorizationMatrixProperty>")


def window():
    ensure_job(JOB, job_xml("fs", shell='echo "r19-guard ran"'))
    have = api(U, J(JOB) + "/configure").status_code
    if have == 200:
        return True
    data = [("scopeFullName", JOB), ("durationMinutes", "30"), ("reason", "e2e-19 guard"), ("approvers", "approver-1"),
            ("actions", "CONFIGURE")]
    r = api(U, "/batch-control/grants/create", "POST", data=data)
    gid = loc_id(r)
    assert gid, (r.status_code, text_of(r.text)[:200])
    assert lib.decide("approver-1", "grants", gid, "approve") in (200, 302)
    return api(U, J(JOB) + "/configure").status_code == 200


def stored_entries():
    return gv(f"""def p = jenkins.model.Jenkins.get().getItemByFullName('{JOB}').getProperty(hudson.security.AuthorizationMatrixProperty)
return p == null ? 'none' : p.getGrantedPermissionEntries().collect {{ k, v -> k.id + '=' + v*.sid }}.join(';')""")


def description():
    return gv(f"return jenkins.model.Jenkins.get().getItemByFullName('{JOB}').description ?: ''")


SENTENCES = ("were not kept", "temporary permission window", "Your other changes were saved", "ask an administrator")


def sec_X():
    check("X", "requester's CONFIGURE window on r19-guard confers its configure page", window())
    mark = changes_mark()
    xml = api(U, J(JOB) + "/config.xml").text
    desc = f"guard-x {int(time.time())}"
    xml = re.sub(r"<description>.*?</description>|<description/>", f"<description>{desc}</description>", xml, count=1, flags=re.S)
    xml = xml.replace("<properties>", "<properties>" + ENTRY, 1)
    r = api(U, J(JOB) + "/config.xml", "POST", data=xml.encode("utf-8"), headers={"Content-Type": "application/xml"})
    body = text_of(r.text)
    check("X", "a config.xml POST adding a matrix entry: 403 with the plain message naming the item (SPEC 2 D-48)",
          r.status_code == 403 and f"'{JOB}'" in body and all(x in body for x in SENTENCES),
          status=r.status_code, body=body[:300])
    entries = stored_entries()
    check("X", "the other change (description) is saved, the authorization entry is not kept (SPEC 2 D-35b)",
          description() == desc and "nobc" not in entries, description=description(), entries=entries)
    rec = changes_since(mark, lambda x: x.get("type") == "GRANT_VIOLATION" and x.get("target") == JOB)
    check("X", "a GRANT_VIOLATION record names requester", any(x.get("user") == U for x in rec),
          records=[(x.get("user"), (x.get("detail") or "")[:120]) for x in rec][:2])


def sec_F():
    check("F", "requester's CONFIGURE window on r19-guard confers its configure page", window())
    mark = changes_mark()
    desc = f"guard-f {int(time.time())}"
    s = Session(U)
    s.go(J(JOB) + "/configure")
    s.page.locator("textarea[name=description]").fill(desc)
    box = s.page.locator("input[name=useProjectSecurity]")
    if box.count() and not box.first.is_checked():
        box.first.locator("xpath=following-sibling::label").first.click()
    s.page.wait_for_timeout(500)
    add = s.page.locator("button", has_text=re.compile(r"Add user", re.I)).first
    add.click()
    dlg = s.page.locator("dialog[open]").first
    dlg.wait_for(timeout=10000)
    dlg.locator("input").first.press_sequentially("nobc", delay=40)
    s.page.wait_for_timeout(400)
    dlg.locator("button[data-id=ok]").first.click()
    s.page.wait_for_timeout(1000)
    # matrix-auth's card layout: one card per sid, a checkbox per permission named by the permission id
    card = s.page.locator("[name='[USER:nobc]']").first
    cell = card.locator("input[type=checkbox][name='[hudson.model.Item.Build]']").first
    note("F", "matrix-auth card", cards=card.count(), build_boxes=cell.count(),
         names=card.locator("input[type=checkbox]").evaluate_all("els => els.slice(0, 8).map(e => e.name)") if card.count() else [])
    groups = s.page.locator(".mas-filter__group-title").all_inner_texts()
    bc_perm = s.page.locator("[data-filter-permission^='io.jenkins.plugins.batchcontrol.security.BatchControlPermissions.']").count()
    check("F", "matrix-auth's job form lists the plugin's permissions under the group 'Batch Control' (SPEC 1)",
          "Batch Control" in groups and bc_perm >= 1, groups=groups[-4:], batch_control_permissions=bc_perm)
    cell.check(force=True)
    s.shot("#main-panel", "R19-GUARD-F-01-matrix-filled")
    status = None
    with s.page.expect_navigation(timeout=30000) as nav:
        s.page.locator("button[name=Submit]").first.click()
    status = nav.value.status if nav.value else None
    s.page.wait_for_load_state("load")
    t = s.text()
    links = s.page.locator("#main-panel a[href]").evaluate_all("els => els.map(e => e.getAttribute('href'))")
    title = s.page.title()
    s.shot("#main-panel", "R19-GUARD-F-02-not-kept")
    s.done()
    check("F", "the browser save answers Jenkins' standard page 'Authorization entries not kept' (403) with the sentences and a "
          "link to the job's Batch Control page (SPEC 2 D-48, DEF-39)",
          status == 403 and "Authorization entries not kept" in title and all(x.lower() in t.lower() for x in SENTENCES[:3])
          and any((h or "").rstrip("/").endswith(J(JOB) + "/batch-control") for h in links),
          status=status, title=title, text=t[:300])
    entries = stored_entries()
    check("F", "the description is saved, the matrix entry is not kept", description() == desc and "nobc" not in entries,
          description=description(), entries=entries)
    rec = changes_since(mark, lambda x: x.get("type") == "GRANT_VIOLATION" and x.get("target") == JOB)
    check("F", "one more GRANT_VIOLATION record names requester", any(x.get("user") == U for x in rec), records=len(rec))


run_sections(WANT, {"X": sec_X, "F": sec_F})
