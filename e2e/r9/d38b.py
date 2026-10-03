"""Item 1 (D-38b / DEF-05): opsreq holds BatchControl/Request + Item/Read only on folder ops/."""
import re
from lib import Session, close, api, log, clean
res = {}
# a request by someone else on a job opsreq cannot read (visibility check)
r = api("requester", "/job/batch-daily/batch-control/submit", "POST",
        data=[("reason", "e2e-09 other user's request"), ("approvers", "approver-1"), ("DATE", "2026-10-03"), ("MODE", "full")])
other = re.search(r"requests/([^/]+)/", r.headers.get("Location", ""))
res["other_request"] = {"status": r.status_code, "id": other.group(1) if other else None}

s = Session("opsreq")
r = s.go("/job/ops/job/job-a/")
res["job_page"] = r.status
s.shot("#main-panel", "D38B-01-job-page")
r = s.go("/job/ops/job/job-a/batch-control/")
res["form"] = r.status
s.page.fill("textarea[name=reason]", "e2e-09 D-38b folder-only Request")
s.page.click("#batch-control-approver-0 + label")
s.shot("form[name=batch-control-request]", "D38B-02-form")
with s.page.expect_navigation() as nav:
    s.page.click("button[name=Submit]")
s.page.wait_for_load_state("load")
res["submit_landed"] = {"status": nav.value.status, "url": s.page.url.replace("http://localhost:8080", ""), "text": clean(s.page.locator("#main-panel").inner_html())[:300]}
s.shot("#main-panel", "D38B-03-after-submit")
rid = re.search(r"/requests/([^/]+)/", s.page.url).group(1)
res["id"] = rid
# re-designate: approver-1 -> approver-2
f = s.page.locator("form[name=changeApprover]")
res["change_form"] = f.count()
if f.count():
    f.locator("#batch-control-approver-0 + label").click()
    f.locator("#batch-control-approver-1 + label").click()
    s.shot(f, "D38B-04-redesignate")
    with s.page.expect_navigation() as nav:
        f.locator("button[type=submit], button[name=Submit]").first.click()
    s.page.wait_for_load_state("load")
    res["redesignate"] = {"status": nav.value.status, "approvers_text": re.findall(r"Approvers?[^\n]{0,80}", s.text())[:3]}
    s.shot("#main-panel", "D38B-05-after-redesignate")
# root and sections
for p in ["/batch-control/", "/batch-control/requests/", "/batch-control/activations/", "/batch-control/grants/", "/batch-control/history/"]:
    r = s.go(p)
    res[f"GET {p}"] = r.status
    if p == "/batch-control/requests/":
        html = s.page.content()
        ids = sorted(set(re.findall(r"href=\"[^\"]*?(\d{8}-\d{6}-\w+)/\"", html)))
        res["visible_request_ids"] = ids
        res["sees_other"] = res["other_request"]["id"] in ids
        res["sees_own"] = rid in ids
        s.shot("#main-panel", "D38B-06-requests-section")
    if p == "/batch-control/":
        res["tabs"] = [t.strip() for t in s.page.locator("nav[data-batch-control-tabs] a").all_inner_texts()]
        s.shot("#main-panel", "D38B-06a-root")
res["other_detail_direct"] = s.go(f"/batch-control/requests/{res['other_request']['id']}/").status
# cancel with confirmation
s.go(f"/batch-control/requests/{rid}/")
s.page.get_by_text("Cancel Request", exact=True).click()
dlg = s.page.locator("dialog[open], .jenkins-dialog").first
dlg.wait_for()
res["cancel_dialog"] = re.sub(r"\s+", " ", dlg.inner_text())
s.shot(dlg, "D38B-07-cancel-dialog")
with s.page.expect_navigation():
    dlg.get_by_role("button", name="Cancel request").click()
s.page.wait_for_load_state("load")
res["after_cancel"] = re.findall(r"CANCELLED|Cancelled", s.text())[:2]
s.shot("#main-panel", "D38B-08-cancelled")
st = api("admin", f"/batch-control/requests/{rid}/").text
res["admin_sees_status"] = sorted(set(re.findall(r"PENDING|CANCELLED|APPROVED", clean(st))))
# activation inside ops
r = s.go("/job/ops/job/cron-a/batch-control-activation/?action=ACTIVATE")
res["act_form_inside"] = r.status
if r.status == 200:
    f = s.page.locator("form[name=batch-control-activation]")
    s.page.fill("textarea[name=reason]", "e2e-09 opsreq ACTIVATE ops/cron-a")
    s.page.click("#batch-control-approver-0 + label")
    with s.page.expect_navigation() as nav:
        f.locator("button[name=Submit]").click()
    s.page.wait_for_load_state("load")
    res["act_submit_inside"] = {"status": nav.value.status, "url": s.page.url.replace("http://localhost:8080", "")}
    s.shot("#main-panel", "D38B-09-activation-pending")
    aid = re.search(r"activations/([^/]+)/", s.page.url)
    res["act_id"] = aid.group(1) if aid else None
s.done()
# HOLD on ops/cron-a after approver approves activation
if res.get("act_id"):
    res["act_approve"] = api("approver-1", f"/batch-control/activations/{res['act_id']}/approve", "POST", data={"comment": "ok"}).status_code
    h = api("opsreq", "/job/ops/job/cron-a/batch-control-activation/submit", "POST",
            data=[("action", "HOLD"), ("reason", "e2e-09 opsreq HOLD"), ("approvers", "approver-1")])
    res["hold_submit_inside"] = {"status": h.status_code, "location": h.headers.get("Location")}
# outside: side/job-b (Read, no Request) and batch-daily (no Read)
for jp in ["/job/side/job/job-b", "/job/batch-daily"]:
    res[f"outside {jp}"] = {
        "job_page": api("opsreq", jp + "/").status_code,
        "request_form": api("opsreq", jp + "/batch-control/").status_code,
        "request_submit": api("opsreq", jp + "/batch-control/submit", "POST", data=[("reason", "x"), ("approvers", "approver-1")]).status_code,
        "activation_form": api("opsreq", jp + "/batch-control-activation/?action=ACTIVATE").status_code,
        "activation_submit": api("opsreq", jp + "/batch-control-activation/submit", "POST", data=[("action", "ACTIVATE"), ("reason", "x"), ("approvers", "approver-1")]).status_code,
    }
s = Session("opsreq")
s.go("/job/side/job/job-b/")
res["side_job_page_request_links"] = s.page.locator("a[href*='batch-control']").count()
s.shot("#main-panel", "D38B-10-side-job-page")
s.done()
# nobc: no permission and no requests -> 404 at the root
res["nobc_root"] = api("nobc", "/batch-control/").status_code
res["nobc_requests"] = api("nobc", "/batch-control/requests/").status_code
close()
log("d38b9", res)
for k, v in res.items():
    print(k, v)
