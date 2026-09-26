#!/usr/bin/env bash
# Administration surfaces that were only ever asserted at DOM level in the
# integration tests:
#   - the Batch Control section of /manage/configure (global settings)
#   - the "Batch Control" permission group on /manage/configureSecurity
#   - any active AdministrativeMonitor banner on /manage
set -euo pipefail
. "$(dirname "$0")/lib.sh"

echo "### administration screens"
bc_login admin

status=$(bc_get admin "$OUT_DIR/admin-configure.html" "/manage/configure")
echo "--- GET /manage/configure -> HTTP $status"
for field in runControlEnabled changeControlEnabled approversText allowAdminSelfApproval \
             pendingTimeoutHours approvedRunTimeoutMinutes grantDurationOptionsText \
             maxGrantMinutes incidentResultsText retentionMonths; do
  if grep -q "$field" "$OUT_DIR/admin-configure.html"; then r=YES; else r=NO; fi
  printf '    field %-28s %s\n' "$field" "$r"
done
echo "    section heading: $(grep -o 'Batch Control' "$OUT_DIR/admin-configure.html" | wc -l) occurrences of 'Batch Control'"

status=$(bc_get admin "$OUT_DIR/admin-security.html" "/manage/configureSecurity/")
echo "--- GET /manage/configureSecurity/ -> HTTP $status"
for needle in 'Batch Control' 'Manage' 'Request' 'Approve' 'RequestGrant' 'ViewHistory' \
              'BatchControlAuthorizationStrategy' 'Batch Control (wrapping)'; do
  if grep -q -- "$needle" "$OUT_DIR/admin-security.html"; then r=YES; else r=NO; fi
  printf '    mentions %-40s %s\n' "'$needle'" "$r"
done

status=$(bc_get admin "$OUT_DIR/admin-manage.html" "/manage/")
echo "--- GET /manage/ -> HTTP $status"
echo "    Batch Control entry: $(grep -o 'Batch Control[^<]*' "$OUT_DIR/admin-manage.html" | sort -u | tr '\n' ' ')"
echo "    alert/monitor banners: $(grep -o 'jenkins-alert[^"]*' "$OUT_DIR/admin-manage.html" | sort -u | tr '\n' ' ')"
echo "    monitor ids present: $(grep -o 'batchcontrol[A-Za-z.]*' "$OUT_DIR/admin-manage.html" | sort -u | tr '\n' ' ')"

status=$(bc_get admin "$OUT_DIR/admin-jobconfig.html" "/job/batch-daily/configure")
echo "--- GET /job/batch-daily/configure (admin) -> HTTP $status"
for needle in 'approvalRequired' 'blockTimer' 'blockUpstream' 'jobApproversText' 'allowedUpstreamJobsText'; do
  if grep -q -- "$needle" "$OUT_DIR/admin-jobconfig.html"; then r=YES; else r=NO; fi
  printf '    job property field %-26s %s\n' "$needle" "$r"
done
