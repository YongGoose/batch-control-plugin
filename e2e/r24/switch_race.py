"""e2e-24 #39: approval racing the change-control switch-off.

Contract (wave A, frozen 2026-10-10):
  C1  After a switch-off has returned, no permission window is active and none becomes active.
  C2  An approval that overlaps the switch-off either is refused with the "Change control is off" refusal, or registers
      a window that the switch-off revokes.
  C3  Either way, turning change control back on confers nothing from it.
  Test note: the race is timing-dependent; a black-box test may stress it with many concurrent approve and switch-off
  rounds, then assert the invariant, and report how often it fails on the plugin without the fix.

usage: python switch_race.py [AGHSX] [rounds]   rows: out/switch_race.jsonl, shots: screenshots/run-24/R24-39-*.png
Items r24-race-1..12 (Freestyle); the requester holds no standing Job/Configure. Every request and decision goes over
REST with API tokens made for this run (core's endpoint, revoked at the end): the requester files CONFIGURE grant
requests (15 minutes, approver-1), approver-1 approves them, and the administrator switches change control off and on
by posting the Batch Control configuration form (configSubmit) with the form's own JSON, captured in the browser from
the administrator's configuration page with the box unticked and ticked (buildFormTree). Server state is read through
the script console (GrantService.listActive, the grant files, the ACL with the windows it confers; reading only).

A  arrangement: the items, change control on, no open window or pending grant request of the requester on them, the
   tokens and the two form bodies; premise: the requester has no CONFIGURE on the items.
G  guard (the same flow without the race): an approval with change control on registers a window that confers
   CONFIGURE (ACL and the requester's configure page 200); the switch-off over REST revokes it (C1); after the switch
   is back on it confers nothing (C3); an approval after the switch-off has returned is refused with "Change control
   is off" (C2), the request stays PENDING.
H  the same race made deterministic: the script console (arrangement, as r22/grant_monitor.py holds a monitor) starts
   a thread that holds, for 4 s, the store's write lock of one PENDING grant request's file; approver-1 approves it
   over REST, so the approval passes its switch check and then waits in its store write; 0.8 s later the
   administrator posts the switch-off (on the plugin without the fix it returns while the approval still waits; a fix
   may also make it wait for the approval); then the approval completes. C1 no window is active after both answered;
   C2 the approval was refused with "Change control is off" or its window is revoked; after the switch is back on, C3
   the requester holds CONFIGURE on none of the items and the requester's configure page (browser) answers 403. If a
   thread dump shows the switch-off itself waiting inside the store (the 64 lock stripes are shared by path hash), the
   attempt is repeated with a new request (up to three).
S  the stress (default 40 rounds): per round, twelve PENDING requests; twelve approvals and the switch-off are released
   together from a barrier. The approvals carry a long decision comment (about 120 KB of text, which the plugin checks
   and stores between its switch check and the window's registration, so each approval stays longer in that gap).
   They are serialized by the plugin, so they form a burst; the offset between the switch-off and the burst adapts
   round by round (earlier when every approval succeeded, later when every one was refused, jittered when both
   happened) so that the switch-off lands inside the burst. After all thirteen answers: C1 no active window in memory
   or on disk; C2 every approval answered either a success whose window is revoked or a 4xx naming "Change control is
   off" (never anything else); then the switch goes back on: C3 the requester has CONFIGURE on none of the items. A
   leaked window is revoked before the next round. One verdict line per contract item for all rounds, with the
   failure rate; a premise line that enough rounds really overlapped (some approvals succeeded and some were refused
   in the same round).
X  the browser view after the stress: the administrator's configuration page shows change control on, and the
   requester's configure page of each item answers 403 (C3).
cleanup: open windows of the requester on the items revoked, pending requests cancelled, change control on, the
tokens revoked."""
import json
import random
import sys
import threading
import time

sys.path.insert(0, __file__.rsplit("/", 1)[0])
import lib  # noqa: E402
from lib import api, check, note, J, BASE, Session  # noqa: E402

