"""e2e-16: a window's end survives a failed write and a restart (D-74, 6325e85; SPEC 8 "deleting the item ends the window").

usage: python durable.py     rows: out/durable.jsonl, shots: R16-DUR-*.png
Restarts Jenkins (docker restart of $BC_CONTAINER-jenkins), so ci/shard.py runs it as a `last` unit. JCasC re-applies the
authorization strategy at every boot, which drops the permissions the arrangement scripts added; r16/arrange.py is run again
after the restart, and a second window (on r16/job-a, never touched) is the positive control that the holder still has
access after the restart.
  1. w16b holds a CONFIGURE window on the job r16-dur-job.
  2. Fault: the grants directory (JENKINS_HOME/batch-control/grants) is made read-only through the script console, so no
     grant file can be written (precondition checked: a file cannot be created there).
  3. The administrator deletes r16-dur-job. The window ends at once although its file cannot be written: the holder's
     grants list shows it ended, a GRANT_REVOKE record names admin, the file on disk still says it is open (the fault
     held), and a job the administrator re-creates at the name gives the holder nothing (403).
  4. Jenkins restarts with the fault still in place: the file still says open, but the window stays ended (the holder
     gets 403 on the re-created job; the grants list still shows it ended), recovered from the GRANT_REVOKE record.
  5. The directory is made writable again: within the periodic work (a minute) the end is written to the file."""
import os
import re
import subprocess
import sys
import time

sys.path.insert(0, __file__.rsplit("/", 1)[0])
import lib  # noqa: E402
from lib import (Session, api, gv, check, note, window, changes, text_of, J, BASE, run_sections, console_ok,
                 revoke_all)  # noqa: E402

lib.LOGNAME[0] = "durable"
U = "w16b"
JOB = "r16-dur-job"
H = {"Content-Type": "application/x-www-form-urlencoded"}
CONTAINER = os.environ.get("BC_CONTAINER", "batch-control-e2e") + "-jenkins" if os.environ.get("BC_CONTAINER") else "batch-control-e2e"


def st(user, path, method="GET", **kw):
    return api(user, path, method, **kw).status_code


def grants_dir_writable(on):
    return gv(f"""def d = new File(jenkins.model.Jenkins.get().rootDir, 'batch-control/grants')
d.setWritable({'true' if on else 'false'}, false)
def probe = new File(d, 'e2e16-probe.tmp'); def can = false
try {{ can = probe.createNewFile(); if (can) probe.delete() }} catch (IOException e) {{ can = false }}
return 'writable=' + d.canWrite() + ' create=' + can""")


def file_open(gid):
    """True when the grant file on disk still has no revokedAtMillis."""
    return gv(f"""def f = new File(jenkins.model.Jenkins.get().rootDir, 'batch-control/grants/{gid}.xml')
return f.exists() ? String.valueOf(new XmlSlurper().parse(f).revokedAtMillis.text() == '') : 'missing'""")


def holder_list_row(gid, tag):
    s = Session(U)
    s.go("/batch-control/grants/")
    row = s.page.locator(f"#main-panel tr:has(a[href*='{gid}'])").first
    text = re.sub(r"\s+", " ", row.inner_text()) if row.count() else ""
    # which list section the row is in: the nearest preceding heading
    section = row.evaluate("""r => { let e = r.closest('table'); while (e && e.previousElementSibling) { e = e.previousElementSibling;
        if (/^H[1-4]$/.test(e.tagName)) return e.innerText; } return ''; }""") if row.count() else ""
    s.shot("#main-panel", f"R16-DUR-{tag}-list")
    if not row.count():
        note("D", f"{tag}: the window's row was not found on the holder's grants list", url=s.page.url.replace(BASE, ""),
             page=re.sub(r"\s+", " ", s.text())[:600])
    s.done()
    return text, section


def wait_ready(timeout=300):
    t0 = time.time()
    while time.time() - t0 < timeout:
        try:
            r = api("admin", "/pluginManager/api/json?tree=plugins[shortName,active]")
            if r.status_code == 200 and any(p.get("shortName") == "batch-control" and p.get("active") for p in r.json()["plugins"]):
                return round(time.time() - t0)
        except Exception:
            pass
        time.sleep(3)
    return None


