"""Scenario 5a: Batch Control configuration page (settings-subpage, Save only, refused save keeps input, red Revert)."""
from lib import Session, close, log, SHOTS
r = {}
s = Session("admin")
s.go("/manage/")
link = s.page.locator("a[href*='batch-control-configuration']").first
r["manage_link"] = link.get_attribute("href") if link.count() else None
resp = s.go("/batch-control-configuration/")
r["status"] = resp.status
r["url"] = s.page.url
r["breadcrumbs"] = s.page.locator(".jenkins-breadcrumbs__list-item").all_inner_texts()
r["side_panel_manage"] = s.page.locator("#side-panel, #tasks, .app-sidebar, aside").count()
r["buttons_bottom"] = [b.inner_text().strip() for b in s.page.locator("#bottom-sticker button, .bottom-sticker-inner button, form[name=config] button[type=submit], form[name=config] .jenkins-submit-button").all()]
r["apply_button"] = s.page.locator("button", has_text="Apply").count()
s.page.screenshot(path=str(SHOTS / "S5a-01-config-page.png"))
# refused save: an invalid maximum grant duration
f = s.page.locator("form[name=config]")
names = [i.get_attribute("name") for i in f.locator("input[type=text], input[type=number]").all()]
r["text_fields"] = names
target = f.locator("input[name='_.maxGrantMinutes']")
if not target.count():
    target = f.locator("input[name*='axGrant']")
typed_reason_field = f.locator("input[name='_.retentionMonths']")
target.first.fill("-5")
other = f.locator("input[name='_.pendingTimeoutHours']")
if other.count():
    other.first.fill("7")
with s.page.expect_navigation() as nav:
    s.page.locator("button", has_text="Save").last.click()
r["refused_status"] = nav.value.status
s.page.wait_for_load_state("load")
r["refused_url"] = s.page.url
al = s.page.locator(".jenkins-alert-danger")
r["refused_alert"] = al.first.inner_text() if al.count() else None
f = s.page.locator("form[name=config]")
r["kept_maxGrant"] = f.locator("input[name='_.maxGrantMinutes'], input[name*='axGrant']").first.input_value()
r["kept_pendingTimeout"] = f.locator("input[name='_.pendingTimeoutHours']").first.input_value() if f.locator("input[name='_.pendingTimeoutHours']").count() else None
if al.count():
    s.shot([al.first, f.locator("input[name='_.maxGrantMinutes'], input[name*='axGrant']").first], "S5a-02-refused-save-keeps-values")
# Revert control: red, red confirm dialog, cancelled
rv = s.page.locator("a, button", has_text="Revert to the plain strategy")
r["revert_control"] = rv.count()
if rv.count():
    r["revert_class"] = rv.first.get_attribute("class")
    r["revert_color"] = rv.first.evaluate("e => getComputedStyle(e).color")
    rv.first.scroll_into_view_if_needed()
    rv.first.click(); s.page.wait_for_timeout(700)
    d = s.page.locator("dialog[open]")
    if d.count():
        ok = d.first.locator("button[data-id=ok], .jenkins-button--primary, button").filter(has_text="Yes").first
        btns = d.first.locator("button")
        r["revert_dialog"] = d.first.inner_text()[:300]
        r["revert_dialog_buttons"] = [(b.inner_text().strip(), b.get_attribute("class")) for b in btns.all()]
        s.shot(d.first, "S5a-03-revert-confirm")
        d.first.locator("button", has_text="Cancel").click()
r["console"] = s.console
s.done(); close()
log("s5a", r)
for k, v in r.items():
    print(f"{k}: {v}")