lib.LOGNAME[0] = "switch_race"
USER, APPROVER = "requester", "approver-1"
ITEMS = [f"r24-race-{i}" for i in range(1, 13)]
ROUNDS = int(sys.argv[2]) if len(sys.argv) > 2 else 40
MIN_MIXED = 10
OFF_TEXT = "Change control is off"
CONFIG = "/manage/batch-control-configuration/"
# About 180 KB url-encoded (letters and spaces only), below the servlet container's 200,000-byte form limit.
PAD = "stress padding that keeps the approval between its switch check and its registration longer " * 1900
TOK = {}
BODY = {}
GS = f"{lib.BC}.security.GrantService"
NAMES = json.dumps(ITEMS)
random.seed(39)


def sess(user):
    return lib.token_session(user, TOK[user][0])


def change_control():
    return lib.gv(lib.CFG + "return cfg.isChangeControlEnabled()") == "true"


def post_switch(on):
    """The administrator's configuration form posted over REST (API token): change control on or off."""
    r = sess("admin").post(BASE + CONFIG + "configSubmit", data={"json": BODY["on" if on else "off"], "Submit": "Save"},
                           allow_redirects=False)
    return r.status_code


def capture_forms():
    """The configuration form's JSON with change control ticked and unticked, built by the page's own buildFormTree."""
    s = Session("admin", fresh=True)
    s.go(CONFIG)
    box = s.page.locator('input[name="_.changeControlEnabled"]')
    out = {}
    for key, want in (("off", False), ("on", True)):
        box.set_checked(want, force=True)
        raw = s.page.evaluate("""() => { const f = document.querySelector('form[action=configSubmit]'); buildFormTree(f);
          return f.querySelector('input[name=json]').value; }""")
        d = json.loads(raw)
        d.pop("Jenkins-Crumb", None)
        out[key] = json.dumps(d)
    s.shot('input[name="_.changeControlEnabled"]', "R24-39-A-config-form")
    s.done()
    return out


def file_requests(tag, items=None):
    """One PENDING CONFIGURE grant request per item (requester, REST); returns {item: request id}."""
    out = {}
    s = sess(USER)
    for item in items or ITEMS:
        r = s.post(BASE + "/batch-control/grants/create", allow_redirects=False,
                   data=[("scopeFullName", item), ("actions", "CONFIGURE"), ("durationMinutes", "15"),
                         ("reason", f"e2e-24 #39 {tag}"), ("approvers", APPROVER)])
        out[item] = lib.loc_id(r)
    return out


def state(request_ids=()):
    """One script-console read: the requester's windows on the items that confer now (GrantService.listActive in
    memory; grant files without revokedAtMillis whose end is in the future on disk), {grant request id: revokedAtMillis
    of its window, 'open' or 'none'} for `request_ids`, the items on which the requester holds Item/CONFIGURE (the ACL
    with every window it confers), and the switch."""
    return lib.facts(f"""def j = jenkins.model.Jenkins.get(); def cl = j.pluginManager.uberClassLoader
def gs = cl.loadClass('{GS}').get(); def items = {NAMES}; def want = {json.dumps(list(request_ids))}
def mem = gs.listActive().findAll {{ it.user == '{USER}' && items.contains(it.scope?.fullName) }}.collect {{ it.id }}
def d = new File(j.rootDir, 'batch-control/grants'); def now = System.currentTimeMillis(); def disk = []; def win = [:]
(d.listFiles() ?: []).findAll {{ it.name.endsWith('.xml') }}.each {{ f -> def g = new XmlSlurper().parse(f)
  if (g.user.text() == '{USER}' && items.contains(g.scope.fullName.text()) && g.revokedAtMillis.text() == ''
      && ((g.expiresAtMillis.text() ?: '0') as long) > now) disk << g.id.text()
  if (want.contains(g.grantRequestId.text())) win[g.grantRequestId.text()] = g.revokedAtMillis.text() ?: 'open' }}
want.each {{ if (!win.containsKey(it)) win[it] = 'none' }}
def u = hudson.model.User.getById('{USER}', false).impersonate2()
def conf = items.findAll {{ n -> def x = j.getItemByFullName(n); x != null && x.getACL().hasPermission2(u, hudson.model.Item.CONFIGURE) }}
def cfg = {lib.BC}.config.BatchControlGlobalConfiguration.get()
return groovy.json.JsonOutput.toJson([memory: mem, disk: disk, windows: win, conferred: conf, on: cfg.changeControlEnabled])""")


