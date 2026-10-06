"""e2e-17: windows whose item changed outside Jenkins' item events, and the startup re-end (S-39-02, D-74 (3), S-39-03).

usage: python disk.py [GFBUC]   rows: out/disk.jsonl, shots: R17-*.png
Reloads the configuration and restarts Jenkins twice (docker restart of $BC_CONTAINER-jenkins), so ci/shard.py runs it as
a `last` unit. JCasC re-applies the authorization strategy at boot, so r17/arrange.py runs again after each restart
(windows kept).
G  The documented gap path (LIMITATIONS 11, T-08-187/T-SEC-101): w17 holds CONFIGURE windows on r17-gone and r17-gone2;
   their directories are removed on disk and the administrator reloads the configuration from disk (no deletion event).
   Then the administrator renames another job onto the name under another letter case (r17-other -> R17-GONE, Rename
   page) and creates R17-GONE2 on the New Item page. Neither job gives the holder anything (403). The window on r17-gone
   ends with "it could not follow its item" (S-39-02: a window already naming the new name of a renamed item ends), the
   one on r17-gone2 with "its item was deleted" (a new item at its name, compared without letter case); both revoked by
   admin, with GRANT_REVOKE records, listed under Ended. No fault is injected: an item removed on disk is the gap the
   plugin documents. (If the reload itself already ended the windows, that is recorded and the 403s still checked.)
F  Failed follow (S-39-02 (b), D-75 (2); fault injected as in r16/durable.py): w17's window on r17/nf-a; the grants
   directory is made read-only (precondition: no file can be created) and the administrator renames r17/nf-a to nf-b on
   the Rename page: the window ends at once with "it could not follow its item" (403 on r17/nf-b, listed under Ended,
   GRANT_REVOKE record by admin) instead of keeping the old name; with the directory writable again, a job renamed onto
   r17/nf-a gives nothing, and the end is written to the file within the periodic work.
B  Startup end (D-74 (3), not covered by e2e-16): w17's window on r17-down and a control window on r17/ctl; the job's
   directory is removed while Jenkins runs, then Jenkins restarts: the window on r17-down has ended, revoked by SYSTEM
   with "its item was deleted" (GRANT_REVOKE "its item no longer exists"), listed under Ended; a job re-created at the
   name gives nothing; the control window still confers Configure (200).
U  Fail-closed restart re-end (S-39-03, D-75 (2); fault injected): w17's window on r17/u-job (and the control window)
   are open; every change log month file is made unreadable (precondition: the JVM cannot read it), then Jenkins
   restarts: every open window has ended, revoked by SYSTEM with "its state could not be confirmed at startup", the
   holder gets 403 on r17/u-job and r17/ctl, and the Ended list says so. The files are made readable again afterwards,
   and the GRANT_REVOKE records written during the fault are then readable.
C  Letter case after a restart (found in the e2e-17 dev runs; ci/shard.py runs it as its own step after GFBU): the
   folders plugin loads a folder's children case-sensitively, so after a restart a job r17/CTL can be created next to
   r17/ctl. w17's window on r17/ctl must stay open (its job still exists); the plugin compares names without regard to
   letter case and ends it ("its item was deleted"). Skipped with a NOTE where the folder's lookup ignores case."""
import re
import sys
import time

sys.path.insert(0, __file__.rsplit("/", 1)[0])
import lib  # noqa: E402
from lib import (Session, api, gv, check, note, window, text_of, J, BASE, run_sections, changes, revoke_all, st, exists,
                 grant_xml, revoke_records, holder_rows, restart_jenkins, rearrange, U, H)  # noqa: E402
import s39  # noqa: E402  (admin_rename, admin_new_item)

lib.LOGNAME[0] = "disk"
WANT = sys.argv[1] if len(sys.argv) > 1 else "GFBU"


def remove_on_disk(name):
    """Removes a top-level job's directory with the controller's own file access (no item event)."""
    return gv(f"""def d = new File(jenkins.model.Jenkins.get().rootDir, 'jobs/{name}')
def had = d.exists(); d.deleteDir(); return 'had=' + had + ' gone=' + !d.exists()""")


