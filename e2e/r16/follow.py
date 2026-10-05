"""e2e-16: windows follow their item (D-74 (3)), deletion records name the deleting user (SPEC 6, 177b13a), refused moves
are recorded once per minute (D-73), and a CREATE window never bypasses the project naming strategy (SPEC 8, T-08-168).

usage: python follow.py [WVMXCN]     rows: out/follow.jsonl, shots: R16-FOLLOW-*.png
W  w16 holds a CONFIGURE window on the job r16-fw/fw-job. The administrator renames the job in the browser (Rename
   page): the window now names r16-fw/fw-job2 (store, w16's grants list and detail page), confers Configure on the
   renamed job, and nothing on a new item the administrator then creates at the old name; w16's own rename of the job
   is still refused (D-71c)
V  w16 holds CONFIGURE windows on the folder r16-fwf and on the job r16-fwf/inner/deep. The administrator renames the
   folder: both windows follow (r16-fwf2, r16-fwf2/inner/deep) and confer on the renamed items only
M  w16 holds a CONFIGURE window on r16-fw/fw-mv. The administrator moves it to r16-fwdest in the browser (Move page):
   the window names r16-fwdest/fw-mv and confers there; nothing at the old location
X  w16 holds a CONFIGURE window on r16-delf/x. configurer (standing Item/Delete, no Administer) tries Delete Folder in
   the browser: refused (D-71: deleting a folder needs an administrator while change control is on), nothing deleted.
   The administrator deletes the folder in the browser: the DELETE change records of the folder and of every item
   inside it name admin (core deletes the children as SYSTEM; 1864bdc), the window ends (GRANT_REVOKE by admin) and
   confers nothing on an item re-created at r16-delf/x; the Changes screen shows the deletions under admin, none SYSTEM
C  mv16 has native Item/Move on r16-mvsrc/mv-job and Item/Create on r16-mvdst1 and r16-mvdst2, no Delete: a move is
   refused (403, plain message naming Delete, nothing moved); the same move repeated within a minute writes one
   GRANT_VIOLATION; a move to another destination writes its own; after the minute the same move writes again
N  core's pattern naming strategy (^nm-.*) is installed: w16's CREATE window on r16 creates r16/nm-ok, and r16/zz-bad
   is refused by the strategy (no item); the strategy is restored afterwards"""
import re
import sys
import time

sys.path.insert(0, __file__.rsplit("/", 1)[0])
import lib  # noqa: E402
from lib import (Session, api, gv, groovy, check, note, window, grant_files, text_of, J, BASE, run_sections,
                 console_ok, changes, violations, revoke_all, ENV)  # noqa: E402

lib.LOGNAME[0] = "follow"
WANT = sys.argv[1] if len(sys.argv) > 1 else "WVMXCN"
U = "w16"
H = {"Content-Type": "application/x-www-form-urlencoded"}
EXPLAIN = "is not allowed: while change control is on, a permission window does not allow renaming a job or folder"


def st(user, path, method="GET", **kw):
    return api(user, path, method, **kw).status_code


def exists(full):
    return st("admin", J(full) + "/api/json") == 200


def grant_row(gid):
    rows = [r for r in grant_files() if r[1] == gid]
    return rows[0] if rows else None


def grant_field(gid, field):
    return gv(f"""def f=new File(jenkins.model.Jenkins.get().rootDir,'batch-control/grants/{gid}.xml')
if(!f.exists()) return ''; def x=new XmlSlurper().parse(f); return x.{field}.text()""")


def arrange():
    """Idempotent: the items each section works on, recreated at their original names when an earlier run moved them."""
    print(groovy(r'''
import jenkins.model.Jenkins
import hudson.model.*
def j = Jenkins.get()
def F = com.cloudbees.hudson.plugins.folder.Folder
def folder = { parent, n -> parent.getItem(n) ?: parent.createProject(F, n) }
def job = { parent, n -> if (parent.getItem(n) == null) { def p = parent.createProject(FreeStyleProject, n); p.setDescription('e2e-16 follow'); p.save() } }
// leftovers of an earlier run of this driver
['r16-fw/fw-job2', 'r16-fwf2', 'r16-fwdest/fw-mv', 'r16-fw/fw-job'].each { n -> def i = j.getItemByFullName(n); if (i != null) i.delete() }
def fw = folder(j, 'r16-fw'); job(fw, 'fw-job'); job(fw, 'fw-mv')
def fwf = folder(j, 'r16-fwf'); def inner = folder(fwf, 'inner'); job(inner, 'deep'); job(fwf, 'side')
folder(j, 'r16-fwdest')
def delf = folder(j, 'r16-delf'); job(delf, 'x'); def dsub = folder(delf, 'sub'); job(dsub, 'y')
return "fw=${fw.items*.name} fwf=${fwf.items*.name} delf=${delf.items*.name}"
'''))


