"""e2e-23 R1-02 (D-81): a run whose record is appended late (a slow listener or publisher after the build) does not hide
the runs recorded before it from a date-filtered history: the History listing for a day shows what runs.csv for the day
holds.

usage: python history_late.py [AL]   rows: out/history_late.jsonl, shots: screenshots/run-23/R23-R1-02-*.png
Items r23-late-slow and r23-late-fast (Freestyle, not approval-required, so the administrator's Build Now runs them at
once; two executors). Two real runs, then the plugin clock's zone is shifted (BatchClock.setForTest with a fixed offset,
the instant stays real) so that a local midnight falls between them, instead of waiting for a real midnight:
  1. a script-console RunListener holds r23-late-slow in onCompleted (after its start and duration are fixed);
  2. 65 s later r23-late-fast runs and is recorded at once (more than the store's 60 s append-order slack, T-10-14);
  3. r23-late-slow is released and recorded last, so its line follows the fast run's line in the month file;
  4. the zone is set so that local midnight M (the whole second at or before the fast run's start) lies more than 60 s
     after the slow run's start + duration: the slow run belongs to day D-1, the fast run to day D, and the slow line is
     the last one appended. The zone, the listener and its system properties are restored at the end, also on failure.
The D-1 / D candidates are chosen so that both days are in the month of the file the lines were appended to; within the
hours where that is impossible (the first of a month, before 15:00 in Asia/Seoul) the listings are not judged (NOTE).
Takes about 1.5 minutes.

A  arrangement: the two items; the holding listener installed; two idle executors.
L  the steps above; premises: the slow line follows the fast line in its file and start + duration of the slow run
   lies more than 60 s before M; then runs.csv?from=D&to=D (guard) names r23-late-fast; the D-1 listing (guard)
   shows r23-late-slow; the History listing ?kind=runs&from=D&to=D (admin, new browser context) shows r23-late-fast."""
import datetime
import json
import re
import sys
import time

sys.path.insert(0, __file__.rsplit("/", 1)[0])
import lib  # noqa: E402
from lib import api, check, note, J, Session  # noqa: E402

lib.LOGNAME[0] = "history_late"
SLOW, FAST = "r23-late-slow", "r23-late-fast"
CLOCK = f"def bc = jenkins.model.Jenkins.get().pluginManager.uberClassLoader.loadClass('{lib.BC}.store.BatchClock')\n"
LISTENER = r"""import hudson.model.*
import hudson.model.listeners.RunListener
class R23HoldSlow extends RunListener<Run> {
    R23HoldSlow() { super(Run) }
    void onCompleted(Run r, TaskListener l) {
        if (r.parent.fullName != '%s') return
        System.setProperty('r23.r102.held', r.number as String)
        long end = System.currentTimeMillis() + 120000L
        while (System.getProperty('r23.r102.release') == null && System.currentTimeMillis() < end) { Thread.sleep(200) }
    }
}
def all = jenkins.model.Jenkins.get().getExtensionList(RunListener)
all.findAll { it.class.name == 'R23HoldSlow' }.each { all.remove(it) }
System.clearProperty('r23.r102.held'); System.clearProperty('r23.r102.release')
all.add(new R23HoldSlow())
return all.count { it.class.name == 'R23HoldSlow' }""" % SLOW
REMOVE = """def all = jenkins.model.Jenkins.get().getExtensionList(hudson.model.listeners.RunListener)
all.findAll { it.class.name == 'R23HoldSlow' }.each { all.remove(it) }
System.setProperty('r23.r102.release', 'x'); Thread.sleep(500)
System.clearProperty('r23.r102.held'); System.clearProperty('r23.r102.release')
return all.count { it.class.name == 'R23HoldSlow' }"""
SLACK_MS = 61000  # the store's append-order slack (60 s) plus a margin
HOLD_S = 65
S = {}


