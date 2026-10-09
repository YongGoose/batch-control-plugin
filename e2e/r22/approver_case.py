"""e2e-22 R4-01 (a): the forms compare approver ids under the realm's id strategy, as the service does.

usage: python approver_case.py [AFS]     rows: out/approver_case.jsonl, shots: screenshots/run-22/R22-R4-01-*.png
The CI realm (Jenkins' own user database) uses IdStrategy.CaseInsensitive, so "Approver-1" and "approver-1" are one
user. The global approvers list approver-1 (JCasC); the job restriction of item r22-case (Freestyle, approval-required)
says "Approver-1". R4-01 (b), a user signing in with another letter case under a case-insensitive LDAP realm, needs the
LDAP profile, which no CI group runs: it is covered by the JenkinsRule tests T-08-194/195 of the fix
(GrantIdStrategyTest, a dummy realm with a case-insensitive id strategy).

A  arrangement: the item with the job restriction "Approver-1"; the realm's id strategy is case-insensitive; the
   global approvers hold approver-1 and approver-2.
F  the requester's Request Run form offers approver-1 (the global spelling), not approver-2 (not in the restriction),
   has its Submit button and no "No approvers are configured" warning (the bug: plain String.equals left none); the
   form submitted with approver-1 ticked files a PENDING request naming approver-1.
S  the service accepts a submission naming approver-1 (302 to the new request; the contrast the PoC showed), and that
   request's page offers the requester the Change Approvers form with approver-1 ticked, not the warning; the requester
   cancels the request at the end."""
import json
import re
import sys

sys.path.insert(0, __file__.rsplit("/", 1)[0])
import lib  # noqa: E402
from lib import check, note, J, Session  # noqa: E402

lib.LOGNAME[0] = "approver_case"
JOB = "r22-case"
USER = "requester"
RESTRICTION = "Approver-1"
NONE_WARNING = "No approvers are configured"
CREATED = []


def sec_A():
    created = lib.ensure_job(JOB, lib.job_xml("fs", shell='echo "r22-case ran"'))
    prop = lib.set_property(JOB, approval=True, timer=True, upstream=True)
    restriction = lib.gv(f"""def j = jenkins.model.Jenkins.get().getItemByFullName({json.dumps(JOB)})
def p = j.getProperty({lib.BC}.config.BatchControlJobProperty)
p.setJobApproversText({json.dumps(RESTRICTION)}); j.save()
return p.getJobApprovers().join(',')""")
    sw = lib.switches()
    ok = ("approvalRequired=true" in prop and restriction == RESTRICTION and sw["id_strategy"] == "CaseInsensitive"
          and "approver-1" in sw["approvers"] and "approver-2" in sw["approvers"])
    check("A", "arrangement: r22-case approval-required with the job restriction 'Approver-1', case-insensitive user "
          "ids, approver-1 and approver-2 global approvers", ok, created=created, prop=prop, restriction=restriction,
          switches=sw)


def offered(page, form):
    return page.locator(f"form[name={form}] input[name=approvers]").evaluate_all(
        "els => els.map(e => ({value: e.value, checked: e.checked}))")


def sec_F():
    s = Session(USER, fresh=True)
    s.go(J(JOB) + "/batch-control/")
    text = s.text()
    boxes = offered(s.page, "batch-control-request")
    values = [b["value"] for b in boxes]
    submit = s.page.locator("form[name=batch-control-request] button[name=Submit]").count()
    s.shot("#main-panel", "R22-R4-01-F-request-form")
    check("F", "the Request Run form offers approver-1 for the job restriction 'Approver-1'",
          any(v.lower() == "approver-1" for v in values), offered=boxes)
    check("F", "the form offers the global spelling 'approver-1'", "approver-1" in values, offered=values)
    check("F", "approver-2 (not in the job restriction) is not offered", "approver-2" not in values, offered=values)
    check("F", "the form can be submitted: a Submit button and no 'No approvers are configured' warning",
          submit == 1 and NONE_WARNING not in text, submit_buttons=submit, warning=NONE_WARNING in text)
    rid = None
    if submit == 1 and "approver-1" in values:
        form = s.page.locator("form[name=batch-control-request]")
        form.locator("textarea[name=reason]").fill("e2e-22 R4-01 through the form")
        lib.tick(form, "approvers", "approver-1")
        with s.page.expect_navigation(timeout=30000):
            form.locator("button[name=Submit]").click()
        s.page.wait_for_load_state("load")
        m = re.search(r"/batch-control/requests/(" + lib.UUID + ")/", s.page.url)
        rid = m.group(1) if m else None
    s.done()
    if rid:
        CREATED.append(rid)
    _, status, page = lib.request_state(rid) if rid else (None, None, "")
    check("F", "submitted with approver-1 ticked: the request is PENDING and names approver-1 (admin's view)",
          status == "PENDING" and "approver-1" in page, id=rid, status=status)


def sec_S():
    status, rid, r = lib.run_req(USER, JOB, "e2e-22 R4-01 approver case", approvers=("approver-1",))
    check("S", "the service accepts a request naming approver-1 (302 to the new request)", status == 302 and rid,
          status=status, id=rid)
    if not rid:
        return
    CREATED.append(rid)
    s = Session(USER, fresh=True)
    s.go(f"/batch-control/requests/{rid}/")
    text = s.text()
    boxes = offered(s.page, "changeApprover")
    s.shot("#main-panel", "R22-R4-01-S-change-approvers")
    s.done()
    check("S", "the request page offers the requester the Change Approvers form with approver-1 ticked, no warning",
          {"value": "approver-1", "checked": True} in boxes and NONE_WARNING not in text, offered=boxes,
          warning=NONE_WARNING in text)


def cleanup():
    for rid in CREATED:
        note("cleanup", f"requester cancels {rid}", status=lib.decide(USER, "requests", rid, "cancel", "e2e-22 cleanup"))


if __name__ == "__main__":
    lib.run(sys.argv, {"A": sec_A, "F": sec_F, "S": sec_S}, cleanup)
