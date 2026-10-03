import sys
from lib import Session, close
s = Session(sys.argv[1])
r = s.go(sys.argv[2])
print(r.status, s.page.url)
print(s.page.content()[:int(sys.argv[3]) if len(sys.argv) > 3 else 3000] if '--html' in sys.argv else s.text()[:3000])
print("console:", s.console); print("bad:", s.bad)
close()
