from lib import Session, close, groovy, api, log, SHOTS

def check(tag):
    cls = groovy("return jenkins.model.Jenkins.get().getAuthorizationStrategy().getClass().simpleName").replace("Result: ", "")
    conf = api("requester", "/job/team/job/app-1/configure").status_code
    s = Session("admin")
    s.go("/manage/")
    mon = s.page.locator(".jenkins-alert", has_text="is not a Batch Control strategy").count()
    s.done()
    out = {"step": tag, "strategy": cls, "requester_configure_under_grant": conf, "monitor_unsupported": mon}
    log("s2", out)
    print(out)
    return out
