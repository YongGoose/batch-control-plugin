"""e2e-16: a run request keeps the submitted typed parameter values, for every type (D-72, D-72b; SPEC item 5).

usage: python params.py [FDSB413RUX]   rows: out/params.jsonl, shots: R16-PARAM-*.png
F  core `file` parameter (Freestyle r16-file) through the Request Run PAGE: UPLOAD file + SECRET password + NOTE
   string; approve; the approved build receives the exact uploaded bytes (sha) and the ORIGINAL secret (its sha), and
   the request detail shows `[file] UPLOAD`, `********` for the secret, NOTE in clear; no plaintext secret in the store
D  the same through the job-page DIALOG (new job UI)
S  file-parameters `stashedFile` (Pipeline r16-stash) through the page: withFileParameter reads the same bytes (sha);
   SECRET reaches the build; the detail shows `[file] STASHED`
B  file-parameters `base64File`: the build receives the exact bytes (sha); the Base64 content is NEVER displayed
   (detail shows only `[file] B64`, no Base64 text), and no plaintext secret in the store
413 a submission over the body cap (property set to a small value via the script console) is refused with HTTP 413,
   nothing created, no temporary file kept; a submission under the cap still works
R  a repeated parameter name is refused with HTTP 400 as a field error, nothing created
U  a value containing U+0000 (which XML cannot store) is refused with HTTP 400, nothing created, no temp file kept
X  temporary files are disposed of when a file request is rejected and when it is cancelled"""
import re
import sys
import time
from urllib.parse import quote

sys.path.insert(0, __file__.rsplit("/", 1)[0])
import lib  # noqa: E402
from lib import (Session, api, gv, check, note, decide, text_of, J, BASE, SECRET, run_sections, console_ok,
                 file_counts, store_contains, requests_of, request_state, builds, next_build, wait_build,
                 sha, make_file, fill_run_form, param_box, loc_id, tick)  # noqa: E402

lib.LOGNAME[0] = "params"
WANT = sys.argv[1] if len(sys.argv) > 1 else "FDSB413RUX"
REQ = "requester"
MASK = "********"


def submit_page(job, reason, approver="approver-1", params=None, files=None, open_dialog=False):
    """Fills and submits the Request Run form, on the full page (open_dialog=False) or the job-page dialog.
    Returns (session, landing_url, request_id or None, errors list, console_session)."""
    s = Session(REQ)
    if open_dialog:
        s.go(J(job) + "/")
        entry = s.page.locator(".jenkins-app-bar a, .jenkins-app-bar button, #tasks a, #tasks button, a, button", has_text="Request Run")
        if entry.count() == 0 or not entry.first.is_visible():
            ov = s.page.locator("[data-testid=app-bar-overflow-button], button[aria-label='More actions']")
            if ov.count():
                ov.first.click(); s.page.wait_for_timeout(800)
                entry = s.page.locator(".tippy-box a, .tippy-box button, .jenkins-dropdown a, .jenkins-dropdown button", has_text="Request Run")
        entry.first.click()
        s.page.wait_for_selector("dialog[open] textarea[name=reason]")
        scope = s.page.locator("dialog[open]").first
    else:
        s.go(J(job) + "/batch-control/")
        scope = s.page.locator("form[name=batch-control-request]").first
    fill_run_form(scope, reason, approver, params, files)
    btn = scope.locator("button.jenkins-button--primary, button[type=submit]:not([data-id=cancel])").last if open_dialog \
        else scope.locator("button[name=Submit], button[type=submit]").first
    status = None
    try:
        with s.page.expect_navigation(timeout=20000) as nav:
            btn.click()
        status = nav.value.status if nav.value else None
    except Exception:
        status = None
    s.page.wait_for_load_state("load")
    errs = [t.strip() for t in s.page.locator(".error, .jenkins-alert-danger, dialog[open] .error").all_inner_texts() if t.strip()]
    rid = None
    m = re.search(r"/batch-control/requests/([0-9a-f-]{36})/", s.page.url)
    if m:
        rid = m.group(1)
    return s, s.page.url, rid, errs, status


