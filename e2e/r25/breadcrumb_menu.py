"""e2e-25 #41: the breadcrumb (context) menu of the Batch Control page lists exactly the sections the tab bar shows to the
same viewer, also for a requester whose BatchControl/Request is held on one folder only (D-38b admits such a user to the
page through their own requests; the menu used to be built from Jenkins-level permissions and came back empty).

usage: python breadcrumb_menu.py [AMGN]   rows: out/breadcrumb_menu.jsonl, shots: screenshots/run-25/R25-41-*.png
Folder r25-team (matrix-auth folder property) holds r25-team/r25-job (Freestyle, approval-required). Accounts (local,
BC_OTHER_PASSWORD): r25-folder-req, r25-folder-act and r25-folder-new hold Overall/Read on Jenkins and Item/Read, Item/Build
and BatchControl/Request on r25-team only; r25-folder-req has a pending run request of its own, r25-folder-act a pending
ACTIVATE request of its own and no run request (both filed over REST), r25-folder-new has none. nobc (JCasC) holds no
Batch Control permission.

The menu is read twice: as JSON from GET /batch-control/contextMenu in the viewer's browser context (what core's breadcrumb
script fetches), and on the screen, by clicking core's dropdown indicator next to the "Batch Control" breadcrumb of
the user's second tab's page (on main the dropdown opens and says "No items" for the folder requesters). The tab bar is
nav[data-batch-control-tabs] on /batch-control/.

A  arrangement: the folder, its job, the three accounts, r25-folder-req's own pending run request and r25-folder-act's own
   pending ACTIVATE request (each filed only when there is none, so a rerun reuses it); facts: Request on the job, not on
   Jenkins, for the three accounts.
M  contract (#41): r25-folder-req (admitted through its run request) and r25-folder-act (admitted through its activation
   request, D-38b/D-38c) open /batch-control/; the context menu lists exactly the tab bar's sections (names and URLs, same
   order), as JSON and in the breadcrumb dropdown on screen. Guards: the tab bar shows r25-folder-req Overview, Run
   Requests and Activations and r25-folder-act Overview and Activations, and each tab answers 200 for the user.
G  guards: admin and approver-1: the menu lists exactly the tab bar's sections (admin: plus the Configuration entry, as
   today; approver-1: no Configuration entry); the dropdown on screen shows them.
N  guards: users who cannot open the page get no menu entries, as today: nobc and r25-folder-new (folder-level Request,
   no request of their own) get 404 for /batch-control/, and /batch-control/contextMenu lists nothing.
cleanup: cancels r25-folder-req's and r25-folder-act's pending requests (approver-1's inbox is left as it was)."""
import json
import re
import sys

sys.path.insert(0, __file__.rsplit("/", 1)[0])
import lib  # noqa: E402
from lib import api, check, note, Session  # noqa: E402

lib.LOGNAME[0] = "breadcrumb_menu"
FOLDER, JOB = "r25-team", "r25-team/r25-job"
REQ, ACT, NEW = "r25-folder-req", "r25-folder-act", "r25-folder-new"
APPROVER = "approver-1"
FOLDER_PERMS = ["hudson.model.Item.Read", "hudson.model.Item.Build", lib.PERM + "Request"]
S = {}

TABS_JS = """() => [...document.querySelectorAll('nav[data-batch-control-tabs] a')].map(a => {
  const c = a.cloneNode(true); c.querySelectorAll('.jenkins-badge, svg').forEach(b => b.remove());
  return {id: a.getAttribute('data-batch-control-tab'), name: c.textContent.replace(/\\s+/g, ' ').trim(),
          href: a.getAttribute('href')}; })"""
DROPDOWN_JS = """() => [...document.querySelectorAll('.tippy-box .jenkins-dropdown__item')].map(i => {
  const c = i.cloneNode(true); c.querySelectorAll('.jenkins-badge, svg, .jenkins-dropdown__item__icon').forEach(b => b.remove());
  return {name: c.textContent.replace(/\\s+/g, ' ').trim(), href: i.getAttribute('href')}; })"""


