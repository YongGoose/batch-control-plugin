"""e2e-22 R4-02: a scripted build of an approval-required job is refused with a 4xx a script can detect, a browser keeps
the D-60 redirect to the pre-filled Request Run form (SPEC 6, D-60; the fix's rule for "script").

usage: python scripted_build.py [ASB]        rows: out/scripted_build.jsonl, shots: screenshots/run-22/R22-R4-02-*.png
Item r22-script (Freestyle, DATE string, MODE choice; approval-required: the lock it gets at creation under run control).
The requester holds Item/Build on it and an API token made for this run (core's endpoint; revoked at the end).

A  arrangement: the item, the requester's Build and Request, the token.
S  scripts (each: HTTP 4xx, no Location, the body names the approval requirement, nothing queued):
   - API token (Authorization), python-requests' default "Accept: */*", POST buildWithParameters;
   - the same on POST build with a json payload;
   - the same with the redirects followed (what `curl -fL` sees): the final answer is a 4xx, not the form's 200;
   - API token with "Accept: application/json";
   - a signed-in session cookie (login form, crumb of that session, no Authorization) with no Accept header at all.
B  browsers (each: 303 to <job>/batch-control/?p.DATE=<value>, nothing queued):
   - a signed-in browser context's request API, "Accept: */*" and no Authorization (the new job page's fetch);
   - the same context with "Accept: text/html,..." (a page navigation / form post);
   - the boundary of the rule: an API token with "Accept: text/html,*/*" counts as a browser too;
   then the browser follows the redirect: the Request Run form shows the submitted DATE (one screenshot).
The rule (ApprovalRequiredFailure.isScriptCaller): a script is a request whose Accept lacks text/html AND that sends no
Accept, or an Accept without */*, or an Authorization header."""
import sys

import requests

sys.path.insert(0, __file__.rsplit("/", 1)[0])
import lib  # noqa: E402
from lib import api, check, note, J, BASE, Session, text_of  # noqa: E402

lib.LOGNAME[0] = "scripted_build"
JOB = "r22-script"
USER = "requester"
TOKEN = {}


def nothing_queued(sec, what, nb0):
    nb, q = lib.next_build(JOB), lib.queue_items(JOB)
    check(sec, f"{what}: nothing queued, no build", nb == nb0 and not q, next_build_before=nb0, next_build=nb, queue=q)


def sec_A():
    created = lib.ensure_job(JOB, lib.job_xml("fs", params=lib.string_p("DATE", "2026-01-01")
                                              + lib.choice_p("MODE", ["full", "partial"]),
                                              shell='echo "DATE=$DATE MODE=$MODE"'))
    prop = lib.set_property(JOB, approval=True, timer=True, upstream=True)
    sw = lib.switches()
    perms = {p: lib.has_permission(USER, JOB, p) for p in ("hudson.model.Item.BUILD", "hudson.model.Item.READ")}
    perms["request"] = lib.has_permission(USER, "", f"{lib.BC}.security.BatchControlPermissions.REQUEST")
    s = requests.Session()
    s.auth = (USER, lib.pw(USER))
    c = s.get(BASE + "/crumbIssuer/api/json").json()
    r = s.post(BASE + f"/user/{USER}/descriptorByName/jenkins.security.ApiTokenProperty/generateNewToken",
               data={"newTokenName": "r22-scripted-build"}, headers={c["crumbRequestField"]: c["crumb"]})
    data = r.json().get("data", {}) if r.status_code == 200 else {}
    TOKEN.update(value=data.get("tokenValue"), uuid=data.get("tokenUuid"))
    ok = (sw["run_control"] is True and "approvalRequired=true" in prop and all(perms.values()) and TOKEN["value"])
    check("A", "arrangement: r22-script approval-required under run control, requester holds Read, Build and Request, "
          "an API token for requester", ok, created=created, prop=prop, switches=sw, perms=perms, token_status=r.status_code)


def refused_plainly(sec, what, r, nb0):
    body = r.text or ""
    loc = r.headers.get("Location", "")
    check(sec, f"{what}: HTTP 4xx without a redirect (a script can tell it failed)",
          400 <= r.status_code < 500 and not loc, status=r.status_code, location=loc.replace(BASE, ""),
          content_type=r.headers.get("Content-Type"))
    check(sec, f"{what}: the body names the approval requirement", "approv" in body.lower(), body=text_of(body)[:200])
    nothing_queued(sec, what, nb0)


