"""e2e-16 (hosting review round 6: D-71..D-73, D-72a/b) on top of ../r6/lib.py: screenshots in screenshots/run-16/
(never committed), rows in r16/out/<driver>.jsonl. Same rules as every e2e pass: one new browser context per
account (real login form), a red-boxed clipped screenshot per step, the server state read separately (REST with
basic auth, the store through the script console, which is used only to arrange or read state).

Verdict lines: every assertion prints "PASS {...}" or "FAIL {...}" (ci/shard.py fails a step on a FAIL line or a
non-zero exit). A section that raises prints one FAIL line and the driver goes on with the next section.

Written so that it does not depend on details D-74 changes (in progress when this was written): no assertion reads
the "No longer applies" label of a window or what happens to a window when an administrator renames its item; the
typed values are looked for in every file under batch-control/requests/run/ (the request file now, the separate
<id>.values.xml after D-74); the name restriction is filled only after Create is ticked (#107 shows the field only
then); parameter inputs are found by core's structure (div[name=parameter] holding input[name=name][value=<NAME>]),
not by the page that renders them (#111 valuePage)."""
import base64
import hashlib
import json
import pathlib
import re
import time
import importlib.util

HERE = pathlib.Path(__file__).resolve().parent
_spec = importlib.util.spec_from_file_location("r6lib", HERE.parent / "r6" / "lib.py")
_l = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_l)
_l.SHOTS = HERE.parent / "screenshots" / "run-16"
_l.OUT = HERE / "out"
_l.SHOTS.mkdir(parents=True, exist_ok=True)
_l.OUT.mkdir(parents=True, exist_ok=True)
Session, close, api, groovy, ENV, BASE, pw, shot = _l.Session, _l.close, _l.api, _l.groovy, _l.ENV, _l.BASE, _l.pw, _l.shot
SHOTS, OUT = _l.SHOTS, _l.OUT

UUID = r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
INCIDENT_ID = r"\d{8}-\d{6}-[a-z0-9]{6}"
SECRET = "r16-Secret-Value-7"   # the password value the approved builds must receive (never shown anywhere)
KNOWN_CONSOLE = ("MIME type ('text/html')", "Jumplist request failed", "Refused to execute script from")
N = {"pass": 0, "fail": 0}
LOGNAME = ["r16"]
KIND_RE = {"Folder": r"\.Folder$", "Freestyle": r"FreeStyleProject$", "Pipeline": r"WorkflowJob$",
           "Multibranch": r"WorkflowMultiBranchProject$"}


def J(full):
    """/job/a/job/b for the full name a/b."""
    return "".join(f"/job/{p}" for p in full.split("/"))


def log(obj):
    with open(OUT / f"{LOGNAME[0]}.jsonl", "a") as fh:
        fh.write(json.dumps(obj, default=str) + "\n")


def check(sec, step, ok, **kw):
    row = dict(sec=sec, step=step, ok=bool(ok), **kw)
    N["pass" if ok else "fail"] += 1
    log(row)
    print(("PASS " if ok else "FAIL ") + json.dumps(row, default=str)[:1500], flush=True)
    return bool(ok)


def note(sec, what, **kw):
    """An observation without a verdict (UX notes, values for the report)."""
    row = dict(sec=sec, note=what, **kw)
    log(row)
    print("NOTE " + json.dumps(row, default=str)[:1500], flush=True)


def run_sections(want, table):
    import sys as _sys
    for sec in want:
        try:
            table[sec]()
        except Exception as e:  # noqa
            check(sec, "section raised", False, error=repr(e)[:600])
    close()
    print(f"SUMMARY {LOGNAME[0]}", N, flush=True)
    _sys.exit(1 if N["fail"] else 0)


def console_ok(sec, s, what, expected=()):
    """No console error and no HTTP >= 400 besides the provoked ones (substrings of the response line)."""
    bad = [b for b in s.bad if not any(e in b for e in expected)]
    codes = {b.split()[0] for b in s.bad if b not in bad}
    cons = [c for c in s.console if not any(k in c for k in KNOWN_CONSOLE)
            and not any(f"status of {code}" in c for code in codes)
            and "reading 'replace'" not in c]  # core header.js breadcrumb overflow, e2e-13 U-1 (core)
    return check(sec, f"{what}: no console error, no HTTP>=400" + (" (besides the provoked responses)" if expected else ""),
                 not cons and not bad, console=cons[:4], bad=bad[:4])


