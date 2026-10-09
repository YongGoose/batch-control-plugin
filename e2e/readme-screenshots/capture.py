"""Recreates the demo data and the five README screenshots (docs/images/*.png). See README.md next to this file.

Runs against a running e2e stack (scripts/up.sh, Jenkins at the root context), preferably a fresh one: the seed
is skipped when the folder `batch` already exists, the screenshots are always taken (each run adds one pending
run request, the one the Request Run form submits). Reuses ../r6/lib.py: Session (one browser context per
account, real login form), api (basic auth + crumb) and groovy (script console). The script console only arranges
state (settings, the demo folder and jobs); every run request, decision, grant and configuration change goes
through the plugin's own HTTP endpoints as the account a person would use, the screenshots through the browser.
"""
import json
import os
import pathlib
import re
import sys
import time
from urllib.parse import urlparse

HERE = pathlib.Path(__file__).resolve().parent
IMAGES = HERE.parent.parent / "docs" / "images"

# lib.py reads both at import. The regular stack serves Jenkins at the root context. Playwright's own Chromium
# formats <input type=date> from the context locale (en-US); Google Chrome would use the host's locale.
_port = "8080"
for _line in (HERE.parent / ".env").read_text().splitlines():
    if _line.startswith("BC_PORT="):
        _port = _line.split("=", 1)[1].strip() or _port
os.environ.setdefault("BC_BASE", f"http://localhost:{_port}")
os.environ.setdefault("BC_BROWSER_CHANNEL", "chromium")
sys.path.insert(0, str(HERE.parent / "r6"))
import lib  # noqa: E402

PREFIX = urlparse(lib.BASE).path.rstrip("/")
FOLDER = "batch"
SETTLEMENT = f"/job/{FOLDER}/job/daily-settlement"
LOAD = f"/job/{FOLDER}/job/nightly-load"
CLOSE = f"/job/{FOLDER}/job/month-end-close"
BOTH = ["approver-1", "approver-2"]

# ------------------------------------------------------------------------------------------------- seed

SEED = r"""
import jenkins.model.Jenkins
import com.cloudbees.hudson.plugins.folder.Folder

def j = Jenkins.get()
if (j.getItemByFullName('%(folder)s') != null) { println 'SEED-SKIPPED'; return }
j.setSystemMessage(null)
j.save()

// Production-like settings instead of the e2e minimums of casc/jenkins.yaml (JCasC restores those on a reboot).
def cfg = j.getExtensionList('io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration')[0]
cfg.setApprovers(['approver-1', 'approver-2', 'admin'])
cfg.setPendingTimeoutHours(24)
cfg.setApprovedRunTimeoutMinutes(60)
cfg.setGrantDurationOptions([15, 30, 60, 120])
cfg.setMaxGrantMinutes(240)
cfg.setNotifyBeforeExpiryMinutes(10)
cfg.save()

def folder = j.createProject(Folder, '%(folder)s')
folder.setDescription('Settlement and data-warehouse batches. Runs need an approved run request.')
folder.save()
def jobs = %(jobs)s
jobs.each { name, xml -> folder.createProjectFromXML(name, new ByteArrayInputStream(xml.getBytes('UTF-8'))) }
println 'SEED-DONE'
"""


def _string(name, desc):
    return (f"<hudson.model.StringParameterDefinition><name>{name}</name><description>{desc}</description>"
            "<defaultValue></defaultValue><trim>true</trim></hudson.model.StringParameterDefinition>")


def _bool(name, desc, default):
    return (f"<hudson.model.BooleanParameterDefinition><name>{name}</name><description>{desc}</description>"
            f"<defaultValue>{str(default).lower()}</defaultValue></hudson.model.BooleanParameterDefinition>")


def _choice(name, desc, choices):
    items = "".join(f"<string>{c}</string>" for c in choices)
    return (f"<hudson.model.ChoiceParameterDefinition><name>{name}</name><description>{desc}</description>"
            f'<choices class="java.util.Arrays$ArrayList"><a class="string-array">{items}</a></choices>'
            "</hudson.model.ChoiceParameterDefinition>")