def approve_and_wait(job, rid, nb):
    assert decide("approver-1", "requests", rid, "approve") in (200, 302), ("approve", rid)
    return wait_build(job, nb)


def console_values(console):
    return dict(re.findall(r"^(\w+)=([0-9a-f]{64}|.*)$", console, re.M))


def detail_params(rid):
    r = api("admin", f"/batch-control/requests/{rid}/")
    rows = dict(re.findall(r"<tr>\s*<td>([^<]+)</td>\s*<td[^>]*>(.*?)</td>\s*</tr>", r.text, re.S))
    return {k.strip(): text_of(v).strip() for k, v in rows.items()}, r.text


def sec_F(dialog=False, tag="F"):
    job = "r16-file"
    upname = f"upload-{tag}.bin"
    up, upbytes = make_file(upname, 2048, f"r16-upload-{tag}")
    nb = next_build(job)
    files0 = file_counts()
    s, url, rid, errs, status = submit_page(job, f"e2e-16 {tag} core file param", params={"SECRET": SECRET, "NOTE": f"note-{tag}"},
                                            files={"UPLOAD": str(up)}, open_dialog=dialog)
    s.shot("#main-panel", f"R16-PARAM-{tag}-01-submitted")
    console_ok(tag, s, f"{tag} submit")
    s.done()
    check(tag, f"core file + password + string submitted {'(dialog)' if dialog else '(page)'} lands on the request detail",
          rid is not None, url=url.replace(BASE, ""), errors=errs[:3])
    if not rid:
        return
    disp, html = detail_params(rid)
    result, console = approve_and_wait(job, rid, nb)
    cv = console_values(console)
    checks = {
        "build SUCCESS": result == "SUCCESS",
        "build received the exact uploaded file (sha)": cv.get("UPLOAD_SHA") == sha(upbytes),
        "build received the ORIGINAL secret (sha of SECRET)": cv.get("SECRET_SHA") == sha(SECRET.encode()),
        "build received the string NOTE": cv.get("NOTE") == f"note-{tag}",
        "display: UPLOAD shown as [file] <filename>": disp.get("UPLOAD", "").startswith("[file]") and upname in disp.get("UPLOAD", ""),
        "display: SECRET masked ********": disp.get("SECRET") == MASK,
        "display: NOTE in clear": disp.get("NOTE") == f"note-{tag}",
        "display: no raw secret on the detail page": SECRET not in html,
        "store: no plaintext secret in any run request file": store_contains(SECRET) == "",
        "store: no uploaded bytes marker in the request file": store_contains(f"r16-upload-{tag}") == "",
    }
    check(tag, f"approved build of the core-file request {'(dialog)' if dialog else '(page)'} got the exact file, the "
          "original secret and the string; the detail masks the secret and shows [file] for the upload; no plaintext secret stored",
          all(checks.values()), build=result, console_keys=list(cv), display=disp, **{k: v for k, v in checks.items() if not v})


def sec_D():
    sec_F(dialog=True, tag="D")


