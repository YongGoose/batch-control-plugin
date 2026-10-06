"""e2e-16: a refused direct build leads to the prefilled Request Run form (D-60, SPEC 6) for the typed parameters of round 6:
a run parameter is carried (T-06-103, fixed in d15e31c), a password never, a file never (named by the notice, #115).

usage: python d60.py [RNFB]     rows: out/d60.jsonl, shots: R16-D60-*.png
R  classic job UI: requester (Item/Build + Request) opens Build with Parameters of the approval-required r16-run-d60
   (run parameter SRC on r16-src, string P, password SECRET), picks r16-src #1 (not the first listed), types P and a
   secret, presses Build: lands on the job's Request Run form with P filled in and SRC = r16-src#1 selected, the secret
   neither in the URL nor prefilled; nothing queued, no build, no request stored
N  new job page: the same through core's parameters dialog (coverage inventory G-M5): lands on the prefilled form
F  core file (r16-file): the file is not carried; the form names UPLOAD in its notice and asks for it again; NOTE carried
B  base64File (r16-stash): neither the Base64 text nor the content is in the URL (G-M3); the notice names B64"""
import re
import sys
from urllib.parse import unquote

sys.path.insert(0, __file__.rsplit("/", 1)[0])
import lib  # noqa: E402
from lib import (Session, api, gv, groovy, check, note, text_of, J, BASE, SECRET, run_sections, console_ok, requests_of,
                 next_build, make_file, param_box, wait_build)  # noqa: E402

lib.LOGNAME[0] = "d60"
WANT = sys.argv[1] if len(sys.argv) > 1 else "RNFB"
REQ = "requester"
JOB = "r16-run-d60"
# Core answers the GET of job/X/build (the classic "Build with Parameters" link) with HTTP 405 and still renders the
# parameters form: core behaviour (e2e-15 known_core), not a Batch Control response.
GET_BUILD_405 = ("/build?delay=0sec",)


def arrange():
    print(groovy(r'''
import jenkins.model.Jenkins
import hudson.model.*
def j = Jenkins.get()
def cl = j.pluginManager.uberClassLoader.loadClass('io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty')
def src = j.getItem('r16-src')
if (src == null) { src = j.createProject(FreeStyleProject, 'r16-src'); src.getBuildersList().add(new hudson.tasks.Shell('echo src')); src.save() }
// the source job runs freely (arrangement: it only has to have builds for the run parameter to list)
src.removeProperty(cl); src.addProperty(cl.getConstructor(boolean).newInstance(false)); src.save()
int guard = 0
while (src.getNextBuildNumber() <= 2 && guard++ < 3) { src.scheduleBuild2(0, new Cause.UserIdCause('admin')).get(120, java.util.concurrent.TimeUnit.SECONDS) }
def d = j.getItem('r16-run-d60')
if (d == null) {
  d = j.createProject(FreeStyleProject, 'r16-run-d60')
  d.addProperty(new ParametersDefinitionProperty([
    new RunParameterDefinition('SRC', 'r16-src', 'the source build', RunParameterDefinition.RunParameterFilter.ALL),
    new StringParameterDefinition('P', 'p0', 'a string', false),
    new PasswordParameterDefinition('SECRET', '', 'a secret')]))
  d.getBuildersList().add(new hudson.tasks.Shell('echo "SRC=$SRC P=$P"')); d.save()
}
return "src builds=" + src.builds.collect { it.number } + " d60 approvalRequired=" + d.getProperty(cl)?.approvalRequired +
  " params=" + d.getProperty(ParametersDefinitionProperty)?.parameterDefinitionNames
'''))


def set_flag(user, value):
    gv("""import jenkins.model.experimentalflags.UserExperimentalFlagsProperty
def u = hudson.model.User.getById('%s', true); def m = new HashMap(); m.put('new-job-page.flag', '%s')
u.addProperty(new UserExperimentalFlagsProperty(m)); u.save(); return 'ok'""" % (user, value))


def queue_of(job):
    r = api("admin", "/queue/api/json?tree=items[task[name,url]]")
    return [i for i in r.json().get("items", []) if (i.get("task") or {}).get("name") == job] if r.status_code == 200 else []


def baseline(job):
    return {"next": next_build(job), "requests": set(requests_of(job))}


def unchanged(job, b):
    nb, reqs, q = next_build(job), set(requests_of(job)), queue_of(job)
    return nb == b["next"] and not (reqs - b["requests"]) and not q, {"next_build": (b["next"], nb),
                                                                         "new_requests": list(reqs - b["requests"]), "queued": len(q)}