def admin_rename(item, new_name, tag):
    """The administrator renames `item` on its Rename page in the browser."""
    s = Session("admin")
    s.go(J(item) + "/confirm-rename")
    box = s.page.locator("input[name=newName]").first
    box.fill(new_name)
    box.blur()
    s.page.wait_for_timeout(800)
    with s.page.expect_navigation() as nav:
        s.page.locator("#main-panel button[name=Submit], #main-panel button.jenkins-button--primary").first.click()
    status = nav.value.status if nav.value else None
    s.shot("#main-panel", f"R16-FOLLOW-{tag}-admin-renamed")
    console_ok(tag, s, "administrator's Rename page")
    s.done()
    return status


def holder_view(gid, name, tag):
    """w16's grants list and the window's detail page name `name`."""
    s = Session(U)
    s.go("/batch-control/grants/")
    row = s.page.locator(f"#main-panel tr:has(a[href*='{gid}'])").first
    list_text = re.sub(r"\s+", " ", row.inner_text()) if row.count() else ""
    s.shot("#main-panel", f"R16-FOLLOW-{tag}-list")
    s.go(f"/batch-control/grants/{gid}/")
    detail = re.sub(r"\s+", " ", s.text())
    s.shot("#main-panel", f"R16-FOLLOW-{tag}-detail")
    console_ok(tag, s, "holder's grants list and detail")
    s.done()
    return list_text, detail


def sec_W():
    revoke_all(U)
    old, new = "r16-fw/fw-job", "r16-fw/fw-job2"
    gid = window(U, old, ["CONFIGURE"], reason="e2e-16 follow: configure fw-job")
    before = st(U, J(old) + "/configure")
    status = admin_rename(old, "fw-job2", "W")
    row = grant_row(gid)
    list_text, detail = holder_view(gid, new, "W")
    res = {"configure before": before, "admin rename page": status, "renamed": exists(new) and not exists(old),
           "stored scope": row[4] if row else None, "configure new name": st(U, J(new) + "/configure")}
    check("W", "after the administrator renames the job, the window names the new full name (store) and confers Configure on "
          "the renamed job", before == 200 and status == 200 and res["renamed"] and res["stored scope"] == new
          and res["configure new name"] == 200, window=gid, **res)
    check("W", "the holder's grants list row and the window's detail page show the new name, not the old one",
          new in list_text and new in detail and f"{old} " not in detail + " " and not re.search(re.escape(old) + r"(?!2)", list_text),
          list_row=list_text[:240], detail=detail[:400])
    # a new item at the old name: the window does not reach it
    api("admin", J("r16-fw") + "/createItem?name=fw-job&mode=hudson.model.FreeStyleProject", "POST", headers=H)
    check("W", "a new job the administrator creates at the old name gets nothing from the window (403); the renamed job "
          "still 200", exists(old) and st(U, J(old) + "/configure") == 403 and st(U, J(new) + "/configure") == 200,
          old_exists=exists(old), configure_old=st(U, J(old) + "/configure"), configure_new=st(U, J(new) + "/configure"))
    # the holder's own rename of the renamed job is still refused (D-71c)
    v0 = len(violations(new, U))
    r = api(U, J(new) + "/confirmRename?newName=fw-job3", "POST")
    check("W", "the holder's own rename of the renamed job is still refused (400 with the explanation, one GRANT_VIOLATION)",
          r.status_code == 400 and EXPLAIN in text_of(r.text) and exists(new) and not exists("r16-fw/fw-job3")
          and len(violations(new, U)) - v0 == 1, status=r.status_code, violations=len(violations(new, U)) - v0)
    renames = changes(lambda c: c.get("type") in ("RENAME", "MOVE") and c.get("target") in (old, new))
    note("W", "change records of the administrator's rename", records=[{k: c.get(k) for k in ("type", "target", "user", "detail")} for c in renames][-2:])


