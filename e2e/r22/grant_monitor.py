"""e2e-22 R3-04: Item/Read checks never wait for the GrantService monitor (the D-35c creating-grant lookup no longer takes
it, nor scans the ended grants).

usage: python grant_monitor.py [ABH]       rows: out/grant_monitor.jsonl (no screenshot: REST only)
The PoC measured job lists with 20,000 retained grants (restart per step, ~15 min): too heavy for CI. This unit makes the
contention deterministic instead: the script console (arrangement) starts a thread "r22-grant-monitor-holder" that holds
the GrantService monitor for HOLD seconds, as a long grant-file write under the monitor would, while the requester's
REST requests run. Every Item/Read check of a signed-in user goes through GrantAwareACL -> GrantService while change
control is on, so without the fix each of these requests waits until the monitor is released.

A  arrangement: item r22-monitor (Freestyle), change control on, the requester reads the item, no holder left over.
B  baseline: the requester's job list (/api/json?tree=jobs[name]) and the item's /api/json answer 200, the list shows
   r22-monitor.
H  the monitor is held for HOLD s; meanwhile PAR parallel job lists and one item request as the requester, and after
   1 s a thread dump (script console, ThreadMXBean): every request answers 200 within LIMIT s with the baseline's
   answer, no thread BLOCKED on the holder's monitor inside a permission check (GrantAwareACL), and the holder still
   holds the monitor when the last answer arrives (so the answers did not wait for its release).
The deterministic JenkinsRule test of the fix is T-08-196 (GrantReadCheckConcurrencyTest); the scaling with the number
of ended grants is not measured here."""
import concurrent.futures
import json
import sys
import time

import requests

sys.path.insert(0, __file__.rsplit("/", 1)[0])
import lib  # noqa: E402
from lib import check, note, gv, J, BASE  # noqa: E402

lib.LOGNAME[0] = "grant_monitor"
JOB = "r22-monitor"
USER = "requester"
HOLDER = "r22-grant-monitor-holder"
HOLD, LIMIT, PAR = 10, 3.0, 8
LIST = "/api/json?tree=jobs[name]"
BASELINE = {}


def holder_alive():
    return gv(f"return Thread.getAllStackTraces().keySet().any {{ it.name == '{HOLDER}' && it.isAlive() }}") == "true"


def get(path):
    t0 = time.monotonic()
    r = requests.get(BASE + path, auth=(USER, lib.pw(USER)), timeout=60)
    return r.status_code, round(time.monotonic() - t0, 3), (r.json() if r.status_code == 200 else None)


def names(body):
    return sorted(j["name"] for j in (body or {}).get("jobs", []))


def sec_A():
    created = lib.ensure_job(JOB, lib.job_xml("fs", shell='echo "r22-monitor"'))
    sw = lib.switches()
    leftover = lib.wait_until(lambda: not holder_alive(), HOLD + 5, 1)
    ok = sw["change_control"] is True and lib.has_permission(USER, JOB, "hudson.model.Item.READ") and leftover
    check("A", "arrangement: r22-monitor exists, change control on (grants are consulted), the requester reads it, no "
          "holder thread left over", ok, created=created, switches=sw, no_leftover_holder=leftover)


def sec_B():
    st, secs, body = get(LIST)
    st2, secs2, body2 = get(J(JOB) + "/api/json?tree=name")
    BASELINE.update(list=names(body), item=(body2 or {}).get("name"))
    check("B", "baseline: the requester's job list and the item answer 200 and the list shows r22-monitor",
          st == 200 and st2 == 200 and JOB in BASELINE["list"] and BASELINE["item"] == JOB,
          list_status=st, list_seconds=secs, item_status=st2, item_seconds=secs2, jobs=len(BASELINE["list"]))


def sec_H():
    if not BASELINE:
        sec_B()
    held = gv(f"""import java.util.concurrent.*
def gs = io.jenkins.plugins.batchcontrol.security.GrantService.get()
def latch = new CountDownLatch(1)
def t = new Thread({{ synchronized (gs) {{ latch.countDown(); Thread.sleep({HOLD * 1000}L) }} }} as Runnable, '{HOLDER}')
t.setDaemon(true)
t.start()
return String.valueOf(latch.await(10, TimeUnit.SECONDS)) + ' alive=' + t.isAlive()""")
    t0 = time.monotonic()
    check("H", f"arrangement: a thread holds the GrantService monitor for {HOLD} s", held.startswith("true"), result=held)
    with concurrent.futures.ThreadPoolExecutor(PAR + 1) as ex:
        futures = [ex.submit(get, LIST) for _ in range(PAR)] + [ex.submit(get, J(JOB) + "/api/json?tree=name")]
        time.sleep(1.0)  # sampling point of the thread dump while the requests are in flight, not a wait for a result
        dump = json.loads(gv(f"""def mx = java.lang.management.ManagementFactory.getThreadMXBean()
def infos = mx.dumpAllThreads(true, false)
def onHolder = infos.findAll {{ it.threadState == Thread.State.BLOCKED && it.lockOwnerName == '{HOLDER}' }}
def inAcl = onHolder.findAll {{ i -> i.stackTrace.any {{ it.className.contains('GrantAwareACL') }} }}
return groovy.json.JsonOutput.toJson([blocked_on_holder: onHolder.size(), blocked_in_permission_check: inAcl.size(),
  lock: onHolder ? onHolder[0].lockName : null, threads: inAcl.collect {{ it.threadName.take(90) }}.take(4),
  holder_alive: infos.any {{ it.threadName == '{HOLDER}' }}])"""))
        results = [f.result() for f in futures]
    answered = round(time.monotonic() - t0, 3)
    alive = holder_alive()
    lists, item = results[:PAR], results[PAR]
    secs = [r[1] for r in results]
    check("H", f"every request answers 200 within {LIMIT} s while the monitor is held ({PAR} job lists + the item)",
          all(r[0] == 200 for r in results) and max(secs) < LIMIT, seconds=secs, statuses=[r[0] for r in results])
    check("H", "the answers equal the baseline (same job list, same item)",
          all(names(r[2]) == BASELINE.get("list") for r in lists) and (item[2] or {}).get("name") == BASELINE.get("item"),
          jobs=[len(names(r[2])) for r in lists], item=(item[2] or {}).get("name"))
    check("H", "thread dump during the requests: no permission check (GrantAwareACL) BLOCKED on the GrantService monitor",
          dump["blocked_in_permission_check"] == 0 and dump["holder_alive"], **dump)
    check("H", "the holder still holds the monitor when the last answer arrives (nothing waited for its release)",
          alive and answered < HOLD, answered_after=answered, hold=HOLD, holder_alive=alive)


def cleanup():
    ended = lib.wait_until(lambda: not holder_alive(), HOLD + 5, 1)
    note("cleanup", "the holder thread has released the monitor and ended", ended=ended)


if __name__ == "__main__":
    lib.run(sys.argv, {"A": sec_A, "B": sec_B, "H": sec_H}, cleanup)
