"""e2e-26: CI units for the wave-B fixes (issues #36, #34, #32 and #33), one driver per issue, on top of ../r19/lib.py:
screenshots in screenshots/run-26/ (never committed), rows in r26/out/<driver>.jsonl.

The rules of every e2e pass hold: one new browser context per page and account (real login form), the server state read
separately (REST with basic auth; the script console and `docker exec` only arrange or read state, never perform the
behaviour under test unless a driver says that the script console IS the user's path, as for #32's case-only rename).
Each driver arranges its own items (named r26-*) in its first section, so it can run again on the same stack, and
restores what it changes globally. Verdict lines as in r16..r23: "PASS {...}" / "FAIL {...}" per assertion (ci/shard.py
fails a step on a FAIL line or a non-zero exit), "NOTE {...}" for an observation without a verdict. Every check names
its contract item (wave-B contracts, main session 2026-10-10) in its text, and every positive check has a guard.

This file is shared by the four drivers and kept identical on every branch that adds one."""
import importlib.util
import json
import os
import re
import subprocess
import time

import pathlib

HERE = pathlib.Path(__file__).resolve().parent
_spec = importlib.util.spec_from_file_location("r19lib", HERE.parent / "r19" / "lib.py")
L19 = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(L19)
# r19/lib.py points r6/lib.py and r16/lib.py at run-19 and r19/out; this pass writes its own.
for _m in (L19._r6, L19._l):
    _m.SHOTS = HERE.parent / "screenshots" / "run-26"
    _m.OUT = HERE / "out"
L19._r6.SHOTS.mkdir(parents=True, exist_ok=True)
L19._r6.OUT.mkdir(parents=True, exist_ok=True)
SHOTS, OUT = L19._r6.SHOTS, L19._r6.OUT

Session, close, api, groovy, gv, ENV, BASE, pw = L19.Session, L19.close, L19.api, L19.groovy, L19.gv, L19.ENV, L19.BASE, L19.pw
check, note, run_sections, text_of, J, decide, loc_id = L19.check, L19.note, L19.run_sections, L19.text_of, L19.J, L19.decide, L19.loc_id
act_req, run_req, activation, job_xml, set_property, wait_until = (L19.act_req, L19.run_req, L19.activation, L19.job_xml,
                                                                   L19.set_property, L19.wait_until)
changes, builds, request_state = L19.changes, L19.builds, L19.request_state
UUID, LOGNAME, CONTAINER = L19.UUID, L19.LOGNAME, L19.CONTAINER
BC = "io.jenkins.plugins.batchcontrol"
JH = "/var/jenkins_home"
STORE = f"{JH}/batch-control"
CFG = f"def cfg = {BC}.config.BatchControlGlobalConfiguration.get()\n"
CLOCK = f"def bc = jenkins.model.Jenkins.get().pluginManager.uberClassLoader.loadClass('{BC}.store.BatchClock')\n"
ERROR_PAGE = re.compile(r"Oops!|A problem occurred while processing the request|Stack trace|java\.lang\.\w+Exception")


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


def sh(script, user=None, timeout=120):
    return dexec("sh", "-c", script, user=user, timeout=timeout)


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


# ---------------------------------------------------------------- items and requests
def delete(item):
    """Deletes `item` as the administrator when it exists; True when it is gone."""
    if api("admin", J(item) + "/api/json").status_code == 200:
        api("admin", J(item) + "/doDelete", "POST")
    return api("admin", J(item) + "/api/json").status_code == 404


def create(item, xml):
    """createItem as the administrator; the HTTP status."""
    parent, _, leaf = item.rpartition("/")
    r = api("admin", (J(parent) if parent else "") + f"/createItem?name={leaf}", "POST", data=xml.encode("utf-8"),
            headers={"Content-Type": "application/xml"})
    return r.status_code


FOLDER_XML = ("<?xml version='1.1' encoding='UTF-8'?><com.cloudbees.hudson.plugins.folder.Folder><description>e2e-26"
              "</description><properties/><folderViews/><healthMetrics/></com.cloudbees.hudson.plugins.folder.Folder>")


def activate(job, user="requester", approver="approver-1", reason="e2e-26 arrangement"):
    """An approved ACTIVATE request through the real flow (REST: requester files, approver approves); True when the
    job page then says the job is activated."""
    st, rid = act_req(user, job, "ACTIVATE", reason, approvers=(approver,))
    if rid:
        decide(approver, "activations", rid, "approve", "e2e-26")
    return activation(job) == "activated"