def sec_V():
    revoke_all(U)
    gf = window(U, "r16-fwf", ["CONFIGURE"], reason="e2e-16 follow: configure the folder r16-fwf")
    gj = window(U, "r16-fwf/inner/deep", ["CONFIGURE"], reason="e2e-16 follow: configure r16-fwf/inner/deep")
    pre = {"folder": st(U, "/job/r16-fwf/configure"), "deep": st(U, J("r16-fwf/inner/deep") + "/configure"),
           "side (no window)": st(U, J("r16-fwf/side") + "/configure")}
    status = admin_rename("r16-fwf", "r16-fwf2", "V")
    rf, rj = grant_row(gf), grant_row(gj)
    post = {"folder": st(U, "/job/r16-fwf2/configure"), "deep": st(U, J("r16-fwf2/inner/deep") + "/configure"),
            "side (no window)": st(U, J("r16-fwf2/side") + "/configure"), "inner (no window)": st(U, J("r16-fwf2/inner") + "/configure")}
    check("V", "after the administrator renames the folder, the window on the folder and the window on a job below it both "
          "follow (store) and confer on the renamed items only",
          status == 200 and pre["folder"] == 200 and pre["deep"] == 200 and pre["side (no window)"] == 403
          and rf and rf[4] == "r16-fwf2" and rj and rj[4] == "r16-fwf2/inner/deep"
          and post["folder"] == 200 and post["deep"] == 200 and post["side (no window)"] == 403 and post["inner (no window)"] == 403,
          before=pre, after=post, stored=[rf[4] if rf else None, rj[4] if rj else None])
    _, detail = holder_view(gj, "r16-fwf2/inner/deep", "V")
    check("V", "the job window's detail page names r16-fwf2/inner/deep", "r16-fwf2/inner/deep" in detail, detail=detail[:300])
    admin_rename("r16-fwf2", "r16-fwf", "V-back")  # back, so a re-run starts from the same names


def sec_M():
    revoke_all(U)
    old, new = "r16-fw/fw-mv", "r16-fwdest/fw-mv"
    gid = window(U, old, ["CONFIGURE"], reason="e2e-16 follow: configure fw-mv")
    s = Session("admin")
    r = s.go(J(old) + "/move/")
    opts = s.page.locator("select[name=destination] option").all_inner_texts() if r and r.status == 200 else []
    status = None
    if r and r.status == 200:
        sel = s.page.locator("select[name=destination]").first
        val = [o.get_attribute("value") for o in sel.locator("option").all() if (o.get_attribute("value") or "").rstrip("/").endswith("r16-fwdest")]
        sel.select_option(val[0] if val else "/r16-fwdest")
        s.shot("#main-panel", "R16-FOLLOW-M-move-page")
        with s.page.expect_navigation() as nav:
            s.page.locator("#main-panel button[name=Submit], #main-panel button.jenkins-button--primary, #main-panel input[type=submit]").first.click()
        status = nav.value.status if nav.value else None
    console_ok("M", s, "administrator's Move page")
    s.done()
    row = grant_row(gid)
    res = {"move page": r.status if r else None, "move": status, "moved": exists(new) and not exists(old),
           "stored scope": row[4] if row else None, "configure new": st(U, J(new) + "/configure")}
    check("M", "after the administrator moves the job, the window names its new location and confers Configure there",
          res["moved"] and res["stored scope"] == new and res["configure new"] == 200, window=gid, options=opts[:6], **res)
    api("admin", J("r16-fw") + "/createItem?name=fw-mv&mode=hudson.model.FreeStyleProject", "POST", headers=H)
    check("M", "a new job at the old location gets nothing from the window", st(U, J(old) + "/configure") == 403,
          configure_old=st(U, J(old) + "/configure"))
    api("admin", J(new) + "/doDelete", "POST")


