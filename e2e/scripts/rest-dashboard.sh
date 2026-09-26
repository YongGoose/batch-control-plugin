#!/usr/bin/env bash
# T-10-06 - the run dashboard shows only the last 7 days and pages at 50 rows.
#
# Volume is produced by triggering batch-cron (no approval required) rather than
# waiting for its one-minute timer. The "older than 7 days" row cannot be
# produced by running anything, so one record is written straight into the store
# with a startedAt 8 days in the past and Jenkins is asked to reload it.
set -euo pipefail
. "$(dirname "$0")/lib.sh"

BUILDS="${1:-55}"

echo "### T-10-06  dashboard window and paging"
bc_login admin
bc_login requester

echo "--- triggering $BUILDS builds of batch-cron as requester"
for i in $(seq 1 "$BUILDS"); do
  status=$(bc_post requester "$OUT_DIR/dash-trigger.html" "/job/batch-cron/build?delay=0sec")
  if [ "$status" != "201" ] && [ "$status" != "302" ]; then
    echo "    build $i -> HTTP $status (expected 201/302)"
  fi
done

echo "--- waiting for the queue to drain"
for _ in $(seq 1 90); do
  bc_get admin "$OUT_DIR/dash-queue.json" "/queue/api/json?tree=items[id]" > /dev/null
  bc_get admin "$OUT_DIR/dash-cron.json" "/job/batch-cron/api/json?tree=lastBuild[number,building]" > /dev/null
  if grep -q '"items":\[\]' "$OUT_DIR/dash-queue.json" && grep -q '"building":false' "$OUT_DIR/dash-cron.json"; then
    break
  fi
  sleep 3
done
echo "    batch-cron: $(cat "$OUT_DIR/dash-cron.json")"

status=$(bc_get admin "$OUT_DIR/dash-runs.csv" "/batch-control/history/runs.csv")
echo "--- runs.csv -> HTTP $status, $(( $(grep -c '' "$OUT_DIR/dash-runs.csv") - 1 )) records in the store"
echo "    cause types: $(cut -d, -f4 "$OUT_DIR/dash-runs.csv" | tail -n +2 | sort | uniq -c | tr '\n' ' ')"

status=$(bc_get admin "$OUT_DIR/dash-page1.html" "/batch-control/dashboard/")
rows=$(grep -c '<tr' "$OUT_DIR/dash-page1.html" || true)
echo "--- GET /batch-control/dashboard/ -> HTTP $status, $rows <tr> elements (header rows included)"
echo "    paging controls: $(grep -o 'href="[^"]*page=[0-9]*"' "$OUT_DIR/dash-page1.html" | sort -u | tr '\n' ' ')"
echo "    page text: $(grep -o -i 'page [0-9]* of [0-9]*\|Next\|Previous\|Older\|Newer' "$OUT_DIR/dash-page1.html" | sort -u | tr '\n' ' ')"

# --- the 8-day-old record
cat > "$OUT_DIR/dash-old.groovy" <<'GROOVY'
import jenkins.model.Jenkins
def dir = new File(Jenkins.get().getRootDir(), 'batch-control/runs')
def now = System.currentTimeMillis()
def old = now - 8L * 24 * 60 * 60 * 1000
def month = new java.text.SimpleDateFormat('yyyy-MM').format(new Date(old))
def file = new File(dir, "${month}.jsonl")
def line = '{"runId":"batch-cron#9001","jobFullName":"batch-cron","number":9001,"causeType":"TIMER",' +
        '"result":"SUCCESS","startedAt":' + old + ',"durationMs":1000,"parameters":{}}'
file.append(line + "\n", 'UTF-8')
return "appended an 8-day-old record to ${file} (startedAt=${new Date(old)})"
GROOVY
echo "--- $(bc_script "$OUT_DIR/dash-old.groovy")"

status=$(bc_get admin "$OUT_DIR/dash-page1b.html" "/batch-control/dashboard/")
echo "--- GET /batch-control/dashboard/ after seeding the old record -> HTTP $status"
if grep -q 'batch-cron#9001\|>9001<' "$OUT_DIR/dash-page1b.html"; then
  echo "    the 8-day-old run appears in the default view: YES (expected NO)"
else
  echo "    the 8-day-old run appears in the default view: NO (as expected)"
fi
status=$(bc_get admin "$OUT_DIR/dash-runs2.csv" "/batch-control/history/runs.csv")
echo "    runs.csv still lists it: $(grep -c 'batch-cron#9001' "$OUT_DIR/dash-runs2.csv" || true)"
