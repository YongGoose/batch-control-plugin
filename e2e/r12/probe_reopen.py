"""Reopen a request dialog after closing it (Escape, then Cancel): is the page still clickable?"""
from lib import Session, close, BASE
import lib
s = Session("requester")
s.go("/job/batch-daily/")
btn = s.page.locator("#main-panel button", has_text="Request Run").first
for how in ["escape", "cancel", "x", "again"]:
    try:
        btn.click(timeout=6000)
    except Exception as e:
        print(how, "CLICK FAILED:", str(e).split("Call log")[1][:900] if "Call log" in str(e) else str(e)[:400])
        print("dialogs:", s.page.evaluate("() => [...document.querySelectorAll('dialog')].map(d => d.open + ' ' + d.className + ' ' + d.outerHTML.length)"))
        s.page.screenshot(path=str(lib.SHOTS / f"RO-{how}.png"))
        break
    s.page.wait_for_selector("dialog[open]")
    s.page.wait_for_timeout(600)
    d = s.page.locator("dialog[open]").first
    if how == "escape":
        s.page.keyboard.press("Escape")
    elif how == "cancel":
        d.locator("button[data-id=cancel]").click()
    else:
        d.locator(".jenkins-dialog__close, button[aria-label=Close]").first.click()
    s.page.wait_for_timeout(1200)
    print(how, "closed; dialogs left:", s.page.evaluate("() => [...document.querySelectorAll('dialog')].map(d => d.open)"))
s.done(); close()