def sec_X():
    revoke_all(U)
    gid = window(U, "r16-delf/x", ["CONFIGURE"], reason="e2e-16 deletion: configure r16-delf/x")
    # configurer has standing Item/Delete on the folder, but no window and no standing permission may delete a folder
    s = Session("configurer")
    s.go("/job/r16-delf/")
    entry = s.page.locator("#tasks a, #tasks button", has_text=re.compile(r"Delete Folder"))
    refusal, cstatus = "", None
    if entry.count():
        entry.first.click()
        s.page.wait_for_selector("dialog[open] button[data-id=ok]", timeout=10000)
        try:
            with s.page.expect_navigation(timeout=20000) as nav:
                s.page.locator("dialog[open] button[data-id=ok]").first.click()
            cstatus = nav.value.status if nav.value else None
        except Exception as e:  # noqa
            note("X", "no navigation after configurer's delete confirmation", error=repr(e)[:200])
        s.page.wait_for_load_state("load")
        refusal = re.sub(r"\s+", " ", s.text())
        if cstatus is None and any(b.startswith("400 POST") and b.endswith("/job/r16-delf/doDelete") for b in s.bad):
            cstatus = 400  # the dialog's form POST answered 400 (Playwright reports no navigation response for it)
        s.shot("#main-panel", "R16-FOLLOW-X-00-configurer-refused")
    s.done()
    check("X", "configurer (standing Item/Delete, no Administer) is refused Delete Folder while change control is on: 400 page "
          "naming the folder kind and pointing to an administrator; nothing deleted (D-71)",
          cstatus == 400 and "administrator" in refusal and "Folder" in refusal and exists("r16-delf") and exists("r16-delf/x"),
          status=cstatus, text=refusal[:300])
    n0 = len(changes())
    s = Session("admin")
    s.go("/job/r16-delf/")
    entry = s.page.locator("#tasks a, #tasks button, .jenkins-app-bar a, .jenkins-app-bar button", has_text=re.compile(r"Delete Folder"))
    found = entry.count() > 0
    status = None
    if found:
        entry.first.click()
        s.page.wait_for_selector("dialog[open] button[data-id=ok]", timeout=10000)
        s.shot("dialog[open]", "R16-FOLLOW-X-01-confirm")
        try:
            with s.page.expect_navigation(timeout=20000) as nav:
                s.page.locator("dialog[open] button[data-id=ok]").first.click()
            status = nav.value.status if nav.value else None
        except Exception as e:  # noqa
            note("X", "no navigation after the delete confirmation", error=repr(e)[:200])
        s.page.wait_for_load_state("load")
    console_ok("X", s, "administrator's Delete Folder")
    s.done()
    gone = not exists("r16-delf")
    targets = ["r16-delf", "r16-delf/x", "r16-delf/sub", "r16-delf/sub/y"]
    fresh = changes()[n0:]  # the change log is append-only: the records this deletion wrote
    dels = {t: [c.get("user") for c in fresh if c.get("type") == "DELETE" and c.get("target") == t] for t in targets}
    check("X", "the administrator deletes the folder in the browser; the DELETE records of the folder and of every item inside it "
          "name admin, none SYSTEM (1864bdc)",
          found and gone and all(v and v[-1] == "admin" for v in dels.values()), delete_entry=found, status=status,
          folder_gone=gone, delete_record_users=dels)
    revoked_by, reason, revoked_at = grant_field(gid, "revokedBy"), grant_field(gid, "revokedReason"), grant_field(gid, "revokedAtMillis")
    rec = [c for c in fresh if c.get("type") == "GRANT_REVOKE" and c.get("grantId") == gid]
    check("X", "the window on r16-delf/x ended with the deletion: revoked, GRANT_REVOKE record by admin (not SYSTEM)",
          bool(revoked_at) and rec and rec[-1].get("user") == "admin" and revoked_by == "admin", revokedAtMillis=revoked_at, revokedBy=revoked_by,
          reason=reason, record=[{k: c.get(k) for k in ("type", "target", "user", "detail")} for c in rec][-1:])
    # re-create the folder and the job: the ended window confers nothing
    groovy("""def j=jenkins.model.Jenkins.get(); def F=com.cloudbees.hudson.plugins.folder.Folder
def d=j.getItem('r16-delf') ?: j.createProject(F,'r16-delf'); if(d.getItem('x')==null) d.createProject(hudson.model.FreeStyleProject,'x')
def s=d.getItem('sub') ?: d.createProject(F,'sub'); if(s.getItem('y')==null) s.createProject(hudson.model.FreeStyleProject,'y'); return 'ok'""")
    check("X", "a job re-created at r16-delf/x gets nothing from the ended window (403)", st(U, J("r16-delf/x") + "/configure") == 403,
          configure=st(U, J("r16-delf/x") + "/configure"))
    s = Session("approver-1")
    s.go("/batch-control/changes/")
    rows = [re.sub(r"\s+", " ", t) for t in s.page.locator("#main-panel tr").all_inner_texts()]
    shown = [r for r in rows if "DELETE" in r.upper() and "r16-delf" in r]
    s.shot("#main-panel", "R16-FOLLOW-X-02-changes")
    s.done()
    check("X", "the Changes screen lists the deletions of the folder and its items under admin, none under SYSTEM",
          len(shown) >= 4 and all(re.search(r"\badmin\b", r) for r in shown[:4]) and not any("SYSTEM" in r for r in shown[:4]),
          rows=shown[:5])