def idle_executors():
    return int(lib.gv("def c = jenkins.model.Jenkins.get().toComputer(); return c.countIdle()"))


def sec_A():
    out = {}
    for job, shell in ((SLOW, "echo r23 slow"), (FAST, "echo r23 fast")):
        out[job] = {"created": lib.ensure_job(job, lib.job_xml("fs", shell=shell)),
                    "prop": lib.set_property(job, approval=False, timer=True, upstream=True)}
    S["zone"] = lib.gv(CLOCK + "return bc.clock().zone.id")
    installed = lib.gv(LISTENER)
    idle = lib.wait_until(lambda: idle_executors() >= 2 and idle_executors(), 90, 2)
    check("A", "arrangement: r23-late-slow and r23-late-fast not approval-required; the holding listener installed; two "
          "idle executors", installed == "1" and idle and all("approvalRequired=false" in v["prop"] for v in out.values()),
          items=out, listener=installed, idle=idle, zone=S["zone"])


def line_of(job, number):
    """(month file, line index, record) of the stored run line of job #number, or (None, None, None)."""
    out = lib.gv(f"""def d = new File(jenkins.model.Jenkins.get().rootDir, 'batch-control/runs')
def hit = ''
(d.listFiles() ?: []).findAll {{ it.name.endsWith('.jsonl') }}.sort {{ it.name }}.each {{ f ->
  f.readLines('UTF-8').eachWithIndex {{ l, i -> if (l.contains('"runId":"{job}#{number}"')) hit = f.name + '\\t' + i + '\\t' + l }} }}
return hit""")
    if not out:
        return None, None, None
    name, idx, line = out.split("\t", 2)
    return name[:-6], int(idx), json.loads(line)


def listed(text, job, number):
    """Whether a CSV line ("<job>#<n>,...") or a listing row ("#<n> <job> ...") names job #number."""
    return re.search(rf"(?:\b{job}#{number}\b|#{number}\s+{job}(?![\w-]))", text) is not None


def build(job):
    n = lib.next_build(job)
    st = api("admin", J(job) + "/build?delay=0sec", "POST").status_code
    return n, st


def midnight_candidates(slow, fast, month):
    """[(offset seconds, D, fits)] with local midnight at the whole second M at or before the fast run's start, more
    than SLACK after the slow run's start + duration; the candidates with D-1 and D in `month` (YYYY-MM) first; offsets
    within +-18 h."""
    end = slow["startedAt"] + slow.get("durationMs", 0)
    m_ms = fast["startedAt"] // 1000 * 1000
    if m_ms - end <= SLACK_MS:
        return [], m_ms
    utc = datetime.datetime.fromtimestamp(m_ms / 1000, datetime.timezone.utc)
    sod = utc.hour * 3600 + utc.minute * 60 + utc.second
    out = []
    for off in (-sod, 86400 - sod):
        if -64800 <= off <= 64800:
            day = (utc + datetime.timedelta(seconds=off)).date()
            fits = day.strftime("%Y-%m") == month and (day - datetime.timedelta(days=1)).strftime("%Y-%m") == month
            out.append((off, day, fits))
    return sorted(out, key=lambda c: not c[2]), m_ms


