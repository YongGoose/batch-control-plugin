#!/usr/bin/env bash
# Sets the controller's executor count. With 0 executors an approved run stays in
# the queue, which is the only way to hold a request in APPROVED-but-not-yet-run
# long enough to look at the "the run starts shortly" notice on its detail page.
#
#   usage: executors.sh 0    # hold the queue
#          executors.sh 2    # let it drain again (the environment's normal value)
set -euo pipefail
. "$(dirname "$0")/lib.sh"
N="${1:?usage: executors.sh <count>}"
bc_login admin
cat > "$OUT_DIR/executors.groovy" <<GROOVY
import jenkins.model.Jenkins
Jenkins.get().setNumExecutors($N)
return "numExecutors = \${Jenkins.get().getNumExecutors()}"
GROOVY
bc_script "$OUT_DIR/executors.groovy"
