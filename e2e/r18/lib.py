"""e2e-18 (final regression of round 6: D-76 change-recording baselines and creation-time saves, the refused approval
while change control is off, the plain strategy refusal pages, the APPROVED and GRANT_EXPIRING notices) on top of
../r17/lib.py: screenshots in screenshots/run-18/ (never committed), rows in r18/out/<driver>.jsonl. The rules of every
e2e pass hold: one new browser context per account (real login form), a red-boxed clipped screenshot per step, the server
state read separately (REST with basic auth, the store through the script console, which only arranges or reads state),
the mails read from the mail sink's API (mailpit on $BC_MAIL_PORT).

Verdict lines as in r16/r17: "PASS {...}" / "FAIL {...}" per assertion (ci/shard.py fails a step on a FAIL line or a
non-zero exit), "NOTE {...}" for an observation without a verdict."""
import importlib.util
import json
import os
import pathlib
import re
import sys
import time

import requests

HERE = pathlib.Path(__file__).resolve().parent
_spec = importlib.util.spec_from_file_location("r17lib", HERE.parent / "r17" / "lib.py")
L17 = importlib.util.module_from_spec(_spec)
sys.modules["r17lib"] = L17
_spec.loader.exec_module(L17)
L16 = L17.L16
# r17/lib.py points r6/lib.py at run-17 and r17/out; this pass writes its own.
L16._l.SHOTS = HERE.parent / "screenshots" / "run-18"
L16._l.OUT = HERE / "out"
L16.SHOTS = L16._l.SHOTS
L16.OUT = L16._l.OUT
L16.SHOTS.mkdir(parents=True, exist_ok=True)
L16.OUT.mkdir(parents=True, exist_ok=True)
SHOTS, OUT = L16.SHOTS, L16.OUT

Session, api, groovy, gv, BASE, ENV = L16.Session, L16.api, L16.groovy, L16.gv, L16.BASE, L16.ENV
check, note, run_sections, console_ok, text_of, J = L16.check, L16.note, L16.run_sections, L16.console_ok, L16.text_of, L16.J
changes, revoke_all, decide, grant_req, loc_id, UUID = L16.changes, L16.revoke_all, L16.decide, L16.grant_req, L16.loc_id, L16.UUID
st, exists, grant_xml, log_since, now_millis = L17.st, L17.exists, L17.grant_xml, L17.log_since, L17.now_millis
LOGNAME = L16.LOGNAME
H = {"Content-Type": "application/x-www-form-urlencoded"}
U = "w18"  # the window holder of this pass (Item/Read and RequestGrant only; r18/arrange.py)
CONTAINER = L17.CONTAINER
MAIL = f"http://localhost:{os.environ.get('BC_MAIL_PORT', '8025')}"
STACK_MARKERS = ("Oops!", "Stack trace", "java.lang.", "\tat ", " at io.jenkins", " at hudson.", " at org.kohsuke",
                 "IllegalStateException", "Logging ID=")


def flat(t):
    return re.sub(r"\s+", " ", t or "").strip()


# ---------------------------------------------------------------- mail sink
def mails(to=None, subject_has=None):
    """Messages in mailpit (newest first) as dicts with To, Subject, ID; filtered by recipient and subject substring."""
    r = requests.get(MAIL + "/api/v1/messages?limit=500", timeout=20)
    out = []
    for m in r.json().get("messages", []):
        rcpt = [a.get("Address") for a in m.get("To") or []]
        if to and to not in rcpt:
            continue
        if subject_has and subject_has not in (m.get("Subject") or ""):
            continue
        out.append({"ID": m["ID"], "To": rcpt, "Subject": m.get("Subject")})
    return out


def mail_text(mid):
    return requests.get(MAIL + f"/api/v1/message/{mid}", timeout=20).json().get("Text") or ""


def wait_mail(to, subject_has, timeout=60):
    t0 = time.time()
    while time.time() - t0 < timeout:
        m = mails(to, subject_has)
        if m:
            return m[0], mail_text(m[0]["ID"]), round(time.time() - t0)
        time.sleep(2)
    return None, "", None


# ---------------------------------------------------------------- global configuration (browser, as admin)
CONFIG = "/manage/batch-control-configuration/"


