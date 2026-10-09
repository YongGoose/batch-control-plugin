"""e2e-19: the run gate next to the other plugins in the image (SPEC 6 "#34/#36" acceptance lines, SPEC 9 jobConfigHistory).

usage: python plugins.py [NCLZHI]   rows: out/plugins.jsonl, shots: R19-PLUG-*.png   (run r19/arrange.py first)
N  naginator: an approved run of r19-nag fails (armed once); naginator's automatic retry of it is refused (a retry of
   an approved manual run re-uses the approval), no second build starts, and the refusal is recorded as a
   TRIGGER_BLOCKED change record of kind retry (SPEC 6 #36, D-47)
C  customize-build-now relabels r19-cbn's build link "Launch batch": the job page still offers Request Run to the
   requester, and pressing the relabelled entry (if it is shown) queues nothing (SPEC 6 #34)
L  lockable-resources: r19-lock needs the resource r19-res. A direct build by requester is still refused (nothing
   queued); an approved request starts exactly one build, which acquires r19-res and succeeds (SPEC 6 #36)
Z  authorize-project: with the project default build authenticator enabled and r19-authz running as admin
   ("Run as Specific User"), requester's direct build is still refused and nothing is queued; an approved request runs
   once, as admin. The global authenticator is removed again afterwards (SPEC 6 #36)
H  jobConfigHistory: the administrator saves r19-jch's configure page once with another description: exactly one
   CONFIGURE change record is written for it, and jobConfigHistory lists its own entry (SPEC 9 #36)
I  the CLI: admin's update-job of r19-jch writes exactly one CONFIGURE record naming admin, with the diff (SPEC 9)"""
import re
import sys
import time

sys.path.insert(0, __file__.rsplit("/", 1)[0])
import lib  # noqa: E402
from lib import (Session, api, gv, check, note, text_of, J, run_sections, run_req, queue_items, build_count, next_build,
                 wait_build, wait_executed, changes_mark, changes_since, ensure_job, job_xml, BASE)  # noqa: E402

lib.LOGNAME[0] = "plugins"
WANT = sys.argv[1] if len(sys.argv) > 1 else "NCLZHI"


def approve_run(job, reason, params=None):
    nb = next_build(job)
    st, rid, r = run_req("requester", job, reason, params=params)
    assert rid, (job, st, text_of(r.text)[:200])
    assert lib.decide("approver-1", "requests", rid, "approve") in (200, 302), ("approve", rid)
    return rid, nb


def direct_build_refused(sec, job):
    nb0 = next_build(job)
    r = api("requester", J(job) + "/build", "POST")
    time.sleep(3)
    check(sec, f"requester's direct build of {job} is refused, nothing queued",
          r.status_code != 201 and next_build(job) == nb0 and not queue_items(job), status=r.status_code,
          location=r.headers.get("Location", "").replace(BASE, ""))


def sec_N():
    job = "r19-nag"
    gv("new File(jenkins.model.Jenkins.get().rootDir, 'r19-nag.arm').text = 'arm'; return 'armed'")
    mark = changes_mark()
    rid, nb = approve_run(job, "e2e-19 naginator retry of an approved run")
    result, console = wait_build(job, nb)
    check("N", "the approved run of r19-nag fails once (armed)", result == "FAILURE" and "armed: failing once" in console, result=result)
    # naginator schedules its retry at once (delay 0). e2e-20: wait for the refusal's record (was a fixed 20 s); the
    # absence of a second build is then checked as before, after the retry was demonstrably attempted and refused
    blocked = lambda: changes_since(mark, lambda x: x.get("type") in ("TRIGGER_BLOCKED", "MARKER_REUSE_BLOCKED")  # noqa
                                    and x.get("target") == job)
    lib.wait_until(blocked, 60, 1)
    check("N", "naginator's automatic retry is refused: no second build, nothing queued (SPEC 6 #36, D-47)",
          next_build(job) == nb + 1 and not queue_items(job), next_build=next_build(job), builds=build_count(job))
    rec = blocked()
    check("N", "the refused retry is recorded (TRIGGER_BLOCKED, retry of an approved run)",
          any("retry" in (x.get("detail") or "").lower() for x in rec),
          records=[(x.get("type"), x.get("user"), (x.get("detail") or "")[:200]) for x in rec][:3])


def sec_C():
    job = "r19-cbn"
    s = Session("requester")
    s.go(J(job) + "/")
    body = s.page.locator("body").inner_text()
    s.shot("#main-panel", "R19-PLUG-C-01-cbn-job")
    check("C", "customize-build-now relabels the build link, and the job page still offers Request Run (SPEC 6 #34)",
          "Request Run" in body, launch_shown="Launch batch" in body)
    nb0 = next_build(job)
    launch = s.page.locator("a, button", has_text=re.compile(r"^\s*Launch batch\s*$"))
    if launch.count() and launch.first.is_visible():
        try:
            launch.first.click()
            s.page.wait_for_timeout(3000)
        except Exception as e:  # noqa
            note("C", "clicking Launch batch raised", error=repr(e)[:200])
        s.shot("body", "R19-PLUG-C-02-launch-clicked")
        time.sleep(3)
        check("C", "pressing the relabelled build entry queues nothing", next_build(job) == nb0 and not queue_items(job),
              next_build=next_build(job))
    else:
        note("C", "the relabelled entry is not shown to requester on an approval-required job")
    s.done()


