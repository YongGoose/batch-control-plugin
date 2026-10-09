"""e2e-23 R3-01 follow-up (record-write guards): a change record that cannot be written never breaks the operation it
records. With JENKINS_HOME/batch-control/changes/ unwritable: an approved ACTIVATE or HOLD is applied and answers
without a 500, the job's other pending requests end INVALIDATED and the requester is mailed; a job moved by a
non-administrator is held with the D-34 lock; a grant request while change control is off gets the 4xx refusal, not a
500; a createItem payload or a copy whose authorization property the guard removes answers the D-48 403.

usage: python record_writes.py [AVMCG]   rows: out/record_writes.jsonl, shots: screenshots/run-23/R23-RW-*.png
Items r23-rw-* (deleted and created again by A). The fault is arranged as r23/kill_switch.py arranges it: `docker exec
-u root` chmod 555 on changes/ and its directories, 444 on its files, just before the action under test; the exact modes
are restored right after it (also on failure, and in the cleanup). Each section first runs the same flow with a writable
store as its guard (REST), then the action under the fault through the browser where a browser path exists.

A  arrangement: run and change control on, changes/ writable; r23-rw-actg and r23-rw-act not activated; r23-rw-hold,
   r23-rw-prod/g and r23-rw-prod/x activated (the last two with every switch off and an allowed upstream job); nobc holds
   native Move and Delete on r23-rw-prod and Create on r23-rw-team (folder matrix entries), nothing on r23-rw-create;
   r23-rw-create/src carries an administrator-set authorization property (Configure for requester and nobc).
V  activation decisions: guard on r23-rw-actg (REST); then requester's three PENDING ACTIVATE requests on r23-rw-act
   (approver-1, approver-2, approver-2), changes/ unwritable, approver-1 presses "Approve Activation" on the request page:
   below 500, no error page, the page says APPROVED; stored APPROVED, the job page says activated, the other two
   INVALIDATED, an approval mail to the requester. The same with three HOLD requests on r23-rw-hold (on hold after).
M  the D-59a move hold: guard (REST, r23-rw-prod/g); then nobc moves r23-rw-prod/x to /r23-rw-team on the folders
   plugin's Move page with changes/ unwritable: below 400, no error page, the moved job's page says its timer and upstream
   triggers are blocked; the job is at r23-rw-team/x only, on hold, and its stored configuration carries approvalRequired,
   blockTimer and blockUpstream with no allowed upstream job.
C  the D-48 report of a creation (D-35c): requester's CREATE window on r23-rw-create; guard: createItem with a payload
   carrying an authorization property (REST); then the same POST with changes/ unwritable, and a copy of src through the
   New Item page ("Duplicate an existing item") with changes/ unwritable: each answers 403 with the D-48 message naming the new item (not
   200), the item exists without the entries in its stored config.xml, nobc cannot configure it, src keeps its property.
G  the closed Grants refusal: requester opens the Request Grant form while change control is on; the administrator turns
   change control off (writable store); guard: the same request over REST is refused with 4xx naming change control;
   then changes/ unwritable and requester submits the open form: 4xx (not 500) with the "Change control is off" message
   and no grant request stored. Change control is turned on again.

Not here: the log record (WARNING or SEVERE) of each failed append is only noted (from `docker logs`); the JenkinsRule
rows T-06a-66..68, T-08-200 and T-02-127/128 assert it. The other records the same fix guards are not exercised: the
ACTIVATED record of a job created while run control is off follows the saved state inside an item listener, so a user
sees the same outcome with or without the guard; the upgrade seeding needs a first start with existing jobs, and the
MARKER_REUSE_BLOCKED refusal a forged queue item, neither a path a user takes here."""
import re
import subprocess
import sys
import time

sys.path.insert(0, __file__.rsplit("/", 1)[0])
import lib  # noqa: E402
from lib import api, check, note, J, Session, text_of  # noqa: E402

