"""e2e-23 R3-01: a change record that cannot be written never stops or reverses the change-control switch. With
JENKINS_HOME/batch-control/changes/ unwritable, turning change control off (the kill switch) completes: no 500, off in
memory and on disk, every open permission window revoked, and after change control is back on the window confers nothing.

usage: python kill_switch.py [AFS]   rows: out/kill_switch.jsonl, shots: screenshots/run-23/R23-R3-01-*.png
Item r23-killswitch (Freestyle); the requester (no standing Job/Configure) gets a 15-minute CONFIGURE window from
approver-1 (REST). The fault is arranged with `docker exec -u root` (chmod 555 on changes/, 444 on its files) and the
exact modes are restored after each section and at the end, also on failure. Change control is left on.

A  arrangement: the item, change control on, changes/ writable; premise: the requester can neither open nor save the
   item's configuration (403).
F  the form path: inside the window (premise: the requester's config.xml POST answers 200), changes/ unwritable, the
   administrator unticks "change control" on /manage/batch-control-configuration/ in a new browser context and saves:
   the save answers below 500 and the page is no error page; change control is off in memory and in the stored
   configuration; the window's grant file carries revokedAtMillis. Then changes/ writable and change control on again:
   the requester's configure page and config.xml POST answer 403.
S  the setter path (JCasC and scripts call it): the same with BatchControlGlobalConfiguration.setChangeControlEnabled(false)
   through the script console: it does not throw, off in memory and on disk, the window revoked, 403 afterwards.

The JCasC boot path (a restart with change control off in jenkins.yaml and changes/ unwritable) is not here: it needs a
container restart, which only a `last` unit may do, and on a plugin without the fix Jenkins would not come back without
repairing the volume; T-01-19 (GlobalSwitchRecordFailureRestartTest) covers it."""
import json
import re
import sys
import time

sys.path.insert(0, __file__.rsplit("/", 1)[0])
import lib  # noqa: E402
from lib import api, check, note, J, Session  # noqa: E402

lib.LOGNAME[0] = "kill_switch"
JOB = "r23-killswitch"
USER, APPROVER = "requester", "approver-1"
CHANGES = f"{lib.JH}/batch-control/changes"
CFG_FILE = f"{lib.JH}/{lib.BC}.config.BatchControlGlobalConfiguration.xml"
MODES = {}


def modes():
    """path -> octal mode of changes/ and every file below it."""
    rc, out = lib.dexec("sh", "-c", f"find {CHANGES} -exec stat -c '%a %n' {{}} +")
    return {ln.split(" ", 1)[1]: ln.split(" ", 1)[0] for ln in out.splitlines() if " " in ln and ln.split(" ", 1)[0].isdigit()}


def break_changes():
    """changes/ and everything below it unwritable (root inside the container); returns the proof of the fault."""
    month = lib.gv("return java.time.YearMonth.now().toString()")
    lib.dexec("sh", "-c", f"touch {CHANGES}/{month}.jsonl", user=None)
    MODES.clear()
    MODES.update(modes())
    lib.dexec("sh", "-c", f"find {CHANGES} -type d -exec chmod 555 {{}} + ; find {CHANGES} -type f -exec chmod 444 {{}} +",
              user="root")
    rc_create, _ = lib.dexec("sh", "-c", f"touch {CHANGES}/r23-probe")
    rc_append, _ = lib.dexec("sh", "-c", f"printf '' >> {CHANGES}/{month}.jsonl")
    now = modes()
    return {"create_refused": rc_create != 0, "append_refused": rc_append != 0, "entries": len(now),
            "modes": {p: m for p, m in now.items() if p in (CHANGES, f"{CHANGES}/{month}.jsonl")}}


def restore_changes():
    """The exact modes recorded before the fault (755/644 for anything new)."""
    if MODES:
        cmds = "; ".join(f"chmod {m} '{p}'" for p, m in MODES.items())
        lib.dexec("sh", "-c", cmds, user="root")
        MODES.clear()
    lib.dexec("sh", "-c", f"chmod u+w {CHANGES}; rm -f {CHANGES}/r23-probe", user="root")
    rc, _ = lib.dexec("sh", "-c", f"touch {CHANGES}/r23-probe && rm -f {CHANGES}/r23-probe")
    return rc == 0


def change_control():
    return lib.gv(lib.CFG + "return cfg.isChangeControlEnabled()")


def set_change_control(on):
    return lib.gv(lib.CFG + f"try {{ cfg.setChangeControlEnabled({str(on).lower()}); return 'returned ' + "
                            f"cfg.isChangeControlEnabled() }} catch (Throwable t) {{ return 'THREW ' + t }}")


def stored_change_control():
    _, out = lib.dexec("sh", "-c", f"grep -o '<changeControlEnabled>[a-z]*</changeControlEnabled>' {CFG_FILE}")
    m = re.search(r">([a-z]+)<", out)
    return m.group(1) if m else None