def wait_api(gone=(), timeout=300):
    """Waits until the root API answers and none of `gone` is listed (the reload has run); (seconds, job names)."""
    t0 = time.time()
    time.sleep(3)
    while time.time() - t0 < timeout:
        try:
            r = api("admin", "/api/json?tree=jobs[name]")
            if r.status_code == 200:
                names = [j["name"] for j in r.json().get("jobs", [])]
                if not any(g in names for g in gone):
                    return round(time.time() - t0), names
        except Exception:
            pass
        time.sleep(2)
    return None, []


def ended_ok(sec, gid, name, reason, by, tag, detail_word):
    g = grant_xml(gid)
    rec = revoke_records(gid)
    lst, section, detail, _ = holder_rows(gid, tag)
    label = f"Revoked ({reason}) by {by}"
    return check(sec, f"the window on {name} ended: revoked by {by} with '{reason}' (grant file and GRANT_REVOKE record), listed under "
                 f"Ended as '{label}'",
                 g.get("revokedReason") == reason and g.get("revokedBy") == by and rec and rec[-1].get("user") == by
                 and detail_word in (rec[-1].get("detail") or "") and section.startswith("Ended") and label in lst,
                 window=gid, stored={k: g.get(k) for k in ("scope", "revokedBy", "revokedReason")},
                 record=[x.get("detail") for x in rec][-1:], section=section, list_row=lst[:240])


def sec_G():
    revoke_all(U)
    for n in ("r17-gone", "r17-gone2", "r17-other"):
        if not exists(n):
            api("admin", f"/createItem?name={n}&mode=hudson.model.FreeStyleProject", "POST", headers=H)
    g1 = window(U, "r17-gone", ["CONFIGURE"], reason="e2e-17 gap path: configure r17-gone")
    g2 = window(U, "r17-gone2", ["CONFIGURE"], reason="e2e-17 gap path: configure r17-gone2")
    pre = {"r17-gone": st(U, "/job/r17-gone/configure"), "r17-gone2": st(U, "/job/r17-gone2/configure")}
    rm = {n: remove_on_disk(n) for n in ("r17-gone", "r17-gone2")}
    rl = api("admin", "/reload", "POST")
    secs, jobs = wait_api(gone=("r17-gone", "r17-gone2"))
    arr = rearrange()  # the reload re-read config.xml; put the arrangement's permissions back in case one was never saved
    after_reload = {gid: grant_xml(gid) for gid in (g1, g2)}
    still_open = {gid: not g.get("revokedAtMillis") for gid, g in after_reload.items()}
    check("G", "precondition: both windows conferred Configure, the directories were removed on disk and the reload no longer "
          "lists the jobs", pre == {"r17-gone": 200, "r17-gone2": 200} and "r17-gone" not in jobs and "r17-gone2" not in jobs
          and secs is not None, before=pre, removed=rm, reload=rl.status_code, ready_after_s=secs, arrange=[(a, b) for a, b, _ in arr])
    note("G", "state of the windows after the reload (T-08-187: not pinned)", open_after_reload=still_open,
         stored={gid: {k: g.get(k) for k in ("scope", "revokedBy", "revokedReason")} for gid, g in after_reload.items()})
    # a rename onto the stale name, under another letter case
    s1, _, _ = s39.admin_rename("r17-other", "R17-GONE", "G-1")
    url = s39.admin_new_item(None, "R17-GONE2", "G-2")
    res = {"rename r17-other -> R17-GONE": s1, "R17-GONE exists": exists("R17-GONE"), "R17-GONE2 exists": exists("R17-GONE2"),
           "configure R17-GONE": st(U, "/job/R17-GONE/configure"), "configure R17-GONE2": st(U, "/job/R17-GONE2/configure")}
    check("G", "neither the job renamed onto the stale name (R17-GONE) nor the new job R17-GONE2 gives the holder anything (403)",
          res["R17-GONE exists"] and res["R17-GONE2 exists"] and res["configure R17-GONE"] == 403 and res["configure R17-GONE2"] == 403,
          **res)
    if still_open[g1]:
        ended_ok("G", g1, "r17-gone", "it could not follow its item", "admin", "G-notfollowed", "not followed")
    else:
        note("G", "the reload had already ended the window on r17-gone; 'it could not follow its item' not reachable this way",
             stored=after_reload[g1])
    if still_open[g2]:
        ended_ok("G", g2, "r17-gone2", "its item was deleted", "admin", "G-newitem", "new item")
    else:
        note("G", "the reload had already ended the window on r17-gone2", stored=after_reload[g2])
    # tidy: the renamed and created jobs back to the arrangement's names
    api("admin", "/job/R17-GONE/doDelete", "POST")
    api("admin", "/job/R17-GONE2/doDelete", "POST")
    for n in ("r17-gone", "r17-gone2", "r17-other"):
        api("admin", f"/createItem?name={n}&mode=hudson.model.FreeStyleProject", "POST", headers=H)