def _job(description, params, command):
    return ("<project><description>" + description + "</description><keepDependencies>false</keepDependencies>"
            "<properties><hudson.model.ParametersDefinitionProperty><parameterDefinitions>" + "".join(params)
            + "</parameterDefinitions></hudson.model.ParametersDefinitionProperty></properties>"
            '<scm class="hudson.scm.NullSCM"/><canRoam>true</canRoam><disabled>false</disabled>'
            "<builders><hudson.tasks.Shell><command>" + command.replace("&", "&amp;").replace("<", "&lt;")
            + "</command></hudson.tasks.Shell></builders><publishers/><buildWrappers/></project>")


JOBS = {
    "daily-settlement": _job(
        "Posts the day's card and transfer settlements to the general ledger.",
        [_string("BUSINESS_DATE", "Business date to settle (YYYY-MM-DD)."),
         _choice("SCOPE", "Settlement streams to process.", ["ALL", "CARD", "TRANSFER"]),
         _bool("DRY_RUN", "Validate and report only; nothing is posted to the ledger.", True)],
        'echo "Settling ${BUSINESS_DATE} (scope ${SCOPE}, dry run ${DRY_RUN})"\nsleep 3\necho "18240 entries balanced"\n'),
    "nightly-load": _job(
        "Loads the vendor extracts into the data warehouse.",
        [_string("LOAD_DATE", "Extract date to load (YYYY-MM-DD)."),
         _choice("TARGET", "Warehouse schema to load into.", ["dwh_prod", "dwh_replay"]),
         _bool("FULL_RELOAD", "Truncate and reload the day instead of merging.", False)],
        # The first run of a date fails (the extract "is missing"), the re-run succeeds: one incident.
        'SRC=/data/inbound/legacy-sftp\necho "Loading ${LOAD_DATE} from ${SRC} into ${TARGET}"\nsleep 1\n'
        'if [ ! -f "$WORKSPACE/.seen-${LOAD_DATE}" ]; then touch "$WORKSPACE/.seen-${LOAD_DATE}"; '
        'echo "ERROR: extract for ${LOAD_DATE} not found in ${SRC}"; exit 1; fi\nsleep 2\necho "Load complete"\n'),
    "month-end-close": _job(
        "Closes the accounting month: accruals, FX revaluation and the trial balance.",
        [_string("CLOSING_MONTH", "Month to close (YYYY-MM).")],
        'echo "Closing ${CLOSING_MONTH}"\nsleep 2\n'),
}


def rel(location):
    """A redirect target as a path relative to lib.BASE (Jenkins may answer with an absolute URL)."""
    path = urlparse(location).path
    return path[len(PREFIX):] if PREFIX and path.startswith(PREFIX) else path


def post(user, path, data, expect=(302, 303)):
    r = lib.api(user, path, "POST", data=data)
    if r.status_code not in expect:
        raise RuntimeError(f"POST {path} as {user}: HTTP {r.status_code}\n{r.text[:800]}")
    return r


def request_run(job, reason, params, approvers):
    """A run request as `requester`, posted urlencoded (the REST form of the request, D-37)."""
    body = {"reason": reason, "approvers": approvers,
            "parameter": [{"name": k, "value": v} for k, v in params.items()]}
    return rel(post("requester", f"{job}/batch-control/submit", {"json": json.dumps(body)}).headers["Location"])


def wait_build(job, number, timeout=90):
    end = time.time() + timeout
    while time.time() < end:
        r = lib.api("admin", f"{job}/{number}/api/json?tree=building,result")
        if r.status_code == 200 and not r.json()["building"]:
            return r.json()["result"]
        time.sleep(1)
    raise RuntimeError(f"{job} #{number} did not finish")


def approve_and_run(job, number, req, approver, comment):
    post(approver, req + "approve", {"comment": comment})
    return wait_build(job, number)


