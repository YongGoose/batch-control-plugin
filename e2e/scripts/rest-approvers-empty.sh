#!/usr/bin/env bash
# T-E2E-08 - with run control on and an EMPTY global approver list, the run
# request screen must warn the requester instead of offering an empty select.
#
# The approver list is emptied through the admin script console (there is no
# HTTP surface for a single field) and restored afterwards.
set -euo pipefail
. "$(dirname "$0")/lib.sh"

echo "### T-E2E-08  empty approver list"
bc_login admin
bc_login requester

cat > "$OUT_DIR/approvers-set.groovy" <<'GROOVY'
import jenkins.model.Jenkins
def c = Jenkins.get().pluginManager.uberClassLoader
        .loadClass('io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration')
        .getMethod('get').invoke(null)
c.setApprovers(new ArrayList<String>())
c.save()
return "approvers now = ${c.getApprovers()}"
GROOVY
echo "--- $(bc_script "$OUT_DIR/approvers-set.groovy")"

status=$(bc_get requester "$OUT_DIR/approvers-empty-form.html" "/job/batch-daily/batch-control/")
echo "--- GET /job/batch-daily/batch-control/ (requester, no approvers) -> HTTP $status"
if grep -q 'No approvers are configured' "$OUT_DIR/approvers-empty-form.html"; then
  echo "    warning shown: YES"
  grep -o 'jenkins-alert[^"]*' "$OUT_DIR/approvers-empty-form.html" | head -1
  grep -o 'No approvers are configured[^<]*' "$OUT_DIR/approvers-empty-form.html" | head -1
else
  echo "    warning shown: NO"
fi

# submitting anyway must be refused, not silently accepted
json='{"reason":"submit with no approver configured","approver":"","parameter":[{"name":"DATE","value":"2026-02-02"},{"name":"MODE","value":"full"}]}'
status=$(bc_post requester "$OUT_DIR/approvers-empty-submit.html" "/job/batch-daily/batch-control/submit" \
        --data-urlencode "json=$json")
echo "--- POST submit with no approver -> HTTP $status"
grep -o -i '<h1>[^<]*\|Failure[^<]*\|must[^<]*\|required[^<]*' "$OUT_DIR/approvers-empty-submit.html" | head -3

cat > "$OUT_DIR/approvers-restore.groovy" <<'GROOVY'
import jenkins.model.Jenkins
def c = Jenkins.get().pluginManager.uberClassLoader
        .loadClass('io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration')
        .getMethod('get').invoke(null)
c.setApprovers(['approver', 'admin'])
c.save()
return "approvers restored = ${c.getApprovers()}"
GROOVY
echo "--- $(bc_script "$OUT_DIR/approvers-restore.groovy")"