lib.LOGNAME[0] = "record_writes"
USER, APPROVER, OTHER_APPROVER, MOVER = "requester", "approver-1", "approver-2", "nobc"
ACT_G, ACT, HOLD = "r23-rw-actg", "r23-rw-act", "r23-rw-hold"
PROD, TEAM, CREATE = "r23-rw-prod", "r23-rw-team", "r23-rw-create"
MOVE_G, MOVE_X = f"{PROD}/g", f"{PROD}/x"
SRC = f"{CREATE}/src"
CHANGES = f"{lib.JH}/batch-control/changes"
MODES = {}
ERROR_PAGE = re.compile(r"(?i)oops|a problem occurred|stack ?trace|exception")
D48 = ("were not kept", "Your other changes were saved")
CC_OFF = "Change control is off"
HELD = ("on hold", "not activated")  # the job page notice of a job that is not in service
PROPERTY = ("<hudson.security.AuthorizationMatrixProperty><inheritanceStrategy "
            "class='org.jenkinsci.plugins.matrixauth.inheritance.InheritParentStrategy'/>"
            f"<permission>USER:hudson.model.Item.Configure:{USER}</permission>"
            f"<permission>USER:hudson.model.Item.Configure:{MOVER}</permission>"
            "</hudson.security.AuthorizationMatrixProperty>")
PAYLOAD = ("<?xml version='1.1' encoding='UTF-8'?><project><description>e2e-23 record writes</description>"
           f"<properties>{PROPERTY}</properties><builders/><publishers/><buildWrappers/></project>")
FOLDER = ("<?xml version='1.1' encoding='UTF-8'?><com.cloudbees.hudson.plugins.folder.Folder><description>e2e-23 record "
          "writes</description><properties/><folderViews/><healthMetrics/></com.cloudbees.hudson.plugins.folder.Folder>")


# ---------------------------------------------------------------- the fault (as r23/kill_switch.py)
def modes():
    """path -> octal mode of changes/ and every file below it."""
    rc, out = lib.dexec("sh", "-c", f"find {CHANGES} -exec stat -c '%a %n' {{}} +")
    return {ln.split(" ", 1)[1]: ln.split(" ", 1)[0] for ln in out.splitlines() if " " in ln and ln.split(" ", 1)[0].isdigit()}


def break_changes():
    """changes/ and everything below it unwritable (root inside the container); True when a new file and an append to
    this month's file are both refused."""
    month = lib.gv("return java.time.YearMonth.now().toString()")
    lib.dexec("sh", "-c", f"mkdir -p {CHANGES} && touch {CHANGES}/{month}.jsonl")
    MODES.clear()
    MODES.update(modes())
    lib.dexec("sh", "-c", f"find {CHANGES} -type d -exec chmod 555 {{}} + ; find {CHANGES} -type f -exec chmod 444 {{}} +",
              user="root")
    rc_create, _ = lib.dexec("sh", "-c", f"touch {CHANGES}/r23-probe")
    rc_append, _ = lib.dexec("sh", "-c", f"printf '' >> {CHANGES}/{month}.jsonl")
    return rc_create != 0 and rc_append != 0


def restore_changes():
    """The exact modes recorded before the fault (and owner write on changes/ for anything new); True when writable."""
    if MODES:
        lib.dexec("sh", "-c", "; ".join(f"chmod {m} '{p}'" for p, m in MODES.items()), user="root")
        MODES.clear()
    lib.dexec("sh", "-c", f"chmod u+w {CHANGES}; rm -f {CHANGES}/r23-probe", user="root")
    rc, _ = lib.dexec("sh", "-c", f"touch {CHANGES}/r23-probe && rm -f {CHANGES}/r23-probe")
    return rc == 0


def faulted(sec, action):
    """Runs `action` with changes/ unwritable and restores it whatever happens; returns the action's value. Notes the
    plugin's WARNING/SEVERE log lines written meanwhile (the JenkinsRule rows assert them; here they are evidence)."""
    since = int(time.time()) - 1
    ok = break_changes()
    try:
        check(sec, "fault: changes/ refuses a new file and an append", ok)
        return action()
    finally:
        back = restore_changes()
        if not back:
            check(sec, "changes/ is writable again after the fault", back)
        p = subprocess.run(["docker", "logs", "--since", str(since), lib.CONTAINER], capture_output=True, text=True,
                           timeout=60)
        lines = [ln.split("\t", 1)[-1] for ln in (p.stdout + p.stderr).splitlines()
                 if re.search(r"\t(SEVERE|WARNING)\t(i\.j\.p\.b\.|io\.jenkins\.plugins\.batchcontrol\.)", ln)]
        note(sec, "plugin log lines at WARNING or SEVERE during the fault", count=len(lines),
             first=[ln[:240] for ln in lines[:3]])


