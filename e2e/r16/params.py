"""e2e-16: a run request keeps the submitted typed parameter values, for every type (D-72, D-72b; SPEC item 5).

usage: python params.py [FDSEB413CRUXY]   rows: out/params.jsonl, shots: R16-PARAM-*.png
F  core `file` parameter (Freestyle r16-file) through the Request Run PAGE: UPLOAD file + SECRET password + NOTE
   string; approve; the approved build receives the exact uploaded bytes (sha) and the ORIGINAL secret (its sha), and
   the request detail shows `[file] UPLOAD`, `********` for the secret, NOTE in clear; no plaintext secret in the store
D  the same through the job-page DIALOG (new job UI)
S  file-parameters `stashedFile` (Pipeline r16-stash) through the page: withFileParameter reads the same bytes (sha);
   SECRET reaches the build; the detail shows `[file] STASHED`
E  the same stashedFile + base64File + password request through the job-page DIALOG (coverage inventory G-H1)
B  file-parameters `base64File`: the build receives the exact bytes (sha); the Base64 content is NEVER displayed
   (detail shows only `[file] B64`, no Base64 text), and no plaintext secret in the store
413 a submission over the body cap (property set to a small value via the script console) is refused with HTTP 413,
   nothing created, no temporary file kept; a submission under the cap still works
C  stage 2 of the cap (D-74 (2)): a chunked multipart body (no Content-Length, so stage 1 cannot judge it) whose kept
   size is over the cap is refused with 413, nothing created; a chunked body under the cap is accepted
R  a repeated parameter name is refused with HTTP 400 as a field error, nothing created
U  a value containing U+0000 (which XML cannot store) is refused with HTTP 400, nothing created, no temp file kept
V  (part of F/S/X) the typed values live in requests/run/<id>.values.xml, the request file <id>.xml holds none and no
   plaintext secret; the values file is gone once the approved run started, and once a request is rejected or cancelled
   (D-74 (1), D-72b (5))
X  temporary files are disposed of when a file request is rejected and when it is cancelled
Y  (after F/S/E/B) every text surface shows the file values only as `[file] <name>` and the secret only masked: the
   History page, requests.csv and runs.csv (parameters column), the Dashboard: no file content, no Base64 text, no
   plaintext secret, no server path (SPEC 5, D-72; coverage inventory G-M6/G-L8)"""
import re
import sys
import time
from urllib.parse import quote

sys.path.insert(0, __file__.rsplit("/", 1)[0])
import lib  # noqa: E402
from lib import (Session, api, gv, check, note, decide, text_of, J, BASE, SECRET, run_sections, console_ok,
                 file_counts, store_contains, requests_of, request_state, builds, next_build, wait_build,
                 sha, make_file, fill_run_form, param_box, loc_id, tick, run_files, run_file_text)  # noqa: E402

lib.LOGNAME[0] = "params"
WANT = sys.argv[1] if len(sys.argv) > 1 else "FDSEB413CRUXY"
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


def values_file_state(tag, rid, secret_param=True):
    """D-74 (1): after submission the request has <id>.xml and <id>.values.xml; the typed values (and the encrypted
    secret) are in the values file only; no plaintext secret anywhere."""
    names = run_files(rid)
    req_text = run_file_text(f"{rid}.xml")
    val_text = run_file_text(f"{rid}.values.xml")
    typed = ("PasswordParameterValue", "FileParameterValue", "StashedFileParameterValue", "Base64FileParameterValue")
    checks = {
        "<id>.xml and <id>.values.xml exist": f"{rid}.xml" in names and f"{rid}.values.xml" in names,
        "the request file holds no typed value": not any(t in req_text for t in typed),
        "the values file holds the typed values": any(t in val_text for t in typed),
        "no plaintext secret in either file": SECRET not in req_text and SECRET not in val_text,
    }
    if secret_param:
        checks["the values file holds the secret in Jenkins' encrypted form"] = bool(re.search(r"\{[A-Za-z0-9+/=]{20,}\}", val_text))
    check(tag, "the typed values are stored in requests/run/<id>.values.xml, not in <id>.xml, the secret encrypted (D-74 (1))",
          all(checks.values()), files=names, **{k: v for k, v in checks.items() if not v})


def values_file_gone(tag, rid, when):
    names = run_files(rid)
    check(tag, f"the values file is removed {when}; the request file stays as the record (D-72b (5), D-74 (1))",
          f"{rid}.xml" in names and f"{rid}.values.xml" not in names, files=names)


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
    values_file_state(tag, rid)
    result, console = approve_and_wait(job, rid, nb)
    values_file_gone(tag, rid, "once the approved run started")
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


