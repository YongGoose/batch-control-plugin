#!/usr/bin/env bash
# ConfigureWithoutGrantMonitor - does the administrator's warning actually fire?
#
# In the correct e2e configuration it must NOT fire (only `admin`, who is exempt,
# holds standing change permissions), so its silence proves nothing on its own: an
# administrative warning that can never appear is a textbook silent failure. This
# scenario therefore breaks the configuration on purpose, twice, and puts it back:
#
#   case 1  a non-admin (requester) is given a standing Item/Configure
#   case 2  the plugin's wrapping authorization strategy is not selected at all
#           (the mistake an administrator makes when following the docs, which say
#            nothing about selecting it - see e2e-01 documentation defect 2)
#
# Restoring re-evaluates init.groovy.d/00-security.groovy, which rebuilds the
# intended matrix from scratch. That also gives the monitor a brand-new delegate
# instance, which is the cache key, so the next render recomputes immediately
# instead of waiting out the 5-minute TTL.
set -euo pipefail
. "$(dirname "$0")/lib.sh"

echo "### ConfigureWithoutGrantMonitor activation"
bc_login admin

SECURITY_SCRIPT=/var/jenkins_home/init.groovy.d/00-security.groovy

state() {   # prints the activation state of every batch-control monitor
  cat > "$OUT_DIR/mon-state.groovy" <<'GROOVY'
import hudson.model.AdministrativeMonitor
import jenkins.model.Jenkins
def sb = new StringBuilder()
def strategy = Jenkins.get().getAuthorizationStrategy()
sb << "strategy=${strategy.getClass().simpleName}"
sb << " delegate=${strategy.metaClass.respondsTo(strategy, 'getDelegate') ? strategy.getDelegate()?.getClass()?.simpleName : '(none)'}\n"
AdministrativeMonitor.all().findAll { it.getClass().name.startsWith('io.jenkins.plugins.batchcontrol') }.each {
  sb << "${it.getClass().simpleName}: enabled=${it.isEnabled()} activated=${it.isActivated()}\n"
}
return sb.toString()
GROOVY
  bc_script "$OUT_DIR/mon-state.groovy" | sed 's/^/    /'
}

banner() {  # is the warning on /manage/ ?
  bc_get admin "$OUT_DIR/mon-manage.html" "/manage/" > /dev/null
  if grep -q 'change control is enabled, but some users hold direct' "$OUT_DIR/mon-manage.html"; then
    echo "    /manage/ shows the warning banner: YES"
  else
    echo "    /manage/ shows the warning banner: NO"
  fi
  echo "    batch-control monitor ids in the page: $(grep -o 'batchcontrol[A-Za-z.]*Monitor' "$OUT_DIR/mon-manage.html" | sort -u | tr '\n' ' ')"
}

echo "--- 0. the correct configuration (expect activated=false, no banner)"
state
banner

echo "--- 1. misconfigure: give 'requester' a standing Item/Configure"
cat > "$OUT_DIR/mon-break1.groovy" <<GROOVY
// Re-runs the bootstrap script (which builds a fresh matrix and wrapper) and then
// adds the standing permission to that same fresh matrix, using the script's own
// 'grant' helper. A fresh delegate instance also resets the monitor's scan cache.
evaluate(new File('$SECURITY_SCRIPT').text + """
grant(hudson.model.Item.CONFIGURE, 'requester')
jenkins.save()
""")
return "requester now holds a standing Item/Configure from the delegate"
GROOVY
bc_script "$OUT_DIR/mon-break1.groovy" | sed 's/^/    /'
state
banner

echo "--- 2. misconfigure: drop the wrapping strategy (plain matrix only)"
cat > "$OUT_DIR/mon-break2.groovy" <<GROOVY
evaluate(new File('$SECURITY_SCRIPT').text + """
jenkins.setAuthorizationStrategy(matrix)
jenkins.save()
""")
return "authorization strategy is now the plain delegate, the wrapper is gone"
GROOVY
bc_script "$OUT_DIR/mon-break2.groovy" | sed 's/^/    /'
state
banner

echo "--- 3. restore the intended configuration"
cat > "$OUT_DIR/mon-restore.groovy" <<GROOVY
evaluate(new File('$SECURITY_SCRIPT').text)
return "restored"
GROOVY
bc_script "$OUT_DIR/mon-restore.groovy" | sed 's/^/    /'
state
banner

echo "--- 4. the requester's standing permission must be gone again"
status=$(bc_get requester "$OUT_DIR/mon-configure.html" "/job/batch-daily/configure" 2>/dev/null || true)
bc_login requester > /dev/null
status=$(bc_get requester "$OUT_DIR/mon-configure.html" "/job/batch-daily/configure")
echo "    GET /job/batch-daily/configure as requester -> HTTP $status (expected 403: no grant, no standing permission)"
