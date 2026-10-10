"""e2e-25 #43 (owner decision 2026-10-10, D-85: a BOM by default): every CSV export (runs.csv, incidents.csv,
changes.csv, requests.csv) starts with the UTF-8 byte order mark EF BB BF, followed by the header row; the content type
stays text/csv;charset=UTF-8 and non-ASCII values are UTF-8 (Excel on Windows decodes a CSV without a BOM in the ANSI
code page, so Korean job names, reasons and comments showed as mojibake).

usage: python csv_bom.py [ACEB]   rows: out/csv_bom.jsonl, shots: screenshots/run-25/R25-43-*.png
Item r25-csv-배치 (Freestyle, approval-required, string parameter NOTE; the build fails, so it has an incident). The
requester files a run request with a Korean reason and NOTE value (REST), approver-1 approves it with a Korean comment
(REST); an ACTIVATE (or, once activated, HOLD) request with a Korean reason, approved, writes a change record that names
the job. The exports are read as raw bytes over REST as approver-1 (ViewHistory) and, in section B, downloaded from the
History page's buttons in approver-1's browser.

A  arrangement: the job, the approved run (waited for), the activation change; the period from yesterday to tomorrow
   (controller date) is the filter of every download.
C  REST, for each export and for the period with no job filter and with job=r25-csv: #43 contract: the bytes start with
   EF BB BF immediately followed by the header row. Guards: HTTP 200, Content-Type text/csv;charset=UTF-8 and the
   attachment name unchanged, the header row is the first line (after a BOM, if any) and is the existing column list, the
   body is strict UTF-8 with no other BOM, and the Korean job name, reason, parameter value and comment are in it as
   UTF-8 in the row of r25-csv-배치.
E  REST, each export filtered to no row (job=r25-no-such-job): contract: BOM + header row and nothing else; guards: 200,
   content type.
B  screen: approver-1 downloads the four files from the History page's CSV buttons: contract: each starts with the BOM
   and the header row; guard: the Korean values are in it."""
import csv
import io
import re
import sys
from urllib.parse import quote

sys.path.insert(0, __file__.rsplit("/", 1)[0])
import lib  # noqa: E402
from lib import api, check, note, J, Session  # noqa: E402

lib.LOGNAME[0] = "csv_bom"
JOB = "r25-csv-배치"
FILTER = "r25-csv"
USER, APPROVER, VIEWER = "requester", "approver-1", "approver-1"
KO_REASON = "e2e-25 #43 한글 사유: 야간 배치 재실행"
KO_VALUE = "값-한글-배치"
KO_COMMENT = "승인합니다 (e2e-25)"
KO_ACT = "e2e-25 #43 활성화 사유"
BOM = b"\xef\xbb\xbf"
HEADERS = {
    "runs.csv": "runId,jobFullName,number,causeType,user,parameters,result,startedAt,durationMs,abortedBy,runRequestId",
    "incidents.csv": "id,runId,jobFullName,result,status,createdAt,resolvedByRunId,rerunRequestIds",
    "changes.csv": "id,type,target,user,at,grantId,detail",
    "requests.csv": "id,jobFullName,parameters,reason,requester,approver,status,createdAt,decidedAt,decisionComment,"
                    "selfApproved,incidentId,executedRunId,decidedBy",
}
# What the row of r25-csv-배치 must hold in each export (column -> exact value or a value it must contain).
S = {}


def period():
    return lib.facts("""def d = java.time.LocalDate.now()
return groovy.json.JsonOutput.toJson([from: d.minusDays(1).toString(), to: d.plusDays(1).toString()])""")


def sec_A():
    xml = lib.job_xml("fs", shell='echo "NOTE=$NOTE"; exit 1', params=lib.string_p("NOTE", "x"))
    created = lib.ensure_job(JOB, xml)
    prop = lib.set_property(JOB, approval=True, timer=True, upstream=True)
    n = lib.next_build(JOB)
    st, rid, _ = lib.run_req(USER, JOB, KO_REASON, approvers=(APPROVER,), params={"NOTE": KO_VALUE})
    dec = lib.decide(APPROVER, "requests", rid, "approve", KO_COMMENT) if rid else None
    result, console = lib.wait_build(JOB, n, timeout=180)
    state = lib.activation(JOB)
    action = "HOLD" if state == "activated" else "ACTIVATE"
    ast, aid = lib.act_req(USER, JOB, action, KO_ACT)
    adec = lib.decide(APPROVER, "activations", aid, "approve", KO_COMMENT) if aid else None
    S.update(period())
    S["request"], S["number"] = rid, n
    check("A", "arrangement: r25-csv-배치 approval-required; the run request with the Korean reason and NOTE value approved "
          "with a Korean comment, its build ran (FAILURE, so an incident); an approved activation change names the job",
          created is not None and "approvalRequired=true" in prop and st in (302, 303) and dec in (200, 302, 303)
          and result == "FAILURE" and f"NOTE={KO_VALUE}" in console and aid is not None and adec in (200, 302, 303),
          created=created, submit=st, request=rid, approve=dec, build=n, result=result, activation=(action, ast, aid, adec),
          period=(S["from"], S["to"]), console=console.strip().splitlines()[-3:])


def query(**extra):
    q = {"from": S["from"], "to": S["to"], **extra}
    return "&".join(f"{k}={quote(v)}" for k, v in q.items())


def rows_of(raw):
    text = raw[len(BOM):] if raw.startswith(BOM) else raw
    return list(csv.DictReader(io.StringIO(text.decode("utf-8"))))


