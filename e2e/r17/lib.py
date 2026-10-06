"""e2e-17 (security-39 fixes: S-39-01 id shape and 404 for every failed record lookup, S-39-02 windows never stay on a
name their item left, S-39-03 time-bounded restart re-end, D-75) on top of ../r16/lib.py: screenshots in
screenshots/run-17/ (never committed), rows in r17/out/<driver>.jsonl. The rules of every e2e pass hold: one new browser
context per account (real login form), a red-boxed clipped screenshot per step, the server state read separately (REST
with basic auth, the store through the script console, which only arranges or reads state).

Verdict lines as in r16: "PASS {...}" / "FAIL {...}" per assertion (ci/shard.py fails a step on a FAIL line or a
non-zero exit), "NOTE {...}" for an observation without a verdict."""
import importlib.util
import json
import os
import pathlib
import re
import subprocess
import sys
import time

HERE = pathlib.Path(__file__).resolve().parent
_spec = importlib.util.spec_from_file_location("r16lib", HERE.parent / "r16" / "lib.py")
L16 = importlib.util.module_from_spec(_spec)
sys.modules["r16lib"] = L16
_spec.loader.exec_module(L16)
# r16/lib.py points r6/lib.py at run-16 and r16/out; this pass writes its own.
L16._l.SHOTS = HERE.parent / "screenshots" / "run-17"
L16._l.OUT = HERE / "out"
L16.SHOTS = L16._l.SHOTS
L16.OUT = L16._l.OUT
L16.SHOTS.mkdir(parents=True, exist_ok=True)
L16.OUT.mkdir(parents=True, exist_ok=True)
SHOTS, OUT = L16.SHOTS, L16.OUT

Session, api, groovy, gv, BASE, ENV = L16.Session, L16.api, L16.groovy, L16.gv, L16.BASE, L16.ENV
check, note, run_sections, console_ok, text_of, J = L16.check, L16.note, L16.run_sections, L16.console_ok, L16.text_of, L16.J
changes, revoke_all, grant_files, decide, grant_req, loc_id, UUID = (L16.changes, L16.revoke_all, L16.grant_files, L16.decide,
                                                                    L16.grant_req, L16.loc_id, L16.UUID)
LOGNAME = L16.LOGNAME
H = {"Content-Type": "application/x-www-form-urlencoded"}
U = "w17"  # the window holder of this pass (Item/Read and RequestGrant only; r17/arrange.py)
CONTAINER = (os.environ["BC_CONTAINER"] + "-jenkins") if os.environ.get("BC_CONTAINER") else "batch-control-e2e"


def st(user, path, method="GET", **kw):
    return api(user, path, method, **kw).status_code


def exists(full):
    return st("admin", J(full) + "/api/json") == 200


def window(user, scope, actions, minutes=60, reason="e2e-17 window"):
    return L16.window(user, scope, actions, minutes, reason)


def grant_xml(gid):
    """Fields of the stored window (grant file), read with core's XStream view of the XML: scope, revokedBy, revokedReason,
    revokedAtMillis; {} when there is no file."""
    out = gv(f"""def f = new File(jenkins.model.Jenkins.get().rootDir, 'batch-control/grants/{gid}.xml')
if (!f.exists()) return '{{}}'
def x = new XmlSlurper().parse(f)
return groovy.json.JsonOutput.toJson([scope: x.scope.fullName.text(), revokedBy: x.revokedBy.text(),
  revokedReason: x.revokedReason.text(), revokedAtMillis: x.revokedAtMillis.text(), user: x.user.text()])""")
    try:
        return json.loads(out)
    except ValueError:
        return {"raw": out[:300]}


def revoke_records(gid):
    return [c for c in changes() if c.get("type") == "GRANT_REVOKE" and c.get("grantId") == gid]


def holder_rows(gid, tag, user=U):
    """The window's row on `user`'s grants list, the heading of the list section it is in, and the detail page text."""
    s = Session(user)
    s.go("/batch-control/grants/")
    row = s.page.locator(f"#main-panel tr:has(a[href*='{gid}'])").first
    text = re.sub(r"\s+", " ", row.inner_text()) if row.count() else ""
    section = row.evaluate("""r => { let e = r.closest('table'); while (e && e.previousElementSibling) { e = e.previousElementSibling;
        if (/^H[1-4]$/.test(e.tagName)) return e.innerText; } return ''; }""") if row.count() else ""
    if row.count():
        row.scroll_into_view_if_needed()
        s.shot([row, s.page.locator("#main-panel h2").filter(has_text=section.strip() or "Ended").first], f"R17-{tag}-list")
    else:
        s.shot("#main-panel", f"R17-{tag}-list")
    s.go(f"/batch-control/grants/{gid}/")
    detail = re.sub(r"\s+", " ", s.text())
    html = s.page.content()
    s.shot("#main-panel", f"R17-{tag}-detail")
    console_ok(tag, s, f"{user}'s grants list and window page")
    s.done()
    return text, section.strip(), detail, html


def restart_jenkins(timeout=400):
    """docker restart of the Jenkins container (SIGTERM, 180 s grace: JaCoCo writes its session), then waits for the
    plugin to be active. Returns the seconds until ready, or None."""
    r = subprocess.run(["docker", "restart", "-t", "180", CONTAINER], capture_output=True, text=True, timeout=timeout)
    t0 = time.time()
    while time.time() - t0 < 300:
        try:
            a = api("admin", "/pluginManager/api/json?tree=plugins[shortName,active]")
            if a.status_code == 200 and any(p.get("shortName") == "batch-control" and p.get("active") for p in a.json()["plugins"]):
                return round(time.time() - t0), r.returncode
        except Exception:
            pass
        time.sleep(3)
    return None, r.returncode


def rearrange():
    """JCasC re-applies the authorization strategy at boot and drops what the arrangement scripts added: put w17's
    permissions back (windows kept, no item created: a new item at a window's name would end that window)."""
    env = dict(os.environ, R17_KEEP_WINDOWS="1", R17_PERMS_ONLY="1")
    out = []
    for script in (HERE / "arrange.py",):
        p = subprocess.run([sys.executable, str(script)], capture_output=True, text=True, timeout=600, env=env)
        out.append((script.name, p.returncode, p.stdout[-300:] + p.stderr[-300:]))
    return out


def log_since(millis, needles):
    """Jenkins' in-memory log records (the ring buffer of /manage/log/all) since `millis` whose message or thrown class
    contains one of `needles`: [level, logger, message head]."""
    n = json.dumps(list(needles))
    out = gv(f"""def needles = {n}
def rows = []
jenkins.model.Jenkins.logRecords.findAll {{ it.millis >= {int(millis)}L }}.each {{ r ->
  def msg = (r.message ?: '') + ' ' + (r.thrown ? r.thrown.getClass().name + ': ' + (r.thrown.message ?: '') : '')
  if (needles.any {{ msg.contains(it) }}) rows << [r.level.toString(), r.loggerName, msg.take(240)]
}}
return groovy.json.JsonOutput.toJson(rows)""")
    try:
        return json.loads(out)
    except ValueError:
        return [["?", "?", out[:240]]]


def now_millis():
    return int(gv("return System.currentTimeMillis()"))