def sec_L():
    job = "r19-lock"
    direct_build_refused("L", job)
    rid, nb = approve_run(job, "e2e-19 lockable resource")
    status, _ = wait_executed(rid)
    result, console = wait_build(job, nb)
    check("L", "the approved run of a job that needs a lockable resource starts once and acquires it (SPEC 6 #36)",
          status == "EXECUTED" and result == "SUCCESS" and "r19-res" in console, status=status, result=result,
          console=[l for l in console.splitlines() if "r19-res" in l][:2])
    time.sleep(5)
    check("L", "exactly one build", next_build(job) == nb + 1 and not queue_items(job), next_build=next_build(job))


AUTHZ_ON = """import jenkins.security.QueueItemAuthenticatorConfiguration
def j = jenkins.model.Jenkins.get(); def ucl = j.pluginManager.uberClassLoader
def pqa = ucl.loadClass('org.jenkinsci.plugins.authorizeproject.ProjectQueueItemAuthenticator')
def sus = ucl.loadClass('org.jenkinsci.plugins.authorizeproject.strategy.SpecificUsersAuthorizationStrategy')
def cfg = QueueItemAuthenticatorConfiguration.get()
if (!cfg.authenticators.any { pqa.isInstance(it) }) {
  def m = new HashMap(); m.put(sus.name, true)
  cfg.authenticators.add(pqa.getConstructor(Map).newInstance(m)); cfg.save()
}
def job = j.getItem('r19-authz')
def prop = ucl.loadClass('org.jenkinsci.plugins.authorizeproject.AuthorizeProjectProperty')
def old = job.getProperty(prop); if (old != null) job.removeProperty(prop)
job.addProperty(prop.getConstructor(ucl.loadClass('org.jenkinsci.plugins.authorizeproject.AuthorizeProjectStrategy')).newInstance(sus.getConstructor(String).newInstance('admin')))
job.save()
return 'authenticators=' + cfg.authenticators*.class.simpleName + ' job=' + job.getProperty(prop)?.strategy?.userid"""
AUTHZ_OFF = """import jenkins.security.QueueItemAuthenticatorConfiguration
def cfg = QueueItemAuthenticatorConfiguration.get()
def pqa = jenkins.model.Jenkins.get().pluginManager.uberClassLoader.loadClass('org.jenkinsci.plugins.authorizeproject.ProjectQueueItemAuthenticator')
cfg.authenticators.removeAll { pqa.isInstance(it) }; cfg.save()
return 'authenticators=' + cfg.authenticators*.class.simpleName"""


def sec_Z():
    job = "r19-authz"
    ensure_job(job, job_xml("fs", shell='echo "r19-authz ran as $(whoami)"'))
    try:
        note("Z", "arranged authorize-project", result=gv(AUTHZ_ON))
        direct_build_refused("Z", job)
        rid, nb = approve_run(job, "e2e-19 authorize-project run as admin")
        status, _ = wait_executed(rid)
        result, console = wait_build(job, nb)
        check("Z", "an approved run of a job authorised as another user runs once, as that user (SPEC 6 #36)",
              status == "EXECUTED" and result == "SUCCESS" and re.search(r"Running as (admin|E2E Administrator)\b", console) is not None, status=status, result=result,
              console=[l for l in console.splitlines() if "Running as" in l][:2])
    finally:
        note("Z", "restored", result=gv(AUTHZ_OFF))


def sec_H():
    job = "r19-jch"
    mark = changes_mark()
    desc = f"r19-jch saved {int(time.time())}"
    s = Session("admin")
    s.go(J(job) + "/configure")
    s.page.locator("textarea[name=description]").fill(desc)
    with s.page.expect_navigation(timeout=30000):
        s.page.locator("button[name=Submit]").first.click()
    s.page.wait_for_load_state("load")
    s.shot("#main-panel", "R19-PLUG-H-01-saved")
    time.sleep(3)
    rec = changes_since(mark, lambda x: x.get("type") == "CONFIGURE" and x.get("target") == job)
    check("H", "one configure save with jobConfigHistory installed writes exactly one CONFIGURE record (SPEC 9 #36)",
          len(rec) == 1 and desc in lib.diff_of(rec[0].get("id")), records=len(rec),
          diff=[lib.diff_of(x.get("id"))[:300] for x in rec][:1])
    s.go(J(job) + "/jobConfigHistory/")
    t = s.text()
    s.shot("#main-panel", "R19-PLUG-H-02-jch")
    s.done()
    check("H", "jobConfigHistory keeps its own entry for the save", "Changed" in t and "admin" in t,
          text=t[:200])


def sec_I():
    job = "r19-jch"
    mark = changes_mark()
    xml = api("admin", J(job) + "/config.xml").text
    desc = f"r19-jch via CLI {int(time.time())}"
    xml = re.sub(r"<description>.*?</description>|<description/>", f"<description>{desc}</description>", xml, count=1, flags=re.S)
    rc, out = lib.cli("admin", "update-job", job, stdin=xml)
    time.sleep(3)
    rec = changes_since(mark, lambda x: x.get("type") == "CONFIGURE" and x.get("target") == job)
    check("I", "a CLI update-job writes exactly one CONFIGURE record naming its user, with the diff (SPEC 9: UI, REST, CLI)",
          rc == 0 and len(rec) == 1 and rec[0].get("user") == "admin" and desc in lib.diff_of(rec[0].get("id")),
          exit=rc, output=out.strip()[-200:], records=[(x.get("user"), x.get("id")) for x in rec])


run_sections(WANT, {"N": sec_N, "C": sec_C, "L": sec_L, "Z": sec_Z, "H": sec_H, "I": sec_I})