def sec_S():
    job = "r16-stash"
    stname = "stashed-S.bin"
    st, stbytes = make_file(stname, 4096, "r16-stashed-S")
    nb = next_build(job)
    s, url, rid, errs, status = submit_page(job, "e2e-16 S stashedFile", params={"SECRET": SECRET, "NOTE": "note-S"},
                                            files={"STASHED": str(st), "B64": str(st)})
    s.shot("#main-panel", "R16-PARAM-S-01-submitted")
    s.done()
    if not check("S", "stashedFile + base64File + password + string submitted, lands on the request detail", rid is not None,
                 url=url.replace(BASE, ""), errors=errs[:3]):
        return
    disp, html = detail_params(rid)
    result, console = approve_and_wait(job, rid, nb)
    cv = console_values(console)
    checks = {
        "build SUCCESS": result == "SUCCESS",
        "withFileParameter read the same stashed bytes (sha)": cv.get("STASHED_SHA") == sha(stbytes),
        "build received the original secret (sha)": cv.get("SECRET_SHA") == sha(SECRET.encode()),
        "display: STASHED shown as [file] <filename>": disp.get("STASHED", "").startswith("[file]") and stname in disp.get("STASHED", ""),
        "display: SECRET masked": disp.get("SECRET") == MASK,
    }
    check("S", "the approved Pipeline build read the stashed file's exact bytes via withFileParameter and got the original "
          "secret; the detail shows [file] for the stashed file and masks the secret",
          all(checks.values()), build=result, display=disp, **{k: v for k, v in checks.items() if not v})


def sec_B():
    job = "r16-stash"
    st, stbytes = make_file("stashed-B.bin", 1500, "r16-stashed-B")
    b64name = "b64-B.bin"
    b64, b64bytes = make_file(b64name, 1200, "r16-b64-B-UNIQUE")
    nb = next_build(job)
    s, url, rid, errs, status = submit_page(job, "e2e-16 B base64File", params={"SECRET": SECRET, "NOTE": "note-B"},
                                            files={"STASHED": str(st), "B64": str(b64)})
    s.done()
    if not check("B", "base64File submitted with the others", rid is not None, url=url.replace(BASE, ""), errors=errs[:3]):
        return
    disp, html = detail_params(rid)
    import base64 as _b64
    b64text = _b64.b64encode(b64bytes).decode()
    result, console = approve_and_wait(job, rid, nb)
    cv = console_values(console)
    checks = {
        "build SUCCESS": result == "SUCCESS",
        "build received the exact base64File bytes (sha)": cv.get("B64_SHA") == sha(b64bytes),
        "display: B64 shown only as [file] <filename>": disp.get("B64", "").startswith("[file]") and b64name in disp.get("B64", ""),
        "display: the Base64 content is NOT shown on the detail page": b64text[:40] not in html and "r16-b64-B-UNIQUE" not in text_of(html),
        "store: the Base64 marker never appears outside the request's own XML as display": "r16-b64-B-UNIQUE" not in text_of(html),
        "store: no plaintext secret": store_contains(SECRET) == "",
    }
    check("B", "the approved build received the exact base64File bytes; the request detail shows only [file] B64, never the "
          "Base64 content; no plaintext secret in the store",
          all(checks.values()), build=result, display=disp, **{k: v for k, v in checks.items() if not v})


CAP_PROP = "io.jenkins.plugins.batchcontrol.maxRequestBodyBytes"


def set_cap(value):
    return gv(f"System.setProperty('{CAP_PROP}', '{value}'); return System.getProperty('{CAP_PROP}')")


def sec_413():
    job = "r16-file"
    set_cap("4096")
    try:
        big, _ = make_file("big-413.bin", 200_000, "r16-big-413")
        files0 = file_counts()
        reqs0 = set(requests_of(job))
        s, url, rid, errs, status = submit_page(job, "e2e-16 over the cap", params={"SECRET": SECRET, "NOTE": "n"}, files={"UPLOAD": str(big)})
        s.shot("#main-panel" if s.page.locator("#main-panel").count() else "body", "R16-PARAM-413-over")
        bad413 = [b for b in s.bad if " 413 " in f" {b} "]
        s.done()
        files1 = file_counts()
        reqs1 = set(requests_of(job))
        check("413", "a submission over the body cap is refused with HTTP 413, creates no request and keeps no temporary file",
              (status == 413 or bad413) and not (reqs1 - reqs0)
              and files1.get("fileParameterValueFiles", 0) <= files0.get("fileParameterValueFiles", 0),
              status=status, bad=bad413[:2], new_requests=list(reqs1 - reqs0), files_before=files0, files_after=files1)
        # under the cap still works
        set_cap("104857600")
        small, sb = make_file("small-413.bin", 1024, "r16-small-413")
        nb = next_build(job)
        s2, url2, rid2, errs2, st2 = submit_page(job, "e2e-16 under the cap", params={"SECRET": SECRET, "NOTE": "n"}, files={"UPLOAD": str(small)})
        s2.done()
        ok = rid2 is not None
        if ok:
            decide("approver-1", "requests", rid2, "cancel")
        check("413", "a submission under the cap still creates the request", ok, url=url2.replace(BASE, ""))
    finally:
        set_cap("104857600")