def seed():
    jobs = "[" + ", ".join(f"'{n}': '''{x}'''" for n, x in JOBS.items()) + "]"
    out = lib.groovy(SEED % {"folder": FOLDER, "jobs": jobs})
    print("seed:", out.splitlines()[-1] if out else "(no output)")
    if "SEED-DONE" not in out:
        return
    r = request_run(SETTLEMENT, "Regular 01:30 settlement for 2026-10-07 was skipped during the DB maintenance "
                    "window (CHG-2291).", {"BUSINESS_DATE": "2026-10-07", "SCOPE": "ALL", "DRY_RUN": False}, BOTH)
    print("daily-settlement #1", approve_and_run(SETTLEMENT, 1, r, "approver-1", "Maintenance closed at 02:10, go ahead."))
    r = request_run(LOAD, "Nightly load aborted at 03:12 on a lock timeout; re-run for 2026-10-08.",
                    {"LOAD_DATE": "2026-10-08", "TARGET": "dwh_prod", "FULL_RELOAD": False}, BOTH)
    print("nightly-load #1", approve_and_run(LOAD, 1, r, "approver-2", "Approved."))
    r = request_run(CLOSE, "Re-close September after the late FX adjustment journal (FIN-118).",
                    {"CLOSING_MONTH": "2026-09"}, ["approver-1"])
    post("approver-1", r + "reject", {"comment": "Wait for the controller's sign-off, then resubmit."})
    r = request_run(LOAD, "Second attempt for 2026-10-08: the extract was re-delivered at 06:40.",
                    {"LOAD_DATE": "2026-10-08", "TARGET": "dwh_prod", "FULL_RELOAD": False}, BOTH)
    print("nightly-load #2", approve_and_run(LOAD, 2, r, "approver-1", "File confirmed on the SFTP host."))
    r = request_run(SETTLEMENT, "Dry run to check the 2026-10-08 totals before the real posting.",
                    {"BUSINESS_DATE": "2026-10-08", "SCOPE": "CARD", "DRY_RUN": True}, BOTH)
    print("daily-settlement #2", approve_and_run(SETTLEMENT, 2, r, "approver-2", "OK for a dry run."))
    r = request_run(CLOSE, "Submitted twice by mistake.", {"CLOSING_MONTH": "2026-09"}, ["approver-1"])
    post("requester", r + "cancel", {})
    request_run(LOAD, "Backfill 2026-10-05 so the new merchant dimension is populated.",
                {"LOAD_DATE": "2026-10-05", "TARGET": "dwh_prod", "FULL_RELOAD": True}, BOTH)

    # Permission window: CONFIGURE on nightly-load, approved, then a configuration change made inside it.
    r = post("requester", "/batch-control/grants/create", {
        "scopeFullName": f"{FOLDER}/nightly-load", "actions": "CONFIGURE", "durationMinutes": "30",
        "reason": "Point the extract path to the new SFTP host (CHG-2304).", "approvers": BOTH})
    grant = rel(r.headers["Location"])
    if not re.fullmatch(r"/batch-control/grants/[0-9a-f-]{36}/", grant):  # not the new request's page: the only one
        grant = re.findall(r'href="[^"]*(/batch-control/grants/[0-9a-f-]{36}/)"',
                           lib.api("requester", "/batch-control/grants/").text)[0]
    post("approver-2", grant + "approve", {"comment": "Approved for the CHG-2304 window."})
    xml = lib.api("requester", f"{LOAD}/config.xml").text
    r = lib.api("requester", f"{LOAD}/config.xml", "POST",
                data=xml.replace("/data/inbound/legacy-sftp", "/data/inbound/sftp-02").encode("utf-8"),
                headers={"Content-Type": "application/xml"})
    print("configuration change inside the window: HTTP", r.status_code)
    post("requester", "/batch-control/grants/create", {
        "scopeFullName": FOLDER, "actions": "CREATE", "createNamePattern": "fx-revaluation",
        "durationMinutes": "60", "reason": "Create the fx-revaluation job for the new FX revaluation batch (PRJ-77).",
        "approvers": ["approver-1"]})


# ------------------------------------------------------------------------------------------ screenshots

def write(page, name, height, top=0):
    """Viewport clip, full width; then (if Pillow is installed) 256 colours, which a flat UI does not show."""
    path = IMAGES / f"{name}.png"
    page.evaluate("document.activeElement && document.activeElement.blur()")  # no focus ring in the picture
    page.wait_for_timeout(600)  # the ring fades out with a CSS transition
    page.screenshot(path=str(path), clip={"x": 0, "y": top, "width": 1280, "height": int(height)})
    try:
        from PIL import Image
        Image.open(path).convert("RGB").quantize(colors=256, method=Image.Quantize.MEDIANCUT,
                                                 dither=Image.Dither.NONE).save(path, optimize=True)
    except ImportError:
        pass
    print(f"docs/images/{name}.png: {path.stat().st_size // 1024} KB")


