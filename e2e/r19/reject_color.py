"""e2e-19: the Reject buttons render in core's destructive colour (71d267b, T-UI-52b in a real browser).

usage: python reject_color.py [RGA]     rows: out/reject_color.jsonl, shots: screenshots/run-19/R19-<R|G|A>-buttons.png
The requester files, over REST and naming approver-1, a pending run request (batch-daily), a pending permission window
request (CONFIGURE on r19-reject) and a pending activation request (ACTIVATE r19-reject); the server lists each as
pending (admin GET of its detail URL). Then approver-1 opens each detail page in a new browser context, and the page's
own computed styles decide:
  - expected red: the computed `color` of a probe element inserted next to the Reject button with
    style="color: var(--destructive-color)", so the check follows the theme rather than a hard-coded RGB;
  - guard: the probe resolves to a colour of its own (it differs from the text colour around it; an undefined
    --destructive-color would make it inherit that colour and the check vacuous);
  - guard: the Reject button of the reject form exists (missing is a FAIL, never a skip);
  - the Reject button's computed `color` equals the probe's;
  - guard: the Approve button of the approve form ("Approve", on the activation page "Approve Activation" or
    "Approve Hold") exists and its computed `color` is not the probe's.
One viewport screenshot per page, scrolled to the bottom where both buttons are.
R = run request, G = permission window (grant) request, A = activation request. The requester cancels the three
requests at the end (a NOTE, no verdict). Light theme only (the default of the seeded accounts)."""
import sys

sys.path.insert(0, __file__.rsplit("/", 1)[0])
import lib  # noqa: E402
from lib import Session, api, check, note, run_sections, J, JOB  # noqa: E402

lib.LOGNAME[0] = "reject_color"
WANT = sys.argv[1] if len(sys.argv) > 1 else "RGA"
APPROVER = "approver-1"
REASON = "e2e-19 Reject colour"

MEASURE = r"""() => {
  const pick = (form, re) => {
    const f = document.querySelector(`form[name="${form}"]`);
    if (!f) return null;
    return [...f.querySelectorAll('button')].find(b => re.test(b.textContent.trim())) || null;
  };
  const reject = pick('reject', /^Reject$/);
  const approve = pick('approve', /^Approve( Activation| Hold)?$/);
  const host = (reject && reject.parentElement) || document.querySelector('#main-panel') || document.body;
  const probe = document.createElement('span');
  probe.setAttribute('style', 'color: var(--destructive-color)');
  probe.textContent = 'probe';
  host.appendChild(probe);
  const probeColor = getComputedStyle(probe).color;
  const hostColor = getComputedStyle(host).color;
  probe.remove();
  const info = b => b && {label: b.textContent.trim(), color: getComputedStyle(b).color, classes: b.className,
                          varColor: getComputedStyle(b).getPropertyValue('--color').trim(),
                          visible: !!(b.offsetWidth || b.offsetHeight)};
  return {probe: probeColor, around: hostColor,
          destructiveVar: getComputedStyle(document.documentElement).getPropertyValue('--destructive-color').trim(),
          theme: document.documentElement.getAttribute('data-theme'),
          reject: info(reject), approve: info(approve),
          rejectForms: document.querySelectorAll('form[name="reject"]').length};
}"""

CREATED = {}


def file_request(kind):
    """The requester files one pending request naming approver-1; returns (detail kind, id, HTTP status)."""
    if kind == "R":
        r = api("requester", J("batch-daily") + "/batch-control/submit", "POST",
                data=[("reason", REASON + " (run)"), ("approvers", APPROVER), ("DATE", "2026-10-08"), ("MODE", "full"),
                      ("SECRET", "r19-not-shown")])
        return "requests", lib.loc_id(r), r.status_code
    if kind == "G":
        r, gid = lib.grant_req("requester", JOB, ["CONFIGURE"], 15, REASON + " (window)", approvers=(APPROVER,))
        return "grants", gid, r.status_code
    r = api("requester", J(JOB) + "/batch-control-activation/submit", "POST",
            data=[("action", "ACTIVATE"), ("reason", REASON + " (activation)"), ("approvers", APPROVER)])
    return "activations", lib.loc_id(r), r.status_code


def page(kind, label):
    path, rid, status = file_request(kind)
    server = api("admin", f"/batch-control/{path}/{rid}/") if rid else None
    pending = bool(server is not None and server.status_code == 200 and ("Pending" in server.text or "PENDING" in server.text))
    if not check(kind, f"precondition: the requester files a pending {label} naming {APPROVER} (server lists it as pending)",
                 rid and pending, id=rid, submit_status=status, detail_status=server.status_code if server is not None else None):
        return
    CREATED[path] = rid
    s = Session(APPROVER)
    resp = s.go(f"/batch-control/{path}/{rid}/")
    p = s.page
    p.mouse.move(0, 0)  # nothing hovered
    m = p.evaluate(MEASURE)
    # A viewport screenshot at the bottom of the page, as the approver sees the two buttons: a full-page clip misplaces
    # core's sticky bottom button bars, and a red box around a red button would only confuse the reader.
    p.evaluate("() => window.scrollTo(0, document.documentElement.scrollHeight)")
    p.wait_for_timeout(300)
    shot = f"R19-{kind}-buttons.png"
    p.screenshot(path=str(lib.SHOTS / shot))
    rej, app, probe = m["reject"], m["approve"], m["probe"]
    colours = {"reject": rej and rej["color"], "approve": app and app["color"], "probe": probe}
    common = dict(page=f"/batch-control/{path}/{rid}/", http=resp.status if resp else None, shot=shot, **colours)
    check(kind, f"guard: the probe color: var(--destructive-color) resolves to its own colour on the {label} page "
          "(differs from the text around it)", probe and probe != m["around"], around=m["around"],
          destructive_var=m["destructiveVar"], theme=m["theme"], **common)
    if not check(kind, f"guard: the {label} page shows a Reject button in its reject form", rej is not None and rej["visible"],
                 reject_forms=m["rejectForms"], **common):
        s.done()
        return
    check(kind, f"the Reject button's computed colour on the {label} page equals the destructive colour (probe)",
          rej["color"] == probe, reject_classes=rej["classes"], reject_var_color=rej["varColor"], **common)
    check(kind, f"guard: the {label} page shows an Approve button whose computed colour is not the destructive colour",
          app is not None and app["visible"] and app["color"] != probe,
          approve_label=app and app["label"], approve_classes=app and app["classes"], **common)
    s.done()


def cleanup():
    out = {path: lib.decide("requester", path, rid, "cancel", "e2e-19 clean-up") for path, rid in CREATED.items()}
    note("cleanup", "the requester cancels the requests this driver filed", statuses=out)


if __name__ == "__main__":
    table = {"R": lambda: page("R", "run request"), "G": lambda: page("G", "permission window request"),
             "A": lambda: page("A", "activation request"), "C": cleanup}
    run_sections(WANT + "C", table)