def grants_dir_writable(on):
    return gv(f"""def d = new File(jenkins.model.Jenkins.get().rootDir, 'batch-control/grants')
d.setWritable({'true' if on else 'false'}, false)
def probe = new File(d, 'e2e17-probe.tmp'); def can = false
try {{ can = probe.createNewFile(); if (can) probe.delete() }} catch (IOException e) {{ can = false }}
return 'writable=' + d.canWrite() + ' create=' + can""")


def sec_F():
    """S-39-02 (b), D-75 (2), fault injected: a window whose grant file cannot be written when its item is renamed ends with
    'it could not follow its item' instead of keeping the old name, where a job renamed there later would find it."""
    revoke_all(U)
    for n in ("nf-a", "nf-x"):
        if not exists(f"r17/{n}"):
            api("admin", J("r17") + f"/createItem?name={n}&mode=hudson.model.FreeStyleProject", "POST", headers=H)
    gid = window(U, "r17/nf-a", ["CONFIGURE"], reason="e2e-17 failed follow: configure r17/nf-a")
    c0 = st(U, J("r17/nf-a") + "/configure")
    fault = grants_dir_writable(False)
    try:
        check("F", "precondition: the window confers Configure on r17/nf-a, and no file can be created in the grants directory "
              "(fault)", c0 == 200 and "create=false" in fault, configure=c0, fault=fault)
        s1, _, _ = s39.admin_rename("r17/nf-a", "nf-b", "F-1")
        g = grant_xml(gid)  # the file still says open (it cannot be written)
        lst, section, _, _ = holder_rows(gid, "F-notfollowed")
        res = {"rename": s1, "renamed": exists("r17/nf-b") and not exists("r17/nf-a"), "configure r17/nf-b": st(U, J("r17/nf-b") + "/configure"),
               "file revokedAtMillis (fault held)": g.get("revokedAtMillis"), "file scope": g.get("scope")}
        rec = revoke_records(gid)
        check("F", "the administrator renames r17/nf-a to nf-b while the window's file cannot be written: the window ends at once "
              "(403 on r17/nf-b, listed under Ended as 'Revoked (it could not follow its item) by admin', GRANT_REVOKE record by "
              "admin naming the failed follow) instead of keeping the old name",
              res["renamed"] and res["configure r17/nf-b"] == 403 and section.startswith("Ended")
              and "Revoked (it could not follow its item) by admin" in lst and rec and rec[-1].get("user") == "admin"
              and "follow" in (rec[-1].get("detail") or ""), **res, section=section, list_row=lst[:240],
              record=[x.get("detail") for x in rec][-1:])
    finally:
        note("F", "fault removed", state=grants_dir_writable(True))
    s2, _, _ = s39.admin_rename("r17/nf-x", "nf-a", "F-2")
    res2 = {"rename r17/nf-x -> nf-a": s2, "configure the job now at r17/nf-a": st(U, J("r17/nf-a") + "/configure")}
    check("F", "after the directory is writable again, a job the administrator renames onto the old name r17/nf-a gives the "
          "holder nothing (403; S-39-02 probe F3)", exists("r17/nf-a") and res2["configure the job now at r17/nf-a"] == 403, **res2)
    written = None
    for _ in range(45):
        g = grant_xml(gid)
        if g.get("revokedAtMillis"):
            written = g
            break
        time.sleep(3)
    check("F", "the end is written to the grant file within the periodic work, with the reason 'it could not follow its item'",
          written is not None and written.get("revokedReason") == "it could not follow its item" and written.get("revokedBy") == "admin",
          stored=written or grant_xml(gid))
    # tidy: names back
    for n in ("nf-a", "nf-b"):
        api("admin", J(f"r17/{n}") + "/doDelete", "POST")


