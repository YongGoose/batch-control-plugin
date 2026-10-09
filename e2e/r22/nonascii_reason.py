"""e2e-22 R4-03: non-ASCII text on the multipart Request Run form survives a refusal and is stored as typed (DEF-09
FormErrors keeps the input; D-72 multipart form).

usage: python nonascii_reason.py [ADKMU]     rows: out/nonascii_reason.jsonl, shots: screenshots/run-22/R22-R4-03-*.png
Item r22-reason (Freestyle, DATE string; approval-required). The requester files, approver-1 is the approver; every
request filed here is cancelled by the requester at the end of its section. The stored text is read from the request's
files under batch-control/requests/run/ (script console, reading only).

A  arrangement: the item, run control on, at least two global approvers (so the form pre-ticks none).
D  control, browser: the reason typed and approver-1 ticked on the first submission -> the request page and the store
   hold the reason as typed.
K  browser: the reason typed, no approver ticked, Submit -> refused, the re-rendered textarea holds the reason exactly
   (the bug: ISO-8859-1 mojibake); then approver-1 ticked and Submit -> the request page and the store hold it as typed.
M  script: multipart/form-data without the json field (reason, approvers, DATE as plain parts) -> the stored reason and
   the stored DATE value are as sent.
U  guard, script: the same as url-encoded form -> stored as sent (the path the fix leaves unchanged)."""
import re
import sys

import requests

sys.path.insert(0, __file__.rsplit("/", 1)[0])
import lib  # noqa: E402
from lib import api, check, note, J, BASE, Session  # noqa: E402

lib.LOGNAME[0] = "nonascii_reason"
JOB = "r22-reason"
USER, APPROVER = "requester", "approver-1"
REASON = "월말 정산 재실행 — ünïcödé ✓"
DATE = "2026-10-09 월말"
FORM = "form[name=batch-control-request]"


def cancel(rid):
    st = lib.decide(USER, "requests", rid, "cancel", "e2e-22 cleanup")
    note("cleanup", f"requester cancels {rid}", status=st)


def stored(rid):
    """(reason in the request file, every stored file's text) of request `rid`."""
    names = lib.run_files(rid)
    texts = {n: lib.gv(f"""def f = new File(jenkins.model.Jenkins.get().rootDir, 'batch-control/requests/run/{n}')
return f.exists() ? f.getText('UTF-8') : ''""") for n in names}
    main = texts.get(f"{rid}.xml", "")
    m = re.search(r"<reason>(.*?)</reason>", main, re.S)
    return (m.group(1) if m else None), texts


def assert_stored(sec, what, rid, date=None):
    status, page_status, page = lib.request_state(rid)
    check(sec, f"{what}: the request page (admin) shows the reason as typed", status == 200 and REASON in page,
          id=rid, http=status, status_text=page_status)
    reason, texts = stored(rid)
    check(sec, f"{what}: the stored reason equals the typed reason", reason == REASON, id=rid, stored=reason,
          files=sorted(texts))
    if date is not None:
        check(sec, f"{what}: the stored DATE value equals the sent value", any(date in t for t in texts.values()),
              id=rid, files=sorted(texts))


def open_form(s):
    s.go(J(JOB) + "/batch-control/")
    form = s.page.locator(FORM)
    form.locator("textarea[name=reason]").fill(REASON)
    for box in form.locator("input[name=approvers]:checked").all():  # start from no ticked approver
        lib.tick(form, "approvers", box.get_attribute("value"), on=False)
    return form


def submit(s, form):
    with s.page.expect_navigation(timeout=30000):
        form.locator("button[name=Submit]").click()
    s.page.wait_for_load_state("load")
    m = re.search(r"/batch-control/requests/(" + lib.UUID + ")/", s.page.url)
    return m.group(1) if m else None


def sec_A():
    created = lib.ensure_job(JOB, lib.job_xml("fs", params=lib.string_p("DATE", "2026-01-01"), shell='echo "DATE=$DATE"'))
    prop = lib.set_property(JOB, approval=True, timer=True, upstream=True)
    sw = lib.switches()
    ok = (sw["run_control"] is True and "approvalRequired=true" in prop and APPROVER in sw["approvers"]
          and len(sw["approvers"]) >= 2 and lib.has_permission(USER, JOB, "hudson.model.Item.READ"))
    check("A", "arrangement: r22-reason approval-required, approver-1 among at least two global approvers, requester "
          "reads the job", ok, created=created, prop=prop, switches=sw)


def sec_D():
    s = Session(USER, fresh=True)
    form = open_form(s)
    lib.tick(form, "approvers", APPROVER)
    rid = submit(s, form)
    text = s.text()
    s.shot("#main-panel", "R22-R4-03-D-direct-detail")
    s.done()
    check("D", "control: a first-time submission is accepted and its page shows the reason as typed",
          rid is not None and REASON in text, id=rid)
    if rid:
        assert_stored("D", "control", rid)
        cancel(rid)


def sec_K():
    s = Session(USER, fresh=True)
    form = open_form(s)
    submit(s, form)
    form = s.page.locator(FORM)
    kept = form.locator("textarea[name=reason]").input_value() if form.count() else None
    errors = s.page.locator(".error, .jenkins-alert-danger, [data-batch-control-field-error]").all_inner_texts()
    s.shot([f"{FORM} textarea[name=reason]", f"{FORM} input[name=approvers]"], "R22-R4-03-K-refused-form")
    check("K", "precondition: the submission without an approver is refused and the form comes back",
          form.count() == 1 and "/batch-control/requests/" not in s.page.url, url=s.page.url.replace(BASE, ""),
          errors=[e[:120] for e in errors][:3])
    check("K", "the re-rendered form keeps the non-ASCII reason exactly as typed", kept == REASON, kept=kept,
          typed=REASON)
    rid = None
    if form.count():
        lib.tick(form, "approvers", APPROVER)
        rid = submit(s, form)
    text = s.text()
    s.shot("#main-panel", "R22-R4-03-K-resubmitted-detail")
    s.done()
    check("K", "resubmitted with approver-1 ticked: accepted, the request page shows the reason as typed",
          rid is not None and REASON in text, id=rid)
    if rid:
        assert_stored("K", "resubmitted", rid)
        cancel(rid)


def scripted(sec, what, **kw):
    rs = requests.Session()
    rs.auth = (USER, lib.pw(USER))
    c = rs.get(BASE + "/crumbIssuer/api/json").json()
    r = rs.post(BASE + J(JOB) + "/batch-control/submit", headers={c["crumbRequestField"]: c["crumb"]},
                allow_redirects=False, **kw)
    rid = lib.loc_id(r)
    check(sec, f"{what}: the submission is accepted (302 to the new request)", r.status_code == 302 and rid,
          status=r.status_code, content_type=r.request.headers.get("Content-Type", "")[:40])
    if rid:
        assert_stored(sec, what, rid, date=DATE)
        cancel(rid)


def sec_M():
    scripted("M", "multipart without the json field",
             files={"reason": (None, REASON.encode("utf-8")), "approvers": (None, APPROVER), "DATE": (None, DATE.encode("utf-8"))})


def sec_U():
    scripted("U", "guard: url-encoded form", data={"reason": REASON, "approvers": APPROVER, "DATE": DATE})


if __name__ == "__main__":
    lib.run(sys.argv, {"A": sec_A, "D": sec_D, "K": sec_K, "M": sec_M, "U": sec_U})