def ours(name, rows):
    """The rows of r25-csv-배치 in one export (changes.csv: the records whose target is the job)."""
    col = "target" if name == "changes.csv" else "jobFullName"
    return [r for r in rows if (r.get(col) or "") == JOB]


def korean_ok(name, rows):
    """Whether the export's rows of r25-csv-배치 carry the Korean values that export holds."""
    mine = ours(name, rows)
    if name == "runs.csv":
        return bool([r for r in mine if KO_VALUE in (r.get("parameters") or "")])
    if name == "incidents.csv":
        return bool(mine)
    if name == "changes.csv":
        return bool(mine)
    return bool([r for r in mine if r.get("reason") == KO_REASON and r.get("decisionComment") == KO_COMMENT
                 and KO_VALUE in (r.get("parameters") or "")])


def content_type(r):
    return re.sub(r"\s+", "", (r.headers.get("Content-Type") or "")).lower()


def judge(sec, how, name, raw, headers_ok=None, status=None, ctype=None, disposition=None):
    """The checks of one downloaded export: the contract (BOM + header row) and the guards."""
    header = HEADERS[name].encode("utf-8") + b"\r\n"
    if status is not None:
        check(sec, f"guard: {how} {name} answers 200 with Content-Type text/csv;charset=UTF-8 and the attachment name "
              f"{name} (unchanged)", status == 200 and ctype == "text/csv;charset=utf-8"
              and disposition == f'attachment; filename="{name}"', status=status, content_type=ctype,
              disposition=disposition)
    check(sec, f"#43 contract: {how} {name} starts with the UTF-8 BOM EF BB BF immediately followed by the header row",
          raw.startswith(BOM + header), first_bytes=raw[:12].hex(" "), head=raw[:80].decode("utf-8", "replace"))
    body = raw[len(BOM):] if raw.startswith(BOM) else raw
    try:
        body.decode("utf-8")
        strict = True
    except UnicodeDecodeError:
        strict = False
    check(sec, f"guard: {how} {name}: the header row is the first line (after a BOM, if any) and is the existing column "
          "list; the body is strict UTF-8 and holds no other BOM", body.startswith(header) and strict and BOM not in body,
          header=body[:len(header) + 2].decode("utf-8", "replace"), strict=strict, other_bom=BOM in body)
    return body


def sec_C():
    for how, extra in (("the period's", {}), ("job=r25-csv's", {"job": FILTER})):
        for name in HEADERS:
            r = api(VIEWER, f"/batch-control/history/{name}?{query(**extra)}")
            raw = r.content
            judge("C", f"REST, {how}", name, raw, status=r.status_code, ctype=content_type(r),
                  disposition=r.headers.get("Content-Disposition"))
            try:
                rows = rows_of(raw)
            except Exception as e:  # noqa
                rows, err = [], repr(e)
            else:
                err = None
            needles = {"job name": JOB.encode("utf-8")}
            if name in ("runs.csv", "requests.csv"):
                needles["NOTE value"] = KO_VALUE.encode("utf-8")
            if name == "requests.csv":
                needles.update({"reason": KO_REASON.encode("utf-8"), "comment": KO_COMMENT.encode("utf-8")})
            present = {k: v in raw for k, v in needles.items()}
            check("C", f"guard: REST, {how} {name}: the Korean values ({', '.join(needles)}) are in it as UTF-8 bytes and "
                  "in the row of r25-csv-배치", all(present.values()) and korean_ok(name, rows), present=present,
                  rows=[{k: v for k, v in x.items() if k in ("jobFullName", "target", "parameters", "reason",
                                                            "decisionComment", "type")} for x in ours(name, rows)][:3],
                  error=err)


def sec_E():
    for name in HEADERS:
        r = api(VIEWER, f"/batch-control/history/{name}?{query(job='r25-no-such-job')}")
        header = HEADERS[name].encode("utf-8") + b"\r\n"
        check("E", f"guard: REST, an empty {name} (no row matches) answers 200 with Content-Type text/csv;charset=UTF-8",
              r.status_code == 200 and content_type(r) == "text/csv;charset=utf-8", status=r.status_code,
              content_type=content_type(r))
        check("E", f"#43 contract: an empty {name} is exactly the BOM and the header row", r.content == BOM + header,
              raw=r.content[:120].decode("utf-8", "replace"), first_bytes=r.content[:6].hex(" "), length=len(r.content))


def sec_B():
    s = Session(VIEWER, fresh=True)
    s.go(f"/batch-control/history/?{query(job=FILTER)}")
    buttons = s.page.locator("#main-panel a.jenkins-button", has_text=re.compile(r"\.csv$"))
    names = [b.inner_text().strip() for b in buttons.all()]
    s.shot([b for b in buttons.all()] or "#main-panel", "R25-43-1-history-csv-buttons")
    check("B", "guard: the History page offers the four CSV exports as buttons", sorted(names) == sorted(HEADERS),
          buttons=names)
    for name in HEADERS:
        btn = s.page.locator("#main-panel a.jenkins-button", has_text=re.compile(rf"^{re.escape(name)}$")).first
        if btn.count() == 0:
            check("B", f"screen: the {name} button is there", False)
            continue
        with s.page.expect_download(timeout=30000) as d:
            btn.click()
        path = lib.OUT / f"download-{name}"
        d.value.save_as(str(path))
        raw = path.read_bytes()
        judge("B", "the downloaded", name, raw)
        check("B", f"guard: the downloaded {name} holds the job name r25-csv-배치 as UTF-8",
              JOB.encode("utf-8") in raw, suggested=d.value.suggested_filename)
    s.done()


if __name__ == "__main__":
    lib.run(sys.argv, {"A": sec_A, "C": sec_C, "E": sec_E, "B": sec_B})
