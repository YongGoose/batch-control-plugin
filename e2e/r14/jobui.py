"""e2e-12 part 3: Batch Control entry points on job, folder, view and build pages, per role and job UI.

usage: python jobui.py <new|classic> [role ...]
Every visible Batch Control entry (new job page: app bar buttons and the "More actions" menu; classic:
side panel tasks; both: notices/links in the main panel) is clicked on a fresh load and must do what its
label says:
  * dialog entries: Escape closes, Cancel closes, an empty submit stays in the dialog with field errors
    (input kept), Enter in a text field, then a complete submit lands on the new item's detail page;
  * navigating entries: the landing page's status (4xx/5xx is a defect unless it is the intended refusal
    page) and, for refusal pages, every link on them followed as the same user.
Rows: out/jobui.jsonl."""
import json, re, sys, time
from lib import Session, close, api, groovy, BASE
import lib

UI = sys.argv[1]
ROLES = sys.argv[2:] or ["admin", "requester", "reqonly", "approver-1", "manager", "nobc"]
FN = lambda f: "/job/" + "/job/".join(f.split("/")) + "/"  # noqa
PAGES = [FN("batch-daily"), FN("batch-cron"), FN("batch-pipeline"), FN("batch-upstream"), FN("team/sub/deep-job"),
         "/view/batch-view/job/batch-daily/", FN("ops"), FN("ops/mb"), FN("batch-daily") + "1/", FN("side/job-b")]
BC = re.compile(r"Request Run|Request Change Permission|Activation|activation|Hold|hold|Direct Build|Build Now|Build with Parameters|"
                r"Batch Control|approval|Approval|Rebuild|Replay|run request|Run request|permission window|Mark as reviewed", re.I)
UUID = r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"


def emit(row):
    row.update(ui=UI)
    lib.log("jobui", row)
    print(json.dumps(row)[:260])


class Nav:
    def __init__(self, s):
        self.last = None
        s.page.on("response", self._r)

    def _r(self, r):
        if r.request.is_navigation_request() and r.request.frame == r.request.frame.page.main_frame:
            self.last = (r.status, r.url.replace(BASE, ""))


def entries(s):
    """Visible Batch Control entries on the current page: (where, label)."""
    p = s.page
    out = []
    for loc, where in ((".jenkins-app-bar a, .jenkins-app-bar button, .app-page-body__header a, .app-page-body__header button", "appbar"),
                       ("#tasks a, #tasks button", "side"),
                       ("#main-panel a, #main-panel button", "main")):
        for el in p.locator(loc).all():
            try:
                if not el.is_visible():
                    continue
                t = re.sub(r"\s+", " ", el.inner_text() or el.get_attribute("aria-label") or el.get_attribute("title") or "").strip()
                h = el.get_attribute("href") or ""
            except Exception:
                continue
            if t and (BC.search(t) or "batch-control" in h) and (where, t) not in out:
                out.append((where, t))
    ov = p.locator("[data-testid=app-bar-overflow-button]")
    if ov.count() and ov.first.is_visible():
        ov.first.click(); p.wait_for_timeout(1000)
        items = [re.sub(r"\s+", " ", x).strip() for x in p.locator(".tippy-box a, .tippy-box button").all_inner_texts()]
        out += [("overflow", t) for t in items if t and BC.search(t)]
        out.append(("overflow-all", " | ".join(items)[:300]))
        p.keyboard.press("Escape"); p.wait_for_timeout(300)
    return out


def settle(p):
    """Wait until no dialog element is left (core removes it after its closing animation)."""
    try:
        p.wait_for_function("() => !document.querySelector('dialog')", timeout=4000)
    except Exception:
        pass
    p.wait_for_timeout(300)


