"""B: D-38a. Request without Job/Build; notice on detail page + e-mail; no notice with Build;
no Request -> no Request Run; folder-only Request."""
import re, time
from lib import Session, close, api, log, clean, mails, mail_body
NOTICE = "The requester does not have Build permission on this job."
res = {}

def submit(user, jobpath, tag, reason):
    s = Session(user)
    r = s.go(jobpath)
    s.shot("#main-panel", f"{tag}-01-job-page")
    appbar = [b.inner_text().strip() for b in s.page.locator(".jenkins-app-bar a, .jenkins-app-bar button").all() if b.inner_text().strip()]
    r2 = s.go(jobpath + "batch-control/")
    s.page.fill("textarea[name=reason]", reason)
    s.page.click("label[for=batch-control-approver-0], #batch-control-approver-0 + label")
    s.shot("form[name=batch-control-request]", f"{tag}-02-form")
    with s.page.expect_navigation() as nav:
        s.page.click("button[name=Submit]")
    s.page.wait_for_load_state("load")
    out = {"user": user, "job_page": r.status, "appbar": appbar, "form": r2.status, "submit": nav.value.status,
           "landed": s.page.url, "text": clean(s.page.locator("#main-panel").inner_html())[:400]}
    s.shot("#main-panel", f"{tag}-03-after-submit")
    m = re.search(r"/requests/(\d{8}-\d{6}-\w+)/", s.page.url)
    out["id"] = m.group(1) if m else None
    out["requester_sees_notice"] = NOTICE in s.text()
    s.done()
    return out

def approver_view(rid, tag):
    s = Session("approver-1")
    r = s.go(f"/batch-control/requests/{rid}/")
    t = s.text()
    has = NOTICE in t
    loc = s.page.get_by_text(NOTICE).first if has else "#main-panel"
    s.shot(loc if has else "#main-panel", f"{tag}-04-approver-detail", pad=60)
    s.done()
    return {"detail": r.status, "notice_on_detail": has}

def mail_for(rid):
    for _ in range(20):
        for m in mails():
            if rid in (m.get("Subject") or "") or rid in (m.get("Snippet") or ""):
                b = mail_body(m["ID"])
                return {"subject": m["Subject"], "to": [x["Address"] for x in m["To"]], "notice_in_mail": NOTICE in b.get("Text", ""), "text": b.get("Text", "")[:900]}
        time.sleep(1)
    # fall back: search body
    for m in mails():
        b = mail_body(m["ID"])
        if rid in b.get("Text", ""):
            return {"subject": m["Subject"], "to": [x["Address"] for x in m["To"]], "notice_in_mail": NOTICE in b.get("Text", ""), "text": b.get("Text", "")[:900]}
    return None

# B1 reqonly (Request + Read, no Build)
b1 = submit("reqonly", "/job/batch-daily/", "B1", "e2e-07 B1 reqonly without Build")
b1.update(approver_view(b1["id"], "B1")); b1["mail"] = mail_for(b1["id"]); res["B1_reqonly"] = b1
# B2 requester (with Build)
b2 = submit("requester", "/job/batch-daily/", "B2", "e2e-07 B2 requester with Build")
b2.update(approver_view(b2["id"], "B2")); b2["mail"] = mail_for(b2["id"]); res["B2_requester"] = b2
# B4 folderreq (Request on team/ only)
b4 = submit("folderreq", "/job/team/job/app-1/", "B4", "e2e-07 B4 folder-only Request")
b4.update(approver_view(b4["id"], "B4")); b4["mail"] = mail_for(b4["id"]); res["B4_folderreq_inside"] = b4
s = Session("folderreq")
r = s.go("/job/batch-daily/")
res["B4_folderreq_outside"] = {"job_page": r.status, "request_run_link": s.page.locator("a[href$='batch-control/'], a[href$='batch-control']").count(),
                               "form_url": api("folderreq", "/job/batch-daily/batch-control/").status_code,
                               "submit_url": api("folderreq", "/job/batch-daily/batch-control/submit", "POST", data={"reason": "x", "approvers": "approver-1"}).status_code}
s.shot("#main-panel", "B4-05-outside-job-page"); s.done()
# B3 nobc (Read+Build, no Request): not offered
s = Session("nobc")
r = s.go("/job/batch-daily/")
links = [a.get_attribute("href") for a in s.page.locator("a").all() if (a.get_attribute("href") or "").rstrip("/").endswith("batch-control")]
res["B3_nobc"] = {"job_page": r.status, "request_run_links": links, "text_request_run": "Request Run" in s.text(),
                  "form_url": api("nobc", "/job/batch-daily/batch-control/").status_code,
                  "notice": re.findall(r"[^.]*approv[^.]*\.", s.text())[:4]}
s.shot("#main-panel", "B3-01-nobc-job-page"); s.done()
close()
log("b_d38a", res)
for k, v in res.items():
    print(k, v)
