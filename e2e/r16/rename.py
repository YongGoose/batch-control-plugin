"""e2e-16: no rename through a permission window (D-71c, SPEC item 8), by the UI and by every URL form of core's rename
endpoints, for a job and a folder; allowed for an administrator and for a user with their own Item/Configure.

usage: python rename.py [UEGCA]     rows: out/rename.jsonl, shots: R16-REN-*.png
U  w16b holds a CONFIGURE window on the job r16/ren-job: Rename page in the browser -> the name check shows the
   explanation, the Rename button answers 400 with it and "Nothing was renamed."; one GRANT_VIOLATION; the window
   still confers Configure afterwards
E  the same window, every URL form (POST): confirmRename, confirm%52ename, confirmRename/, doRename, and the trailing
   segment forms confirmRename/extra, confirm%52ename/extra; checkNewName (GET, POST). Each: the job is never renamed;
   the change endpoints answer 4xx (400 with the explanation); one GRANT_VIOLATION per distinct new name for the
   change endpoints, none for checkNewName. A form that renames is a FAIL (the job is renamed back by the admin)
G  w16b holds a CONFIGURE window on the folder r16-renf: Rename page and confirmRename, confirmRename/extra refused
C  w16b holds a DELETE window on r16/ren-dc and a CREATE window on r16 (core's second rule): confirmRename refused
A  the administrator renames r16/ren-adm and back; configurer (standing Item/Configure) renames r16/ren-own and back:
   both succeed, no GRANT_VIOLATION
The window state after an administrator's rename is not asserted (D-74 changes it)."""
import re
import sys
import time

sys.path.insert(0, __file__.rsplit("/", 1)[0])
import lib  # noqa: E402
from lib import (Session, api, check, note, window, violations, text_of, J, BASE, run_sections, console_ok)  # noqa: E402

lib.LOGNAME[0] = "rename"
WANT = sys.argv[1] if len(sys.argv) > 1 else "UEGCA"
U = "w16b"
EXPLAIN = "is not allowed: while change control is on, a permission window does not allow renaming a job or folder"


def exists(full):
    return api("admin", J(full) + "/api/json").status_code == 200


def restore(full, renamed_to):
    """Admin renames a wrongly renamed item back (arrangement after a FAIL)."""
    parent = full.rsplit("/", 1)[0] if "/" in full else ""
    cur = (parent + "/" if parent else "") + renamed_to
    if exists(cur) and not exists(full):
        api("admin", J(cur) + f"/confirmRename?newName={full.rsplit('/', 1)[-1]}", "POST")


def ui_rename(sec, item, new_name, tag):
    """The Rename page in the browser as U: type a name, read the check's message, press Rename."""
    s = Session(U)
    r = s.go(J(item) + "/confirm-rename")
    info = {"page": r.status if r else None}
    if info["page"] == 200:
        box = s.page.locator("input[name=newName]").first
        box.fill(new_name)
        box.blur()
        s.page.wait_for_timeout(1500)
        msgs = [t.strip() for t in s.page.locator(".validation-error-area, .error, .warning").all_inner_texts() if t.strip()]
        info["check_message"] = msgs[:2]
        s.shot("#main-panel", f"R16-REN-{tag}-01-check")
        btn = s.page.locator("#main-panel button[name=Submit], #main-panel button.jenkins-button--primary").first
        info["button_enabled"] = btn.is_enabled()
        if btn.is_enabled():
            with s.page.expect_navigation() as nav:
                btn.click()
            info["status"] = nav.value.status if nav.value else None
            info["text"] = re.sub(r"\s+", " ", s.text())[:500]
            s.shot("#main-panel" if s.page.locator("#main-panel").count() else "body", f"R16-REN-{tag}-02-refused")
    s.done()
    return info


def sec_U():
    item, new = "r16/ren-job", "ren-ui-1"
    gid = window(U, item, ["CONFIGURE"], reason="e2e-16 configure ren-job")
    before = len(violations(item, U))
    conf0 = api(U, J(item) + "/configure").status_code
    info = ui_rename("U", item, new, "U")
    after = len(violations(item, U))
    ok = (info.get("page") == 200 and any(EXPLAIN in m for m in info.get("check_message", []))
          and (not info.get("button_enabled") or (info.get("status") == 400 and EXPLAIN in info.get("text", "")
                                                    and "Nothing was renamed." in info.get("text", "")))
          and exists(item) and not exists("r16/" + new))
    check("U", "Rename page as a CONFIGURE window holder: the name check explains the refusal, Rename answers 400 with the "
          "explanation and 'Nothing was renamed.', the job keeps its name", ok, window=gid, **info,
          still_there=exists(item), renamed_to_exists=exists("r16/" + new))
    # the name check alone (a validation while typing) records nothing; the pressed Rename button records one
    check("U", "the refused rename is recorded once as GRANT_VIOLATION naming w16b and r16/ren-job (none for the name check)",
          after - before == (1 if info.get("status") else 0), before=before, after=after, button_pressed=bool(info.get("status")))
    check("U", "the window still confers Configure before and after the refusal", conf0 == 200 and api(U, J(item) + "/configure").status_code == 200)
    restore(item, new)


FORMS = ["confirmRename", "confirm%52ename", "confirmRename/", "doRename", "confirmRename/extra", "confirm%52ename/extra"]