def text_of(html):
    t = re.sub(r"<script.*?</script>", " ", html, flags=re.S)
    t = re.sub(r"<[^>]+>", " ", t)
    return re.sub(r"\s+", " ", t)


def gv(script):
    """Groovy through the script console; the value after the 'Result:' prefix (which may have no trailing space when
    the script returned an empty string)."""
    out = groovy(script)
    if out.startswith("Result:"):
        out = out[len("Result:"):]
    return out.strip()


def loc_id(r, pattern=UUID):
    m = re.search(pattern, r.headers.get("Location", "") or "")
    return m.group(0) if m else None


def tick(scope, name, value, on=True):
    """Checks (on) or unchecks a Jenkins checkbox by clicking its label, as a person does."""
    box = scope.locator(f"input[name={name}][value='{value}']")
    if box.is_checked() != on:
        box.locator("xpath=following-sibling::label").first.click()


# ---------------------------------------------------------------- grants
def grant_req(user, scope, actions, minutes=15, reason="e2e-16", approvers=("approver-1",), pattern=None, extra=()):
    data = [("scopeFullName", scope), ("durationMinutes", str(minutes)), ("reason", reason)]
    data += [("actions", a) for a in actions] + [("approvers", a) for a in approvers] + list(extra)
    if pattern is not None:
        data.append(("createNamePattern", pattern))
    r = api(user, "/batch-control/grants/create", "POST", data=data)
    return r, loc_id(r)


def decide(user, kind, rid, verb, comment="e2e-16"):
    return api(user, f"/batch-control/{kind}/{rid}/{verb}", "POST", data={"comment": comment}).status_code


def window(user, scope, actions, minutes=30, reason="e2e-16 window", pattern=None):
    r, gid = grant_req(user, scope, actions, minutes, reason, pattern=pattern)
    assert gid, (scope, actions, r.status_code, text_of(r.text)[:300])
    st = decide("approver-1", "grants", gid, "approve")
    assert st in (200, 302), ("approve", gid, st)
    return gid


def revoke_all(user):
    """Revoke every active grant of `user` (not revoked, not yet expired; Grant has no status field), so a check does
    not depend on windows left by earlier sections or runs."""
    script = ("def now=System.currentTimeMillis()\n"
              "def d=new File(jenkins.model.Jenkins.get().rootDir,'batch-control/grants'); def o=[]\n"
              "if(d.exists()) d.listFiles().findAll{it.name.endsWith('.xml')}.each{f-> def g=new XmlSlurper().parse(f)\n"
              "  if(g.user.text()==" + json.dumps(user) + " && g.revokedAtMillis.text()=='' && (g.expiresAtMillis.text() as long) > now) o<<g.id.text()}\n"
              "return o.join(',')")
    ids = [x for x in gv(script).split(",") if x]
    for gid in ids:
        api("manager", f"/batch-control/grants/{gid}/revoke", "POST")
    return ids


def grant_files(user=None):
    """The stored grant request and grant files of `user`: id -> (type, fullName, kind descriptor, actions, status)."""
    out = gv("""def root = new File(jenkins.model.Jenkins.get().rootDir, 'batch-control')
def rows = []
['grants', 'requests/grant'].each { d -> def f = new File(root, d); if (f.exists()) f.listFiles().findAll { it.name.endsWith('.xml') }.each { x ->
  def g = new XmlSlurper().parse(x)
  rows << [d, g.id.text(), g.user.text() ?: g.requester.text(), g.scope.type.text(), g.scope.fullName.text(), g.itemKind.descriptorId.text(),
           g.actions.children()*.text().join('+'), g.status.text()].join('|') } }
return rows.join('\\n')""")
    rows = [l.split("|") for l in out.splitlines() if l.count("|") >= 7]
    return [r for r in rows if user is None or r[2] == user]


# ---------------------------------------------------------------- change records
def changes(pred=None):
    """This month's change records (batch-control/changes/<yyyy-MM>.jsonl of the controller's clock)."""
    raw = gv("""def f = new File(jenkins.model.Jenkins.get().rootDir, 'batch-control/changes/' + java.time.YearMonth.now().toString() + '.jsonl')
return f.exists() ? f.text : ''""")
    rows = []
    for line in raw.splitlines():
        line = line.strip()
        if line.startswith("{"):
            try:
                rows.append(json.loads(line))
            except ValueError:
                pass
    return [r for r in rows if pred is None or pred(r)]


