"""nobc (Build, no Batch Control permission): Direct Build on the parameterized batch-daily, new job page (core dialog)
and classic (parameters page), what the refusal looks like and whether its links work."""
import re
from lib import Session, close, BASE, groovy
import lib
for ui in ["new", "classic"]:
    groovy("""import jenkins.model.experimentalflags.*
def u=hudson.model.User.getById('nobc',true); def m=new HashMap(); m.put('new-job-page.flag','%s'); u.addProperty(new UserExperimentalFlagsProperty(m)); u.save()""" % ("true" if ui == "new" else "false"))
    s = Session("nobc")
    s.go("/job/batch-daily/")
    s.page.locator("#main-panel button, #tasks a", has_text="Direct Build (needs approval)").first.click()
    s.page.wait_for_timeout(2500)
    if ui == "new":
        d = s.page.locator("dialog[open]").first
        d.get_by_role("button", name=re.compile("^Build$")).click(); s.page.wait_for_timeout(3000)
        print(ui, "dialog open:", s.page.locator("dialog[open]").count(), "| toasts:", [t for t in s.page.locator(".jenkins-notification, #notification-bar").all_inner_texts() if t.strip()],
              "| dialog text:", re.sub(r"\s+", " ", s.page.locator("dialog[open]").first.inner_text())[:300] if s.page.locator("dialog[open]").count() else None, "| bad:", s.bad[-2:])
        s.page.screenshot(path=str(lib.SHOTS / "NB-new-direct-build.png"))
    else:
        with s.page.expect_navigation() as nav:
            s.page.locator("#main-panel button[name=Submit], #main-panel button.jenkins-button--primary").first.click()
        print(ui, "status", nav.value.status, s.page.url.replace(BASE, ""), "|", re.sub(r"\s+", " ", s.text())[:400])
        print(" links:", [(a.inner_text().strip()[:30], s.context.request.get(a.evaluate("e=>e.href")).status) for a in s.page.locator("#main-panel a[href]").all() if not (a.get_attribute("href") or "#").startswith("#")])
        s.shot("#main-panel", "NB-classic-refusal")
    s.done()
groovy("""import jenkins.model.experimentalflags.*
def u=hudson.model.User.getById('nobc',true); def m=new HashMap(); m.put('new-job-page.flag','true'); u.addProperty(new UserExperimentalFlagsProperty(m)); u.save()""")
# same refusal for requester (classic) for comparison
groovy("""import jenkins.model.experimentalflags.*
def u=hudson.model.User.getById('requester',true); def m=new HashMap(); m.put('new-job-page.flag','false'); u.addProperty(new UserExperimentalFlagsProperty(m)); u.save()""")
s = Session("requester")
s.go("/job/batch-daily/build?delay=0sec")
with s.page.expect_navigation() as nav:
    s.page.locator("#main-panel button[name=Submit], #main-panel button.jenkins-button--primary").first.click()
print("requester classic", nav.value.status, s.page.url.replace(BASE, ""), "|", re.sub(r"\s+", " ", s.text())[:300])
print(" links:", [(a.inner_text().strip()[:30], s.context.request.get(a.evaluate("e=>e.href")).status) for a in s.page.locator("#main-panel a[href]").all() if not (a.get_attribute("href") or "#").startswith("#")][:8])
s.shot("#main-panel", "NB-requester-classic-refusal")
s.done(); close()
groovy("""import jenkins.model.experimentalflags.*
def u=hudson.model.User.getById('requester',true); def m=new HashMap(); m.put('new-job-page.flag','true'); u.addProperty(new UserExperimentalFlagsProperty(m)); u.save()""")
