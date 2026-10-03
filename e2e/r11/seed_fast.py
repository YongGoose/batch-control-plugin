"""R4-13 seed: 60 admin builds of the non-approval job `fast`, one at a time (identical queue items would merge)."""
import time
from lib import api
for i in range(60):
    api("admin", "/job/fast/build?delay=0sec", "POST")
    for _ in range(60):
        q = api("admin", "/queue/api/json?tree=items%5Btask%5Bname%5D%5D").json()["items"]
        if not any(x["task"].get("name") == "fast" for x in q):
            break
        time.sleep(0.3)
print(api("admin", "/job/fast/api/json?tree=nextBuildNumber").json())
