"""e2e-23: CI units for the fixes of the bug-hunt batch A (R1-01, R3-01, R2-02 with R2-01, R2-03, R1-02), one driver per
bug, on top of ../r19/lib.py: screenshots in screenshots/run-23/ (never committed), rows in r23/out/<driver>.jsonl.

The rules of every e2e pass hold: one new browser context per page and account (real login form), the server state read
separately (REST with basic auth; the script console and `docker exec` only arrange or read state, never perform the
behaviour under test). Each driver arranges its own items (named r23-*) idempotently in its first section and restores
what it changes globally. Verdict lines as in r16..r22: "PASS {...}" / "FAIL {...}" per assertion (ci/shard.py fails a
step on a FAIL line or a non-zero exit), "NOTE {...}" for an observation without a verdict. Each driver asserts the
correct behaviour, so it fails on a plugin without the fix.

This file is shared by the five drivers and kept identical on every branch that adds one."""
import importlib.util
import json
import pathlib
import subprocess
import time

HERE = pathlib.Path(__file__).resolve().parent
_spec = importlib.util.spec_from_file_location("r19lib", HERE.parent / "r19" / "lib.py")
L19 = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(L19)
# r19/lib.py points r6/lib.py and r16/lib.py at run-19 and r19/out; this pass writes its own.
for _m in (L19._r6, L19._l):
    _m.SHOTS = HERE.parent / "screenshots" / "run-23"
    _m.OUT = HERE / "out"
L19._r6.SHOTS.mkdir(parents=True, exist_ok=True)
L19._r6.OUT.mkdir(parents=True, exist_ok=True)
SHOTS, OUT = L19._r6.SHOTS, L19._r6.OUT

Session, close, api, groovy, gv, ENV, BASE, pw = L19.Session, L19.close, L19.api, L19.groovy, L19.gv, L19.ENV, L19.BASE, L19.pw
check, note, run_sections, text_of, J, decide, loc_id, tick = (L19.check, L19.note, L19.run_sections, L19.text_of, L19.J,
                                                               L19.decide, L19.loc_id, L19.tick)
builds, next_build, request_state, run_req, act_req, queue_items = (L19.builds, L19.next_build, L19.request_state,
                                                                    L19.run_req, L19.act_req, L19.queue_items)
ensure_job, job_xml, set_property, wait_until, wait_build, activation = (L19.ensure_job, L19.job_xml, L19.set_property,
                                                                         L19.wait_until, L19.wait_build, L19.activation)
changes_mark, changes_since = L19.changes_mark, L19.changes_since
UUID, LOGNAME, CONTAINER = L19.UUID, L19.LOGNAME, L19.CONTAINER
BC = "io.jenkins.plugins.batchcontrol"
JH = "/var/jenkins_home"
CFG = f"def cfg = {BC}.config.BatchControlGlobalConfiguration.get()\n"


def facts(script):
    """A JSON object computed by the script console (arrangement facts, server state)."""
    return json.loads(gv(script))


def switches():
    return facts(CFG + """return groovy.json.JsonOutput.toJson([run_control: cfg.runControlEnabled,
  change_control: cfg.changeControlEnabled, approvers: cfg.approvers as List])""")


def dexec(*cmd, user=None, timeout=120):
    """`docker exec` in the Jenkins container (arrangement and evidence only); returns (rc, stdout+stderr)."""
    args = ["docker", "exec"] + (["-u", user] if user else []) + [CONTAINER] + list(cmd)
    p = subprocess.run(args, capture_output=True, text=True, timeout=timeout)
    return p.returncode, (p.stdout + p.stderr)


def timed(fn):
    t0 = time.monotonic()
    v = fn()
    return v, round(time.monotonic() - t0, 3)


def run(argv, table, cleanup=None):
    """Runs the sections named by the letters of argv[1] (default: all, in table order; the arrangement "A" always
    runs first), each catching its exception as one FAIL line, then `cleanup` (its exception is a FAIL too), then
    closes the browser, prints the summary and exits 1 when any check failed."""
    want = [x for x in (argv[1] if len(argv) > 1 else "".join(table)) if x in table]
    if "A" in table and "A" not in want:
        want.insert(0, "A")
    for sec in want:
        try:
            table[sec]()
        except Exception as e:  # noqa: a section that raises is one FAIL, the driver goes on
            check(sec, "section raised", False, error=repr(e)[:600])
    if cleanup is not None:
        try:
            cleanup()
        except Exception as e:  # noqa
            check("cleanup", "restoring the global state raised", False, error=repr(e)[:600])
    run_sections([], table)
