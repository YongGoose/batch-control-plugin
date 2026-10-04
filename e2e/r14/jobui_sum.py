import json, sys
for l in open("out/jobui.jsonl"):
    r = json.loads(l)
    if len(sys.argv) > 1 and r["ui"] != sys.argv[1]:
        continue
    if r["kind"] == "entries":
        print(f'{r["ui"]} {r["role"]:10} {r["page"][:42]:42} ENTRIES {[e[1] for e in r["entries"] if e[0] != "overflow-all"]}')
        continue
    if r["kind"] == "page":
        print(f'{r["ui"]} {r["role"]:10} {r["page"][:42]:42} PAGE {r["status"]}')
        continue
    keys = ["result", "escape_closes", "cancel_closes", "close_x_closes", "empty_submit_stays", "empty_submit_errors", "enter_in_text",
            "submit_landing", "submit_stuck", "server_detail_status", "after_build", "nav", "h1", "links", "menu", "notifications", "exception", "console", "url_unchanged", "reopen_failed"]
    d = {k: r[k] for k in keys if k in r and r[k] not in (None, [], "")}
    print(f'{r["ui"]} {r["role"]:10} {r["page"][:42]:42} [{r["where"]}] {r["label"][:40]} :: {json.dumps(d)[:700]}')
