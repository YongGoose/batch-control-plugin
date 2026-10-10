"""e2e-26 #34: store writes are forced to disk (wave-B contract #34; JenkinsRule rows T-04-70..72, StoreForceTest).

usage: python store_force.py [ATB]   rows: out/store_force.jsonl, the trace: out/store_force-strace.log
A power loss cannot be simulated, so this unit watches the system calls instead: an strace sidecar container
(r26/strace.Dockerfile, alpine + strace) joins the Jenkins container's PID namespace (`docker run --pid=container:<jenkins>
--cap-add SYS_PTRACE`, nothing is added to the Jenkins container itself) and traces fsync/fdatasync of every JVM thread
(`strace -f -y -p <java pid>`, -y prints the path behind each file descriptor). The JDK's FileChannel.force and
FileDescriptor.sync are fsync/fdatasync on Linux. Around each operation the script console forces two marker files in
JENKINS_HOME (r26-force-mark-<op>-begin/-end), which bound that operation's part of the trace and prove that the tracer
sees a force made by this JVM in that window (the guard of every positive check below).

A  arrangement: run and change control on; item r26-sf (Freestyle, not activated, approval required) deleted and
   created again; no open r26-sf window or pending request; the sidecar image builds.
T  traced operations (REST, as the users do them; the UI is not under test here), each between its markers:
   grant-approve  approver-1 approves requester's CONFIGURE window on r26-sf: a forced temporary file in requests/grant/
                  and in grants/ (forced in the directory, gone after the operation: renamed onto the target), and
                  grants/ itself forced (Linux can force a directory)
   revoke         the administrator revokes the window: changes/YYYY-MM.jsonl forced (the GRANT_REVOKE append), a forced
                  temporary file in grants/, grants/ forced
   run-submit     requester's run request on r26-sf: a forced temporary file in requests/run/, requests/run/ forced
   run-decide     approver-1 approves it (the run-request decision): a forced temporary file in requests/run/
   act-decide     approver-1 approves requester's ACTIVATE request on r26-sf: forced temporary files in activations/ and
                  activation-requests/, activations/ forced, changes/YYYY-MM.jsonl forced (the ACTIVATED append)
B  behaviour guard (T-04-72, passes with and without the fix): the window was active after the approval and is not after
   the revocation; the run request is APPROVED; the ACTIVATE request is APPROVED and r26-sf activated; no temporary file is
   left in grants/, requests/grant/, requests/run/, activations/ or activation-requests/.

Without the fix every "forced" check of T fails (no fsync/fdatasync on any path under batch-control/), while the marker
premises and B pass. Not covered: the durability itself after a power loss (no e2e can cut the power of the volume), the
other writers (incidents, snapshots, settings, the retention rewrite), a forced append of a run record (written when a
build completes, outside these windows: noted, not asserted), and platforms other than Linux."""
import json
import re
import subprocess
import sys
import time

sys.path.insert(0, __file__.rsplit("/", 1)[0])
import lib  # noqa: E402
from lib import api, check, note, J  # noqa: E402

lib.LOGNAME[0] = "store_force"
USER, APPROVER = "requester", "approver-1"
JOB = "r26-sf"
IMAGE = "bc-e2e-strace:1"
SIDE = lib.CONTAINER + "-strace"
TRACE_LOG = lib.OUT / "store_force-strace.log"
LINE = re.compile(r"^(?:\[pid\s+\d+\] )?(fsync|fdatasync)\((\d+)<([^>]*)>")
MONTH = re.compile(r"/changes/\d{4}-\d{2}\.jsonl$")
S = {}


def docker(*args, timeout=600):
    p = subprocess.run(["docker"] + list(args), capture_output=True, text=True, timeout=timeout)
    return p.returncode, p.stdout + p.stderr


def trace_text():
    return docker("logs", SIDE, timeout=60)[1]


def start_tracer():
    """Builds the sidecar image and attaches strace to every thread of the Jenkins JVM; True once it has attached."""
    rc, out = docker("build", "-q", "-t", IMAGE, "-f", str(lib.HERE / "strace.Dockerfile"), str(lib.HERE))
    if rc != 0:
        return False, "image build failed: " + out[-300:]
    docker("rm", "-f", SIDE, timeout=60)
    rc, out = docker("run", "-d", "--name", SIDE, f"--pid=container:{lib.CONTAINER}", "--cap-add", "SYS_PTRACE", IMAGE,
                     "sh", "-c", "exec strace -f -y -e trace=fsync,fdatasync -p \"$(pgrep -o -f jenkins.war)\"")
    if rc != 0:
        return False, "sidecar did not start: " + out[-300:]
    attached = lib.wait_until(lambda: re.search(r"Process \d+ attached", trace_text()), timeout=60, step=1)
    return bool(attached), trace_text()[:300]


def stop_tracer():
    """SIGTERM (strace detaches cleanly), keeps the trace in out/, removes the sidecar."""
    docker("stop", "-t", "15", SIDE, timeout=60)
    text = trace_text()
    if text:
        TRACE_LOG.write_text(text)
    docker("rm", "-f", SIDE, timeout=60)
    return text


