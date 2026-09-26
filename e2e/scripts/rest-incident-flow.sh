#!/usr/bin/env bash
# The incident lifecycle on a real Jenkins (SPEC item 11). Until now this flow
# existed only in integration tests.
#
#   a failing build registers an incident automatically (incidentResults contains
#   FAILURE) -> resolving it straight away is refused -> ACKNOWLEDGED with the
#   cause -> a comment records the action taken -> RESOLVED
#
# The operator is `approver`, who holds ViewHistory - the permission every
# incident endpoint checks.
set -euo pipefail
. "$(dirname "$0")/lib.sh"

JOB="${1:-batch-failing}"

echo "### incident lifecycle on $JOB"

bc_login admin
bc_login approver
bc_login requester

count_incidents() {
  bc_get approver "$OUT_DIR/inc-list.csv" "/batch-control/history/incidents.csv" > /dev/null
  echo $(( $(grep -c '' "$OUT_DIR/inc-list.csv") - 1 ))
}

before=$(count_incidents)
echo "incidents before: $before"

# --- 1. make the job fail
status=$(bc_post requester "$OUT_DIR/inc-build.html" "/job/$JOB/build?delay=0sec")
echo "--- POST /job/$JOB/build (requester, job is not approval-controlled) -> HTTP $status"
echo "--- waiting for the build to fail"
for _ in $(seq 1 40); do
  bc_get admin "$OUT_DIR/inc-last.json" "/job/$JOB/lastBuild/api/json?tree=number,building,result" > /dev/null
  if grep -q '"building":false' "$OUT_DIR/inc-last.json"; then break; fi
  sleep 2
done
echo "    lastBuild: $(cat "$OUT_DIR/inc-last.json")"

# --- 2. the incident must appear on its own
echo "--- waiting for the incident to be registered"
for _ in $(seq 1 20); do
  now=$(count_incidents)
  if [ "$now" -gt "$before" ]; then break; fi
  sleep 2
done
echo "incidents after the failure: $(count_incidents) (expected $((before + 1)))"
echo "    newest row: $(tail -1 "$OUT_DIR/inc-list.csv")"

bc_get approver "$OUT_DIR/inc-index.html" "/batch-control/incidents/" > /dev/null
INCIDENT_ID=$(grep -o 'href="[0-9]\{8\}-[0-9]\{6\}-[a-z0-9]\{6\}/"' "$OUT_DIR/inc-index.html" \
        | sed -e 's#href="##' -e 's#/"##' | sort | tail -1)
echo "incident id = $INCIDENT_ID"
if [ -z "$INCIDENT_ID" ]; then echo "e2e: no incident id on the list page" >&2; exit 1; fi
DETAIL="/batch-control/incidents/$INCIDENT_ID/"

status=$(bc_get approver "$OUT_DIR/inc-detail-open.html" "$DETAIL")
echo "--- GET $DETAIL (approver) -> HTTP $status"
echo "    status on the page: $(grep -o 'OPEN\|ACKNOWLEDGED\|RESOLVED' "$OUT_DIR/inc-detail-open.html" | sort -u | tr '\n' ' ')"
echo "    links the failing run: $(grep -o 'href="[^"]*/job/'"$JOB"'/[0-9]*/*"' "$OUT_DIR/inc-detail-open.html" | sort -u | head -2 | tr '\n' ' ')"

# --- 3. the state machine must refuse a shortcut
status=$(bc_post approver "$OUT_DIR/inc-resolve-early.html" "${DETAIL}resolve" \
        --data-urlencode "comment=skipping acknowledgement on purpose")
echo "--- POST ${DETAIL}resolve while still OPEN -> HTTP $status (expected 400)"
echo "    message: $(grep -o -i '<h1>[^<]*\|Incident [^<]*is OPEN[^<]*' "$OUT_DIR/inc-resolve-early.html" | head -2 | tr '\n' ' ')"

# --- 4. acknowledge with the cause
status=$(bc_post approver "$OUT_DIR/inc-ack.html" "${DETAIL}acknowledge" \
        --data-urlencode "comment=Cause: the upstream feed delivered an empty file, the job exits 1 on an empty input.")
echo "--- POST ${DETAIL}acknowledge -> HTTP $status"

# --- 5. a comment for the action taken
status=$(bc_post approver "$OUT_DIR/inc-comment.html" "${DETAIL}comment" \
        --data-urlencode "comment=Action: asked the provider to redeliver; will rerun once the file is in place.")
echo "--- POST ${DETAIL}comment -> HTTP $status"

# an empty comment must be refused
status=$(bc_post approver "$OUT_DIR/inc-comment-empty.html" "${DETAIL}comment" --data-urlencode "comment=")
echo "--- POST ${DETAIL}comment with an empty comment -> HTTP $status (expected 400)"

# --- 6. resolve
status=$(bc_post approver "$OUT_DIR/inc-resolve.html" "${DETAIL}resolve" \
        --data-urlencode "comment=Resolved: the redelivered file processed cleanly.")
echo "--- POST ${DETAIL}resolve -> HTTP $status"

status=$(bc_get approver "$OUT_DIR/inc-detail-final.html" "$DETAIL")
echo "--- GET $DETAIL after the lifecycle -> HTTP $status"
echo "    status on the page: $(grep -o 'OPEN\|ACKNOWLEDGED\|RESOLVED' "$OUT_DIR/inc-detail-final.html" | sort -u | tr '\n' ' ')"
for needle in 'Cause: the upstream feed delivered an empty file' \
              'Action: asked the provider to redeliver' \
              'Resolved: the redelivered file processed cleanly'; do
  if grep -q -- "$needle" "$OUT_DIR/inc-detail-final.html"; then r=YES; else r=NO; fi
  printf '    keeps %-55s %s\n' "'${needle:0:45}'" "$r"
done

echo "--- incidents.csv row for $INCIDENT_ID:"
bc_get approver "$OUT_DIR/inc-list.csv" "/batch-control/history/incidents.csv" > /dev/null
grep "$INCIDENT_ID" "$OUT_DIR/inc-list.csv" | sed 's/^/    /'

echo "--- a resolved incident must not accept another transition:"
status=$(bc_post approver "$OUT_DIR/inc-ack-again.html" "${DETAIL}acknowledge" --data-urlencode "comment=again")
echo "    POST acknowledge on a RESOLVED incident -> HTTP $status (expected 400)"

echo "--- and the requester (no ViewHistory) must not reach any of it:"
status=$(bc_get requester "$OUT_DIR/inc-403.html" "$DETAIL")
echo "    GET $DETAIL as requester -> HTTP $status (expected 403)"
status=$(bc_post requester "$OUT_DIR/inc-403b.html" "${DETAIL}comment" --data-urlencode "comment=nope")
echo "    POST ${DETAIL}comment as requester -> HTTP $status (expected 403)"