# ---------------------------------------------------------------- server state
def set_change_control(on):
    return lib.gv(lib.CFG + f"try {{ cfg.setChangeControlEnabled({str(on).lower()}); return 'returned ' + "
                            f"cfg.isChangeControlEnabled() }} catch (Throwable t) {{ return 'THREW ' + t }}")


def act_status(rid):
    """The stored status of an activation request (activation-requests/<id>.xml)."""
    return lib.gv(f"""def f = new File(jenkins.model.Jenkins.get().rootDir, 'batch-control/activation-requests/{rid}.xml')
return f.exists() ? new XmlSlurper().parse(f).status.text() : 'missing'""")


def pending_activations():
    """Ids of the PENDING activation requests of the r23-rw-* items."""
    out = lib.gv("""def d = new File(jenkins.model.Jenkins.get().rootDir, 'batch-control/activation-requests')
return (d.listFiles() ?: []).findAll { it.name.endsWith('.xml') }.collect { new XmlSlurper().parse(it) }
  .findAll { it.status.text() == 'PENDING' && it.jobFullName.text().startsWith('r23-rw-') }.collect { it.id.text() }.join(',')""")
    return [x for x in out.split(",") if re.fullmatch(r"[0-9a-f-]{36}", x)]


def grant_requests():
    return int(lib.gv("""def d = new File(jenkins.model.Jenkins.get().rootDir, 'batch-control/requests/grant')
return (d.listFiles() ?: []).count { it.name.endsWith('.xml') && !it.name.endsWith('.values.xml') }"""))


def open_windows():
    """Ids of the active grants of the requester on r23-rw-* items."""
    out = lib.gv("""def now = System.currentTimeMillis()
def d = new File(jenkins.model.Jenkins.get().rootDir, 'batch-control/grants')
return (d.listFiles() ?: []).findAll { it.name.endsWith('.xml') }.collect { new XmlSlurper().parse(it) }
  .findAll { it.user.text() == 'requester' && it.revokedAtMillis.text() == '' &&
             ((it.expiresAtMillis.text() ?: '0') as long) > now && it.text().contains('r23-rw-') }.collect { it.id.text() }.join(',')""")
    return [x for x in out.split(",") if re.fullmatch(r"[0-9a-f-]{36}", x)]


def lock(job):
    """The Batch Control switches in the stored config.xml of `job` (None when the job does not exist)."""
    r = api("admin", J(job) + "/config.xml")
    if r.status_code != 200:
        return None
    x = r.text

    def tag(t):
        m = re.search(rf"<{t}>([^<]*)</{t}>", x)
        return m.group(1) if m else None
    allowed = re.search(r"<allowedUpstreamJobs>(.*?)</allowedUpstreamJobs>|<allowedUpstreamJobs/>", x, re.S)
    return {"approvalRequired": tag("approvalRequired"), "blockTimer": tag("blockTimer"),
            "blockUpstream": tag("blockUpstream"),
            "allowed": re.findall(r"<string>([^<]*)</string>", allowed.group(1) or "") if allowed else []}


def locked(state):
    return bool(state) and state["approvalRequired"] == state["blockTimer"] == state["blockUpstream"] == "true" \
        and not state["allowed"]


def entries(item):
    """The authorization entries for requester or nobc in the stored config.xml of `item` (None when it is missing)."""
    r = api("admin", J(item) + "/config.xml")
    if r.status_code != 200:
        return None
    return re.findall(rf"<permission>[^<]*:(?:{USER}|{MOVER})</permission>", r.text)


def mail_to_requester(rid, timeout=45):
    """The subject of a mail to the requester naming the request and an approval (waits for the dispatcher)."""
    found = lib.wait_until(lambda: [m["subject"] for m in lib.L19.mails(f"{USER}@e2e.local")
                                    if rid in m["subject"] and "approv" in m["subject"].lower()], timeout, 3)
    return found[0] if found else None