def revoke_open():
    s = state()
    ids = sorted(set(s["memory"]) | set(s["disk"]))
    for gid in ids:
        api("admin", f"/batch-control/grants/active/{gid}/revoke", "POST")
    return ids


def cancel_pending():
    out = lib.gv(f"""def d = new File(jenkins.model.Jenkins.get().rootDir, 'batch-control/requests/grant'); def ids = []
(d.listFiles() ?: []).findAll {{ it.name.endsWith('.xml') }}.each {{ f -> def g = new XmlSlurper().parse(f)
  if (g.requester.text() == '{USER}' && g.status.text() == 'PENDING' && {NAMES}.contains(g.scope.fullName.text()))
    ids << g.id.text() }}
return ids.join(',')""")
    ids = [x for x in out.split(",") if x]
    for rid in ids:
        if TOK.get(USER, (None,))[0]:
            sess(USER).post(BASE + f"/batch-control/grants/{rid}/cancel", allow_redirects=False)
        else:
            api(USER, f"/batch-control/grants/{rid}/cancel", "POST")
    return ids


def sec_A():
    made = [lib.ensure_job(i, lib.job_xml("fs", shell="echo r24-39")) for i in ITEMS]
    if not change_control():
        lib.gv(lib.CFG + "cfg.setChangeControlEnabled(true); return 'on'")
    revoked, cancelled = revoke_open(), cancel_pending()
    for user in (USER, APPROVER, "admin"):
        TOK[user] = lib.make_token(user, "r24-switch-race")
    BODY.update(capture_forms())
    off, on = json.loads(BODY["off"]), json.loads(BODY["on"])
    diff = sorted(k for k in set(off) | set(on) if off.get(k) != on.get(k))
    s = state()
    check("A", "arrangement: the items, change control on, the requester holds CONFIGURE on none, API tokens for "
          "requester/approver-1/admin, the configuration form's two bodies differ only in changeControlEnabled",
          s["on"] and not s["conferred"] and all(v[0] for v in TOK.values()) and diff == ["changeControlEnabled"],
          created=sum(made), revoked=revoked, cancelled=cancelled, conferred=s["conferred"], form_diff=diff)


def sec_G():
    ids = file_requests("G")
    item, rid = ITEMS[0], ids[ITEMS[0]]
    r = sess(APPROVER).post(BASE + f"/batch-control/grants/{rid}/approve", data={"comment": "e2e-24 #39 G " + PAD},
                            allow_redirects=False)
    on_get = api(USER, J(item) + "/configure").status_code
    s = state([rid])
    check("G", "[#39 C1/C3] guard premise: with change control on, approver-1's REST approval (with the stress's long "
          "comment) opens a window that confers CONFIGURE (ACL, and the requester's configure page answers 200)",
          r.status_code in (200, 302, 303) and item in s["conferred"] and on_get == 200, http=r.status_code,
          configure=on_get, window=s["windows"])
    st_off = post_switch(False)
    s = state([rid])
    check("G", "[#39 C1] guard: the switch-off posted over REST returns, change control is off and the window is revoked "
          "(memory and disk)", st_off in (200, 302, 303) and not s["on"] and not s["memory"] and not s["disk"]
          and s["windows"][rid] not in ("open", "none"), http=st_off, memory=s["memory"], disk=s["disk"],
          revoked_at=s["windows"][rid])
    late = ids[ITEMS[1]]
    r2 = sess(APPROVER).post(BASE + f"/batch-control/grants/{late}/approve", data={"comment": "e2e-24 #39 G late"},
                             allow_redirects=False)
    t2 = lib.text_of(r2.text)
    st = lib.gv(f"""def f = new File(jenkins.model.Jenkins.get().rootDir, 'batch-control/requests/grant/{late}.xml')
return f.exists() ? new XmlSlurper().parse(f).status.text() : 'missing'""")
    check("G", "[#39 C2] guard: an approval after the switch-off has returned is refused with a 4xx naming "
          f"'{OFF_TEXT}', and the request stays PENDING", 400 <= r2.status_code < 500 and OFF_TEXT in t2
          and st == "PENDING", http=r2.status_code, status=st, message=lib.refusal_text(t2))
    st_on = post_switch(True)
    s = state()
    get = api(USER, J(item) + "/configure").status_code
    check("G", "[#39 C3] guard: after the switch is back on (REST), the revoked window confers nothing (ACL, configure "
          "page 403)", st_on in (200, 302, 303) and s["on"] and not s["conferred"] and get == 403, http=st_on,
          conferred=s["conferred"], configure=get)
    cancel_pending()