def sec_S():
    if not TOKEN.get("value"):
        check("S", "precondition: an API token for requester (section A)", False)
        return
    auth = (USER, TOKEN["value"])
    nb0 = lib.next_build(JOB)
    url = BASE + J(JOB)
    r = requests.post(url + "/buildWithParameters", params={"DATE": "2026-10-09-s1"}, auth=auth, allow_redirects=False)
    refused_plainly("S", "API token, Accept */*, POST buildWithParameters", r, nb0)
    r = requests.post(url + "/build", data={"json": '{"parameter":[{"name":"DATE","value":"2026-10-09-s2"}]}'},
                      auth=auth, allow_redirects=False)
    refused_plainly("S", "API token, Accept */*, POST build (json payload)", r, nb0)
    r = requests.post(url + "/buildWithParameters", params={"DATE": "2026-10-09-s3"}, auth=auth)
    check("S", "API token, redirects followed (curl -fL): the final answer is a 4xx, not the HTML form's 200",
          400 <= r.status_code < 500 and not r.history, status=r.status_code,
          history=[h.status_code for h in r.history], final_url=r.url.replace(BASE, ""))
    r = requests.post(url + "/buildWithParameters", params={"DATE": "2026-10-09-s4"}, auth=auth,
                      headers={"Accept": "application/json"}, allow_redirects=False)
    refused_plainly("S", "API token, Accept application/json, POST buildWithParameters", r, nb0)
    # A signed-in session (the cookie of a browser login) without an Authorization header and without any Accept header.
    b = Session(USER, fresh=True)
    cookie = {"Cookie": "; ".join(f"{ck['name']}={ck['value']}" for ck in b.context.cookies())}
    b.done()
    s = requests.Session()
    who = s.get(BASE + "/whoAmI/api/json", headers=cookie).json().get("name")
    c = s.get(BASE + "/crumbIssuer/api/json", headers=cookie).json()
    req = requests.Request("POST", url + "/buildWithParameters", params={"DATE": "2026-10-09-s5"},
                           headers={**cookie, c["crumbRequestField"]: c["crumb"]})
    prepared = req.prepare()  # not merged with the session's defaults: no Accept, no Authorization
    r = s.send(prepared, allow_redirects=False)
    check("S", "precondition: the session is signed in as requester and the request carries neither Accept nor "
          "Authorization", who == USER and "Accept" not in prepared.headers and "Authorization" not in prepared.headers,
          who=who, sent_headers=sorted(prepared.headers.keys()))
    refused_plainly("S", "session cookie, no Accept header, POST buildWithParameters", r, nb0)


def sec_B():
    nb0 = lib.next_build(JOB)
    s = Session(USER, fresh=True)
    req = s.context.request
    c = req.get(BASE + "/crumbIssuer/api/json").json()
    crumb = {c["crumbRequestField"]: c["crumb"]}
    cases = [("browser context, Accept */*, no Authorization (the new job page's fetch)", "r22-b1", {"Accept": "*/*"}),
             ("browser context, Accept text/html (a page navigation or form post)", "r22-b2",
              {"Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"})]
    last = None
    for what, value, headers in cases:
        rb = req.post(f"{BASE}{J(JOB)}/buildWithParameters?DATE={value}", headers={**crumb, **headers}, max_redirects=0)
        loc = rb.headers.get("location", "")
        check("B", f"{what}: 303 to the pre-filled Request Run form (D-60)",
              rb.status == 303 and f"{J(JOB)}/batch-control/" in loc and f"p.DATE={value}" in loc,
              status=rb.status, location=loc.replace(BASE, ""))
        last = loc
    if TOKEN.get("value"):  # the boundary of the rule: an Accept naming text/html is a browser, credentials or not
        r = requests.post(f"{BASE}{J(JOB)}/buildWithParameters", params={"DATE": "r22-b3"}, auth=(USER, TOKEN["value"]),
                          headers={"Accept": "text/html,*/*;q=0.8"}, allow_redirects=False)
        loc = r.headers.get("Location", "")
        check("B", "API token with Accept text/html (an Accept naming text/html is a browser): 303 to the form",
              r.status_code == 303 and "p.DATE=r22-b3" in loc, status=r.status_code, location=loc.replace(BASE, ""))
    nothing_queued("B", "browser submissions", nb0)
    if last:
        s.page.goto(last if last.startswith("http") else BASE.split("/jenkins")[0] + last)
        s.page.wait_for_load_state("load")
        box = s.page.locator("div[name=parameter]:has(input[name=name][value='DATE']) input[name=value]").first
        shown = box.input_value() if box.count() else None
        s.shot("#main-panel", "R22-R4-02-browser-prefilled-form")
        check("B", "the browser lands on the Request Run form with the submitted DATE", shown == "r22-b2",
              url=s.page.url.replace(BASE, ""), date=shown)
    s.done()


def cleanup():
    if TOKEN.get("uuid"):
        r = api(USER, f"/user/{USER}/descriptorByName/jenkins.security.ApiTokenProperty/revoke", "POST",
                data={"tokenUuid": TOKEN["uuid"]})
        note("cleanup", "requester's r22 API token revoked", status=r.status_code)


if __name__ == "__main__":
    lib.run(sys.argv, {"A": sec_A, "S": sec_S, "B": sec_B}, cleanup)
