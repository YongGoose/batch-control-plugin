from lib import Session, close, SHOTS
s = Session("admin")
s.go("/batch-control/changes/")
s.shot(s.page.locator("tr", has_text="permission windows used").first, "84-01-move-record")
s.go("/administrativeMonitor/batch-control-strategy/reviewed?item=ops%2Fmv-job")
s.page.screenshot(path=str(SHOTS / "86-04-reviewed-page-header.png"))
print([c.strip() for c in s.page.locator(".jenkins-breadcrumbs__list-item").all_inner_texts()])
s.done(); close()
