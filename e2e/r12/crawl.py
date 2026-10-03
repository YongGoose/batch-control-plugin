"""e2e-12 part 1: scripted crawl of every Batch Control page and entry point, per role and per job UI.

usage: python crawl.py <role> <ui: new|classic>
For every page reached (start set + Batch Control links found on the way, two pages per URL pattern):
  * page status, JS console errors/page errors, failed sub-requests;
  * every control in the main panel, side panel and breadcrumbs: links (GET via the browser context,
    redirects followed, status recorded), forms (POST action probed with GET -> 405 expected, GET action
    fetched), dead links (href '#', '', 'javascript:' without a handler);
  * every non-submit button / dialog link: clicked on a fresh load of the page and classified
    (dialog opened, navigated, DOM changed, nothing). A dialog is closed again with Escape and, on a
    second open, with its Cancel button; both must close it.
Rows go to out/crawl.jsonl. Nothing is POSTed here (state-changing buttons are clicked in actions.py)."""
import json, re, sys, time
from urllib.parse import urljoin, urlparse
from lib import Session, close, BASE, groovy
import lib

ROLE, UI = sys.argv[1], sys.argv[2]
ids = json.loads((lib.HERE / "out" / "ids.json").read_text())
UUID = r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
KNOWN_CORE_CONSOLE = ("MIME type ('text/html')",)          # D-70 core DialogEvent, see e2e-11 UX 2
SKIP = re.compile(r"/logout|/exit|/restart|/safeRestart|/quietDown|/doDelete|/delete$|/script$|/cli/|/login\b")
FN = lambda f: "/job/" + "/job/".join(f.split("/")) + "/"  # noqa

START = ["/batch-control/", "/batch-control/requests/", "/batch-control/activations/", "/batch-control/grants/",
         "/batch-control/changes/", "/batch-control/dashboard/", "/batch-control/incidents/", "/batch-control/history/",
         "/batch-control/history/?kind=requests", "/batch-control/history/?kind=incidents", "/batch-control/history/?kind=changes",
         "/batch-control/history/summary", "/batch-control/grants/new", "/manage/",
         FN("batch-daily"), FN("batch-cron"), FN("batch-pipeline"), FN("batch-upstream"), FN("ops/job-a"),
         FN("team/sub/deep-job"), FN("ops/mb"), FN("ops"), FN("team"), FN("team/sub"), FN("side/job-b"), FN("fast"),
         "/view/batch-view/", "/view/batch-view/job/batch-daily/", FN("batch-daily") + "1/", FN("fast") + "60/",
         FN("batch-daily") + "batch-control/", FN("batch-cron") + "batch-control-activation/",
         FN("batch-failing") + "1/", FN("batch-failing")]
for k, v in ids.items():
    if isinstance(v, str) and re.fullmatch(UUID, v):
        kind = {"r": "requests", "a": "activations", "g": "grants"}[k[0]]
        START.append(f"/batch-control/{kind}/{v}/")

PATTERN_LIMIT = 2
if UI == "classic":
    # The /batch-control/ pages do not depend on the job page flag (crawled in the "new" pass);
    # the classic pass covers job, folder, view and build pages and their Batch Control sub-pages.
    START = [p for p in START if p.startswith(("/job/", "/view/"))]


def pattern(path):
    p = re.sub(UUID, "{id}", path)
    p = re.sub(r"/\d{8}-\d{6}-[a-z0-9]{6}", "/{cid}", p)
    p = re.sub(r"/\d+/", "/{n}/", p)
    p = re.sub(r"page=\d+", "page={n}", p)
    p = re.sub(r"month=[\d-]+", "month={m}", p)
    return p


def crawlable(path):
    if SKIP.search(path) or ".csv" in path:
        return False
    if re.search(r"/(submit|approve|reject|cancel|revoke|changeApprover|migrate|revert|markReviewed|acknowledge|resolve|comment|rerun|create)\b", path):
        return False
    if UI == "classic" and not path.startswith(("/job/", "/view/")):
        return False
    return "/batch-control" in path


def emit(row):
    row.update(role=ROLE, ui=UI)
    lib.log("crawl", row)