def arrange_mover():
    return groovy(r'''
import hudson.model.*
import hudson.security.*
import org.jenkinsci.plugins.matrixauth.*
import com.cloudbees.hudson.plugins.folder.properties.AuthorizationMatrixProperty as FAMP
def j = jenkins.model.Jenkins.get()
if (User.getById('mv16', false) == null) j.getSecurityRealm().createAccount('mv16', "''' + ENV["BC_OTHER_PASSWORD"] + r'''")
def s = j.getAuthorizationStrategy()
['hudson.model.Hudson.Read', 'hudson.model.View.Read'].each { s.add(Permission.fromId(it), new PermissionEntry(AuthorizationType.USER, 'mv16')) }
j.save()
def F = com.cloudbees.hudson.plugins.folder.Folder
def prop = { f -> def p = f.getProperties().get(FAMP); if (p == null) { p = new FAMP([:]); f.addProperty(p) }; p }
def give = { f, perms -> def p = prop(f); perms.each { p.add(it, new PermissionEntry(AuthorizationType.USER, 'mv16')) }; f.save() }
def src = j.getItem('r16-mvsrc') ?: j.createProject(F, 'r16-mvsrc')
if (src.getItem('mv-job') == null) src.createProject(FreeStyleProject, 'mv-job')
give(src, [Item.READ, Permission.fromId('hudson.model.Item.Move')])
['r16-mvdst1', 'r16-mvdst2'].each { n -> def d = j.getItem(n) ?: j.createProject(F, n); give(d, [Item.READ, Item.CREATE]) }
def U = User.getById('mv16', true).impersonate2()
def job = j.getItemByFullName('r16-mvsrc/mv-job')
return "move=" + job.getACL().hasPermission2(U, Permission.fromId('hudson.model.Item.Move')) + " delete=" + job.getACL().hasPermission2(U, Item.DELETE) +
  " create dst1=" + j.getItem('r16-mvdst1').getACL().hasPermission2(U, Item.CREATE)
''')


def move_post(dest):
    return api("mv16", "/job/r16-mvsrc/job/mv-job/move/move", "POST", data={"destination": dest})