def url_forms(sec, item, kind_word):
    gid = window(U, item, ["CONFIGURE"], reason=f"e2e-16 configure {item}")
    parent = item.rsplit("/", 1)[0] + "/" if "/" in item else ""
    rows = {}
    for i, form in enumerate(FORMS):
        new = f"ren-{sec.lower()}{i}-{int(time.time()) % 100000}"
        before = len(violations(item, U))
        r = api(U, J(item) + f"/{form}?newName={new}", "POST")
        t = text_of(r.text)
        renamed = exists(parent + new)
        rows[form] = {"status": r.status_code, "explained": EXPLAIN in t, "nothing_renamed": "Nothing was renamed." in t,
                      "renamed": renamed, "still_there": exists(item), "violations": len(violations(item, U)) - before}
        if form == "doRename" and kind_word == "folder":
            # doRename is core's Job#doDoRename: a folder has no such endpoint (404), so nothing to refuse or record
            check(sec, f"POST {form} on the folder {item}: no such endpoint, not renamed, nothing recorded",
                  not renamed and rows[form]["still_there"] and r.status_code == 404 and rows[form]["violations"] == 0,
                  form=form, new_name=new, **rows[form])
            continue
        check(sec, f"POST {form} on the {kind_word} {item} as a CONFIGURE window holder: not renamed, 4xx with the explanation, "
              "one GRANT_VIOLATION", not renamed and rows[form]["still_there"] and 400 <= r.status_code < 500
              and rows[form]["explained"] and rows[form]["violations"] == 1, form=form, new_name=new, **rows[form])
        if renamed:
            restore(item, new)
            gid = window(U, item, ["CONFIGURE"], reason=f"e2e-16 configure {item} again")
    for m in ("GET", "POST"):
        before = len(violations(item, U))
        r = api(U, J(item) + "/checkNewName?newName=ren-chk", m)
        check(sec, f"{m} checkNewName on {item}: the validation shows the explanation and records nothing",
              r.status_code == 200 and EXPLAIN in text_of(r.text).replace("&#039;", "'") and len(violations(item, U)) == before,
              status=r.status_code, text=text_of(r.text)[:200])
    check(sec, f"the window on {item} still confers Configure after the refusals", api(U, J(item) + "/configure").status_code == 200)
    return rows


def sec_E():
    url_forms("E", "r16/ren-job", "job")


def sec_G():
    item = "r16-renf"
    info = ui_rename("G", item, "r16-renf-ui", "G")  # the window is requested in url_forms below; first without one
    note("G", "Rename page of the folder without a window (core's own refusal)", **info)
    url_forms("G", item, "folder")
    info = ui_rename("G", item, "r16-renf-ui2", "G2")
    check("G", "Rename page of the folder as a CONFIGURE window holder: refused with the explanation, folder keeps its name",
          info.get("page") == 200 and any(EXPLAIN in m for m in info.get("check_message", [])) and exists(item)
          and not exists("r16-renf-ui2") and (not info.get("button_enabled") or info.get("status") == 400), **info)
    restore(item, "r16-renf-ui2")


def sec_C():
    item = "r16/ren-dc"
    gd = window(U, item, ["DELETE"], reason="e2e-16 delete ren-dc")
    gc = window(U, "r16", ["CREATE"], reason="e2e-16 create in r16")
    for form in ("confirmRename", "confirmRename/extra"):
        new = f"ren-dc-{form.replace('/', '-')}"
        before = len(violations(item, U))
        r = api(U, J(item) + f"/{form}?newName={new}", "POST")
        renamed = exists("r16/" + new)
        check("C", f"POST {form} with a DELETE window on the job and a CREATE window on its folder (core's second rule): "
              "not renamed, 4xx with the explanation, one GRANT_VIOLATION",
              not renamed and exists(item) and 400 <= r.status_code < 500 and EXPLAIN in text_of(r.text)
              and len(violations(item, U)) - before == 1, status=r.status_code, renamed=renamed, windows=[gd, gc],
              violations=len(violations(item, U)) - before)
        if renamed:
            restore(item, new)
            gd = window(U, item, ["DELETE"], reason="e2e-16 delete ren-dc again")


def sec_A():
    res = {}
    for user, item, new in (("admin", "r16/ren-adm", "ren-adm2"), ("configurer", "r16/ren-own", "ren-own2")):
        before = len(violations(item)) + len(violations("r16/" + new))
        r = api(user, J(item) + f"/confirmRename?newName={new}", "POST")
        moved = exists("r16/" + new) and not exists(item)
        r2 = api(user, J("r16/" + new) + f"/confirmRename?newName={item.rsplit('/', 1)[1]}", "POST")
        back = exists(item)
        after = len(violations(item)) + len(violations("r16/" + new))
        res[user] = {"rename": r.status_code, "moved": moved, "back": r2.status_code, "restored": back, "violations": after - before}
        check("A", f"{user} ({'administrator' if user == 'admin' else 'own standing Item/Configure'}) renames {item} and back: "
              "both succeed, no GRANT_VIOLATION", r.status_code == 302 and moved and r2.status_code == 302 and back and after == before,
              **res[user])
    # the UI path for the administrator
    s = Session("admin")
    s.go("/job/r16/job/ren-adm/confirm-rename")
    s.page.locator("input[name=newName]").first.fill("ren-adm3")
    s.page.locator("input[name=newName]").first.blur()
    s.page.wait_for_timeout(1000)
    with s.page.expect_navigation() as nav:
        s.page.locator("#main-panel button[name=Submit], #main-panel button.jenkins-button--primary").first.click()
    st = nav.value.status if nav.value else None
    s.shot("#main-panel", "R16-REN-A-admin-renamed")
    console_ok("A", s, "admin rename page")
    s.done()
    ok = exists("r16/ren-adm3")
    api("admin", "/job/r16/job/ren-adm3/confirmRename?newName=ren-adm", "POST")
    check("A", "the administrator renames in the browser (Rename page)", st == 200 and ok, status=st, renamed=ok, restored=exists("r16/ren-adm"))


if __name__ == "__main__":
    run_sections(WANT, {"U": sec_U, "E": sec_E, "G": sec_G, "C": sec_C, "A": sec_A})
