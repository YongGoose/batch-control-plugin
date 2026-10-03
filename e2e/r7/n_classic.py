"""Classic job page (new job page off for requester): side panel, Request Change Permission prefill, Request Run page."""
import re
from lib import Session, close, log
res = {}
s = Session("requester")
s.go("/user/requester/experiments/")
sel = s.page.locator("form[name=config] select").nth(2); res["sel_opts"] = sel.locator("option").all_inner_texts()
opts = sel.locator("option").all_inner_texts()
sel.select_option(label="Disabled")
s.page.click("button[name=Submit]"); s.page.wait_for_load_state("load")
s.go("/job/team/job/sub/job/deep-job/")
res["classic_side"] = [re.sub(r"\s+", " ", a.inner_text()).strip() for a in s.page.locator("#tasks a").all()][:12]
s.shot("#side-panel", "N2-01-classic-side-panel")
s.page.locator("#tasks a", has_text="Request Change").first.click(); s.page.wait_for_load_state("load")
res["classic_prefill"] = (s.page.url, s.page.locator("select[name=scopeType]").input_value(), s.page.locator("input[name=scopeFullName]").input_value())
s.go("/job/batch-daily/batch-control/")
res["classic_request_run_h1"] = s.page.locator("h1").first.inner_text()
s.shot("#main-panel", "N2-02-classic-request-run")
s.go("/user/requester/experiments/")
s.page.locator("form[name=config] select").nth(2).select_option(index=0)
s.page.click("button[name=Submit]"); s.page.wait_for_load_state("load")
s.done(); close()
log("n_classic", res)
for k, v in res.items(): print(k, v)
