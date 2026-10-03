"""Which pages log the 'Refused to execute script from /static/<hash>/' console error (plugin vs core)."""
import sys
from lib import Session, close
s = Session(sys.argv[1])
reqs = []
s.page.on("request", lambda r: reqs.append(r.url) if r.resource_type == "script" and r.url.rstrip("/").endswith("81ab13ce") else None)
for p in sys.argv[2:]:
    s.console.clear(); reqs.clear()
    s.go(p); s.page.wait_for_timeout(1500)
    print(p, [c for c in s.console if "Refused" in c][:1], reqs[:2])
s.done(); close()