def hold_store_write(request_id, hold_ms):
    """Arrangement (script console): a thread "r24-store-write-holder" holds, for `hold_ms`, the store's write lock
    of the grant request file `request_id`.xml (FileStore stripes its write locks by path), as a slow disk would hold
    that write. An approval of the request then waits in its store write, after its switch check and before its window
    is registered. Returns 'held' once the lock is taken."""
    return lib.gv(f"""def fs = {lib.BC}.store.FileStore.get()
def path = {lib.BC}.store.PathCodec.resolveUnder(fs.grantRequestDir(), '{request_id}.xml')
def lock = fs.lockFor(path)
def t = new Thread({{ lock.lock(); try {{ Thread.sleep({hold_ms}) }} finally {{ lock.unlock() }} }} as Runnable, 'r24-store-write-holder')
t.daemon = true; t.start()
def t0 = System.currentTimeMillis()
while (!lock.isLocked() && System.currentTimeMillis() - t0 < 2000) Thread.sleep(10)
return lock.isLocked() ? 'held' : 'not held'""")


def off_thread():
    """The request thread of a configSubmit still running (thread dump, script console): its name, state, and whether
    it waits inside the store (FileStore), i.e. on the held lock stripe; [] when none runs."""
    return lib.facts("""def out = []
Thread.getAllStackTraces().each { t, st -> if (t.name.contains('configSubmit')) out << [name: t.name.take(120),
  state: t.state.toString(), in_store: st.any { it.className.endsWith('store.FileStore') },
  top: st.take(8).collect { it.className.tokenize('.').last() + '.' + it.methodName }] }
return groovy.json.JsonOutput.toJson(out)""")


def sec_H():
    """C1/C2/C3, made deterministic: the approval waits between its switch check and its registration while the
    switch-off runs; the invariant is read after both have answered."""
    item = ITEMS[0]
    for attempt in (1, 2, 3):
        rid = file_requests(f"H attempt {attempt}", [item])[item]
        held = hold_store_write(rid, 4000)
        out = {}

        def approve():
            r = sess(APPROVER).post(BASE + f"/batch-control/grants/{rid}/approve", data={"comment": "e2e-24 #39 H"},
                                    allow_redirects=False)
            out["approve"] = (r.status_code, lib.text_of(r.text), time.monotonic())

        def switch_off():
            out["off"] = (post_switch(False), time.monotonic())

        ta = threading.Thread(target=approve)
        ta.start()
        time.sleep(0.8)
        waiting = "approve" not in out
        t0 = time.monotonic()
        to = threading.Thread(target=switch_off)
        to.start()
        time.sleep(1.2)
        off_returned_while_held = "off" in out and "approve" not in out
        dump = [] if "off" in out else off_thread()
        collided = any(d["in_store"] for d in dump)
        ta.join(30)
        to.join(30)
        code, text, t_app = out.get("approve", (None, "", None))
        st_off, t_off = out.get("off", (None, None))
        if held != "held" or not waiting or collided:
            note("H", f"attempt {attempt}: not usable (the approval was not held, or the switch-off waited on the same "
                 "store lock stripe, which the 64 stripes share by path hash); retrying with a new request", held=held,
                 approval_waiting=waiting, off_thread=dump)
            post_switch(True)
            revoke_open()
            cancel_pending()
            continue
        check("H", "premise: the approval was waiting in its store write (after its switch check) when the switch-off "
              "was posted; the switch-off left change control off", st_off in (200, 302, 303) and not change_control(),
              off_http=st_off, off_returned_while_approval_held=off_returned_while_held,
              off_waited_for=None if off_returned_while_held else dump,
              off_seconds=round(t_off - t0, 2) if t_off else None,
              approval_answered_after_off=(t_app > t_off) if (t_app and t_off) else None)
        after = state([rid])
        win = after["windows"].get(rid)
        check("H", "[#39 C1] after the switch-off and the held approval had both answered, no permission window is "
              "active (GrantService memory and grant files)", not after["memory"] and not after["disk"],
              memory=after["memory"], disk=after["disk"], window=win)
        refused = code is not None and 400 <= code < 500 and OFF_TEXT in text
        revoked = code in (200, 302, 303) and win not in ("open", "none", None)
        check("H", "[#39 C2] the overlapping approval was either refused with the 'Change control is off' 4xx or its "
              "window was revoked", refused or revoked, http=code, window=win, message=lib.refusal_text(text)[:300])
        st_on = post_switch(True)
        s = state()
        sb = Session(USER, fresh=True)
        r = sb.go(J(item) + "/configure")
        page = r.status if r else None
        sb.shot("body", "R24-39-H-requester-configure-after-on")
        sb.done()
        check("H", "[#39 C3] after change control is back on, the requester holds CONFIGURE on none of the items and "
              "the requester's configure page (browser) answers 403", st_on in (200, 302, 303) and s["on"]
              and not s["conferred"] and page == 403, http=st_on, conferred=s["conferred"], configure_page=page)
        note("H", "revoked after the section", windows=revoke_open(), cancelled=cancel_pending())
        return
    check("H", "premise: the approval could be held between its switch check and its registration in one of three "
          "attempts", False)