def path_of(url):
    """The context-relative path of a menu or tab URL (absolute or root-relative)."""
    u = url or ""
    if u.startswith(lib.ORIGIN):
        u = u[len(lib.ORIGIN):]
    return u.split("?")[0]


def pending_own(user=REQ, sub="requests/run"):
    """Ids of `user`'s PENDING requests in batch-control/<sub> (store read, arrangement only)."""
    out = lib.gv("""def d = new File(jenkins.model.Jenkins.get().rootDir, 'batch-control/""" + sub + """')
def ids = []
if (d.exists()) d.listFiles().findAll { it.name ==~ /[0-9a-f-]{36}\\.xml/ }.each { f ->
  def x = new XmlSlurper().parse(f); if (x.requester.text() == '""" + user + """' && x.status.text() == 'PENDING') ids << x.id.text() }
return ids.join(',')""")
    return [x for x in out.split(",") if x]


def sec_A():
    accounts = {u: lib.ensure_account(u, ["hudson.model.Hudson.Read"]) for u in (REQ, ACT, NEW)}
    made = lib.gv("""import jenkins.model.Jenkins
import hudson.model.*
import hudson.security.*
import org.jenkinsci.plugins.matrixauth.*
def j = Jenkins.get()
def F = com.cloudbees.hudson.plugins.folder.Folder
def f = j.getItem('""" + FOLDER + """') ?: j.createProject(F, '""" + FOLDER + """')
def uber = j.pluginManager.uberClassLoader
def cl = uber.loadClass('com.cloudbees.hudson.plugins.folder.properties.AuthorizationMatrixProperty')
def prop = f.getProperties().get(cl)
if (prop == null) { prop = cl.getConstructor(List).newInstance([]); f.addProperty(prop) }
['""" + REQ + "', '" + ACT + "', '" + NEW + """'].each { u -> """ + json.dumps(FOLDER_PERMS) + """.each { pid ->
  prop.add(Permission.fromId(pid), new PermissionEntry(AuthorizationType.USER, u)) } }
f.save()
if (f.getItem('r25-job') == null) { def p = f.createProject(FreeStyleProject, 'r25-job'); p.setDescription('e2e-25 #41'); p.save() }
return f.getItems()*.name.join(',')""")
    prop = lib.set_property(JOB, approval=True, timer=True, upstream=True)
    own = pending_own()
    filed = None
    if not own:
        st, rid, _ = lib.run_req(REQ, JOB, "e2e-25 #41 a request of the folder-level requester", approvers=(APPROVER,))
        filed = {"status": st, "id": rid}
        own = pending_own()
    S["own"] = own
    own_act = pending_own(ACT, "activation-requests")
    if not own_act:
        st, aid = lib.act_req(ACT, JOB, "ACTIVATE", "e2e-25 #41 an activation request of the folder-level requester",
                              approvers=(APPROVER,))
        filed = dict(filed or {}, activation={"status": st, "id": aid})
        own_act = pending_own(ACT, "activation-requests")
    S["own_act"] = own_act
    f = lib.facts("""import jenkins.model.Jenkins
import hudson.model.*
def j = Jenkins.get(); def job = j.getItemByFullName('""" + JOB + """')
def R = """ + lib.BC + """.security.BatchControlPermissions.REQUEST
def any = """ + lib.BC + """.ui.SectionAccess.anyPermission()
def perm = { uid, Closure c -> def u = User.getById(uid, false); u == null ? null : c(u.impersonate2()) }
def out = [:]
['""" + REQ + "', '" + ACT + "', '" + NEW + """', 'nobc'].each { uid ->
  out[uid] = [job_request: perm(uid) { a -> job.getACL().hasPermission2(a, R) },
              job_read: perm(uid) { a -> job.getACL().hasPermission2(a, Item.READ) },
              jenkins_any_bc: perm(uid) { a -> any.any { p -> j.getACL().hasPermission2(a, p) } }] }
return groovy.json.JsonOutput.toJson(out)""")
    S["facts"] = f
    ok = (all(f[u]["job_request"] is True and f[u]["jenkins_any_bc"] is False for u in (REQ, ACT, NEW))
          and f["nobc"]["jenkins_any_bc"] is False and bool(own) and bool(own_act) and "approvalRequired=true" in prop)
    check("A", "arrangement: r25-team/r25-job approval-required; r25-folder-req, r25-folder-act and r25-folder-new hold "
          "Request on the folder only (none on Jenkins); r25-folder-req has a pending run request of its own, r25-folder-act "
          "a pending ACTIVATE request; nobc holds no Batch Control permission", ok, folder_items=made, accounts=accounts,
          facts=f, own=own, own_activation=own_act, filed=filed, prop=prop)


