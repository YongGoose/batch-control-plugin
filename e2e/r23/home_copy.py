"""e2e-23 R2-03 (D-80): an activation survives a copy of the job's directory, as a copy, restore or remount of
JENKINS_HOME makes it. The in-container variant of the bug-hunt PoC (which copied the whole volume): the job directory is
copied aside with `cp -a` and put back in place of the original, so it gets a new inode (the identity the activation
used to be bound to) and keeps its files, then the item is reloaded from disk. The job keeps running on its timer.

usage: python home_copy.py [ACV]   rows: out/home_copy.jsonl, shots: screenshots/run-23/R23-R2-03-*.png
Item r23-cron (Freestyle, `* * * * *`, not approval-required, timer and upstream not blocked), activated through the
ACTIVATE request flow (requester, approver-1; only when it is not activated yet). The job is enabled at the start and
disabled at the end, so its timer does not run during the later units. Takes about 1.5 minutes (one timer minute).

A  arrangement: r23-cron enabled, activated; run control on; premise: its page says it is activated.
C  the copy: between two timer minutes and with no build running, `docker exec` copies jobs/r23-cron to a sibling with
   `cp -a`, moves the original aside, moves the copy into its place and removes the original; premise: the directory's
   inode changed and its files (config.xml, builds) are there; the administrator reloads the item (POST reload).
V  the requester's page of r23-cron (new browser context) still says the job is activated and not "not activated"; the
   next timer run starts (a new build started by the timer within 90 s) and no TRIGGER_BLOCKED record names r23-cron
   after the copy. Guard against a false pass: the timer run is the activation gate's own decision."""
import re
import sys
import time

sys.path.insert(0, __file__.rsplit("/", 1)[0])
import lib  # noqa: E402
from lib import api, check, note, J, Session  # noqa: E402

lib.LOGNAME[0] = "home_copy"
JOB = "r23-cron"
DIR = f"{lib.JH}/jobs/{JOB}"
USER, APPROVER = "requester", "approver-1"
TIMER = "<hudson.triggers.TimerTrigger><spec>* * * * *</spec></hudson.triggers.TimerTrigger>"
S = {}


def inode():
    rc, out = lib.dexec("stat", "-c", "%i", DIR)
    return out.strip() if rc == 0 else None


def timer_builds(after):
    r = api("admin", J(JOB) + "/api/json?tree=builds[number,result,building,actions[causes[shortDescription]]]")
    out = []
    for b in (r.json().get("builds", []) if r.status_code == 200 else []):
        causes = [c.get("shortDescription", "") for a in b.get("actions", []) if a for c in a.get("causes", []) or []]
        if b["number"] > after:
            out.append({"number": b["number"], "result": b.get("result"), "building": b.get("building"), "causes": causes})
    return out


def last_number():
    r = api("admin", J(JOB) + "/api/json?tree=lastBuild[number]")
    return ((r.json().get("lastBuild") or {}).get("number") or 0) if r.status_code == 200 else 0


def sec_A():
    created = lib.ensure_job(JOB, lib.job_xml("fs", shell="echo r23 tick", triggers=TIMER))
    prop = lib.set_property(JOB, approval=False, timer=False, upstream=False)
    en = api("admin", J(JOB) + "/enable", "POST").status_code
    state, act = lib.activation(JOB), None
    if state != "activated":
        st, aid = lib.act_req(USER, JOB, "ACTIVATE", "e2e-23 R2-03 activate the cron job")
        dec = lib.decide(APPROVER, "activations", aid, "approve", "e2e-23") if aid else None
        act = {"submit": st, "id": aid, "approve": dec}
        state = lib.wait_until(lambda: lib.activation(JOB) == "activated" and "activated", 20, 1) or lib.activation(JOB)
    spec = lib.gv(f"return jenkins.model.Jenkins.get().getItemByFullName('{JOB}').triggers.values()*.spec.join(',') + "
                  f"' disabled=' + jenkins.model.Jenkins.get().getItemByFullName('{JOB}').disabled")
    sw = lib.switches()
    check("A", "arrangement: r23-cron enabled with a `* * * * *` timer, not approval-required, activated through the "
          "request flow; run control on", "approvalRequired=false" in prop and state == "activated"
          and spec == "* * * * * disabled=false" and sw["run_control"] is True,
          created=created, prop=prop, enable=en, state=state, activate=act, timer=spec)


def sec_C():
    # Copy between two timer minutes (seconds 5..45 of the controller's minute) and while no build of the job runs.
    lib.wait_until(lambda: 5 <= int(lib.gv("return java.time.LocalTime.now().second")) <= 45
                   and not any(b["building"] for b in timer_builds(last_number() - 1)), 70, 1)
    before = inode()
    script = (f"set -e; cd {lib.JH}/jobs; rm -rf .{JOB}.copy .{JOB}.old; cp -a {JOB} .{JOB}.copy; mv {JOB} .{JOB}.old; "
              f"mv .{JOB}.copy {JOB}; rm -rf .{JOB}.old; ls -A {JOB}")
    rc, out = lib.dexec("sh", "-c", script)
    after = inode()
    files = out.split()
    check("C", "premise: the copied directory replaced the original with a new inode and its files",
          rc == 0 and before and after and before != after and "config.xml" in files, inode_before=before,
          inode_after=after, rc=rc, files=files[:12])
    S["after"] = last_number()
    S["mark"] = lib.changes_mark()
    r = api("admin", J(JOB) + "/reload", "POST")
    check("C", "the administrator reloads the item from disk", r.status_code in (200, 302, 303), http=r.status_code)


def sec_V():
    s = Session(USER, fresh=True)
    s.go(J(JOB) + "/")
    page = s.text()
    s.shot("#main-panel", "R23-R2-03-1-job-page-after-copy")
    s.done()
    m = re.search(r"Batch Control: this job is ([a-z ]+?)\.", page)
    check("V", "screen: the requester's page of r23-cron still says the job is activated (not 'not activated')",
          m is not None and m.group(1) == "activated" and "not activated" not in page,
          notice=m.group(0) if m else None, text=re.sub(r"\s+", " ", page)[:400])
    started = lib.wait_until(lambda: [b for b in timer_builds(S.get("after", 0))
                                      if any("timer" in c.lower() for c in b["causes"])], 90, 3)
    check("V", "the next timer run starts after the copy (a new build started by the timer within 90 s)", bool(started),
          builds=started or timer_builds(S.get("after", 0)))
    blocked = lib.changes_since(S.get("mark", 0), lambda r: r.get("type") == "TRIGGER_BLOCKED" and r.get("target") == JOB)
    check("V", "no TRIGGER_BLOCKED record names r23-cron after the copy", not blocked,
          records=[{k: r.get(k) for k in ("type", "target", "detail", "message") if r.get(k)} for r in blocked][:2])
    s = Session("admin", fresh=True)
    s.go("/batch-control/activations/")
    s.shot("#main-panel", "R23-R2-03-2-activations")
    s.done()


def cleanup():
    st = api("admin", J(JOB) + "/disable", "POST").status_code
    note("cleanup", "r23-cron disabled, so its timer does not run during the later units", http=st)


if __name__ == "__main__":
    lib.run(sys.argv, {"A": sec_A, "C": sec_C, "V": sec_V}, cleanup)