def mark(name):
    """Forces JENKINS_HOME/r26-force-mark-<name> through the script console (FileChannel.force)."""
    return lib.gv(f"""import java.nio.channels.FileChannel
import java.nio.file.*
def p = Paths.get(jenkins.model.Jenkins.get().rootDir.path, 'r26-force-mark-{name}')
def ch = FileChannel.open(p, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
try {{ ch.write(java.nio.ByteBuffer.wrap([1] as byte[])); ch.force(true) }} finally {{ ch.close() }}
Files.delete(p)
return p.toString()""")


def forced_between(begin, end):
    """The paths forced between the forces of the two marker files (None when a marker is not in the trace yet)."""
    paths = [m.group(3) for m in (LINE.match(ln) for ln in trace_text().splitlines()) if m]
    b = [i for i, p in enumerate(paths) if p.endswith("/r26-force-mark-" + begin)]
    e = [i for i, p in enumerate(paths) if p.endswith("/r26-force-mark-" + end)]
    if not b or not e or e[-1] < b[-1]:
        return None
    return paths[b[-1] + 1:e[-1]]


def existing(paths):
    """The subset of `paths` that exist in the container now."""
    if not paths:
        return set()
    script = "; ".join(f"[ -e '{p}' ] && echo '{p}'" for p in sorted(set(paths)) if "'" not in p)
    return set(lib.sh(script + "; true")[1].split("\n")) - {""}


def traced(op, action):
    """Runs `action` between the markers of `op`; returns (value, forced paths under batch-control/ or None, all)."""
    mark(f"{op}-begin")
    value = action()
    mark(f"{op}-end")
    box = lib.wait_until(lambda: (lambda v: None if v is None else [v])(forced_between(f"{op}-begin", f"{op}-end")),
                         timeout=30, step=1)
    forced = box[0] if box else None
    check("T", f"premise ({op}): the tracer saw both marker forces of this window (it sees a FileChannel.force of this JVM, "
          "with its path)", forced is not None)
    if forced is None:
        return value, None, None
    ours = [p for p in forced if p.startswith(lib.STORE + "/")]
    note("T", f"{op}: forced paths in the window", store=ours, others=len(forced) - len(ours),
         other_examples=[p for p in forced if not p.startswith(lib.STORE + "/")][:4])
    return value, ours, forced


def assert_temp(op, ours, sub, what):
    d = f"{lib.STORE}/{sub}"
    cand = [p for p in (ours or []) if p.rsplit("/", 1)[0] == d]
    gone = [p for p in cand if p not in existing(cand)]
    check("T", f"contract #34 ({op}): writing {what} forces its temporary file in batch-control/{sub}/ before the rename "
          "(a forced file there that is gone afterwards)", bool(gone), temporary=gone, forced_in_dir=cand)


def assert_dir(op, ours, sub, what):
    d = f"{lib.STORE}/{sub}"
    check("T", f"contract #34 ({op}): after the rename of {what} the directory batch-control/{sub}/ is forced (the "
          "platform allows it)", d in (ours or []), forced_store_paths=ours)


def assert_month(op, ours, what):
    hit = [p for p in (ours or []) if MONTH.search(p)]
    check("T", f"contract #34 ({op}): the {what} append to batch-control/changes/YYYY-MM.jsonl is forced before the "
          "operation answers", bool(hit), forced=hit, forced_store_paths=ours)


# ---------------------------------------------------------------- server state
def active_grants():
    """Ids of the requester's grants on r26-sf that are neither revoked nor expired."""
    out = lib.gv(f"""def now = System.currentTimeMillis()
def d = new File(jenkins.model.Jenkins.get().rootDir, 'batch-control/grants')
return (d.listFiles() ?: []).findAll {{ it.name.endsWith('.xml') }}.collect {{ new XmlSlurper().parse(it) }}
  .findAll {{ it.user.text() == '{USER}' && it.revokedAtMillis.text() == '' && it.text().contains('{JOB}') &&
             ((it.expiresAtMillis.text() ?: '0') as long) > now }}.collect {{ it.id.text() }}.join(',')""")
    return [x for x in out.split(",") if re.fullmatch(lib.UUID, x)]


def stray():
    """Files in the rewritten store directories that are not a stored .xml entity or activations/.schema."""
    out = lib.sh(f"cd {lib.STORE} && for d in grants requests/grant requests/run activations activation-requests; do "
                 "[ -d $d ] && find $d -maxdepth 1 -type f ! -name '*.xml' ! -name .schema; done; true")[1]
    return [x for x in out.splitlines() if x.strip()]


# ---------------------------------------------------------------- sections
def sec_A():
    sw = lib.switches()
    for gid in active_grants():
        api("admin", f"/batch-control/grants/active/{gid}/revoke", "POST")
    for rid in lib.pending_activations(JOB):
        api("admin", f"/batch-control/activations/{rid}/cancel", "POST")
    gone = lib.delete(JOB)
    made = lib.create(JOB, lib.job_xml("fs", shell="echo r26"))
    state = lib.activation(JOB)
    rc, out = docker("build", "-q", "-t", IMAGE, "-f", str(lib.HERE / "strace.Dockerfile"), str(lib.HERE))
    check("A", "arrangement: run and change control on; r26-sf re-created (not activated), no open window or pending "
          "activation request on it; the strace sidecar image builds",
          sw["run_control"] and sw["change_control"] and gone and made == 200 and state == "not activated"
          and not active_grants() and rc == 0, switches=sw, deleted=gone, created=made, job_page=state, image=rc,
          build=out.strip()[-120:], container=lib.CONTAINER)