def one_round(n, offset_ms):
    """One race: offset > 0 delays the switch-off, offset < 0 delays the approvals."""
    ids = file_requests(f"S round {n}")
    sessions = [sess(APPROVER) for _ in ITEMS]
    admin = sess("admin")
    barrier = threading.Barrier(len(ITEMS) + 1)
    results = {}

    def approve(k, item):
        barrier.wait()
        if offset_ms < 0:
            time.sleep(-offset_ms / 1000.0)
        r = sessions[k].post(BASE + f"/batch-control/grants/{ids[item]}/approve",
                             data={"comment": f"e2e-24 #39 r{n} " + PAD}, allow_redirects=False)
        results[item] = (r.status_code, lib.text_of(r.text))

    def switch_off():
        barrier.wait()
        if offset_ms > 0:
            time.sleep(offset_ms / 1000.0)
        r = admin.post(BASE + CONFIG + "configSubmit", data={"json": BODY["off"], "Submit": "Save"},
                       allow_redirects=False)
        results["_off"] = (r.status_code, "")

    threads = [threading.Thread(target=approve, args=(k, item)) for k, item in enumerate(ITEMS)]
    threads.append(threading.Thread(target=switch_off))
    for t in threads:
        t.start()
    for t in threads:
        t.join(120)
    after_off = state(list(ids.values()))
    approved, refused, other = [], [], []
    for item in ITEMS:
        code, text = results.get(item, (None, ""))
        if code in (200, 302, 303):
            approved.append(item)
        elif code is not None and 400 <= code < 500 and OFF_TEXT in text:
            refused.append(item)
        else:
            other.append((item, code, lib.refusal_text(text)[:160]))
    unrevoked = [(i, after_off["windows"].get(ids[i])) for i in approved
                 if after_off["windows"].get(ids[i]) in ("open", "none", None)]
    st_on = post_switch(True)
    after_on = state()
    row = dict(round=n, offset_ms=offset_ms, off_http=results.get("_off", (None,))[0], off_left_off=not after_off["on"],
               approved=len(approved), refused=len(refused), other=other,
               active_after_off={"memory": after_off["memory"], "disk": after_off["disk"]}, unrevoked=unrevoked,
               on_http=st_on, conferred_after_on=after_on["conferred"])
    row["revoked_after_round"] = revoke_open()
    cancel_pending()
    return row