def screen(s, response):
    """(status, error text or None, page text) of a browser answer."""
    s.page.wait_for_load_state("load")
    t = re.sub(r"\s+", " ", s.page.locator("body").inner_text())
    err = ERROR_PAGE.search(t)
    return (response.status if response else None), (err.group(0) if err else None), t


def delete(item):
    if api("admin", J(item) + "/api/json").status_code == 200:
        api("admin", J(item) + "/doDelete", "POST")
    return api("admin", J(item) + "/api/json").status_code == 404


def create(item, xml):
    parent, _, leaf = item.rpartition("/")
    r = api("admin", (J(parent) if parent else "") + f"/createItem?name={leaf}", "POST", data=xml.encode("utf-8"),
            headers={"Content-Type": "application/xml"})
    return r.status_code


def activate(job):
    """An approved ACTIVATE request (requester -> approver-1) with a writable store; True when the job is activated."""
    st, rid = lib.act_req(USER, job, "ACTIVATE", "e2e-23 record writes: arrangement")
    if rid:
        lib.decide(APPROVER, "activations", rid, "approve", "e2e-23")
    return lib.activation(job) == "activated"


def three(job, action):
    """requester's three PENDING requests of `action` on `job`: the first for approver-1, the others for approver-2."""
    ids = [lib.act_req(USER, job, action, f"e2e-23 record writes {action} {i}", approvers=(a,))[1]
           for i, a in enumerate((APPROVER, OTHER_APPROVER, OTHER_APPROVER), 1)]
    return ids


# ---------------------------------------------------------------- sections
def sec_A():
    writable = restore_changes()
    on = set_change_control(True) if lib.gv(lib.CFG + "return cfg.isChangeControlEnabled()") != "true" else "already on"
    for rid in pending_activations():
        api("admin", f"/batch-control/activations/{rid}/cancel", "POST")
    for gid in open_windows():
        api("admin", f"/batch-control/grants/active/{gid}/revoke", "POST")
    gone = all(delete(i) for i in (ACT_G, ACT, HOLD, PROD, TEAM, CREATE))
    made = [create(ACT_G, lib.job_xml("fs", shell="echo r23")), create(ACT, lib.job_xml("fs", shell="echo r23")),
            create(HOLD, lib.job_xml("fs", shell="echo r23"))]
    made += [create(f, FOLDER) for f in (PROD, TEAM, CREATE)]
    made += [create(MOVE_G, lib.job_xml("fs", shell="echo r23")), create(MOVE_X, lib.job_xml("fs", shell="echo r23")),
             create(SRC, PAYLOAD)]
    live = {j: activate(j) for j in (HOLD, MOVE_G, MOVE_X)}
    props = {j: lib.set_property(j, approval=False, timer=False, upstream=False, allowed="batch-daily")
             for j in (MOVE_G, MOVE_X)}
    perms = lib.gv(f"""import hudson.model.*
import hudson.security.*
import org.jenkinsci.plugins.matrixauth.*
import com.cloudbees.hudson.plugins.folder.properties.AuthorizationMatrixProperty as FAMP
def j = jenkins.model.Jenkins.get()
def give = {{ f, perms -> def p = f.getProperties().get(FAMP); if (p == null) {{ p = new FAMP([:]); f.addProperty(p) }}
  perms.each {{ p.add(it, new PermissionEntry(AuthorizationType.USER, '{MOVER}')) }}; f.save() }}
give(j.getItemByFullName('{PROD}'), [Permission.fromId('hudson.model.Item.Move'), Item.DELETE])
give(j.getItemByFullName('{TEAM}'), [Item.CREATE])
def U = User.getById('{MOVER}', true).impersonate2()
def x = j.getItemByFullName('{MOVE_X}')
return 'move=' + x.getACL().hasPermission2(U, Permission.fromId('hudson.model.Item.Move')) + ' delete=' +
  x.getACL().hasPermission2(U, Item.DELETE) + ' create=' + j.getItemByFullName('{TEAM}').getACL().hasPermission2(U, Item.CREATE) +
  ' create-on-{CREATE}=' + j.getItemByFullName('{CREATE}').getACL().hasPermission2(U, Item.CREATE)""")
    states = {j: lib.activation(j) for j in (ACT_G, ACT)}
    src = entries(SRC)
    sw = lib.switches()
    check("A", "arrangement: run and change control on, changes/ writable; r23-rw-actg and r23-rw-act not activated; "
          "r23-rw-hold, r23-rw-prod/g and r23-rw-prod/x activated, the last two unlocked; nobc may move r23-rw-prod/x "
          "into r23-rw-team only; r23-rw-create/src carries Configure entries for requester and nobc",
          writable and sw["run_control"] and sw["change_control"] and gone and all(c == 200 for c in made)
          and all(v == "not activated" for v in states.values()) and all(live.values())
          and all("approvalRequired=false blockTimer=false blockUpstream=false" in p for p in props.values())
          and perms == f"move=true delete=true create=true create-on-{CREATE}=false" and src is not None and len(src) == 2,
          writable=writable, change_control=on, switches=sw, deleted=gone, created=made, not_activated=states,
          activated=live, props=props, nobc=perms, src_entries=src, container=lib.CONTAINER)