def set_switches(run, change, tag, extra=None):
    """The administrator sets the two switches (and optionally text fields {name: value}) on the Batch Control
    configuration page and saves, as a person does (clicking the toggle labels). Returns the save's HTTP status."""
    s = Session("admin")
    s.go(CONFIG)
    for name, want in [("runControlEnabled", run), ("changeControlEnabled", change)]:
        cb = s.page.locator(f"input[name='_.{name}']")
        if cb.is_checked() != want:
            s.page.locator(f"input[name='_.{name}'] + label").click()
    for name, value in (extra or {}).items():
        box = s.page.locator(f"input[name='_.{name}']").first
        box.fill(str(value))
    s.shot([s.page.locator("input[name='_.runControlEnabled']").locator("xpath=ancestor::div[contains(@class,'jenkins-section')][1]")]
           + ([s.page.locator(f"input[name='_.{n}']").first for n in extra] if extra else []), f"R18-{tag}-config")
    with s.page.expect_navigation() as nav:
        s.page.click("button[name=Submit]")
    s.page.wait_for_load_state("load")
    status = nav.value.status if nav.value else None
    s.done()
    return status


def switches():
    out = gv("""def c = io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration.get()
return "${c.runControlEnabled},${c.changeControlEnabled},${c.notifyBeforeExpiryMinutes}".toString()""")
    run, change, before = (out.split(",") + ["", "", ""])[:3]
    return {"run": run == "true", "change": change == "true", "notifyBeforeExpiryMinutes": before}


# ---------------------------------------------------------------- change records
def diff_of(record_id):
    """The stored diff of a change record (batch-control/changes/diff/<id>.patch, ARCHITECTURE storage), '' when none."""
    if not re.fullmatch(r"[0-9]{8}-[0-9]{6}-[a-z0-9]{6}", record_id or ""):
        return ""
    return gv(f"""def f = new File(jenkins.model.Jenkins.get().rootDir, 'batch-control/changes/diff/{record_id}.patch')
return f.exists() ? f.getText('UTF-8') : ''""")


def records_for(target, since=0):
    """This month's change records whose target is `target` (at or after the epoch millis `since`), oldest first; a
    CONFIGURE record carries its stored diff under "diff"."""
    rows = changes(lambda r: r.get("target") == target)
    rows = [r for r in rows if since == 0 or record_millis(r) >= since]
    for r in rows:
        if r.get("type") == "CONFIGURE" and "diff" not in r:
            r["diff"] = diff_of(r.get("id"))
    return rows


def record_millis(r):
    at = r.get("at")
    if isinstance(at, (int, float)):
        return int(at)
    if isinstance(at, dict):
        return int(at.get("epochSecond", 0)) * 1000 + int(at.get("nano", 0)) // 1_000_000
    try:
        from datetime import datetime
        return int(datetime.fromisoformat(str(at).replace("Z", "+00:00")).timestamp() * 1000)
    except ValueError:
        return 0


def local_time(millis):
    """The controller's display form of an instant (yyyy-MM-dd HH:mm:ss in the JVM zone), as the History page shows it."""
    return gv(f"return new Date({int(millis)}L).format('yyyy-MM-dd HH:mm:ss', TimeZone.getDefault())")


def history_changes(user, job, tag, since=0):
    """`user` opens the History page filtered to change records of `job`; returns the rows {type, target, user, at, detail}
    shown at or after the epoch millis `since` (compared in the page's own time format), and the page text."""
    start = local_time(since // 1000 * 1000) if since else ""
    s = Session(user)
    s.go(f"/batch-control/history/?kind=changes&job={requests.utils.quote(job)}")
    rows = []
    for tr in s.page.locator("#main-panel table tbody tr").all():
        cells = [flat(td.inner_text()) for td in tr.locator("td").all()]
        if len(cells) >= 6:
            at = re.sub(r"\s+[A-Z]{2,5}$", "", cells[3])
            if not start or at >= start:
                rows.append({"type": cells[0], "target": cells[1], "user": cells[2], "at": cells[3], "detail": cells[5]})
    s.shot("#main-panel", f"R18-{tag}-history")
    text = flat(s.text())
    console_ok(tag, s, f"{user}'s History page (change records of {job})")
    s.done()
    return rows, text


def configure_save(user, job, tag, description=None):
    """`user` opens the job's configure page in the browser, optionally replaces the description, and presses Save.
    Returns (status of the configure page, status of the save's landing page)."""
    s = Session(user)
    r = s.go(J(job) + "/configure")
    page = r.status if r else None
    if page != 200:
        s.done()
        return page, None
    if description is not None:
        box = s.page.locator("textarea[name=description]").first
        box.fill(description)
    s.shot(s.page.locator("textarea[name=description]").first, f"R18-{tag}-configure")
    with s.page.expect_navigation() as nav:
        s.page.locator("button[name=Submit]").first.click()
    s.page.wait_for_load_state("load")
    landing = nav.value.status if nav.value else None
    s.done()
    return page, landing