def tabs_and_menu(user, shot_tag):
    """Opens /batch-control/ as `user` in a new context: (status, tabs, menu JSON status, menu items, dropdown items)."""
    s = Session(user, fresh=True)
    r = s.go("/batch-control/")
    status = r.status if r else None
    tabs = s.page.evaluate(TABS_JS) if status == 200 else []
    if status == 200:
        s.shot("nav[data-batch-control-tabs]", f"R25-41-{shot_tag}-1-tabs")
    m = s.context.request.get(lib.BASE + "/batch-control/contextMenu")
    try:
        items = [{"name": i.get("displayName"), "url": i.get("url")} for i in (m.json().get("items") or [])]
    except Exception:  # noqa: a 404 page is not JSON
        items = None
    dropdown = None
    if status == 200:
        # The dropdown sits on the "Batch Control" crumb of a section page: the user's second tab.
        s.go("/batch-control/" + (tabs[1]["href"].split("/batch-control/", 1)[1] if len(tabs) > 1 else "requests/"))
        dropdown = open_crumb_menu(s, f"R25-41-{shot_tag}-2-breadcrumb-menu")
    tab_status = {}
    for t in tabs:
        rr = s.context.request.get(lib.ORIGIN + t["href"]) if t.get("href") else None
        tab_status[t["name"]] = rr.status if rr else None
    s.done()
    return {"status": status, "tabs": tabs, "menu_status": m.status, "menu": items, "dropdown": dropdown,
            "tab_status": tab_status}


def open_crumb_menu(s, shot_name):
    """Opens the dropdown of the "Batch Control" breadcrumb (core's indicator next to the crumb) and returns its items
    ([] when the dropdown opened without items) and what the breadcrumb offered (crumb, data-has-menu, indicator).
    Screenshot of the breadcrumb bar and the dropdown."""
    p = s.page
    li = p.locator(".jenkins-breadcrumbs__list-item:has(> a:text-is('Batch Control'))").first
    info = {"crumb": li.count() > 0}
    if li.count():
        info["has_menu"] = li.get_attribute("data-has-menu")
        ind = li.locator(".dropdown-indicator, .jenkins-menu-dropdown-chevron")
        info["indicator"] = ind.count()
        if ind.count():
            li.hover()
            ind.first.click()
            try:
                p.locator(".tippy-box .jenkins-dropdown__item").first.wait_for(state="visible", timeout=5000)
            except Exception:  # noqa: an empty menu shows no item
                p.wait_for_timeout(1500)
            info["items"] = p.evaluate(DROPDOWN_JS)
            info["boxes"] = p.locator(".tippy-box").count()
    s.shot([".jenkins-breadcrumbs", ".tippy-box"] if info.get("boxes") else ".jenkins-breadcrumbs", shot_name)
    if info.get("boxes"):
        p.keyboard.press("Escape")
    return info


def sections(menu):
    """The menu entries that are Batch Control sections (under /batch-control/), as (name, path)."""
    return [(i["name"], path_of(i["url"])) for i in (menu or []) if "/batch-control/" in path_of(i["url"])]


def extras(menu):
    return [(i["name"], path_of(i["url"])) for i in (menu or []) if "/batch-control/" not in path_of(i["url"])]


def tab_pairs(tabs):
    return [(t["name"], path_of(t["href"])) for t in tabs]