def approve_in_browser(sec, rid, shot):
    """approver-1 presses "Approve Activation" on the request page with changes/ unwritable."""
    s = Session(APPROVER, fresh=True)
    s.go(f"/batch-control/activations/{rid}/")
    form = s.page.locator("form[name=approve]")
    form.locator("textarea[name=comment]").fill("e2e-23 approved with the change log unwritable")
    s.shot("#main-panel", f"R23-RW-{shot}-1-request")

    def press():
        with s.page.expect_response(lambda r: r.request.method == "POST" and r.url.endswith(f"/{rid}/approve")) as ri:
            form.locator("button[name=Submit]").click()
        return ri.value
    post = faulted(sec, press)
    status, err, text = screen(s, post)
    s.shot("body", f"R23-RW-{shot}-2-after-approve")
    landed = s.page.url
    s.done()
    return status, err, text, landed


def decision(sec, job, action, shot):
    ids = three(job, action)
    check(sec, f"precondition: three PENDING {action} requests on {job}",
          all(ids) and all(act_status(i) == "PENDING" for i in ids), ids=ids)
    status, err, text, landed = approve_in_browser(sec, ids[0], shot)
    check(sec, f"screen: approving the {action} with changes/ unwritable answers below 500, the page is no error page "
          "and says APPROVED", status is not None and status < 500 and not err and "Status APPROVED" in text,
          http=status, error=err, landed=landed, text=text[:400])
    stored = [act_status(i) for i in ids]
    state = lib.activation(job)
    want = "activated" if action == "ACTIVATE" else "on hold"
    check(sec, f"the {action} is applied: the request is stored APPROVED and the job page says '{want}'",
          stored[0] == "APPROVED" and state == want, stored=stored[0], job_page=state)
    check(sec, "the job's other two PENDING requests are INVALIDATED (S-13-07)", stored[1:] == ["INVALIDATED"] * 2,
          others=stored[1:])
    subject = mail_to_requester(ids[0])
    check(sec, "the requester is mailed the approval", subject is not None, subject=subject)
    return ids


def sec_V():
    ids = three(ACT_G, "ACTIVATE")
    st = lib.decide(APPROVER, "activations", ids[0], "approve", "e2e-23 guard")
    stored = [act_status(i) for i in ids]
    subject = mail_to_requester(ids[0])
    check("V", "guard (writable store): approving the first of three ACTIVATE requests activates r23-rw-actg, invalidates "
          "the other two and mails the requester", st in (200, 302, 303) and stored == ["APPROVED"] + ["INVALIDATED"] * 2
          and lib.activation(ACT_G) == "activated" and subject is not None, approve=st, stored=stored, mail=subject)
    decision("V", ACT, "ACTIVATE", "V1")
    decision("V", HOLD, "HOLD", "V2")