def landed_form(s, job):
    url = unquote(s.page.url)
    on_form = (J(job) + "/batch-control/") in url and "/requests/" not in url
    return url, on_form


def notice_text(s):
    n = s.page.locator("[data-batch-control-notice]")
    return " | ".join(re.sub(r"\s+", " ", t) for t in n.all_inner_texts()) if n.count() else ""


def press_build(s, scope):
    btn = scope.locator("button[name=Submit], button.jenkins-button--primary, button[type=submit]").filter(
        has_text=re.compile(r"^\s*Build")).first
    if not btn.count():
        btn = scope.locator("button[name=Submit], button.jenkins-button--primary").first
    try:
        with s.page.expect_navigation(timeout=20000):
            btn.click()
    except Exception as e:  # noqa
        note("-", "no navigation after Build", error=repr(e)[:200])
    s.page.wait_for_load_state("load")


def sec_R():
    set_flag(REQ, "false")
    try:
        b = baseline(JOB)
        s = Session(REQ)
        r = s.go(J(JOB) + "/build?delay=0sec")
        form = s.page.locator("form[name=parameters]").first
        had_form = form.count() > 0
        box = param_box(form, "SRC")
        sel = box.locator("select").first
        options = [(o.get_attribute("value"), o.inner_text().strip()) for o in sel.locator("option").all()]
        one = [v for v, t in options if re.search(r"#1\b", v or "") or re.search(r"#1\b", t)]
        sel.select_option(one[0])
        param_box(form, "P").locator("input[name=value]").first.fill("from-direct-build")
        sb = param_box(form, "SECRET")
        change = sb.locator("button.hidden-password-update-btn")
        if change.count() and change.first.is_visible():
            change.first.click()
        sb.locator("input[name=value]:visible").first.fill(SECRET)
        s.shot("#main-panel", "R16-D60-R-01-parameters")
        press_build(s, form)
        url, on_form = landed_form(s, JOB)
        f = s.page.locator("form[name=batch-control-request]").first
        p_val = param_box(f, "P").locator("input[name=value]").first.input_value() if f.count() else None
        src_sel = param_box(f, "SRC").locator("select").first if f.count() else None
        src_val = src_sel.evaluate("e => e.value") if src_sel is not None and src_sel.count() else None
        src_text = src_sel.evaluate("e => e.options[e.selectedIndex] ? e.options[e.selectedIndex].text : ''") if src_val else None
        sec_val = param_box(f, "SECRET").locator("input[name=value]").first.input_value() if f.count() else None
        s.shot("#main-panel", "R16-D60-R-02-prefilled")
        # the password field of a fresh, unprefilled form (core renders the definition's encrypted default there)
        s.go(J(JOB) + "/batch-control/")
        fresh = param_box(s.page.locator("form[name=batch-control-request]").first, "SECRET").locator("input[name=value]").first.input_value()
        console_ok("R", s, "classic parameters page -> prefilled form", expected=GET_BUILD_405)
        s.done()
        ok_state, state = unchanged(JOB, b)
        check("R", "classic UI: a refused Build with Parameters lands on the Request Run form with P filled in and the run "
              "parameter SRC = r16-src#1 selected (T-06-103), the secret neither in the URL nor prefilled; nothing queued, "
              "no build, no request stored",
              r is not None and had_form and on_form and p_val == "from-direct-build" and src_val and re.search(r"r16-src#1$", src_val)
              and sec_val == fresh and sec_val != SECRET and SECRET not in url and ok_state,
              options=options, landing=url.replace(BASE, "")[:300], P=p_val, SRC=src_val, SRC_text=src_text,
              secret_field_as_unprefilled=sec_val == fresh, **state)
    finally:
        set_flag(REQ, "true")