def click_entry(s, where, label):
    p = s.page
    settle(p)
    if where == "overflow":
        p.locator("[data-testid=app-bar-overflow-button]").first.click(); p.wait_for_timeout(1000)
        el = p.locator(".tippy-box a, .tippy-box button", has_text=label).first
    else:
        sel = {"appbar": ".jenkins-app-bar a, .jenkins-app-bar button, .app-page-body__header a, .app-page-body__header button",
               "side": "#tasks a, #tasks button", "main": "#main-panel a, #main-panel button"}[where]
        el = p.locator(sel).filter(has_text=label).first
    el.click(timeout=8000)
    p.wait_for_timeout(1500)
    try:
        p.wait_for_load_state("load", timeout=10000)
    except Exception:
        pass


def fill_dialog(d, role):
    if d.locator("textarea[name=reason]").count():
        d.locator("textarea[name=reason]").fill(f"e2e-12 {UI} entry test by {role}")
    acts = d.locator("input[name=actions]")
    if acts.count() and not any(a.is_checked() for a in acts.all()):
        acts.first.locator("xpath=following-sibling::label").first.click()
    apps = d.locator("input[name=approvers]")
    if apps.count() and not any(a.is_checked() for a in apps.all()):
        apps.first.locator("xpath=following-sibling::label").first.click()


def submit_btn(d):
    return d.locator("button.jenkins-button--primary, button[type=submit]:not([data-id=cancel])").last


def test_dialog(s, nav, row, path, where, label, role):
    p = s.page
    d = p.locator("dialog[open]").first
    row["dialog_title"] = re.sub(r"\s+", " ", d.inner_text())[:90]
    row["url_unchanged"] = p.url.replace(BASE, "").split("?")[0] == path.split("?")[0]
    p.keyboard.press("Escape"); p.wait_for_timeout(500)
    row["escape_closes"] = p.locator("dialog[open]").count() == 0
    if not row["escape_closes"]:
        s.go(path)
    click_entry(s, where, label)
    d = p.locator("dialog[open]").first
    c = d.locator("button[data-id=cancel], button:has-text('Cancel')")
    row["has_cancel"] = c.count() > 0
    if c.count():
        c.first.click(); p.wait_for_timeout(500)
        row["cancel_closes"] = p.locator("dialog[open]").count() == 0
    else:
        p.keyboard.press("Escape"); p.wait_for_timeout(500)
    x = None
    # close (X)
    click_entry(s, where, label)
    d = p.locator("dialog[open]").first
    x = d.locator(".jenkins-dialog__close, .jenkins-dialog__title__button, button[aria-label=Close], button[title=Close]")
    if x.count() and x.first.is_visible():
        x.first.click(); p.wait_for_timeout(500)
        row["close_x_closes"] = p.locator("dialog[open]").count() == 0
    else:
        row["close_x_closes"] = "no X button"
        p.keyboard.press("Escape"); p.wait_for_timeout(500)
    # empty submit
    click_entry(s, where, label)
    d = p.locator("dialog[open]").first
    if not d.count():
        row["reopen_failed"] = True
        return
    is_request_form = d.locator("textarea[name=reason]").count() > 0
    row["request_form"] = is_request_form
    if not is_request_form:
        row["dialog_fields"] = [e.get_attribute("name") for e in d.locator("input, select, textarea").all()][:10]
        # core build parameters dialog (Direct Build): submit as is
        b = d.get_by_role("button", name=re.compile("^Build$"))
        if b.count():
            b.first.click(); p.wait_for_timeout(4000)
            try:
                p.wait_for_load_state("load", timeout=10000)
            except Exception:
                pass
            row["after_build"] = {"url": p.url.replace(BASE, ""), "dialog_open": p.locator("dialog[open]").count(),
                                  "h1": p.locator("h1").first.inner_text()[:80] if p.locator("h1").count() else None,
                                  "nav": nav.last}
        return
    for b in d.locator("input[name=approvers]").all():
        if b.is_checked():
            b.locator("xpath=following-sibling::label").first.click()
    d.locator("textarea[name=reason]").fill("")
    submit_btn(d).click(); p.wait_for_timeout(2500)
    d = p.locator("dialog[open]").first
    row["empty_submit_stays"] = d.count() > 0
    row["empty_submit_errors"] = [t.strip()[:90] for t in p.locator("dialog[open] .error, dialog[open] .jenkins-alert-danger").all_inner_texts() if t.strip()][:4]
    if not d.count():
        row["empty_submit_landing"] = (p.url.replace(BASE, ""), nav.last)
        return
    # Enter in a single-line text input (if any) must not submit an incomplete request silently
    txt = d.locator("input[type=text]:visible").first
    if txt.count():
        n_before = p.url
        txt.press("Enter"); p.wait_for_timeout(1500)
        row["enter_in_text"] = {"dialog_open": p.locator("dialog[open]").count(), "url": p.url.replace(BASE, "")}
        d = p.locator("dialog[open]").first
        if not d.count():
            return
    fill_dialog(d, role)
    submit_btn(d).click()
    try:
        p.wait_for_url(re.compile(r".*/batch-control/(requests|grants|activations)/" + UUID + "/$"), timeout=12000)
    except Exception:
        pass
    p.wait_for_load_state("load")
    row["submit_landing"] = p.url.replace(BASE, "")
    row["submit_nav"] = nav.last
    if p.locator("dialog[open]").count():
        row["submit_stuck"] = re.sub(r"\s+", " ", p.locator("dialog[open]").first.inner_text())[:400]
    m = re.search(r"/batch-control/(requests|grants|activations)/(" + UUID + ")/", p.url)
    if m:
        row["server_detail_status"] = api("admin", m.group(0)).status_code