def sec_B():
    revoke_all(U)
    if not exists("r17-down"):
        api("admin", "/createItem?name=r17-down&mode=hudson.model.FreeStyleProject", "POST", headers=H)
    gid = window(U, "r17-down", ["CONFIGURE"], reason="e2e-17 startup end: configure r17-down")
    ctl = window(U, "r17/ctl", ["CONFIGURE"], reason="e2e-17 startup end: control window on r17/ctl")
    pre = {"r17-down": st(U, "/job/r17-down/configure"), "r17/ctl": st(U, J("r17/ctl") + "/configure")}
    rm = remove_on_disk("r17-down")
    secs, rc = restart_jenkins()
    arr = rearrange()
    note("B", "Jenkins restarted after the job's directory was removed; arrangement run again", container=lib.CONTAINER,
         ready_after_s=secs, docker_rc=rc, arrange=[(a, b, o[-160:]) for a, b, o in arr])
    check("B", "precondition: both windows conferred Configure before, the job's directory was removed, Jenkins is back and "
          "the job is gone", pre == {"r17-down": 200, "r17/ctl": 200} and "gone=true" in rm and secs is not None and not exists("r17-down"),
          before=pre, removed=rm, ready_after_s=secs)
    ended_ok("B", gid, "r17-down", "its item was deleted", "SYSTEM", "B-startup", "no longer exists")
    api("admin", "/createItem?name=r17-down&mode=hudson.model.FreeStyleProject", "POST", headers=H)
    res = {"configure re-created r17-down": st(U, "/job/r17-down/configure"), "control r17/ctl": st(U, J("r17/ctl") + "/configure"),
           "control window": {k: grant_xml(ctl).get(k) for k in ("scope", "revokedAtMillis")}}
    check("B", "a job re-created at the name gives the holder nothing (403), while the control window still confers Configure (200)",
          res["configure re-created r17-down"] == 403 and res["control r17/ctl"] == 200, **res)


def sec_C():
    """Letter case after a restart (observed in the e2e-17 dev runs): the folders plugin loads a folder's children into a
    case-sensitive map, so after a restart (or reload) Jenkins finds r17/ctl but not r17/CTL, and a new job r17/CTL can be
    created next to r17/ctl. A window on r17/ctl is not about r17/CTL and should stay open; GrantService compares names
    without regard to letter case (S-39-02 fix (4), "as Jenkins looks them up"). Run after B/U (it needs a restart since
    r17 was created); its own step in ci/shard.py."""
    revoke_all(U)
    premise = gv("""def j = jenkins.model.Jenkins.get()
return 'ctl=' + (j.getItemByFullName('r17/ctl') != null) + ' CTL=' + (j.getItemByFullName('r17/CTL') != null)""")
    if premise != "ctl=true CTL=false":
        note("C", "the folder r17 finds its children without regard to letter case here; nothing to check", premise=premise)
        return
    gid = window(U, "r17/ctl", ["CONFIGURE"], reason="e2e-17 letter case: configure r17/ctl")
    c0 = st(U, J("r17/ctl") + "/configure")
    url = s39.admin_new_item("r17", "CTL", "C-1")
    items = gv("return jenkins.model.Jenkins.get().getItem('r17').items*.name.sort().join(',')")
    g = grant_xml(gid)
    rec = revoke_records(gid)
    lst, section, _, _ = holder_rows(gid, "C-case")
    check("C", "after a restart the administrator creates r17/CTL next to r17/ctl (the folder's lookup is case-sensitive then): "
          "the holder's window on r17/ctl, whose job still exists, stays open and still confers Configure on it",
          c0 == 200 and "CTL" in items.split(",") and "ctl" in items.split(",") and not g.get("revokedAtMillis")
          and st(U, J("r17/ctl") + "/configure") == 200, premise=premise, created=url.replace(BASE, ""), items=items,
          stored={k: g.get(k) for k in ("scope", "revokedBy", "revokedReason")}, record=[x.get("detail") for x in rec][-1:],
          section=section, list_row=lst[:240], configure_ctl=st(U, J("r17/ctl") + "/configure"),
          configure_CTL=st(U, J("r17/CTL") + "/configure"))
    api("admin", J("r17/CTL") + "/doDelete", "POST")
    revoke_all(U)


