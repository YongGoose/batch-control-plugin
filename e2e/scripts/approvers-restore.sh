#!/usr/bin/env bash
# Restores the approver list to [approver, admin] after approvers-clear.sh.
set -euo pipefail
. "$(dirname "$0")/lib.sh"
bc_login admin
cat > "$OUT_DIR/approvers-restore.groovy" <<'GROOVY'
import jenkins.model.Jenkins
def c = Jenkins.get().pluginManager.uberClassLoader
        .loadClass('io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration')
        .getMethod('get').invoke(null)
c.setApprovers(['approver', 'admin'])
c.save()
return "approvers restored = ${c.getApprovers()}"
GROOVY
bc_script "$OUT_DIR/approvers-restore.groovy"