def sec_S(dialog=False, tag="S"):
    job = "r16-stash"
    stname = f"stashed-{tag}.bin"
    b64name = f"b64-{tag}.bin"
    st, stbytes = make_file(stname, 4096, f"r16-stashed-{tag}")
    b64, b64bytes = make_file(b64name, 1100, f"r16-b64-{tag}")
    nb = next_build(job)
    s, url, rid, errs, status = submit_page(job, f"e2e-16 {tag} stashedFile", params={"SECRET": SECRET, "NOTE": f"note-{tag}"},
                                            files={"STASHED": str(st), "B64": str(b64)}, open_dialog=dialog)
    s.shot("#main-panel", f"R16-PARAM-{tag}-01-submitted")
    console_ok(tag, s, f"{tag} submit")
    s.done()
    if not check(tag, f"stashedFile + base64File + password + string submitted {'(dialog)' if dialog else '(page)'}, lands on "
                 "the request detail", rid is not None, url=url.replace(BASE, ""), errors=errs[:3]):
        return
    disp, html = detail_params(rid)
    values_file_state(tag, rid)
    result, console = approve_and_wait(job, rid, nb)
    values_file_gone(tag, rid, "once the approved run started")
    cv = console_values(console)
    checks = {
        "build SUCCESS": result == "SUCCESS",
        "withFileParameter read the same stashed bytes (sha)": cv.get("STASHED_SHA") == sha(stbytes),
        "withFileParameter read the same base64File bytes (sha)": cv.get("B64_SHA") == sha(b64bytes),
        "build received the original secret (sha)": cv.get("SECRET_SHA") == sha(SECRET.encode()),
        "display: STASHED shown as [file] <filename>": disp.get("STASHED", "").startswith("[file]") and stname in disp.get("STASHED", ""),
        "display: B64 shown as [file] <filename>": disp.get("B64", "").startswith("[file]") and b64name in disp.get("B64", ""),
        "display: SECRET masked": disp.get("SECRET") == MASK,
        "store: no plaintext secret": store_contains(SECRET) == "",
    }
    check(tag, f"the approved Pipeline build {'(dialog)' if dialog else '(page)'} read the stashed and base64 files' exact "
          "bytes via withFileParameter and got the original secret; the detail shows [file] for both files and masks the secret",
          all(checks.values()), build=result, display=disp, **{k: v for k, v in checks.items() if not v})


def sec_E():
    sec_S(dialog=True, tag="E")


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
            check("413", "the under-cap request is cancelled by its requester (clean-up)", decide(REQ, "requests", rid2, "cancel") in (200, 302))
        check("413", "a submission under the cap still creates the request", ok, url=url2.replace(BASE, ""))
    finally:
        set_cap("104857600")


def chunked_submit(job, size, marker):
    """A raw multipart submission sent with Transfer-Encoding: chunked (no Content-Length), as a script could send it."""
    import secrets as _sec
    boundary = "----e2e16" + _sec.token_hex(8)
    parts = []
    for k, v in (("reason", f"e2e-16 chunked {marker}"), ("approvers", "approver-1"), ("NOTE", "n"), ("SECRET", "")):
        parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="{k}"\r\n\r\n{v}\r\n'.encode())
    body = (marker + "\n").encode() + b"x" * max(0, size - len(marker) - 1)
    parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="UPLOAD"; filename="{marker}.bin"\r\n'
                 f'Content-Type: application/octet-stream\r\n\r\n'.encode() + body + b"\r\n")
    parts.append(f"--{boundary}--\r\n".encode())

    def gen():
        for p in parts:
            for i in range(0, len(p), 8192):
                yield p[i:i + 8192]
    return api(REQ, J(job) + "/batch-control/submit", "POST", data=gen(),
               headers={"Content-Type": f"multipart/form-data; boundary={boundary}"})


