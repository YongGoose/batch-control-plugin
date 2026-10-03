"""e2e-14 driver (final main after round 3 and 4, 2026-10-04): ../r6/lib.py with screenshots in screenshots/run-14/
and logs in r12/out/. Same rules: one new context per account, red-boxed clipped shots."""
import pathlib
import sys
HERE = pathlib.Path(__file__).resolve().parent
import importlib.util  # noqa: E402
_spec = importlib.util.spec_from_file_location("r6lib", HERE.parent / "r6" / "lib.py")
_l = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_l)
_l.SHOTS = HERE.parent / "screenshots" / "run-14"
_l.OUT = HERE / "out"
_l.SHOTS.mkdir(parents=True, exist_ok=True)
_l.OUT.mkdir(parents=True, exist_ok=True)
Session, close, api, groovy, log, ENV, BASE, pw = _l.Session, _l.close, _l.api, _l.groovy, _l.log, _l.ENV, _l.BASE, _l.pw
shot = _l.shot
SHOTS = _l.SHOTS
import re  # noqa: E402

def clean(html):
    t = re.sub(r"<script.*?</script>", " ", html, flags=re.S)
    t = re.sub(r"<[^>]+>", " ", t)
    return re.sub(r"\s+", " ", t)

def mails(query=""):
    import requests
    r = requests.get("http://localhost:8025/api/v1/search", params={"query": query or "*", "limit": 200}) if query else \
        requests.get("http://localhost:8025/api/v1/messages", params={"limit": 200})
    return r.json().get("messages", [])

def mail_body(mid):
    import requests
    return requests.get(f"http://localhost:8025/api/v1/message/{mid}").json()