def sec_D():
    revoke_all(U)
    if st("admin", J(JOB) + "/api/json") != 200:
        api("admin", f"/createItem?name={JOB}&mode=hudson.model.FreeStyleProject", "POST", headers=H)
    gid = window(U, JOB, ["CONFIGURE"], reason="e2e-16 durability: configure r16-dur-job")
    ctrl = window(U, "r16/job-a", ["CONFIGURE"], reason="e2e-16 durability: control window on r16/job-a")
    c0 = st(U, J(JOB) + "/configure")
    fault = grants_dir_writable(False)
    try:
        check("D", "precondition: the window confers Configure, and the fault holds (no file can be created in the grants directory)",
              c0 == 200 and "create=false" in fault, configure=c0, fault=fault)
        n0 = len(changes())
        d = api("admin", J(JOB) + "/doDelete", "POST")
        rec = [c for c in changes()[n0:] if c.get("type") == "GRANT_REVOKE" and c.get("grantId") == gid]
        on_disk_open = file_open(gid)
        text, section = holder_list_row(gid, "1-after-delete")
        api("admin", f"/createItem?name={JOB}&mode=hudson.model.FreeStyleProject", "POST", headers=H)
        c1 = st(U, J(JOB) + "/configure")
        check("D", "the administrator deletes the job while no grant file can be written: the window ends anyway (GRANT_REVOKE by "
              "admin; listed as ended), its file still says open (the fault held), and the re-created job gives the holder 403",
              d.status_code in (200, 302) and rec and rec[-1].get("user") == "admin" and on_disk_open == "true" and c1 == 403
              and section.startswith("Ended"), delete=d.status_code, record=[r.get("detail") for r in rec][-1:], file_still_open=on_disk_open,
              list_section=section, list_row=text[:200], configure_recreated=c1)
        # restart with the fault in place
        r = subprocess.run(["docker", "restart", "-t", "180", CONTAINER], capture_output=True, text=True, timeout=400)
        secs = wait_ready()
        # permissions only: the windows of this section (the ended one and the control window) must stay as they are
        rearr = subprocess.run([sys.executable, str(lib.HERE / "arrange.py")], capture_output=True, text=True, timeout=600,
                               env=dict(os.environ, R16_KEEP_WINDOWS="1"))
        note("D", "Jenkins restarted; r16/arrange.py run again (JCasC reset the arrangement's permissions)", container=CONTAINER,
             rc=r.returncode, stderr=r.stderr[-200:], ready_after_s=secs, arrange_rc=rearr.returncode)
        ctrl_conf = st(U, "/job/r16/job/job-a/configure")
        on_disk_open2 = file_open(gid)
        c2 = st(U, J(JOB) + "/configure")
        text2, section2 = holder_list_row(gid, "2-after-restart")
        check("D", "after a restart with the fault still in place the file still says open, yet the window stays ended (403 on the "
              "re-created job, listed as ended) while the holder's control window still confers (200): recovered from the "
              "GRANT_REVOKE record", secs is not None and ctrl_conf == 200 and on_disk_open2 == "true" and c2 == 403
              and section2.startswith("Ended"), ready_after_s=secs, control_window=ctrl, control_configure=ctrl_conf,
              file_still_open=on_disk_open2, configure_recreated=c2, list_section=section2, list_row=text2[:200])
    finally:
        restored = grants_dir_writable(True)
        note("D", "fault removed", state=restored)
    flushed = None
    for _ in range(45):
        if file_open(gid) == "false":
            flushed = True
            break
        time.sleep(3)
    check("D", "once the directory is writable again the end is written to the grant file within the periodic work",
          flushed is True and st(U, J(JOB) + "/configure") == 403 and st(U, "/job/r16/job/job-a/configure") == 200,
          file_open=file_open(gid),
          revokedBy=gv(f"""def f=new File(jenkins.model.Jenkins.get().rootDir,'batch-control/grants/{gid}.xml'); return new XmlSlurper().parse(f).revokedBy.text()"""))


if __name__ == "__main__":
    run_sections("D", {"D": sec_D})