def violations(target, user=None):
    return [r for r in changes(lambda r: r.get("type") == "GRANT_VIOLATION" and r.get("target") == target
                               and (user is None or r.get("user") == user))]


# ---------------------------------------------------------------- run requests and builds
def file_counts():
    """Entries of core's and file-parameters' temporary file directories under JENKINS_HOME (D-72)."""
    out = gv("""def root = jenkins.model.Jenkins.get().rootDir
return ['fileParameterValueFiles', 'stashedFileParameterValueFiles'].collect { d -> def f = new File(root, d)
  d + '=' + (f.exists() ? (f.listFiles() ?: []).size() : 0) }.join(',')""")
    return {k: int(v) for k, v in (x.split("=") for x in out.split(",") if "=" in x)}


def store_contains(needle, sub="requests/run"):
    """Names of the files under batch-control/<sub> whose text contains `needle` (typed values, plaintext secrets)."""
    n = json.dumps(needle)
    return gv(f"""def d = new File(jenkins.model.Jenkins.get().rootDir, 'batch-control/{sub}')
def hits = []
if (d.exists()) d.eachFileRecurse {{ f -> if (f.isFile() && f.getText('UTF-8').contains({n})) hits << f.name }}
return hits.join(',')""")


def requests_of(job):
    """Stored run request ids of `job` (request files only, not a values file)."""
    out = gv(f"""def d = new File(jenkins.model.Jenkins.get().rootDir, 'batch-control/requests/run')
def ids = []
if (d.exists()) d.listFiles().findAll {{ it.name ==~ /[0-9a-f-]{{36}}\\.xml/ }}.each {{ f ->
  def x = new XmlSlurper().parse(f); if (x.jobFullName.text() == {json.dumps(job)}) ids << x.id.text() }}
return ids.join(',')""")
    return [x for x in out.split(",") if x]


def request_state(rid):
    r = api("admin", f"/batch-control/requests/{rid}/")
    t = text_of(r.text)
    m = re.search(r"Status (\w+)", t)
    return r.status_code, (m.group(1) if m else None), t


def builds(job):
    r = api("admin", J(job) + "/api/json?tree=builds[number,result,building]")
    return r.json().get("builds", []) if r.status_code == 200 else []


def wait_build(job, number, timeout=180):
    """Waits until build `number` of `job` has finished; returns (result, console text)."""
    t0 = time.time()
    while time.time() - t0 < timeout:
        r = api("admin", J(job) + f"/{number}/api/json?tree=result,building")
        if r.status_code == 200 and not r.json().get("building") and r.json().get("result"):
            return r.json()["result"], api("admin", J(job) + f"/{number}/consoleText").text
        time.sleep(2)
    return None, api("admin", J(job) + f"/{number}/consoleText").text


def next_build(job):
    return api("admin", J(job) + "/api/json?tree=nextBuildNumber").json()["nextBuildNumber"]


def sha(data):
    return hashlib.sha256(data).hexdigest()


def b64(data):
    return base64.b64encode(data).decode()


def make_file(name, size, marker):
    """A file in out/files/ whose content starts with a unique marker; returns (path, bytes)."""
    d = OUT / "files"
    d.mkdir(parents=True, exist_ok=True)
    head = f"{marker}\n".encode()
    body = (head + (marker.encode() + b"-0123456789abcdef\n") * (size // (len(marker) + 18) + 1))[:max(size, len(head))]
    p = d / name
    p.write_bytes(body)
    return p, body


def param_box(scope, name):
    """Core's structure of one parameter on a form: div[name=parameter] holding input[name=name][value=<name>]."""
    return scope.locator(f"div[name=parameter]:has(input[name=name][value='{name}'])").first


def fill_run_form(scope, reason, approver="approver-1", params=None, files=None):
    """Fills the Request Run form (page or dialog): reason, one approver, text/password values, files."""
    scope.locator("textarea[name=reason]").fill(reason)
    tick(scope, "approvers", approver)
    for name, value in (params or {}).items():
        box = param_box(scope, name)
        change = box.locator("button.hidden-password-update-btn")  # core's concealed password field
        if change.count() and change.first.is_visible():
            change.first.click()
        box.locator("input[name=value]:visible, textarea[name=value]:visible").first.fill(value)
    for name, path in (files or {}).items():
        box = param_box(scope, name)
        box.locator("input[type=file]").first.set_input_files(str(path))