ELEMENTS_JS = r"""
() => {
  const regionOf = el => el.closest('#breadcrumbBar, .jenkins-breadcrumbs, [data-type=breadcrumb]') ? 'crumb'
     : el.closest('#side-panel') ? 'side' : el.closest('#main-panel, .app-page-body, main') ? 'main'
     : el.closest('header, #page-header, footer') ? 'chrome' : 'other';
  const vis = el => !!(el.offsetWidth || el.offsetHeight || el.getClientRects().length);
  const out = [];
  document.querySelectorAll('a, button, input[type=submit], input[type=button], [role=button], select').forEach((el, i) => {
    const r = regionOf(el);
    if (r === 'chrome' || r === 'other') return;
    const f = el.closest('form');
    out.push({i, tag: el.tagName.toLowerCase(), region: r, visible: vis(el),
      text: (el.innerText || el.value || el.getAttribute('aria-label') || el.title || '').trim().replace(/\s+/g,' ').slice(0,80),
      href: el.getAttribute('href'), anchor: !!(el.id || el.getAttribute('name')) && !el.hasAttribute('href') && !(el.innerText||'').trim(), abs: el.href || null, type: el.getAttribute('type'),
      onclick: el.getAttribute('onclick'), cls: el.className && el.className.baseVal === undefined ? el.className : '',
      data: Object.fromEntries(Object.entries(el.dataset || {}).slice(0, 8)),
      disabled: !!el.disabled,
      form: f ? {method: (f.getAttribute('method') || 'get').toLowerCase(), action: f.action, name: f.getAttribute('name')} : null});
  });
  return out;
}"""


def check_url(s, url, cache):
    if url in cache:
        return cache[url]
    try:
        r = s.context.request.get(url, max_redirects=5, timeout=20000)
        cache[url] = (r.status, r.url.replace(BASE, ""))
    except Exception as e:  # noqa
        cache[url] = (0, str(e)[:80])
    return cache[url]


def classify_click(s, path, el):
    """Fresh load, click element el (by index among the same selector), observe."""
    p = s.page
    s.go(path)
    loc = p.locator("a, button, input[type=submit], input[type=button], [role=button], select").nth(el["i"])
    if not loc.count() or not loc.is_visible():
        return "not-visible-on-reload", {}
    before_url = p.url
    before_html = len(p.content())
    n_console = len(s.console)
    try:
        loc.click(timeout=5000)
    except Exception as e:  # noqa
        return "click-failed", {"err": str(e)[:120]}
    p.wait_for_timeout(1200)
    try:
        p.wait_for_load_state("load", timeout=10000)
    except Exception:
        pass
    info = {"console": [c for c in s.console[n_console:] if not any(k in c for k in KNOWN_CORE_CONSOLE)][:3]}
    dlg = p.locator("dialog[open]")
    if dlg.count():
        info["dialog"] = re.sub(r"\s+", " ", dlg.first.inner_text())[:160]
        p.keyboard.press("Escape")
        p.wait_for_timeout(500)
        info["escape_closes"] = p.locator("dialog[open]").count() == 0
        if not info["escape_closes"]:
            # some dialogs (preventCloseOnOutsideClick) may still close on Escape; record either way
            pass
        # reopen and use Cancel
        if info["escape_closes"]:
            try:
                loc.click(timeout=5000)
                p.wait_for_timeout(1200)
            except Exception:
                pass
        d2 = p.locator("dialog[open]")
        if d2.count():
            c = d2.first.locator("button[data-id=cancel], button:has-text('Cancel'), .jenkins-dialog__close, button[aria-label='Close']")
            info["cancel_button"] = c.count()
            if c.count():
                c.first.click()
                p.wait_for_timeout(500)
                info["cancel_closes"] = p.locator("dialog[open]").count() == 0
        info["url_unchanged"] = p.url == before_url
        return "dialog", info
    if p.url != before_url:
        st = None
        return "navigated", dict(info, to=p.url.replace(BASE, ""))
    menus = p.locator(".tippy-box, .jenkins-dropdown, [data-tippy-root]")
    if menus.count():
        info["menu"] = re.sub(r"\s+", " ", menus.first.inner_text())[:200]
        p.keyboard.press("Escape")
        return "menu", info
    if abs(len(p.content()) - before_html) > 20:
        return "dom-changed", info
    return "NOTHING", info