def save(page, name, bottom, pad=24):
    """From the top of the page (header and breadcrumb) to the bottom edge of `bottom`, with the viewport as tall
    as the document first, so sticky bars (header, form buttons) sit where a reader sees them."""
    page.set_viewport_size({"width": 1280, "height": 900})
    h = page.evaluate("document.documentElement.scrollHeight")
    page.set_viewport_size({"width": 1280, "height": min(max(900, h), 2400)})
    page.wait_for_timeout(300)
    loc = page.locator(bottom).last if isinstance(bottom, str) else bottom
    box = loc.bounding_box()
    write(page, name, box["y"] + box["height"] + pad)


def param(page, name):
    return page.locator('[name="parameter"]').filter(has=page.locator(f'input[name="name"][value="{name}"]'))


def tick(checkbox, on=True):
    """Sets a checkbox with a real click event (Jenkins draws its own box over the input)."""
    if checkbox.is_checked() != on:
        checkbox.evaluate("el => el.click()")


def shot_request_run():
    s = lib.Session("requester")
    s.go(f"{SETTLEMENT}/batch-control/")
    p = s.page
    p.fill('textarea[name="reason"]', "Re-run for 2026-10-08 after upstream file arrived late (vendor ticket INC-5521).")
    # One approver only: with two boxes checked the multipart form currently keeps just the last one, so the
    # approval screenshot (approver-1's view) would show no Approve/Reject buttons.
    tick(p.locator("#batch-control-approver-0"))
    param(p, "BUSINESS_DATE").locator('input[name="value"]').fill("2026-10-08")
    param(p, "SCOPE").locator('select[name="value"]').select_option("ALL")
    tick(param(p, "DRY_RUN").locator('input[name="value"]'), on=False)
    save(p, "request-run", 'button[name="Submit"]')
    with p.expect_navigation():
        p.locator('button[name="Submit"]').click()
    req = rel(p.url)
    s.done()
    return req


def shot_approval(req):
    s = lib.Session("approver-1")
    s.go(req)
    if s.page.locator('button:has-text("Approve")').count() == 0:
        raise RuntimeError(f"approver-1 sees no Approve button on {req}")
    save(s.page, "approval", "#main-panel")
    s.done()


def shot_permission_window():
    s = lib.Session("requester")
    s.go(f"/batch-control/grants/new?scopeFullName={FOLDER}/nightly-load")
    p = s.page
    tick(p.locator("#grant-action-configure"))
    p.select_option('select[name="durationMinutes"]', "60")
    p.fill('textarea[name="reason"]', "Add the new SFTP host as a fallback source for the vendor extract (CHG-2311).")
    tick(p.locator("#grant-approver-0"))
    tick(p.locator("#grant-approver-1"))
    save(p, "permission-window", 'button[name="Submit"]')
    s.done()


def shot_history():
    s = lib.Session("approver-1")
    s.go("/batch-control/history/?kind=requests")
    save(s.page, "dashboard", s.page.get_by_text(re.compile(r"matching records")).first)
    s.done()


def shot_global_config():
    s = lib.Session("admin")
    s.go("/manage/configure")
    p = s.page
    section = p.locator(".jenkins-section", has=p.locator(".jenkins-section__title", has_text="Batch Control")).first
    # The sections above scroll under the translucent header and would show through it, blurred: hide them.
    section.evaluate("""sec => { for (const el of document.querySelectorAll('#main-panel *')) {
        if (!el.contains(sec) && (el.compareDocumentPosition(sec) & Node.DOCUMENT_POSITION_FOLLOWING)) {
            el.style.visibility = 'hidden'; } } }""")
    header = p.locator("#page-header").bounding_box()["height"]
    box = section.bounding_box()
    p.set_viewport_size({"width": 1280, "height": int(header + box["height"] + 160)})
    p.evaluate(f"window.scrollTo(0, {box['y'] - header - 16})")
    p.wait_for_timeout(500)
    box = section.bounding_box()  # viewport coordinates after the scroll
    write(p, "global-config", box["y"] + box["height"] + 16)
    s.done()


def main():
    IMAGES.mkdir(parents=True, exist_ok=True)
    seed()
    shot_approval(shot_request_run())
    shot_permission_window()
    shot_history()
    shot_global_config()
    lib.close()


if __name__ == "__main__":
    main()