def act_status(rid):
    """The stored status of an activation request (activation-requests/<id>.xml), 'missing' when there is none."""
    return gv(f"""def f = new File(jenkins.model.Jenkins.get().rootDir, 'batch-control/activation-requests/{rid}.xml')
return f.exists() ? new XmlSlurper().parse(f).status.text() : 'missing'""")


def pending_activations(prefix):
    """Ids of the PENDING activation requests of the items whose full name starts with `prefix`."""
    out = gv(f"""def d = new File(jenkins.model.Jenkins.get().rootDir, 'batch-control/activation-requests')
return (d.listFiles() ?: []).findAll {{ it.name.endsWith('.xml') }}.collect {{ new XmlSlurper().parse(it) }}
  .findAll {{ it.status.text() == 'PENDING' && it.jobFullName.text().startsWith({json.dumps(prefix)}) }}.collect {{ it.id.text() }}.join(',')""")
    return [x for x in out.split(",") if re.fullmatch(UUID, x)]


def page_state(user, job, shot_name=None):
    """The activation notice of the job page as `user` sees it in a new browser context ('activated', 'not activated',
    'on hold' ... or None), with a screenshot of the notice."""
    s = Session(user, fresh=True)
    try:
        s.go(J(job) + "/")
        t = re.sub(r"\s+", " ", s.page.locator("body").inner_text())
        m = re.search(r"Batch Control: this (?:job|folder) is ([a-z ]+?)\.", t)
        if shot_name:
            notice = s.page.get_by_text(re.compile(r"Batch Control: this (job|folder) is")).first
            s.shot(notice if notice.count() else "#main-panel", shot_name)
        return m.group(1) if m else None
    finally:
        s.done()


def records_since(mark_ms, kinds, target):
    """Change records of `kinds` on `target` stamped at or after `mark_ms` (this month's file and the previous one)."""
    out = gv(f"""def root = new File(jenkins.model.Jenkins.get().rootDir, 'batch-control/changes')
def ym = java.time.YearMonth.now()
return [ym.minusMonths(1), ym].collect {{ new File(root, it.toString() + '.jsonl') }}.findAll {{ it.exists() }}
  .collect {{ it.getText('UTF-8') }}.join('\\n')""")
    rows = []
    for line in out.splitlines():
        try:
            r = json.loads(line)
        except ValueError:
            continue
        if r.get("type") in kinds and r.get("target") == target and _ms(r.get("at")) >= mark_ms:
            rows.append(r)
    return rows


def _ms(at):
    """Epoch millis of a record's `at` (epoch millis or an ISO-8601 instant); 0 when it cannot be read."""
    import datetime
    if isinstance(at, (int, float)):
        return int(at)
    try:
        return int(datetime.datetime.fromisoformat(str(at).replace("Z", "+00:00")).timestamp() * 1000)
    except Exception:  # noqa
        return 0


def now_ms():
    return int(gv("return System.currentTimeMillis()"))


# ---------------------------------------------------------------- store faults (as r23/kill_switch.py)
_MODES = {}


def modes(path):
    """path -> octal mode of `path` and everything below it."""
    rc, out = sh(f"find '{path}' -exec stat -c '%a %n' {{}} +")
    return {ln.split(" ", 1)[1]: ln.split(" ", 1)[0] for ln in out.splitlines() if " " in ln and ln.split(" ", 1)[0].isdigit()}


def make_unwritable(path):
    """`path` (a store directory) and everything below it unwritable for the jenkins user (chmod 555/444 as root in the
    container); the modes before are kept for restore(). Returns the proof: a create refused to the jenkins user."""
    _MODES[path] = modes(path)
    sh(f"find '{path}' -type d -exec chmod 555 {{}} + ; find '{path}' -type f -exec chmod 444 {{}} +", user="root")
    rc_create, _ = sh(f"touch '{path}/r26-probe'")
    return {"create_refused": rc_create != 0, "entries": len(_MODES[path])}


def restore(path):
    """The exact modes recorded before the fault (and owner write on `path` for anything new); True when writable."""
    old = _MODES.pop(path, {})
    if old:
        sh("; ".join(f"chmod {m} '{p}'" for p, m in old.items()), user="root")
    sh(f"chmod u+w '{path}'; rm -f '{path}/r26-probe'", user="root")
    rc, _ = sh(f"touch '{path}/r26-probe' && rm -f '{path}/r26-probe'")
    return rc == 0


def sha(path):
    rc, out = sh(f"sha256sum '{path}' | cut -c1-64")
    return out.strip() if rc == 0 else None


# ---------------------------------------------------------------- restarts
def plugin_active():
    try:
        a = api("admin", "/pluginManager/api/json?tree=plugins[shortName,active]")
        return a.status_code == 200 and any(p.get("shortName") == "batch-control" and p.get("active")
                                            for p in a.json()["plugins"])
    except Exception:  # noqa
        return False


