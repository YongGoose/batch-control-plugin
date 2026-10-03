"""Recon: new job page overflow menu (requests made, items rendered)."""
import re, sys, json
from lib import Session, close, SHOTS
s = Session(sys.argv[1])
for p in sys.argv[2:]:
    resp = []
    s.page.on("response", lambda r: resp.append(r) if r.request.resource_type in ("fetch", "xhr") else None)
    s.go(p); s.page.wait_for_timeout(800)
    resp.clear(); s.console.clear()
    s.page.locator("[data-testid=app-bar-overflow-button]").first.click(); s.page.wait_for_timeout(1500)
    print("====", p, [t.strip() for t in s.page.locator(".tippy-box").all_inner_texts()])
    for r in resp:
        try:
            body = r.text()
        except Exception:
            body = ""
        print(r.status, r.url, body[:1500])
    print("console", [c[:120] for c in s.console])
    s.page.keyboard.press("Escape")
s.done(); close()
