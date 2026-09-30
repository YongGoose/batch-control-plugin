#!/usr/bin/env bash
# D-34 / P-14 - while run control is on, every newly created job starts
# activation-locked: approvalRequired, blockTimer and blockUpstream all true and
# no upstream allow list, so no cause at all can start it until somebody turns a
# switch off in its configuration (a recorded CONFIGURE change).
#
#   A  created through REST createItem with no plugin property -> locked
#   B  created with a payload that opts out (approvalRequired=false,
#      blockTimer=false, blockUpstream=false, an upstream allow list)
#      -> the payload does not win, the job is locked
#   C  a TIMER cause on A is refused by the queue gate
#   D  admin clears blockTimer on A through config.xml -> the TIMER cause
#      passes, and a CONFIGURE change record for A exists
#
# Every check prints PASS/FAIL; the script exits non-zero on any FAIL. The jobs
# are deleted at the end.
set -euo pipefail
. "$(dirname "$0")/lib.sh"

echo "### D-34 / P-14  the new-job activation lock"

bc_login admin

A=batch-e2e-d34-plain
B=batch-e2e-d34-optout
FAILS=0
check() {   # $1 = label, $2 = expected, $3 = actual
  if [ "$2" = "$3" ]; then echo "PASS  $1 ($3)"; else echo "FAIL  $1: expected '$2', got '$3'"; FAILS=$((FAILS + 1)); fi
}

make_config() {   # $1 = extra <properties> body
  cat > "$OUT_DIR/d34-config.xml" <<XML
<?xml version='1.1' encoding='UTF-8'?>
<project>
  <description>Created by the e2e D-34 scenario.</description>
  <keepDependencies>false</keepDependencies>
  <properties>$1</properties>
  <scm class="hudson.scm.NullSCM"/>
  <canRoam>true</canRoam>
  <disabled>false</disabled>
  <blockBuildWhenDownstreamBuilding>false</blockBuildWhenDownstreamBuilding>
  <blockBuildWhenUpstreamBuilding>false</blockBuildWhenUpstreamBuilding>
  <triggers/>
  <concurrentBuild>false</concurrentBuild>
  <builders>
    <hudson.tasks.Shell>
      <command>echo "d34 sample job"</command>
    </hudson.tasks.Shell>
  </builders>
  <publishers/>
  <buildWrappers/>
</project>
XML
}

create_job() {    # $1 = name
  bc_post admin "$OUT_DIR/d34-create-$1.html" "/createItem?name=$1" \
    -H 'Content-Type: application/xml' --data-binary "@$OUT_DIR/d34-config.xml"
}

field() {         # $1 = job, $2 = element -> value of the plugin property's element
  bc_get admin "$OUT_DIR/d34-$1.xml" "/job/$1/config.xml" > /dev/null
  sed -n '/BatchControlJobProperty/,/\/io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty/p' \
    "$OUT_DIR/d34-$1.xml" | grep -o "<$2>[^<]*" | head -1 | sed "s#<$2>##"
}

upstream_list() { # $1 = job -> number of <string> entries in allowedUpstreamJobs
  bc_get admin "$OUT_DIR/d34-$1.xml" "/job/$1/config.xml" > /dev/null
  sed -n '/<allowedUpstreamJobs/,/<\/allowedUpstreamJobs>/p' "$OUT_DIR/d34-$1.xml" | grep -c '<string>' || true
}

assert_locked() { # $1 = job
  check "$1 approvalRequired" true "$(field "$1" approvalRequired)"
  check "$1 blockTimer"       true "$(field "$1" blockTimer)"
  check "$1 blockUpstream"    true "$(field "$1" blockUpstream)"
  check "$1 allowedUpstreamJobs entries" 0 "$(upstream_list "$1")"
}

# --- A: plain creation
make_config ''
check "POST /createItem?name=$A" 200 "$(create_job "$A")"
assert_locked "$A"

# --- B: the payload tries to opt out
make_config '
    <io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty>
      <approvalRequired>false</approvalRequired>
      <blockTimer>false</blockTimer>
      <blockUpstream>false</blockUpstream>
      <allowedUpstreamJobs><string>some-upstream</string></allowedUpstreamJobs>
      <jobApprovers/>
    </io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty>'
check "POST /createItem?name=$B (opt-out payload)" 200 "$(create_job "$B")"
assert_locked "$B"

# --- C/D: probe a TIMER cause through the queue gate
cat > "$OUT_DIR/d34-timer.groovy" <<GROOVY
import hudson.model.CauseAction
import hudson.model.Queue
import hudson.triggers.TimerTrigger
import jenkins.model.Jenkins
def job = Jenkins.get().getItemByFullName('$A')
def r = Queue.getInstance().schedule2(job, 0, [new CauseAction(new TimerTrigger.TimerTriggerCause())])
Queue.getInstance().clear()
return "created=\${r.isCreated()}"
GROOVY
check "TIMER cause on locked $A" "created=false" "$(bc_script "$OUT_DIR/d34-timer.groovy" | tr -d '[:space:]' | sed 's/^Result://')"

bc_get admin "$OUT_DIR/d34-edit.xml" "/job/$A/config.xml" > /dev/null
sed 's#<blockTimer>true</blockTimer>#<blockTimer>false</blockTimer>#' "$OUT_DIR/d34-edit.xml" > "$OUT_DIR/d34-edit-new.xml"
check "admin POST /job/$A/config.xml (blockTimer=false)" 200 \
  "$(bc_post admin "$OUT_DIR/d34-edit.html" "/job/$A/config.xml" \
      -H 'Content-Type: application/xml' --data-binary "@$OUT_DIR/d34-edit-new.xml")"
check "$A blockTimer after admin edit" false "$(field "$A" blockTimer)"
check "TIMER cause on $A after blockTimer cleared" "created=true" "$(bc_script "$OUT_DIR/d34-timer.groovy" | tr -d '[:space:]' | sed 's/^Result://')"

bc_get admin "$OUT_DIR/d34-changes.csv" "/batch-control/history/changes.csv" > /dev/null
check "CONFIGURE change record for $A" yes \
  "$(grep -q ",CONFIGURE,$A," "$OUT_DIR/d34-changes.csv" && echo yes || echo no)"

# --- cleanup
for job in "$A" "$B"; do
  bc_post admin "$OUT_DIR/d34-delete.html" "/job/$job/doDelete" > /dev/null
done

echo "### D-34 / P-14: $FAILS failure(s)"
[ "$FAILS" -eq 0 ]
