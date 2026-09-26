#!/usr/bin/env bash
# The direct-build path on an approval-protected job: a user-originated build
# must be refused with guidance (SPEC item 6 / PoC assumption D), not silently
# dropped, and no build may start.
set -euo pipefail
. "$(dirname "$0")/lib.sh"

echo "### direct build attempt on an approval-protected job"
bc_login requester
bc_login admin

before=$(bc_get admin "$OUT_DIR/blocked-before.json" "/job/batch-daily/api/json?tree=builds[number]" > /dev/null; \
         grep -o '"number":[0-9]*' "$OUT_DIR/blocked-before.json" | wc -l | tr -d ' ')
echo "builds before: $before"

status=$(bc_post requester "$OUT_DIR/blocked-build.html" "/job/batch-daily/build?delay=0sec")
echo "--- POST /job/batch-daily/build (requester) -> HTTP $status  (core refuses a parameterized job without a form submission before the plugin is reached)"
grep -o '<h1>[^<]*\|<h2>[^<]*' "$OUT_DIR/blocked-build.html" | head -3
echo "    guidance text: $(grep -o -i 'Approval required[^<]*' "$OUT_DIR/blocked-build.html" | head -1)"
echo "    link offered:  $(grep -o 'href="[^"]*batch-control[^"]*"' "$OUT_DIR/blocked-build.html" | sort -u | tr '\n' ' ')"

status=$(bc_post requester "$OUT_DIR/blocked-buildwp.html" "/job/batch-daily/buildWithParameters?DATE=2026-03-03&MODE=full")
echo "--- POST /job/batch-daily/buildWithParameters (requester) -> HTTP $status"
echo "    guidance text: $(grep -o -i 'Approval required[^<]*' "$OUT_DIR/blocked-buildwp.html" | head -1)"

sleep 5
after=$(bc_get admin "$OUT_DIR/blocked-after.json" "/job/batch-daily/api/json?tree=builds[number]" > /dev/null; \
        grep -o '"number":[0-9]*' "$OUT_DIR/blocked-after.json" | wc -l | tr -d ' ')
echo "builds after: $after (expected $before)"
bc_get admin "$OUT_DIR/blocked-queue.json" "/queue/api/json?tree=items[task[name],why]" > /dev/null
echo "queue: $(cat "$OUT_DIR/blocked-queue.json")"