def sec_N():
    set_flag(REQ, "true")
    b = baseline(JOB)
    s = Session(REQ)
    s.go(J(JOB) + "/")
    entry = s.page.locator("#main-panel button, #main-panel a, .jenkins-app-bar button, .jenkins-app-bar a, #tasks a",
                           has_text=re.compile(r"Build with Parameters|Direct Build"))
    entries = [t.strip() for t in entry.all_inner_texts()]
    opened = False
    url, on_form, p_val = "", False, None
    if entry.count():
        entry.first.click()
        try:
            s.page.wait_for_selector("dialog[open] div[name=parameter]", timeout=10000)
            opened = True
        except Exception:
            opened = False
    if opened:
        d = s.page.locator("dialog[open]").first
        param_box(d, "P").locator("input[name=value]").first.fill("from-new-page")
        s.shot("dialog[open]", "R16-D60-N-01-dialog")
        press_build(s, d)
        s.page.wait_for_timeout(1500)
        url, on_form = landed_form(s, JOB)
        f = s.page.locator("form[name=batch-control-request]").first
        p_val = param_box(f, "P").locator("input[name=value]").first.input_value() if f.count() else None
        s.shot("#main-panel", "R16-D60-N-02-prefilled")
    console_ok("N", s, "new job page parameters dialog -> prefilled form")
    s.done()
    ok_state, state = unchanged(JOB, b)
    check("N", "new job page: core's parameters dialog of the approval-required job leads to the prefilled Request Run form "
          "(P filled in), never the classic parameters page; nothing queued or stored (G-M5)",
          opened and on_form and p_val == "from-new-page" and ok_state, entries=entries, dialog=opened,
          landing=url.replace(BASE, "")[:300], P=p_val, **state)


def classic_file(sec, job, files, params, expect_notice_names):
    set_flag(REQ, "false")
    try:
        b = baseline(job)
        s = Session(REQ)
        s.go(J(job) + "/build?delay=0sec")
        form = s.page.locator("form[name=parameters]").first
        for name, path in files.items():
            param_box(form, name).locator("input[type=file]").first.set_input_files(str(path))
        for name, value in params.items():
            pb = param_box(form, name)
            change = pb.locator("button.hidden-password-update-btn")
            if change.count() and change.first.is_visible():
                change.first.click()
            pb.locator("input[name=value]:visible").first.fill(value)
        press_build(s, form)
        url, on_form = landed_form(s, job)
        notice = notice_text(s)
        f = s.page.locator("form[name=batch-control-request]").first
        note_val = param_box(f, "NOTE").locator("input[name=value]").first.input_value() if f.count() else None
        s.shot("#main-panel", f"R16-D60-{sec}-prefilled")
        console_ok(sec, s, f"{job}: refused build with a file -> prefilled form", expected=GET_BUILD_405)
        s.done()
        ok_state, state = unchanged(job, b)
        return url, on_form, notice, note_val, ok_state, state
    finally:
        set_flag(REQ, "true")


def sec_F():
    up, ub = make_file("d60-upload.bin", 1500, "r16-d60-UPLOAD-CONTENT")
    url, on_form, notice, note_val, ok_state, state = classic_file("F", "r16-file", {"UPLOAD": up},
                                                                    {"NOTE": "note-d60", "SECRET": SECRET}, ["UPLOAD"])
    check("F", "core file: the refused build lands on the prefilled form with NOTE carried, the file not carried (no content, no "
          "name in the URL) and the notice naming UPLOAD and asking for it again; the secret not carried; nothing queued or stored",
          on_form and note_val == "note-d60" and "UPLOAD" in notice and "r16-d60-UPLOAD-CONTENT" not in url
          and "p.UPLOAD" not in url and SECRET not in url and ok_state,
          landing=url.replace(BASE, "")[:300], notice=notice[:300], NOTE=note_val, **state)


def sec_B():
    import base64 as _b
    st, sb = make_file("d60-stashed.bin", 900, "r16-d60-STASHED")
    b6, bb = make_file("d60-b64.bin", 600, "r16-d60-B64-CONTENT")
    url, on_form, notice, note_val, ok_state, state = classic_file("B", "r16-stash", {"STASHED": st, "B64": b6},
                                                                    {"NOTE": "note-b64", "SECRET": SECRET}, ["STASHED", "B64"])
    b64text = _b.b64encode(bb).decode()
    check("B", "stashedFile + base64File: the refused build lands on the prefilled form; neither the Base64 text nor the content "
          "is in the URL; the notice names both file parameters; NOTE carried; nothing queued or stored (G-M3)",
          on_form and note_val == "note-b64" and "B64" in notice and "STASHED" in notice and b64text[:24] not in url
          and "r16-d60-B64-CONTENT" not in url and "p.B64" not in url and ok_state,
          landing=url.replace(BASE, "")[:300], notice=notice[:300], NOTE=note_val, **state)


if __name__ == "__main__":
    arrange()
    run_sections(WANT, {"R": sec_R, "N": sec_N, "F": sec_F, "B": sec_B})