def sec_M():
    r = api(MOVER, J(MOVE_G) + "/move/move", "POST", data={"destination": f"/{TEAM}"})
    g = f"{TEAM}/g"
    check("M", "guard (writable store): nobc's move of r23-rw-prod/g lands held and locked at r23-rw-team/g",
          r.status_code < 400 and lib.activation(g) in HELD and locked(lock(g)),
          http=r.status_code, job_page=lib.activation(g), lock=lock(g))
    s = Session(MOVER, fresh=True)
    s.go(J(MOVE_X) + "/move/")
    sel = s.page.locator("select[name=destination]")
    dest = [v for v in (o.get_attribute("value") for o in sel.locator("option").all()) if (v or "").rstrip("/") == f"/{TEAM}"]
    sel.select_option(dest[0] if dest else f"/{TEAM}")
    s.shot("#main-panel", "R23-RW-M-1-move-page")

    def press():
        with s.page.expect_response(lambda x: x.request.method == "POST" and x.url.endswith("/move/move")) as ri:
            s.page.locator("form[action=move] button[name=Submit]").click()
        return ri.value
    post = faulted("M", press)
    status, err, text = screen(s, post)
    s.shot("body", "R23-RW-M-2-after-move")
    landed = s.page.url
    s.done()
    x = f"{TEAM}/x"
    shown = [x for x in ("(blockTimer) is on", "(blockUpstream) is on") if x in text]
    check("M", "screen: the move with changes/ unwritable answers below 400, the page is no error page and the moved job's "
          "page says its timer and upstream triggers are blocked", status is not None and status < 400 and not err
          and landed.rstrip("/").endswith(J(f"{TEAM}/x")) and len(shown) == 2,
          http=status, error=err, landed=landed, shown=shown, text=text[:400])
    old = api("admin", J(MOVE_X) + "/api/json").status_code
    new = api("admin", J(x) + "/api/json").status_code
    state, stored = lib.activation(x), lock(x)
    check("M", "the move is complete and the job is held with the D-34 lock (D-59a): r23-rw-team/x only, not activated, "
          "approvalRequired, blockTimer and blockUpstream on and no allowed upstream job in its stored configuration",
          old == 404 and new == 200 and state in HELD and locked(stored),
          old=old, new=new, job_page=state, lock=stored)


def create_item(name):
    return api(USER, J(CREATE) + f"/createItem?name={name}", "POST", data=PAYLOAD.encode("utf-8"),
               headers={"Content-Type": "application/xml"})


def reported(status, text, item):
    return status == 403 and all(x in text for x in D48) and item in text


def removed(item):
    e = entries(item)
    nobc = api(MOVER, J(item) + "/configure").status_code
    return e == [] and nobc == 403, {"entries": e, "nobc_configure": nobc}


def sec_C():
    r, gid = lib.L19._l.grant_req(USER, CREATE, ["CREATE"], 30, "e2e-23 record writes", approvers=(APPROVER,))
    st = lib.decide(APPROVER, "grants", gid, "approve", "e2e-23") if gid else None
    page = api(USER, J(CREATE) + "/newJob").status_code
    check("C", "precondition: requester's CREATE window on r23-rw-create is open (New Item page 200)",
          gid is not None and st in (200, 302, 303) and page == 200, request=gid, approve=st, new_item=page)
    g = create_item("guard-new")
    ok, facts = removed(f"{CREATE}/guard-new")
    check("C", "guard (writable store): a createItem payload with an authorization property answers the D-48 403 naming "
          "the item, which exists without the entries", reported(g.status_code, text_of(g.text), f"{CREATE}/guard-new") and ok,
          http=g.status_code, text=text_of(g.text)[:300], **facts)

    r = faulted("C", lambda: create_item("new"))
    body = text_of(r.text)
    check("C", "createItem with a payload and changes/ unwritable answers the D-48 403 naming r23-rw-create/new (not 200, "
          "not 500)", reported(r.status_code, body, f"{CREATE}/new"), http=r.status_code, text=body[:400])
    ok, facts = removed(f"{CREATE}/new")
    check("C", "r23-rw-create/new exists without the payload's entries (stored config.xml) and nobc cannot configure it",
          ok, **facts)

    s = Session(USER, fresh=True)
    s.go(J(CREATE) + "/newJob")
    s.page.locator("input#name").fill("copy")
    s.page.get_by_text("Duplicate an existing item").click()  # Jenkins 2.568: reveals the "Copy from" field
    s.page.locator("input#from").fill("src")
    s.shot("#from", "R23-RW-C-1-new-item-copy", pad=90)

    def press():
        with s.page.expect_response(lambda x: x.request.method == "POST" and "/createItem" in x.url) as ri:
            s.page.locator("#ok-button").click()
        return ri.value
    post = faulted("C", press)
    status, err, text = screen(s, post)
    s.shot("body", "R23-RW-C-2-after-copy")
    title = s.page.title()
    s.done()
    check("C", "screen: copying src through the New Item page with changes/ unwritable answers the D-48 403 page "
          "naming r23-rw-create/copy (not 200, not 500)", reported(status, text, f"{CREATE}/copy") and not err,
          http=status, title=title, error=err, text=text[:400])
    ok, facts = removed(f"{CREATE}/copy")
    src = entries(SRC)
    check("C", "the copy exists without the source's entries and nobc cannot configure it; src keeps its property",
          ok and src is not None and len(src) == 2, src_entries=src, **facts)


