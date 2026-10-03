"""e2e-06 driver (hosting review 2026-10-03), Jenkins under the context path /jenkins.

Python port of ../extra/lib.mjs for machines without Node.js:
  python3 -m venv venv && venv/bin/pip install playwright requests
Playwright drives the installed Google Chrome (channel="chrome"), no browser download.

Rules as in ../extra: one new browser context per account (real login form), a
clipped, red-boxed screenshot per step (screenshots/run-6/<name>.png, never
committed), server state read separately with basic auth; the script console is
used only to arrange or read state.
"""
import json
import os
import pathlib

import requests
from playwright.sync_api import sync_playwright

HERE = pathlib.Path(__file__).resolve().parent
SHOTS = HERE.parent / "screenshots" / "run-6"
OUT = HERE / "out"
SHOTS.mkdir(parents=True, exist_ok=True)
OUT.mkdir(parents=True, exist_ok=True)
BASE = os.environ.get("BC_BASE", "http://localhost:8080/jenkins")

ENV = {}
for line in (HERE.parent / ".env").read_text().splitlines():
    if "=" in line and not line.startswith("#"):
        k, v = line.split("=", 1)
        ENV[k] = v
PW = {"admin": ENV["BC_ADMIN_PASSWORD"], "requester": ENV["BC_REQUESTER_PASSWORD"],
      "approver-1": ENV["BC_APPROVER_PASSWORD"], "approver-2": ENV["BC_APPROVER_PASSWORD"]}


def pw(user):
    return PW.get(user, ENV["BC_OTHER_PASSWORD"])


_pw = None
_browser = None


def browser():
    global _pw, _browser
    if _browser is None:
        _pw = sync_playwright().start()
        _browser = _pw.chromium.launch(channel="chrome")
    return _browser


def close():
    global _pw, _browser
    if _browser:
        _browser.close()
        _pw.stop()
    _browser = _pw = None


class Session:
    """A fresh browser context logged in as one account; records console errors and failed requests."""

    def __init__(self, user, dark=False):
        self.user = user
        self.context = browser().new_context(viewport={"width": 1280, "height": 900}, locale="en-US",
                                             color_scheme="dark" if dark else "light")
        self.page = self.context.new_page()
        self.page.set_default_timeout(20000)
        self.console = []
        self.bad = []
        self.page.on("console", lambda m: self.console.append(f"{m.type}: {m.text}") if m.type in ("error", "warning") else None)
        self.page.on("pageerror", lambda e: self.console.append(f"pageerror: {e}"))
        self.page.on("response", lambda r: self.bad.append(f"{r.status} {r.request.method} {r.url}") if r.status >= 400 else None)
        if user:
            p = self.page
            p.goto(f"{BASE}/login")
            p.fill("#j_username", user)
            p.fill('input[name="j_password"]', pw(user))
            p.click('button[name="Submit"], button[type="submit"]')
            p.wait_for_load_state("load")
            if "loginError" in p.url:
                raise RuntimeError(f"login failed for {user}")

    def go(self, path):
        r = self.page.goto(BASE + path)
        self.page.wait_for_load_state("load")
        return r

    def shot(self, target, name, pad=20):
        return shot(self.page, target, name, pad)

    def text(self):
        m = self.page.locator("#main-panel")
        return m.inner_text() if m.count() else self.page.locator("body").inner_text()

    def done(self):
        self.context.close()


def shot(page, target, name, pad=20):
    f = SHOTS / f"{name}.png"
    targets = target if isinstance(target, list) else [target]
    locs = [page.locator(t).first if isinstance(t, str) else t for t in targets]
    boxes = []
    for t in locs:
        try:
            if t.count() == 0:
                continue
            t.evaluate("el => { el.style.outline = '3px solid red'; el.style.outlineOffset = '2px'; }")
            b = t.bounding_box()
            if b:
                boxes.append(b)
        except Exception:
            pass
    if boxes:
        x0 = max(0, min(b["x"] for b in boxes) - pad)
        y0 = max(0, min(b["y"] for b in boxes) - pad)
        x1 = max(b["x"] + b["width"] for b in boxes) + pad
        y1 = max(b["y"] + b["height"] for b in boxes) + pad
        page.screenshot(path=str(f), full_page=True, clip={"x": x0, "y": y0, "width": max(1, x1 - x0), "height": max(1, min(y1 - y0, 3000))})
    else:
        page.screenshot(path=str(f), full_page=True)
    for t in locs:
        try:
            if t.count():
                t.evaluate("el => { el.style.outline = ''; }")
        except Exception:
            pass
    return f.name


def api(user, path, method="GET", data=None, crumb=True, **kw):
    s = requests.Session()
    s.auth = (user, pw(user))
    headers = kw.pop("headers", {})
    if method == "POST" and crumb:
        c = s.get(BASE + "/crumbIssuer/api/json")
        if c.status_code == 200:
            j = c.json()
            headers[j["crumbRequestField"]] = j["crumb"]
    return s.request(method, BASE + path, data=data, headers=headers, allow_redirects=False, **kw)


def groovy(script):
    r = api("admin", "/scriptText", "POST", data={"script": script})
    return r.text.strip()


def log(name, obj):
    with open(OUT / f"{name}.jsonl", "a") as fh:
        fh.write(json.dumps(obj, default=str) + "\n")