def sec_C():
    job = "r16-file"
    set_cap("4096")
    try:
        reqs0 = set(requests_of(job))
        files0 = file_counts()
        r = chunked_submit(job, 200_000, "r16-chunked-over")
        reqs1 = set(requests_of(job))
        files1 = file_counts()
        sent_chunked = r.request.headers.get("Transfer-Encoding") == "chunked" and "Content-Length" not in r.request.headers
        check("C", "stage 2: a chunked body (no Content-Length) whose kept size is over the cap is refused with 413, creates no "
              "request and keeps no temporary file", sent_chunked and r.status_code == 413 and not (reqs1 - reqs0)
              and files1.get("fileParameterValueFiles", 0) <= files0.get("fileParameterValueFiles", 0),
              chunked=sent_chunked, status=r.status_code, new=list(reqs1 - reqs0), body=text_of(r.text)[:200],
              files_before=files0, files_after=files1)
        set_cap("104857600")
        r2 = chunked_submit(job, 1500, "r16-chunked-under")
        rid = loc_id(r2)
        check("C", "a chunked body under the cap is accepted (302 to the new request)", r2.status_code in (302, 303) and rid,
              status=r2.status_code, location=(r2.headers.get("Location") or "").replace(BASE, ""))
        if rid:
            decide(REQ, "requests", rid, "cancel")
    finally:
        set_cap("104857600")


def sec_Y():
    import base64 as _b
    files = sorted((lib.OUT / "files").glob("b64-*.bin")) + sorted((lib.OUT / "files").glob("stashed-*.bin")) + \
        sorted((lib.OUT / "files").glob("upload-*.bin"))
    b64s = {f.name: _b.b64encode(f.read_bytes()).decode()[:32] for f in files}
    markers = {f.name: f.read_bytes().split(b"\n", 1)[0].decode() for f in files}
    # the CSV links as the History page renders them (its from/to range; a bare requests.csv answers the header only)
    page = api("approver-1", "/batch-control/history/").text
    links = {m.group(1): m.group(0).split('"')[1].replace("&amp;", "&") for m in re.finditer(r'href="((?:requests|runs)\.csv)\?[^"]*"', page)}
    surfaces = {"requests.csv": "/batch-control/history/" + links.get("requests.csv", "requests.csv"),
                "runs.csv": "/batch-control/history/" + links.get("runs.csv", "runs.csv"),
                "history": "/batch-control/history/", "history requests": "/batch-control/history/?kind=requests",
                "dashboard": "/batch-control/dashboard/"}
    PATHS = re.compile(r"fileParameterValueFiles|stashedFileParameterValueFiles|/var/jenkins_home|jenkins-stapler-uploads")
    for name, path in surfaces.items():
        r = api("approver-1", path)
        body = r.text if name.endswith(".csv") else text_of(r.text)
        rows = [l for l in body.splitlines() if "r16-file" in l or "r16-stash" in l] if name.endswith(".csv") else [body]
        joined = "\n".join(rows)
        problems = {
            "base64 text": [n for n, b in b64s.items() if b in joined],
            "file content marker": [n for n, m in markers.items() if m in joined],
            "plaintext secret": SECRET in joined,
            "server path": PATHS.findall(joined)[:3],
        }
        shows_file = "[file] " in joined
        check("Y", f"{name}: the r16 typed requests/runs show files only as [file] <name> and no content, Base64, plaintext "
              "secret or server path", r.status_code == 200 and (shows_file or not name.endswith(".csv"))
              and not any(problems.values()), status=r.status_code, url=path, rows=len(rows) if name.endswith(".csv") else None,
              shows_file=shows_file, **{k: v for k, v in problems.items() if v})
        if name.endswith(".csv"):
            note("Y", f"{name} sample row", row=(rows[:1] or [""])[0][:400])


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
        pending_files = run_files(rid)
        had_values = f"{rid}.values.xml" in pending_files
        st = decide(actor, "requests", rid, verb)
        time.sleep(2)
        files_after = file_counts()
        check("X", f"the values file existed while the request was pending ({verb})", had_values, files=pending_files)
        values_file_gone("X", rid, f"once the request is {verb}ed")
        # the submitted upload raised the count; after the request ends it is disposed back
        check("X", f"a file request's temporary upload is disposed of when it is {verb}ed (file count returns to the pre-submit level)",
              st in (200, 302) and files_after.get("fileParameterValueFiles", 0) <= files0.get("fileParameterValueFiles", 0),
              decide_status=st, files_before=files0, files_submitted=files_submitted, files_after=files_after,
              request_status=request_state(rid)[1])


if __name__ == "__main__":
    run_sections(list(dict.fromkeys(
        [c for c in [s for s in ["F", "D", "S", "E", "B"] if s in WANT]]
        + (["413"] if "4" in WANT or "413" in WANT else [])
        + [c for c in ["C", "R", "U", "X", "Y"] if c in WANT])),
        {"F": sec_F, "D": sec_D, "S": sec_S, "E": sec_E, "B": sec_B, "413": sec_413, "C": sec_C, "R": sec_R, "U": sec_U,
         "X": sec_X, "Y": sec_Y})