def change_files_readable(on):
    return gv(f"""def d = new File(jenkins.model.Jenkins.get().rootDir, 'batch-control/changes')
def out = []
(d.listFiles() ?: []).findAll {{ it.name.endsWith('.jsonl') }}.each {{ f -> f.setReadable({'true' if on else 'false'}, false)
  def can = true; try {{ f.withReader {{ it.read() }} }} catch (Exception e) {{ can = false }}
  out << f.name + ':' + can }}
return out.join(',')""")


def sec_U():
    revoke_all(U)
    gid = window(U, "r17/u-job", ["CONFIGURE"], reason="e2e-17 fail-closed re-end: configure r17/u-job")
    ctl = window(U, "r17/ctl", ["CONFIGURE"], reason="e2e-17 fail-closed re-end: control window on r17/ctl")
    pre = {"r17/u-job": st(U, J("r17/u-job") + "/configure"), "r17/ctl": st(U, J("r17/ctl") + "/configure")}
    fault = change_files_readable(False)
    try:
        check("U", "precondition: both windows confer Configure, and no change log month file can be read (fault)",
              pre == {"r17/u-job": 200, "r17/ctl": 200} and fault and ":true" not in fault, before=pre, fault=fault)
        secs, rc = restart_jenkins()
        arr = rearrange()
        note("U", "Jenkins restarted with the change log unreadable; arrangement run again", ready_after_s=secs, docker_rc=rc,
             arrange=[(a, b) for a, b, _ in arr], still_unreadable=change_files_readable(False))
        res = {"configure r17/u-job": st(U, J("r17/u-job") + "/configure"), "configure r17/ctl": st(U, J("r17/ctl") + "/configure"),
               "stored": {g: {k: grant_xml(g).get(k) for k in ("revokedBy", "revokedReason")} for g in (gid, ctl)}}
        lst, section, _, _ = holder_rows(gid, "U-unconfirmed")
        lst2, section2, _, _ = holder_rows(ctl, "U-unconfirmed-ctl")
        label = "Revoked (its state could not be confirmed at startup) by SYSTEM"
        check("U", "after a restart that cannot read the change log every open window has ended (fail-closed): revoked by SYSTEM "
              "with 'its state could not be confirmed at startup', 403 on r17/u-job and r17/ctl, listed under Ended",
              secs is not None and res["configure r17/u-job"] == 403 and res["configure r17/ctl"] == 403
              and all(v.get("revokedBy") == "SYSTEM" and v.get("revokedReason") == "its state could not be confirmed at startup"
                      for v in res["stored"].values())
              and section.startswith("Ended") and label in lst and section2.startswith("Ended") and label in lst2,
              ready_after_s=secs, **res, list_rows=[lst[:200], lst2[:200]])
    finally:
        restored = change_files_readable(True)
        note("U", "fault removed", state=restored)
    recs = {g: revoke_records(g) for g in (gid, ctl)}
    check("U", "once readable, the change log holds a GRANT_REVOKE record by SYSTEM for each of the two windows",
          all(r and r[-1].get("user") == "SYSTEM" for r in recs.values()),
          records={g: [x.get("detail") for x in r][-1:] for g, r in recs.items()})


if __name__ == "__main__":
    run_sections(WANT, {"G": sec_G, "F": sec_F, "B": sec_B, "U": sec_U, "C": sec_C})
