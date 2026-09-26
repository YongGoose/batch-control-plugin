#!/usr/bin/env bash
# D-30 (issue #9) - a blocked re-use of an approved-run marker is refused AND
# audited.
#
# What is verified:
#   1. a normal approved run leaves NO MARKER_REUSE_BLOCKED record (no false
#      positives - this is the half that would make the feature useless);
#   2. replaying the spent marker of an EXECUTED request is refused, no build
#      starts, and a record is written naming the ACCOUNT THAT TRIED, not the
#      requester of the original approval;
#   3. presenting the marker on a job it was not issued for is refused and
#      recorded against the job it was presented on;
#   4. the attempts are visible on the history screen, not only in the log.
#
# The marker is plugin-internal: no HTTP endpoint hands one out, so the replay is
# submitted through the admin script console, which puts the request through the
# real queue gate (Queue.schedule2 -> QueueDecisionHandler). The attempting
# identity is impersonated so the recorded actor is a different person from the
# requester.
set -euo pipefail
. "$(dirname "$0")/lib.sh"

echo "### D-30  blocked approval-marker re-use"

bc_login requester
bc_login approver
bc_login admin

count_reuse() {
  bc_get admin "$OUT_DIR/reuse-changes.csv" "/batch-control/history/changes.csv" > /dev/null
  grep -c 'MARKER_REUSE_BLOCKED' "$OUT_DIR/reuse-changes.csv" || true
}
count_builds() {
  bc_get admin "$OUT_DIR/reuse-builds.json" "/job/$1/api/json?tree=builds[number]" > /dev/null
  # The grep must not fail the pipeline under `set -o pipefail`: a job with no
  # builds at all is a legitimate answer here (batch-pipeline starts empty).
  { grep -o '"number":[0-9]*' "$OUT_DIR/reuse-builds.json" || true; } | wc -l | tr -d ' '
}

before_records=$(count_reuse)
before_daily=$(count_builds batch-daily)
before_pipeline=$(count_builds batch-pipeline)
echo "before: $before_records MARKER_REUSE_BLOCKED records, batch-daily $before_daily builds, batch-pipeline $before_pipeline builds"

# --- 1. one ordinary approved run
json='{"reason":"D-30 baseline: one ordinary approved run","approver":"approver","parameter":[{"name":"DATE","value":"2026-04-04"},{"name":"MODE","value":"full"}]}'
status=$(bc_post requester "$OUT_DIR/reuse-create.html" "/job/batch-daily/batch-control/submit" \
        -D "$OUT_DIR/reuse-create.headers" --data-urlencode "json=$json")
REQUEST_PATH=$(grep -i '^location:' "$OUT_DIR/reuse-create.headers" | tail -1 \
        | sed -e 's/^[Ll]ocation: *//' -e 's#^https*://[^/]*##' | tr -d '\r')
REQUEST_ID=$(basename "$REQUEST_PATH")
echo "--- POST submit (requester) -> HTTP $status, request $REQUEST_ID"
status=$(bc_post approver "$OUT_DIR/reuse-approve.html" "${REQUEST_PATH}approve" \
        --data-urlencode "comment=D-30 baseline")
echo "--- POST ${REQUEST_PATH}approve (approver) -> HTTP $status"

echo "--- waiting for the approved build to finish"
for _ in $(seq 1 30); do
  bc_get admin "$OUT_DIR/reuse-last.json" "/job/batch-daily/lastBuild/api/json?tree=number,building,result" > /dev/null
  if grep -q '"building":false' "$OUT_DIR/reuse-last.json"; then break; fi
  sleep 2
done
echo "    lastBuild: $(cat "$OUT_DIR/reuse-last.json")"
bc_get admin "$OUT_DIR/reuse-detail.html" "$REQUEST_PATH" > /dev/null
echo "    request status: $(grep -o -i 'EXECUTED\|APPROVED\|PENDING' "$OUT_DIR/reuse-detail.html" | sort -u | tr '\n' ' ')"
after_normal=$(count_reuse)
echo "--- records after an ordinary run: $after_normal (expected $before_records - a legitimate run must not be audited as re-use)"

# --- 2. replay the spent marker, as a DIFFERENT account than the requester
cat > "$OUT_DIR/reuse-replay.groovy" <<GROOVY
import hudson.model.Queue
import hudson.security.ACL
import hudson.model.User
import jenkins.model.Jenkins

def jenkins = Jenkins.get()
def uber = jenkins.pluginManager.uberClassLoader
def markerClass = uber.loadClass('io.jenkins.plugins.batchcontrol.queue.ApprovedRunAction')
def marker = markerClass.getConstructor(String).newInstance('$REQUEST_ID')
def sb = new StringBuilder()

// 'approver' is deliberately not the requester of the approval, so the recorded
// actor proves the record names who tried rather than who was approved.
def ctx = ACL.as2(User.getById('approver', true).impersonate2())
try {
  def result = Queue.getInstance().schedule2(jenkins.getItemByFullName('batch-daily'), 0, [marker])
  sb << "same job, spent marker: refused=\${result.isRefused()} created=\${result.isCreated()}\n"
} finally { ctx.close() }

// The same marker presented on a job it was never issued for, attempted by admin.
def result2 = Queue.getInstance().schedule2(jenkins.getItemByFullName('batch-pipeline'), 0, [marker])
sb << "other job: refused=\${result2.isRefused()} created=\${result2.isCreated()}\n"
return sb.toString()
GROOVY
echo "--- replaying the marker through the queue gate:"
bc_script "$OUT_DIR/reuse-replay.groovy" | sed 's/^/    /'

sleep 5
after_replay=$(count_reuse)
echo "--- records after the two replays: $after_replay (expected $((before_records + 2)))"
echo "--- the new records:"
grep 'MARKER_REUSE_BLOCKED' "$OUT_DIR/reuse-changes.csv" | tail -2 | sed 's/^/    /'

echo "--- no build may have started:"
echo "    batch-daily   $(count_builds batch-daily) builds (before the replays: $((before_daily + 1)) including the legitimate run)"
echo "    batch-pipeline $(count_builds batch-pipeline) builds (expected $before_pipeline)"
bc_get admin "$OUT_DIR/reuse-queue.json" "/queue/api/json?tree=items[task[name],why]" > /dev/null
echo "    queue: $(cat "$OUT_DIR/reuse-queue.json")"

# --- 3. the attempts must be readable on the history screen, not just in the log
status=$(bc_get approver "$OUT_DIR/reuse-history.html" "/batch-control/history/")
echo "--- GET /batch-control/history/ (approver, default Runs tab) -> HTTP $status"
for needle in 'Blocked re-use of an approved-run marker' 'Attempted by' 'attempt(s)' "$REQUEST_ID"; do
  if grep -q -- "$needle" "$OUT_DIR/reuse-history.html"; then r=YES; else r=NO; fi
  printf '    warning block carries %-45s %s\n' "'$needle'" "$r"
done
echo "    rows in the warning table: $(grep -c 'MARKER_REUSE\|Blocked re-use' "$OUT_DIR/reuse-history.html" || true) markers of the block"
echo "    accounts named in the block: $(grep -o '<td>approver</td>\|<td>admin</td>\|<td>requester</td>' "$OUT_DIR/reuse-history.html" | sort | uniq -c | tr '\n' ' ')"