def sec_M():
    for user, tag, want, how in ((REQ, "folder-requester", ["Overview", "Run Requests", "Activations"], "a run request"),
                                 (ACT, "folder-activation-requester", ["Overview", "Activations"], "an activation request")):
        v = tabs_and_menu(user, tag)
        names = [t["name"] for t in v["tabs"]]
        check("M", f"guard: {user} (Request on one folder only, {how} of its own) opens /batch-control/ and the tab bar "
              f"shows {', '.join(want)}", v["status"] == 200 and names == want, status=v["status"], tabs=names)
        check("M", f"guard: every tab the tab bar shows {user} answers 200", bool(v["tab_status"])
              and set(v["tab_status"].values()) == {200}, tab_status=v["tab_status"])
        check("M", f"#41 contract: GET /batch-control/contextMenu as {user} lists exactly the tab bar's sections (names "
              "and URLs, same order) and nothing else", v["menu_status"] == 200 and v["menu"] is not None
              and sections(v["menu"]) == tab_pairs(v["tabs"]) and not extras(v["menu"]),
              menu_status=v["menu_status"], menu=v["menu"], tabs=tab_pairs(v["tabs"]))
        dd = [(i["name"], path_of(i["href"])) for i in ((v["dropdown"] or {}).get("items") or [])]
        check("M", f"#41 contract (screen): the breadcrumb dropdown of 'Batch Control' shows {user} the tab bar's "
              "sections (names and URLs, same order)", dd == tab_pairs(v["tabs"]), dropdown=v["dropdown"],
              tabs=tab_pairs(v["tabs"]))


def sec_G():
    for user, tag in (("admin", "admin"), (APPROVER, "approver")):
        v = tabs_and_menu(user, tag)
        names = [t["name"] for t in v["tabs"]]
        check("G", f"guard: {user} opens /batch-control/ and sees the tab bar", v["status"] == 200 and len(names) >= 5,
              status=v["status"], tabs=names)
        ext = extras(v["menu"])
        want_ext = [("Configuration", ext[0][1])] if user == "admin" and ext else []
        check("G", f"guard: the context menu of {user} lists exactly the tab bar's sections"
              + (" plus the Configuration entry" if user == "admin" else " and no other entry"),
              v["menu_status"] == 200 and sections(v["menu"]) == tab_pairs(v["tabs"])
              and (len(ext) == 1 and ext[0][0] == "Configuration" if user == "admin" else not ext),
              menu=v["menu"], tabs=tab_pairs(v["tabs"]), extras=ext, expected_extras=want_ext)
        dd = [(i["name"], path_of(i["href"])) for i in ((v["dropdown"] or {}).get("items") or [])]
        check("G", f"guard (screen): the breadcrumb dropdown shows {user} the tab bar's sections",
              [x for x in dd if "/batch-control/" in x[1]] == tab_pairs(v["tabs"]), dropdown=v["dropdown"])


def sec_N():
    for user in ("nobc", NEW):
        s = Session(user, fresh=True)
        r = s.go("/batch-control/")
        status = r.status if r else None
        s.shot("#main-panel, body", f"R25-41-noaccess-{user}")
        m = s.context.request.get(lib.BASE + "/batch-control/contextMenu")
        try:
            items = m.json().get("items")
        except Exception:  # noqa
            items = None
        home = s.go("/")
        links = s.page.eval_on_selector_all("a[href]", "els => els.map(e => e.getAttribute('href'))") if home else []
        s.done()
        check("N", f"guard: {user} cannot open the Batch Control page (404) and gets no menu entries (contextMenu 404 or "
              "an empty list), as today", status == 404 and (m.status == 404 or items == []),
              page=status, menu_status=m.status, items=items)
        check("N", f"guard: the Jenkins home page offers {user} no link to batch-control/",
              not [h for h in links if re.search(r"/batch-control/?$", h or "")], links=[h for h in links if "batch" in (h or "")][:5])


def cleanup():
    done = {rid: api(REQ, f"/batch-control/requests/{rid}/cancel", "POST").status_code for rid in pending_own()}
    done.update({rid: api(ACT, f"/batch-control/activations/{rid}/cancel", "POST").status_code
                 for rid in pending_own(ACT, "activation-requests")})
    note("cleanup", "cancelled r25-folder-req's and r25-folder-act's pending requests", cancelled=done)


if __name__ == "__main__":
    lib.run(sys.argv, {"A": sec_A, "M": sec_M, "G": sec_G, "N": sec_N}, cleanup=cleanup)
