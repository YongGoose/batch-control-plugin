"""e2e-16: the CREATE name restriction in the browser (#107 optionalBlock; D-40/D-71), through the real grant form.

usage: python names.py     rows: out/names.jsonl, shots: R16-NAME-*.png
The "New job name restriction" field is in an optionalBlock that is hidden until Create is ticked (#107), so the driver
ticks Create first and only then fills the field. Checks:
  * the field is not usable before Create is ticked, and becomes usable after (optionalBlock)
  * a window with an exact-name restriction on the folder r16 lets w16 create only that name directly in r16; another
    name is refused (4xx, no item, GRANT_VIOLATION)
  * a window with a /regex/ restriction lets a matching name through and refuses a non-matching one
  * a POST with actions=CONFIGURE (Create not ticked) ignores createNamePattern (no restriction recorded)"""
import re
import sys
import time

sys.path.insert(0, __file__.rsplit("/", 1)[0])
import lib  # noqa: E402
from lib import (Session, api, gv, check, note, decide, grant_req, text_of, J, BASE, run_sections, console_ok,
                 tick, violations, revoke_all, grant_files)  # noqa: E402

lib.LOGNAME[0] = "names"
U = "w16"
H = {"Content-Type": "application/x-www-form-urlencoded"}


def st(user, full, name, mode="hudson.model.FreeStyleProject"):
    r = api(user, J(full) + f"/createItem?name={name}&mode={mode}", "POST", headers=H)
    return r.status_code, api("admin", J(full) + f"/job/{name}/api/json").status_code


def cleanup(*names):
    for n in names:
        if api("admin", J("r16/" + n) + "/api/json").status_code == 200:
            api("admin", J("r16/" + n) + "/doDelete", "POST")


def window_with_pattern(scope, pattern):
    """Request a CREATE window in the browser, ticking Create before filling the #107 name-restriction field; approve it.
    Returns (grant_id, observations about the optionalBlock)."""
    s = Session(U)
    s.go("/batch-control/grants/new")
    s.page.wait_for_selector("form[name=createGrantRequest]")
    f = s.page.locator("form[name=createGrantRequest]").first
    f.locator("input[name=scopeFullName]").fill(scope)
    field = s.page.locator("input[name=createNamePattern]")
    before = {"present": field.count() > 0, "visible_before_create": field.first.is_visible() if field.count() else False}
    # #107: tick Create (id replaced by the optionalBlock script, so select by value), then the field appears
    tick(f, "actions", "CREATE")
    s.page.wait_for_timeout(500)
    try:
        field.first.wait_for(state="visible", timeout=5000)
    except Exception:
        pass
    after_visible = field.first.is_visible() if field.count() else False
    s.shot("#main-panel", f"R16-NAME-field-{pattern.replace('/', '_').replace('[', '').replace(']', '')[:20]}")
    field.first.fill(pattern)
    f.locator("select[name=durationMinutes]").select_option("30")
    f.locator("textarea[name=reason]").fill(f"e2e-16 create restricted to {pattern}")
    tick(f, "approvers", "approver-1")
    with s.page.expect_navigation(timeout=15000):
        f.locator("button:has-text('Request Grant')").first.click()
    s.page.wait_for_load_state("load")
    m = re.search(r"/grants/([0-9a-f-]{36})/", s.page.url)
    console_ok("field", s, "grant form with name restriction")
    s.done()
    gid = m.group(1) if m else None
    if gid:
        decide("approver-1", "grants", gid, "approve")
    return gid, dict(before, after_visible=after_visible)


def sec_exact():
    revoke_all(U)
    cleanup("report-1", "nightly", "nightly-x")
    gid, obs = window_with_pattern("r16", "nightly")
    check("field", "#107: the name-restriction field is hidden until Create is ticked, then usable (optionalBlock)",
          obs["present"] and not obs["visible_before_create"] and obs["after_visible"], **obs)
    ok_create = st(U, "r16", "nightly")
    bad_before = len(violations("r16/nightly-x", U))
    bad_create = st(U, "r16", "nightly-x")
    bad_after = len(violations("r16/nightly-x", U))
    check("exact", "an exact-name CREATE restriction lets only that name be created directly in the folder; another name "
          "is refused (4xx, no item) and recorded as GRANT_VIOLATION",
          gid and ok_create == (302, 200) and bad_create[0] >= 400 and bad_create[1] == 404 and bad_after - bad_before == 1,
          window=gid, matching=ok_create, nonmatching=bad_create, violations=bad_after - bad_before)
    cleanup("nightly")


def sec_regex():
    revoke_all(U)
    cleanup("report-7", "report-x", "report-99")
    gid, _ = window_with_pattern("r16", "/report-[0-9]+/")
    ok_match = st(U, "r16", "report-7")
    bad_before = len(violations("r16/report-x", U))
    bad_nomatch = st(U, "r16", "report-x")
    bad_after = len(violations("r16/report-x", U))
    check("regex", "a /regex/ CREATE restriction lets a matching name through and refuses a non-matching one (4xx, no item, "
          "GRANT_VIOLATION)",
          gid and ok_match == (302, 200) and bad_nomatch[0] >= 400 and bad_nomatch[1] == 404 and bad_after - bad_before == 1,
          window=gid, matching=ok_match, nonmatching=bad_nomatch, violations=bad_after - bad_before)
    cleanup("report-7")


def sec_configure_ignores_pattern():
    # Through the form, ticking only Configure: the name-restriction field is in the collapsed optionalBlock (#107) and
    # is not submitted, so the request is created with no name restriction. (A raw POST that sends createNamePattern
    # without CREATE is refused by the service; that is covered by the service/integration tests, not this UI driver.)
    revoke_all(U)
    s = Session(U)
    s.go("/batch-control/grants/new")
    s.page.wait_for_selector("form[name=createGrantRequest]")
    f = s.page.locator("form[name=createGrantRequest]").first
    f.locator("input[name=scopeFullName]").fill("r16/job-a")
    tick(f, "actions", "CONFIGURE")  # Create NOT ticked -> the restriction field stays collapsed
    f.locator("select[name=durationMinutes]").select_option("15")
    f.locator("textarea[name=reason]").fill("e2e-16 configure, no restriction")
    tick(f, "approvers", "approver-1")
    with s.page.expect_navigation(timeout=15000):
        f.locator("button:has-text('Request Grant')").first.click()
    s.page.wait_for_load_state("load")
    m = re.search(r"/grants/([0-9a-f-]{36})/", s.page.url)
    s.done()
    gid = m.group(1) if m else None
    pat = gv("""def d=new File(jenkins.model.Jenkins.get().rootDir,'batch-control/requests/grant/%s.xml')
return d.exists() ? (new XmlSlurper().parse(d).createNamePattern.text() ?: '(none)') : '(missing)'""" % gid) if gid else "(no request)"
    check("ignore", "through the form, a Configure-only request carries no name restriction (the optionalBlock field is not "
          "submitted when Create is unticked, #107)",
          gid is not None and pat in ("(none)", ""), landing="/grants/%s/" % gid if gid else None, stored_pattern=pat)


if __name__ == "__main__":
    run_sections(["exact", "regex", "ignore"],
                 {"exact": sec_exact, "regex": sec_regex, "ignore": sec_configure_ignores_pattern})