def sec_L():
    n_slow, st_slow = build(SLOW)
    held = lib.wait_until(lambda: lib.gv("return System.getProperty('r23.r102.held') ?: ''") == str(n_slow), 60, 1)
    check("L", "precondition: r23-late-slow ran and is held in onCompleted", st_slow in (200, 201, 302) and bool(held),
          build=n_slow, http=st_slow, held=held)
    time.sleep(HOLD_S)
    n_fast, st_fast = build(FAST)
    result, _ = lib.wait_build(FAST, n_fast, timeout=120)
    fast_line = lib.wait_until(lambda: line_of(FAST, n_fast)[2] is not None and line_of(FAST, n_fast), 30, 1)
    slow_before = line_of(SLOW, n_slow)[2]
    check("L", "precondition: r23-late-fast ran and is recorded while r23-late-slow is still held (not recorded)",
          result == "SUCCESS" and bool(fast_line) and slow_before is None, build=n_fast, http=st_fast, result=result,
          slow_recorded=slow_before is not None)
    lib.gv("System.setProperty('r23.r102.release', 'x'); return 'released'")
    slow_line = lib.wait_until(lambda: line_of(SLOW, n_slow)[2] is not None and line_of(SLOW, n_slow), 60, 1)
    f_month, f_idx, fast = fast_line if fast_line else (None, None, None)
    s_month, s_idx, slow = slow_line if slow_line else (None, None, None)
    ok = bool(fast and slow) and f_month == s_month and s_idx > f_idx \
        and fast["startedAt"] // 1000 * 1000 - (slow["startedAt"] + slow.get("durationMs", 0)) > SLACK_MS
    check("L", "premise: the slow run's line follows the fast run's line in its month file, and the slow run's start + "
          "duration lies more than 60 s before the fast run's start (whole second)", ok, month=f_month, fast_index=f_idx, slow_index=s_idx,
          fast=fast, slow=slow)
    if not ok:
        return
    cands, m_ms = midnight_candidates(slow, fast, f_month)
    if not cands:
        check("L", "premise: a midnight more than 60 s after the slow run's end", False, midnight=m_ms)
        return
    off, day, fits = cands[0]
    zone = lib.gv(CLOCK + f"bc.setForTest(java.time.Clock.system(java.time.ZoneOffset.ofTotalSeconds({off}))); "
                          "return bc.clock().zone.id")
    S["shifted"] = True
    d, d1 = str(day), str(day - datetime.timedelta(days=1))
    note("L", "plugin clock zone shifted so that local midnight lies between the two runs", zone=zone, offset_s=off,
         midnight_ms=m_ms, D=d, fits_month=fits)
    if not fits:
        note("L", f"D-1 and D cannot both be in {f_month} at this hour; the listings are not judged in this run")
        return
    fast_in = lambda t: listed(t, FAST, n_fast)  # noqa: E731
    slow_in = lambda t: listed(t, SLOW, n_slow)  # noqa: E731
    csv = api("admin", f"/batch-control/history/runs.csv?from={d}&to={d}")
    check("L", f"guard: runs.csv?from={d}&to={d} names r23-late-fast #{n_fast} and not r23-late-slow",
          csv.status_code == 200 and fast_in(csv.text) and not slow_in(csv.text), http=csv.status_code,
          lines=csv.text.splitlines()[:4])
    prev = api("admin", f"/batch-control/history/?kind=runs&from={d1}&to={d1}")
    check("L", f"guard: the D-1 listing ({d1}) shows r23-late-slow #{n_slow}", prev.status_code == 200
          and slow_in(lib.text_of(prev.text)), http=prev.status_code)
    s = Session("admin", fresh=True)
    s.go(f"/batch-control/history/?kind=runs&from={d}&to={d}")
    page = s.text()
    s.shot("#main-panel", "R23-R1-02-1-history-day-D")
    s.done()
    check("L", f"screen: the History listing ?kind=runs&from={d}&to={d} shows r23-late-fast #{n_fast}", fast_in(page),
          text=re.sub(r"\s+", " ", page)[:600])


def cleanup():
    zone = lib.gv(CLOCK + "bc.reset(); return bc.clock().zone.id")
    left = lib.gv(REMOVE)
    note("cleanup", "plugin clock zone reset, holding listener removed", zone=zone, original=S.get("zone"), listeners=left)
    if left != "0" or (S.get("zone") and zone != S["zone"]):
        raise RuntimeError(f"zone {zone} (was {S.get('zone')}), listeners left {left}")


if __name__ == "__main__":
    lib.run(sys.argv, {"A": sec_A, "L": sec_L}, cleanup)