def follow_links(s, row):
    """On a refusal/landing page, follow every main-panel link as the same user."""
    out = []
    for a in s.page.locator("#main-panel a[href]").all():
        h = a.get_attribute("href")
        if not h or h.startswith("#") or "logout" in h:
            continue
        url = a.evaluate("e => e.href")
        r = s.context.request.get(url, max_redirects=5)
        out.append((re.sub(r"\s+", " ", a.inner_text()).strip()[:40], url.replace(BASE, ""), r.status))
    row["links"] = out


def main():
    for role in ROLES:
        s = Session(role)
        nav = Nav(s)
        for path in PAGES:
            r = s.go(path)
            st = r.status if r else None
            if st != 200:
                emit({"role": role, "page": path, "kind": "page", "status": st})
                continue
            ents = entries(s)
            emit({"role": role, "page": path, "kind": "entries", "entries": ents})
            for where, label in ents:
                if where == "overflow-all":
                    continue
                row = {"role": role, "page": path, "kind": "entry", "where": where, "label": label}
                s.go(path)
                nc = len(s.console)
                nav.last = None
                try:
                    click_entry(s, where, label)
                    if s.page.locator("dialog[open]").count():
                        row["result"] = "dialog"
                        test_dialog(s, nav, row, path, where, label, role)
                    elif nav.last:
                        row["result"] = "navigated"
                        row["nav"] = nav.last
                        row["h1"] = s.page.locator("h1").first.inner_text()[:80] if s.page.locator("h1").count() else None
                        if nav.last[0] >= 400 or "batch-control" in s.page.url or re.search(r"/build|refus", s.page.url):
                            row["text"] = re.sub(r"\s+", " ", s.text())[:300]
                            follow_links(s, row)
                    elif s.page.locator(".tippy-box").count():
                        row["result"] = "menu"
                        row["menu"] = s.page.locator(".tippy-box").first.inner_text()[:150]
                    else:
                        row["result"] = "NOTHING"
                        row["notifications"] = [t for t in s.page.locator(".jenkins-notification, #notification-bar").all_inner_texts() if t.strip()]
                except Exception as e:  # noqa
                    row["exception"] = str(e)[:200]
                row["console"] = [c for c in s.console[nc:] if "MIME type" not in c][:3]
                emit(row)
        s.done()
    close()


if __name__ == "__main__":
    main()
