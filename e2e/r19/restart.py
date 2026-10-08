"""e2e-19: run requests with typed values across a real restart (SPEC 4 "restart durability", SPEC 5 D-72; coverage
inventory G-H2, e2e-16 NOT covered "restart between approval and run with typed values").

usage: python restart.py     rows: out/restart.jsonl, shots: R19-RST-*.png
Restarts Jenkins (docker restart of $BC_CONTAINER-jenkins), so ci/shard.py runs it inside a `last` unit (r16-durable,
after r16/durable.py). Uses r16-file (core file UPLOAD, password SECRET, string NOTE; r16/arrange.py) and r19-life.
Before the restart:
  P  requester submits a r16-file request on the Request Run page (file + secret + note): PENDING, with its values file
  Q  with no executor, a second r16-file request (another file and note, same secret) is approved: APPROVED, its run
     waits in the queue
  W  a r19-life request is approved while its run cannot start (no executor) and then left APPROVED in the queue too
Jenkins restarts (JCasC puts two executors back and resets the arrangement's permissions: r16/arrange.py runs again).
After the restart:
  P  is still PENDING with its values file (SPEC 4: pending requests are recovered as PENDING); approved now, its
     build receives exactly the uploaded bytes and the original secret
  Q  the queued run starts exactly once after the restart, with exactly its own uploaded bytes, NOTE and the original
     secret; the request is EXECUTED and links that build; the values file is gone; no plaintext secret in the store
  W  r19-life ran exactly once (no duplicate submission after the restart, SPEC 4 "no double submission")"""
import importlib.util
import os
import re
import subprocess
import sys
import time

sys.path.insert(0, __file__.rsplit("/", 1)[0])
import lib  # noqa: E402
from lib import (Session, api, gv, check, note, J, run_req, next_build, queue_items, wait_build, wait_executed, wait_until,
                 request_state, fill_run_form, make_file, sha, run_files, BASE)  # noqa: E402

lib.LOGNAME[0] = "restart"
_spec = importlib.util.spec_from_file_location("r17lib", lib.HERE.parent / "r17" / "lib.py")
L17 = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(L17)
JOB = "r16-file"
SECRET = "r19-Restart-Secret-5"


def submit(reason, note_value, path):
    s = Session("requester")
    s.go(J(JOB) + "/batch-control/")
    form = s.page.locator("form[name=batch-control-request]").first
    fill_run_form(form, reason, "approver-1", {"SECRET": SECRET, "NOTE": note_value}, {"UPLOAD": str(path)})
    with s.page.expect_navigation(timeout=20000):
        form.locator("button[name=Submit], button[type=submit]").first.click()
    m = re.search(r"/batch-control/requests/([0-9a-f-]{36})/", s.page.url)
    s.done()
    return m.group(1) if m else None


def values(console):
    return dict(re.findall(r"^(UPLOAD_SHA|SECRET_SHA|NOTE)=(.*)$", console, re.M))


def main():
    p_path, p_bytes = make_file("r19-restart-p.bin", 4096, "r19-restart-p")
    q_path, q_bytes = make_file("r19-restart-q.bin", 3072, "r19-restart-q")
    p = submit("e2e-19 pending across a restart", "note-p", p_path)
    check("P", "a r16-file request (file + secret + note) is PENDING with its values file before the restart",
          p and request_state(p)[1] == "PENDING" and f"{p}.values.xml" in run_files(p), request=p, files=run_files(p) if p else None)
    gv("jenkins.model.Jenkins.get().setNumExecutors(0); return 0")
    nb = next_build(JOB)
    q = submit("e2e-19 approved, queued across a restart", "note-q", q_path)
    assert q, "second request"
    assert lib.decide("approver-1", "requests", q, "approve") in (200, 302)
    nbw = next_build("r19-life")
    st, w, _ = run_req("requester", "r19-life", "e2e-19 approved, queued across a restart (no parameters file)", params={"DATE": "2026-05-05"})
    assert w, st
    assert lib.decide("approver-1", "requests", w, "approve") in (200, 302)
    time.sleep(3)
    check("Q", "before the restart both approved runs wait in the queue (no executor): APPROVED, one queue item each",
          request_state(q)[1] == "APPROVED" and len(queue_items(JOB)) == 1 and len(queue_items("r19-life")) == 1,
          q=request_state(q)[1], queue=len(queue_items(JOB)), queue_life=len(queue_items("r19-life")))
    secs, rc = L17.restart_jenkins()
    rearr = subprocess.run([sys.executable, str(lib.HERE.parent / "r16" / "arrange.py")], capture_output=True, text=True,
                           timeout=600, env=dict(os.environ, R16_KEEP_WINDOWS="1"))
    note("restart", "Jenkins restarted; r16/arrange.py run again", ready_after_s=secs, rc=rc, arrange_rc=rearr.returncode)
    gv("jenkins.model.Jenkins.get().setNumExecutors(2); return 2")
    # Q: the queued approved run starts once
    status, _ = wait_executed(q, 240)
    result, console = wait_build(JOB, nb, 240)
    v = values(console)
    time.sleep(20)
    check("Q", "after the restart the queued approved run started exactly once with its own file, NOTE and the original "
          "secret; the request is EXECUTED (SPEC 4, SPEC 5 D-72)",
          status == "EXECUTED" and result == "SUCCESS" and v.get("UPLOAD_SHA") == sha(q_bytes) and v.get("NOTE") == "note-q"
          and v.get("SECRET_SHA") == sha(SECRET.encode()) and next_build(JOB) == nb + 1 and not queue_items(JOB),
          status=status, result=result, values={k: x[:16] for k, x in v.items()}, next_build=next_build(JOB), before=nb)
    check("Q", "its values file is gone and no plaintext secret is stored", f"{q}.values.xml" not in run_files(q)
          and lib._l.store_contains(SECRET) == "", files=run_files(q))
    sw, _ = wait_executed(w, 120)
    time.sleep(10)
    check("W", "the parameterless approved run also ran exactly once after the restart (no double submission, SPEC 4)",
          sw == "EXECUTED" and next_build("r19-life") == nbw + 1, status=sw, next_build=next_build("r19-life"), before=nbw)
    # P: still pending; approve now
    st, status, _ = request_state(p)
    check("P", "after the restart the pending request is still PENDING with its values file (SPEC 4)",
          status == "PENDING" and f"{p}.values.xml" in run_files(p), status=status, files=run_files(p))
    nb2 = next_build(JOB)
    s, _ = lib.browser_decide("approver-1", p, "approve", "after the restart", "R19-RST-P-01-approved-after-restart")
    s.done()
    result, console = wait_build(JOB, nb2, 180)
    v = values(console)
    check("P", "approved after the restart, its build receives exactly the uploaded bytes and the original secret (SPEC 5)",
          result == "SUCCESS" and v.get("UPLOAD_SHA") == sha(p_bytes) and v.get("NOTE") == "note-p"
          and v.get("SECRET_SHA") == sha(SECRET.encode()), result=result, values={k: x[:16] for k, x in v.items()})


try:
    main()
except Exception as e:  # noqa
    check("restart", "driver raised", False, error=repr(e)[:600])
finally:
    try:
        gv("jenkins.model.Jenkins.get().setNumExecutors(2); return 2")
    except Exception:  # noqa
        pass
lib.close()
print("SUMMARY restart", lib.N, flush=True)
sys.exit(1 if lib.N["fail"] else 0)