def sec_S():
    rows = []
    offset = 0.0
    for n in range(1, ROUNDS + 1):
        r = one_round(n, round(offset, 1))
        rows.append(r)
        bad = bool(r["active_after_off"]["memory"] or r["active_after_off"]["disk"] or r["unrevoked"] or r["other"]
                   or r["conferred_after_on"])
        note("S", f"round {n}" + (" BROKEN" if bad else ""), **r)
        # Keep the switch-off inside the approval burst: earlier when it came after every approval, later when it came
        # before every one, jittered by up to 4 ms when it overlapped.
        if r["refused"] == 0:
            offset -= 4
        elif r["approved"] == 0:
            offset += 4
        else:
            offset += random.uniform(-4, 4)
        offset = max(-80.0, min(80.0, offset))
    total = len(rows)
    c1 = [r["round"] for r in rows if r["active_after_off"]["memory"] or r["active_after_off"]["disk"]]
    c2 = [r["round"] for r in rows if r["unrevoked"] or r["other"]]
    c3 = [r["round"] for r in rows if r["conferred_after_on"]]
    broken = sorted(set(c1) | set(c2) | set(c3))
    mixed = [r["round"] for r in rows if r["approved"] and r["refused"]]
    premise = [r["round"] for r in rows if not (r["off_http"] in (200, 302, 303) and r["off_left_off"]
                                                 and r["on_http"] in (200, 302, 303))]
    leaked = sum(len(r["unrevoked"]) for r in rows)
    note("S", "failure rate", rounds=total, broken_rounds=len(broken), rate=f"{len(broken)}/{total}",
         rate_of_overlapping_rounds=f"{len(set(broken) & set(mixed))}/{len(mixed)}", leaked_windows=leaked,
         successful_approvals=sum(r["approved"] for r in rows), refused_approvals=sum(r["refused"] for r in rows),
         mixed_rounds=len(mixed))
    check("S", "premise: every switch-off and switch-on posted over REST succeeded and the switch-off left change "
          "control off", not premise, failing_rounds=premise)
    check("S", f"premise: the switch-off really overlapped the approvals (rounds where some approvals succeeded and "
          f"others were refused: at least {MIN_MIXED})", len(mixed) >= MIN_MIXED, mixed_rounds=len(mixed), rounds=total)
    check("S", "[#39 C1] after every switch-off had returned, no permission window of the requester was active "
          "(GrantService memory and grant files)", not c1, rounds=total, broken=c1, rate=f"{len(c1)}/{total}")
    check("S", "[#39 C2] every overlapping approval was either refused with the 'Change control is off' 4xx or its "
          "window was revoked by the switch-off (no other answer)", not c2, rounds=total, broken=c2,
          rate=f"{len(c2)}/{total}", leaked_windows=leaked)
    check("S", "[#39 C3] after change control was turned back on, the requester held CONFIGURE on none of the items",
          not c3, rounds=total, broken=c3, rate=f"{len(c3)}/{total}")


def sec_X():
    s = Session("admin", fresh=True)
    s.go(CONFIG)
    on = s.page.locator('input[name="_.changeControlEnabled"]').is_checked()
    s.shot('input[name="_.changeControlEnabled"]', "R24-39-X-1-config-on")
    s.done()
    s = Session(USER, fresh=True)
    codes = {}
    for i, item in enumerate(ITEMS):
        r = s.go(J(item) + "/configure")
        codes[item] = r.status if r else None
        if i == 0:
            s.shot("body", "R24-39-X-2-requester-configure")
    s.done()
    check("X", "[#39 C3] after the stress: the configuration page shows change control on, and the requester's "
          "configure page of every item answers 403", on and all(c == 403 for c in codes.values()), change_control=on,
          configure=codes)


def cleanup():
    revoked, cancelled = revoke_open(), cancel_pending()
    on = change_control() or lib.gv(lib.CFG + "cfg.setChangeControlEnabled(true); return 'on'") == "on"
    toks = {u: lib.revoke_token(u, t[1]) for u, t in TOK.items() if t[1]}
    note("cleanup", "open windows revoked, pending requests cancelled, change control on, tokens revoked",
         revoked=revoked, cancelled=cancelled, change_control=on, tokens=toks)


if __name__ == "__main__":
    lib.run(sys.argv[:2], {"A": sec_A, "G": sec_G, "H": sec_H, "S": sec_S, "X": sec_X}, cleanup)
