"""Item 2: the monitor sentence that points to the change records names the Changes tab."""
import re
from lib import Session, close, log, clean, api
s = Session("admin"); s.go("/manage/")
mon = s.page.locator(".jenkins-alert", has_text="changed under").first
t = re.sub(r"\s+", " ", mon.inner_text()) if mon.count() else ""
res = {"sentences": [m.group(0) for m in re.finditer(r"[^.]*(Changes|Change Records)[^.]*\.", t)], "change_records_present": "Change Records" in t,
       "links": mon.locator("a").evaluate_all("as => as.map(a => [a.innerText.trim(), a.getAttribute('href')])") if mon.count() else None}
s.shot(mon, "2-01-monitor-changes-tab") if mon.count() else None
s.done(); close(); log("s86b", res); print(res)