def requester_configure():
    """(GET configure, POST config.xml) status codes of the requester on the item."""
    get = api(USER, J(JOB) + "/configure").status_code
    xml = api("admin", J(JOB) + "/config.xml").text
    new = re.sub(r"<description>[^<]*</description>", f"<description>e2e-23 R3-01 {int(time.time())}</description>", xml,
                 count=1)
    post = api(USER, J(JOB) + "/config.xml", "POST", data=new.encode("utf-8"),
               headers={"Content-Type": "application/xml"}).status_code
    return get, post


def open_window(sec):
    r = api(USER, "/batch-control/grants/create", "POST",
            data=[("scopeFullName", JOB), ("actions", "CONFIGURE"), ("durationMinutes", "15"),
                  ("reason", f"e2e-23 R3-01 {sec}"), ("approvers", APPROVER)])
    gid = lib.loc_id(r)
    st = lib.decide(APPROVER, "grants", gid, "approve", "e2e-23") if gid else None
    get, post = requester_configure()
    check(sec, "precondition: the requester's CONFIGURE window is open (config.xml POST 200)",
          gid is not None and st in (200, 302, 303) and post == 200, request=gid, approve=st, configure=get, save=post)
    return gid


def grants_of(request_id):
    """[(grant id, revokedAtMillis or '')] of the grants made from the grant request."""
    out = lib.gv(f"""def d = new File(jenkins.model.Jenkins.get().rootDir, 'batch-control/grants')
return (d.listFiles() ?: []).findAll {{ it.name.endsWith('.xml') && it.getText('UTF-8').contains({json.dumps(request_id)}) }}
  .collect {{ f -> def g = new XmlSlurper().parse(f); g.id.text() + '=' + g.revokedAtMillis.text() }}.join(',')""")
    return [tuple(x.split("=", 1)) for x in out.split(",") if "=" in x]


def after_kill_switch(sec, gid):
    cc, stored = change_control(), stored_change_control()
    grants = grants_of(gid)
    check(sec, "change control is off in memory and in the stored configuration", cc == "false" and stored == "false",
          memory=cc, stored=stored)
    check(sec, "the open window is revoked (its grant file carries revokedAtMillis)",
          bool(grants) and all(r for _, r in grants), grants=grants)
    ok = restore_changes()
    back = set_change_control(True)
    get, post = requester_configure()
    check(sec, "after changes/ is writable again and change control is back on, the requester can neither open nor save "
          "the configuration (403)", ok and back == "returned true" and get == 403 and post == 403,
          writable=ok, change_control=back, configure=get, save=post)


def sec_A():
    created = lib.ensure_job(JOB, lib.job_xml("fs", shell="echo r23"))
    writable = restore_changes()
    on = set_change_control(True)
    get, post = requester_configure()
    check("A", "arrangement: r23-killswitch exists, changes/ writable, change control on; premise: the requester can "
          "neither open nor save its configuration (403)", writable and on == "returned true" and get == 403 and post == 403,
          created=created, writable=writable, change_control=on, configure=get, save=post, container=lib.CONTAINER)


def sec_F():
    gid = open_window("F")
    fault = break_changes()
    check("F", "fault: changes/ refuses a new file and an append", fault["create_refused"] and fault["append_refused"],
          **fault)
    s = Session("admin", fresh=True)
    s.go("/manage/batch-control-configuration/")
    box = s.page.locator('input[name="_.changeControlEnabled"]')
    box.set_checked(False, force=True)
    s.shot("#main-panel", "R23-R3-01-1-change-control-unticked")
    with s.page.expect_response(lambda r: "configSubmit" in r.url and r.request.method == "POST") as ri:
        s.page.locator('button[name="Submit"]').click()
    s.page.wait_for_load_state("load")
    status = ri.value.status
    page = s.page.locator("body").inner_text()
    s.shot("body", "R23-R3-01-2-after-save")
    s.done()
    error_page = re.search(r"(?i)oops|a problem occurred|stack trace|exception", page)
    check("F", "screen: the save answers below 500 and the page is no error page", status < 500 and not error_page,
          http=status, error=error_page.group(0) if error_page else None, text=re.sub(r"\s+", " ", page)[:300])
    after_kill_switch("F", gid)
    s = Session(USER, fresh=True)
    r = s.go(J(JOB) + "/configure")
    s.shot("body", "R23-R3-01-3-requester-configure-after")
    code = r.status if r else None
    s.done()
    check("F", "screen: the requester's configure page answers 403 after the kill switch", code == 403, http=code)


def sec_S():
    gid = open_window("S")
    fault = break_changes()
    out = set_change_control(False)
    check("S", "setChangeControlEnabled(false) with changes/ unwritable does not throw (D-42)",
          fault["append_refused"] and out.startswith("returned"), result=out[:300], fault=fault["append_refused"])
    after_kill_switch("S", gid)


def cleanup():
    ok = restore_changes()
    on = set_change_control(True) if change_control() != "true" else "already on"
    note("cleanup", "changes/ writable again and change control on", writable=ok, change_control=on)
    if not ok:
        raise RuntimeError("changes/ is still unwritable")


if __name__ == "__main__":
    lib.run(sys.argv, {"A": sec_A, "F": sec_F, "S": sec_S}, cleanup)