def sec_C():
    note("C", "arrangement", perms=arrange_mover())
    item = "r16-mvsrc/mv-job"
    # the browser refusal page first
    s = Session("mv16")
    r = s.go("/job/r16-mvsrc/job/mv-job/move/")
    status = None
    page_text = ""
    if r and r.status == 200:
        sel = s.page.locator("select[name=destination]").first
        vals = [o.get_attribute("value") for o in sel.locator("option").all()]
        dst = [v for v in vals if (v or "").rstrip("/").endswith("r16-mvdst1")]
        if dst:
            sel.select_option(dst[0])
            with s.page.expect_navigation() as nav:
                s.page.locator("#main-panel button[name=Submit], #main-panel button.jenkins-button--primary, #main-panel input[type=submit]").first.click()
            status = nav.value.status if nav.value else None
            page_text = re.sub(r"\s+", " ", s.text())
        s.shot("#main-panel" if s.page.locator("#main-panel").count() else "body", "R16-FOLLOW-C-01-refused")
    link_to_item = s.page.locator("a[href*='job/r16-mvsrc/job/mv-job']").count() > 0
    s.done()
    v1 = len(violations(item, "mv16"))
    check("C", "a move without Delete on the item is refused in the browser: 403, a plain message naming Delete, a link to the "
          "item, nothing moved, one GRANT_VIOLATION",
          status == 403 and "Delete" in page_text and link_to_item and exists(item) and not exists("r16-mvdst1/mv-job") and v1 >= 1,
          status=status, text=page_text[:300], link=link_to_item, violations=v1)
    # the same move repeated within the minute: merged
    codes = [move_post("/r16-mvdst1").status_code for _ in range(3)]
    v2 = len(violations(item, "mv16"))
    check("C", "the same refused move repeated 3 times within the minute writes no further GRANT_VIOLATION (D-73)",
          all(c == 403 for c in codes) and v2 == v1 and exists(item), codes=codes, before=v1, after=v2)
    c2 = move_post("/r16-mvdst2").status_code
    v3 = len(violations(item, "mv16"))
    check("C", "a refused move to another destination writes its own record", c2 == 403 and v3 == v2 + 1, status=c2, before=v2, after=v3)
    time.sleep(62)
    c3 = move_post("/r16-mvdst1").status_code
    v4 = len(violations(item, "mv16"))
    check("C", "after the minute the same refused move writes a new record", c3 == 403 and v4 == v3 + 1 and exists(item),
          status=c3, before=v3, after=v4)


def sec_N():
    revoke_all(U)
    for n in ("nm-ok", "zz-bad"):
        if exists("r16/" + n):
            api("admin", J("r16/" + n) + "/doDelete", "POST")
    gid = window(U, "r16", ["CREATE"], reason="e2e-16 create under a naming strategy")
    prev = gv("""def j=jenkins.model.Jenkins.get(); def p=j.getProjectNamingStrategy(); def d=p.getClass().name
j.setProjectNamingStrategy(new jenkins.model.ProjectNamingStrategy.PatternProjectNamingStrategy('^nm-.*', 'e2e-16: names start with nm-', false)); j.save(); return d""")
    try:
        ok = api(U, J("r16") + "/createItem?name=nm-ok&mode=hudson.model.FreeStyleProject", "POST", headers=H)
        bad = api(U, J("r16") + "/createItem?name=zz-bad&mode=hudson.model.FreeStyleProject", "POST", headers=H)
        s = Session(U)
        s.go("/job/r16/newJob")
        s.page.locator("input#name, input[name=name]").first.fill("zz-ui")
        s.page.wait_for_timeout(1500)
        msg = [t.strip() for t in s.page.locator(".validation-error-area, .input-validation-message, .error, #itemname-invalid").all_inner_texts() if t.strip()]
        s.shot("#main-panel", "R16-FOLLOW-N-01-new-item")
        s.done()
        check("N", "under core's pattern naming strategy the CREATE window creates an admitted name (nm-ok) and the strategy "
              "refuses another (zz-bad): no item", ok.status_code in (200, 302) and exists("r16/nm-ok")
              and bad.status_code >= 400 and not exists("r16/zz-bad"), window=gid, admitted_status=ok.status_code, refused_status=bad.status_code,
              bad_text=text_of(bad.text)[:200], new_item_page_messages=msg[:3])
    finally:
        restored = gv("""def j=jenkins.model.Jenkins.get(); j.setProjectNamingStrategy(jenkins.model.ProjectNamingStrategy.DEFAULT_NAMING_STRATEGY); j.save()
return j.getProjectNamingStrategy().getClass().name""")
        note("N", "naming strategy restored", previous=prev, now=restored)
    for n in ("nm-ok", "zz-bad"):
        if exists("r16/" + n):
            api("admin", J("r16/" + n) + "/doDelete", "POST")


if __name__ == "__main__":
    arrange()
    run_sections(WANT, {"W": sec_W, "V": sec_V, "M": sec_M, "X": sec_X, "C": sec_C, "N": sec_N})