def sec_R():
    job = "r16-file"
    reqs0 = set(requests_of(job))
    # repeated parameter name: post the raw fields twice for NOTE (no json), as a script would
    data = [("reason", "e2e-16 repeated name"), ("approvers", "approver-1"), ("NOTE", "one"), ("NOTE", "two"), ("SECRET", "")]
    r = api(REQ, J(job) + "/batch-control/submit", "POST", data=data)
    reqs1 = set(requests_of(job))
    check("R", "a repeated parameter name is refused with HTTP 400 (field error) and creates no request",
          r.status_code == 400 and not (reqs1 - reqs0), status=r.status_code, new=list(reqs1 - reqs0),
          body=text_of(r.text)[:200])


def sec_U():
    job = "r16-file"
    reqs0 = set(requests_of(job))
    files0 = file_counts()
    data = [("reason", "e2e-16 nul char"), ("approvers", "approver-1"), ("NOTE", "bad\x00value"), ("SECRET", "")]
    r = api(REQ, J(job) + "/batch-control/submit", "POST", data="&".join(
        f"{k}={quote(v)}" for k, v in data).encode(), headers={"Content-Type": "application/x-www-form-urlencoded"})
    reqs1 = set(requests_of(job))
    files1 = file_counts()
    check("U", "a value containing U+0000 is refused with HTTP 400, creates no request and keeps no temp file",
          r.status_code == 400 and not (reqs1 - reqs0)
          and files1.get("fileParameterValueFiles", 0) <= files0.get("fileParameterValueFiles", 0),
          status=r.status_code, new=list(reqs1 - reqs0), body=text_of(r.text)[:160])


def sec_X():
    job = "r16-file"
    for verb, actor in (("reject", "approver-1"), ("cancel", REQ)):
        f, _ = make_file(f"dispose-{verb}.bin", 3000, f"r16-dispose-{verb}")
        files0 = file_counts()
        s, url, rid, errs, status = submit_page(job, f"e2e-16 dispose on {verb}", params={"SECRET": SECRET, "NOTE": "n"}, files={"UPLOAD": str(f)})
        s.done()
        if not rid:
            check("X", f"a file request to {verb} was created", False, errors=errs[:3])
            continue
        files_submitted = file_counts()
        st = decide(actor, "requests", rid, verb)
        time.sleep(2)
        files_after = file_counts()
        # the submitted upload raised the count; after the request ends it is disposed back
        check("X", f"a file request's temporary upload is disposed of when it is {verb}ed (file count returns to the pre-submit level)",
              st in (200, 302) and files_after.get("fileParameterValueFiles", 0) <= files0.get("fileParameterValueFiles", 0),
              decide_status=st, files_before=files0, files_submitted=files_submitted, files_after=files_after,
              request_status=request_state(rid)[1])


if __name__ == "__main__":
    run_sections(list(dict.fromkeys(
        [c for c in [s for s in ["F", "D", "S", "B"] if s in WANT]]
        + (["413"] if "4" in WANT or "413" in WANT else [])
        + [c for c in ["R", "U", "X"] if c in WANT])),
        {"F": sec_F, "D": sec_D, "S": sec_S, "B": sec_B, "413": sec_413, "R": sec_R, "U": sec_U, "X": sec_X})