def wait_ready(timeout=420):
    t0 = time.time()
    while time.time() - t0 < timeout:
        if plugin_active():
            return round(time.time() - t0)
        time.sleep(3)
    return None


def restart():
    """docker restart of the Jenkins container (SIGTERM with 180 s grace, so JaCoCo writes its session), then waits for
    the plugin. Returns (seconds until ready or None, docker exit code). A unit that calls it is `last` in its group:
    JCasC re-applies the authorization strategy at boot and drops what the SETUP arrangement added."""
    t0 = time.time()
    r = subprocess.run(["docker", "restart", "-t", "180", CONTAINER], capture_output=True, text=True, timeout=600)
    ready = wait_ready()
    return (round(time.time() - t0) if ready is not None else None), r.returncode


def container_env():
    p = subprocess.run(["docker", "inspect", "-f", "{{json .Config.Env}}", CONTAINER], capture_output=True, text=True,
                       timeout=60)
    return dict(e.split("=", 1) for e in json.loads(p.stdout or "[]") if "=" in e)


def _compose_args():
    """The `docker compose` arguments of the project that created the Jenkins container (from its labels)."""
    p = subprocess.run(["docker", "inspect", "-f", "{{json .Config.Labels}}", CONTAINER], capture_output=True, text=True,
                       timeout=60)
    labels = json.loads(p.stdout or "{}")
    files = [f for f in labels.get("com.docker.compose.project.config_files", "").split(",") if f]
    args = ["docker", "compose", "-p", labels.get("com.docker.compose.project", ""),
            "--project-directory", labels.get("com.docker.compose.project.working_dir", "")]
    for f in files:
        args += ["-f", f]
    return args, labels.get("com.docker.compose.service", "jenkins")


# The environment keys whose value must not change when the container is re-created in another zone.
_KEPT = ("JAVA_TOOL_OPTIONS", "JENKINS_OPTS", "BC_JENKINS_URL", "CASC_JENKINS_CONFIG")


def recreate_in_zone(tz):
    """Re-creates the Jenkins container of the running compose project with the time zone `tz` (TZ and the JVM's
    -Duser.timezone, both from ${TZ} in docker-compose.yml) and the same JENKINS_HOME volume: the controller restarts
    in another zone. The compose files and project come from the container's labels; first `compose config` with the
    container's current zone must render exactly the running container's environment (so nothing but the zone
    changes). Returns a dict: ok, seconds, zone_env, user_timezone, reason."""
    args, service = _compose_args()
    before = container_env()
    env = dict(os.environ, TZ=before.get("TZ", ""))
    p = subprocess.run(args + ["config", "--format", "json"], capture_output=True, text=True, env=env, timeout=120)
    if p.returncode != 0:
        return {"ok": False, "reason": "compose config failed: " + (p.stderr or p.stdout)[-300:]}
    rendered = json.loads(p.stdout)["services"][service].get("environment", {})
    differ = [k for k in list(_KEPT) + ["JAVA_OPTS", "TZ"] if (rendered.get(k) or "") != (before.get(k) or "")]
    if differ:
        return {"ok": False, "reason": "compose would not re-create the running container (environment differs in "
                                       + ", ".join(differ) + "): run the unit through ci/run.sh or r26/wrap.sh"}
    # The re-created container starts a new log: keep the old one with the driver's output (ci/run.sh collects r26/out).
    old = subprocess.run(["docker", "logs", "--timestamps", CONTAINER], capture_output=True, text=True, timeout=120)
    (OUT / f"jenkins-before-{tz.replace('/', '_')}-{int(time.time())}.log").write_text(old.stdout + old.stderr)
    t0 = time.time()
    p = subprocess.run(args + ["up", "-d", "--no-deps", "--no-build", service], capture_output=True, text=True,
                       env=dict(os.environ, TZ=tz), timeout=600)
    if p.returncode != 0:
        return {"ok": False, "reason": "compose up failed: " + (p.stderr or p.stdout)[-300:]}
    ready = wait_ready()
    after = container_env()
    m = re.search(r"-Duser\.timezone=(\S+)", after.get("JAVA_OPTS", ""))
    kept = all((after.get(k) or "") == (before.get(k) or "") for k in _KEPT)
    return {"ok": ready is not None and after.get("TZ") == tz and kept, "seconds": round(time.time() - t0),
            "zone_env": after.get("TZ"), "user_timezone": m.group(1) if m else None, "kept": kept,
            "reason": None if ready is not None else "Jenkins did not become ready"}