def main():
    s = Session(ROLE)
    queue = list(START)
    seen, pat_count, cache, clicked = set(), {}, {}, set()
    while queue:
        path = queue.pop(0)
        if path in seen:
            continue
        seen.add(path)
        pt = pattern(path)
        if path not in START:
            pat_count[pt] = pat_count.get(pt, 0) + 1
            if pat_count[pt] > PATTERN_LIMIT:
                continue
        nc, nb = len(s.console), len(s.bad)
        try:
            r = s.go(path)
            status = r.status if r else None
        except Exception as e:  # noqa
            emit({"page": path, "kind": "page", "result": "load-error", "detail": str(e)[:200]})
            continue
        time.sleep(0.3)
        cons = [c for c in s.console[nc:] if not any(k in c for k in KNOWN_CORE_CONSOLE)]
        bad = [b for b in s.bad[nb:] if not b.endswith(path) and "favicon" not in b]
        h1 = s.page.locator("h1").first.inner_text().strip()[:80] if s.page.locator("h1").count() else ""
        emit({"page": path, "kind": "page", "status": status, "final": s.page.url.replace(BASE, ""), "h1": h1,
              "console": cons[:5], "bad_subrequests": bad[:5]})
        if status and status >= 400:
            continue
        els = s.page.evaluate(ELEMENTS_JS)
        is_bc = "/batch-control" in path
        for el in els:
            label = el["text"] or el.get("href") or ""
            relevant = is_bc or el["region"] == "crumb" or re.search(
                r"batch-control|Request|Activation|Approval|approval|Hold|Permission|Direct Build|Rebuild|Build",
                (el.get("href") or "") + " " + label + " " + json.dumps(el["data"]))
            if not relevant or (not el["visible"] and el["tag"] != "a") or el.get("anchor"):
                continue
            row = {"page": path, "pattern": pt, "region": el["region"], "visible": el["visible"], "tag": el["tag"], "label": label[:80],
                   "href": el.get("href"), "data": el["data"]}
            href = el.get("href")
            # --- links
            if el["tag"] == "a":
                if href is None or href.strip() in ("", "#") or href.startswith("javascript:"):
                    has_handler = bool(el["onclick"] or el["data"])
                    emit(dict(row, kind="link", result="handler-only" if has_handler else "DEAD-HREF"))
                    if has_handler:
                        key = (pt, label)
                        if key not in clicked:
                            clicked.add(key)
                            res, info = classify_click(s, path, el)
                            emit(dict(row, kind="click", result=res, detail=info))
                    continue
                url = el["abs"]
                if urlparse(url).netloc != urlparse(BASE).netloc:
                    emit(dict(row, kind="link", result="external", target=url))
                    continue
                tpath = url.replace(BASE, "")
                if SKIP.search(tpath):
                    continue
                st, fin = check_url(s, url, cache)
                emit(dict(row, kind="link", status=st, target=tpath, final=fin,
                          result="OK" if 200 <= st < 400 else "BROKEN"))
                if el["data"].get("dialogUrl") or el["data"].get("callback") or "task-link" in (el["cls"] or "") and "batch-control" in tpath:
                    key = (pt, label)
                    if key not in clicked:
                        clicked.add(key)
                        res, info = classify_click(s, path, el)
                        emit(dict(row, kind="click", result=res, detail=info))
                if st == 200 and crawlable(tpath.split("#")[0]):
                    queue.append(tpath.split("#")[0])
                continue
            # --- buttons
            f = el["form"]
            is_submit = el["tag"] == "input" or (el["tag"] == "button" and (el["type"] in (None, "submit")))
            if f and is_submit:
                act = f["action"]
                if f["method"] == "post":
                    st, fin = check_url(s, act, cache)
                    emit(dict(row, kind="post-form", target=act.replace(BASE, ""), status_get=st,
                              result="OK(405 on GET)" if st == 405 else ("BROKEN" if st in (404, 0) or st >= 500 else f"get={st}")))
                else:
                    emit(dict(row, kind="get-form", target=act.replace(BASE, ""), result="checked-in-actions"))
                continue
            if el["tag"] == "select":
                emit(dict(row, kind="select", result="present"))
                continue
            key = (pt, label, el["data"].get("href", "")[:0] or el["region"])
            if key in clicked:
                emit(dict(row, kind="click", result="same-as-checked"))
                continue
            clicked.add(key)
            res, info = classify_click(s, path, el)
            emit(dict(row, kind="click", result=res, detail=info))
    s.done()
    close()


if __name__ == "__main__":
    main()