def sec_G():
    s = Session(USER, fresh=True)
    s.go("/batch-control/grants/new")
    form = s.page.locator("form[name=createGrantRequest]")
    form.locator("input[name=scopeFullName]").fill(ACT)
    form.locator("#grant-action-configure").check(force=True)
    form.locator("select[name=durationMinutes]").select_option("15")
    form.locator("textarea[name=reason]").fill("e2e-23 record writes: change control is off")
    form.locator("#grant-approver-0").check(force=True)
    s.shot("#main-panel", "R23-RW-G-1-form-while-on")
    off = set_change_control(False)
    before = grant_requests()
    g = api(USER, "/batch-control/grants/create", "POST",
            data=[("scopeFullName", ACT), ("actions", "CONFIGURE"), ("durationMinutes", "15"),
                  ("reason", "e2e-23 guard"), ("approvers", APPROVER)])
    gt = text_of(g.text)
    check("G", "guard (writable store, change control off): the grant request is refused with 4xx naming change control, "
          "nothing stored", off == "returned false" and 400 <= g.status_code < 500 and CC_OFF in gt
          and grant_requests() == before, change_control=off, http=g.status_code, text=gt[:300], stored=grant_requests() - before)

    def press():
        with s.page.expect_response(lambda x: x.request.method == "POST" and x.url.endswith("/grants/create")) as ri:
            form.locator("button[name=Submit]").click()
        return ri.value
    post = faulted("G", press)
    status, err, text = screen(s, post)
    s.shot("body", "R23-RW-G-2-refused")
    s.done()
    check("G", "screen: submitting the open form with change control off and changes/ unwritable answers 4xx (not 500) "
          "with the 'Change control is off' message, and no grant request is stored",
          status is not None and 400 <= status < 500 and not err and CC_OFF in text and grant_requests() == before,
          http=status, error=err, text=text[:400], stored=grant_requests() - before)
    on = set_change_control(True)
    check("G", "change control is on again", on == "returned true", change_control=on)


def cleanup():
    ok = restore_changes()
    on = set_change_control(True) if lib.gv(lib.CFG + "return cfg.isChangeControlEnabled()") != "true" else "already on"
    left = pending_activations()
    for rid in left:
        api("admin", f"/batch-control/activations/{rid}/cancel", "POST")
    windows = open_windows()
    for gid in windows:
        api("admin", f"/batch-control/grants/active/{gid}/revoke", "POST")
    note("cleanup", "changes/ writable again, change control on, no pending request or open window of r23-rw-* left",
         writable=ok, change_control=on, cancelled=left, revoked=windows, left=pending_activations() + open_windows())
    if not ok:
        raise RuntimeError("changes/ is still unwritable")


if __name__ == "__main__":
    lib.run(sys.argv, {"A": sec_A, "V": sec_V, "M": sec_M, "C": sec_C, "G": sec_G}, cleanup)
