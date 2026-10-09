"""e2e-22: CI units for the fixes of the bug-hunt batch B (R4-02, R4-03, R4-01, R2-04, R2-05, R3-04), one driver per
bug, on top of ../r19/lib.py: screenshots in screenshots/run-22/ (never committed), rows in r22/out/<driver>.jsonl.

The rules of every e2e pass hold: one new browser context per page and account (real login form), the server state read
separately (REST with basic auth; the script console only arranges or reads state, never performs the behaviour under
test). Each driver arranges its own items (named r22-*) idempotently in its first section and restores what it changes
globally. Verdict lines as in r16..r21: "PASS {...}" / "FAIL {...}" per assertion (ci/shard.py fails a step on a FAIL
line or a non-zero exit), "NOTE {...}" for an observation without a verdict. Each driver asserts the correct behaviour,
so it fails on a plugin without the fix.

This file is shared by the six drivers and kept identical on every branch that adds one."""
import importlib.util
import json
import pathlib
import time

HERE = pathlib.Path(__file__).resolve().parent
_spec = importlib.util.spec_from_file_location("r19lib", HERE.parent / "r19" / "lib.py")
L19 = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(L19)
# r19/lib.py points r6/lib.py and r16/lib.py at run-19 and r19/out; this pass writes its own.
for _m in (L19._r6, L19._l):
    _m.SHOTS = HERE.parent / "screenshots" / "run-22"
    _m.OUT = HERE / "out"
L19._r6.SHOTS.mkdir(parents=True, exist_ok=True)
L19._r6.OUT.mkdir(parents=True, exist_ok=True)
SHOTS, OUT = L19._r6.SHOTS, L19._r6.OUT

Session, close, api, groovy, gv, ENV, BASE, pw = L19.Session, L19.close, L19.api, L19.groovy, L19.gv, L19.ENV, L19.BASE, L19.pw
check, note, run_sections, text_of, J, decide, loc_id, tick = (L19.check, L19.note, L19.run_sections, L19.text_of, L19.J,
                                                               L19.decide, L19.loc_id, L19.tick)
builds, next_build, request_state, run_req, queue_items, run_files = (L19.builds, L19.next_build, L19.request_state,
                                                                      L19.run_req, L19.queue_items, L19.run_files)
ensure_job, job_xml, string_p, choice_p, set_property, wait_until = (L19.ensure_job, L19.job_xml, L19.string_p,
                                                                     L19.choice_p, L19.set_property, L19.wait_until)
UUID, LOGNAME = L19.UUID, L19.LOGNAME
BC = "io.jenkins.plugins.batchcontrol"


def facts(script):
    """A JSON object computed by the script console (arrangement facts, server state)."""
    return json.loads(gv(script))


def switches():
    return facts(f"""def c = {BC}.config.BatchControlGlobalConfiguration.get()
return groovy.json.JsonOutput.toJson([run_control: c.runControlEnabled, change_control: c.changeControlEnabled,
  approvers: c.approvers as List, id_strategy: hudson.model.User.idStrategy().getClass().simpleName])""")


def has_permission(user, item, permission):
    """Whether `user` holds `permission` (e.g. 'hudson.model.Item.READ') on item `item` ('' = the root), read as admin."""
    target = f"j.getItemByFullName({json.dumps(item)})" if item else "j"
    return gv(f"""def j = jenkins.model.Jenkins.get(); def u = hudson.model.User.getById({json.dumps(user)}, false)
def t = {target}
return (u == null || t == null) ? 'null' : t.getACL().hasPermission2(u.impersonate2(), {permission})""") == "true"


def wait_queued(job, timeout=30):
    """The queue items of `job` once there is at least one (admin's view of the queue); [] on timeout."""
    return wait_until(lambda: queue_items(job), timeout, 1) or []


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
