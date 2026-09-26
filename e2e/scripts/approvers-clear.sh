#!/usr/bin/env bash
# Empties the global approver list (T-E2E-08 needs that state for the browser
# pass). Restore it with approvers-restore.sh.
set -euo pipefail
. "$(dirname "$0")/lib.sh"
bc_login admin
cat > "$OUT_DIR/approvers-clear.groovy" <<'GROOVY'
import jenkins.model.Jenkins
def c = Jenkins.get().pluginManager.uberClassLoader
        .loadClass('io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration')
        .getMethod('get').invoke(null)
c.setApprovers(new ArrayList<String>())
c.save()
return "approvers now = ${c.getApprovers()}"
GROOVY
bc_script "$OUT_DIR/approvers-clear.groovy"