def sec_T():
    ok, info = start_tracer()
    check("T", "premise: the strace sidecar attached to every thread of the Jenkins JVM", ok, detail=info)
    if not ok:
        stop_tracer()
        return
    try:
        # grant approve
        r, gid = lib.L19._l.grant_req(USER, JOB, ["CONFIGURE"], minutes=15, reason="e2e-26 #34", approvers=(APPROVER,))
        S["grant_request"] = gid
        st, ours, _ = traced("grant-approve", lambda: lib.decide(APPROVER, "grants", gid, "approve", "e2e-26 #34"))
        S["approve_status"], S["active_after_approve"] = st, active_grants()
        assert_temp("grant-approve", ours, "requests/grant", "the grant request's APPROVED state")
        assert_temp("grant-approve", ours, "grants", "the new grant file")
        assert_dir("grant-approve", ours, "grants", "the new grant file")
        # revoke
        gids = S["active_after_approve"]
        st, ours, _ = traced("revoke", lambda: [api("admin", f"/batch-control/grants/active/{g}/revoke", "POST").status_code
                                                for g in gids])
        S["revoke_status"], S["active_after_revoke"] = st, active_grants()
        assert_month("revoke", ours, "GRANT_REVOKE record's")
        assert_temp("revoke", ours, "grants", "the revoked grant file")
        assert_dir("revoke", ours, "grants", "the revoked grant file")
        # run request: submission, then the decision
        (st, rid, _), ours, _ = traced("run-submit", lambda: lib.run_req(USER, JOB, "e2e-26 #34 run request"))
        S["run_request"] = rid
        assert_temp("run-submit", ours, "requests/run", "the new run request")
        assert_dir("run-submit", ours, "requests/run", "the new run request")
        st, ours, _ = traced("run-decide", lambda: lib.decide(APPROVER, "requests", rid, "approve", "e2e-26 #34"))
        S["run_decide_status"] = st
        assert_temp("run-decide", ours, "requests/run", "the run request's APPROVED state")
        # activation decision
        st, aid = lib.act_req(USER, JOB, "ACTIVATE", "e2e-26 #34", approvers=(APPROVER,))
        S["act_request"] = aid
        st, ours, _ = traced("act-decide", lambda: lib.decide(APPROVER, "activations", aid, "approve", "e2e-26 #34"))
        S["act_decide_status"] = st
        assert_temp("act-decide", ours, "activations", "the new activation state")
        assert_temp("act-decide", ours, "activation-requests", "the ACTIVATE request's APPROVED state")
        assert_dir("act-decide", ours, "activations", "the new activation state")
        assert_month("act-decide", ours, "ACTIVATED record's")
    finally:
        text = stop_tracer()
        n = sum(1 for ln in text.splitlines() if LINE.match(ln))
        alive = lib.plugin_active()
        check("T", "guard: the sidecar detached and Jenkins still answers", alive, forced_lines=n, log=str(TRACE_LOG))


def sec_B():
    st, run_state, _ = lib.request_state(S["run_request"]) if S.get("run_request") else (None, None, None)
    check("B", "guard (T-04-72): the window was active after the approval and is not after the revocation",
          S.get("approve_status") in (200, 302, 303) and len(S.get("active_after_approve") or []) == 1
          and S.get("active_after_revoke") == [], approve=S.get("approve_status"),
          after_approve=S.get("active_after_approve"), after_revoke=S.get("active_after_revoke"))
    check("B", "guard (T-04-72): the run request reads back as decided (APPROVED or later) and the ACTIVATE request is "
          "APPROVED with r26-sf activated",
          run_state in ("APPROVED", "EXECUTED", "QUEUED", "RUNNING", "STARTED") and S.get("act_request")
          and lib.act_status(S["act_request"]) == "APPROVED" and lib.activation(JOB) == "activated",
          run_request=run_state, act=lib.act_status(S["act_request"]) if S.get("act_request") else None,
          job_page=lib.activation(JOB))
    left = stray()
    check("B", "guard (T-04-72): no temporary file is left in grants/, requests/grant/, requests/run/, activations/, "
          "activation-requests/", not left, stray=left)


def cleanup():
    docker("rm", "-f", SIDE, timeout=60)
    for gid in active_grants():
        api("admin", f"/batch-control/grants/active/{gid}/revoke", "POST")
    note("cleanup", "sidecar removed, no open r26-sf window", sidecar=docker("ps", "-aq", "-f", f"name={SIDE}")[1].strip(),
         windows=active_grants())


if __name__ == "__main__":
    lib.run(sys.argv, {"A": sec_A, "T": sec_T, "B": sec_B}, cleanup)
