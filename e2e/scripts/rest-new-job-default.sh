#!/usr/bin/env bash
# D-31 / D-32 (issue #10) - while run control is on, every newly created job
# starts approval-required.
#
#   A  created through the REST createItem endpoint with no plugin property
#      -> the property must be there with approvalRequired=true
#   B  created with approvalRequired=false in the payload
#      -> the payload must not win (D-31: opting out is a later, recorded change)
#   C  created with a one-minute timer
#      -> the timer must still run it (approval blocks people, not automation)
#   D  created while run control is OFF
#      -> no default is applied (the feature must be invisible when switched off)
#
# Manual runs of A are refused with guidance; TIMER and SCM causes pass the gate.
# The four jobs are deleted at the end.
set -euo pipefail
. "$(dirname "$0")/lib.sh"

echo "### D-31 / D-32  the new-job approval default"

bc_login admin
bc_login requester

A=batch-e2e-d31-plain
B=batch-e2e-d31-optout
C=batch-e2e-d31-timer
D=batch-e2e-d31-switchoff

# minimal Freestyle payloads ----------------------------------------------------
make_config() {   # $1 = extra <properties> body, $2 = extra <triggers> body
  cat > "$OUT_DIR/d31-config.xml" <<XML
<?xml version='1.1' encoding='UTF-8'?>
<project>
  <description>Created by the e2e D-31 scenario.</description>
  <keepDependencies>false</keepDependencies>
  <properties>$1</properties>
  <scm class="hudson.scm.NullSCM"/>
  <canRoam>true</canRoam>
  <disabled>false</disabled>
  <blockBuildWhenDownstreamBuilding>false</blockBuildWhenDownstreamBuilding>
  <blockBuildWhenUpstreamBuilding>false</blockBuildWhenUpstreamBuilding>
  <triggers>$2</triggers>
  <concurrentBuild>false</concurrentBuild>
  <builders>
    <hudson.tasks.Shell>
      <command>echo "d31 sample job"</command>
    </hudson.tasks.Shell>
  </builders>
  <publishers/>
  <buildWrappers/>
</project>
XML
}

create_job() {    # $1 = name
  bc_post admin "$OUT_DIR/d31-create-$1.html" "/createItem?name=$1" \
    -H 'Content-Type: application/xml' --data-binary "@$OUT_DIR/d31-config.xml"
}

report_property() {  # $1 = name
  bc_get admin "$OUT_DIR/d31-$1.xml" "/job/$1/config.xml" > /dev/null
  if grep -q 'BatchControlJobProperty' "$OUT_DIR/d31-$1.xml"; then
    echo "    property present, approvalRequired = $(grep -o '<approvalRequired>[a-z]*' "$OUT_DIR/d31-$1.xml" | head -1 | sed 's#<approvalRequired>##')"
  else
    echo "    property absent"
  fi
}

# --- A: plain creation
make_config '' ''
echo "--- POST /createItem?name=$A (admin, no plugin property in the payload) -> HTTP $(create_job "$A")"
report_property "$A"

# --- B: the payload tries to opt out
make_config '
    <io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty>
      <approvalRequired>false</approvalRequired>
      <blockTimer>false</blockTimer>
      <blockUpstream>false</blockUpstream>
      <allowedUpstreamJobs/>
      <jobApprovers/>
    </io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty>' ''
echo "--- POST /createItem?name=$B (admin, payload says approvalRequired=false) -> HTTP $(create_job "$B")"
report_property "$B"

# --- A: a manual run must be refused with guidance, and nothing may build
status=$(bc_post requester "$OUT_DIR/d31-manual.html" "/job/$A/build?delay=0sec")
echo "--- POST /job/$A/build (requester) -> HTTP $status (expected 400 with guidance)"
echo "    guidance: $(grep -o -i 'Approval required[^<]*' "$OUT_DIR/d31-manual.html" | head -1)"

# --- A: which causes the queue gate lets through
cat > "$OUT_DIR/d31-causes.groovy" <<GROOVY
import hudson.model.CauseAction
import hudson.model.Cause
import hudson.model.Queue
import hudson.triggers.SCMTrigger
import hudson.triggers.TimerTrigger
import jenkins.model.Jenkins

def job = Jenkins.get().getItemByFullName('$A')
def sb = new StringBuilder()
def probe = { String label, Cause cause ->
    // A user-originated cause is refused by THROWING hudson.model.Failure (so the
    // person sees why); the unattended causes are refused by returning false.
    // Catching it here keeps all three results in one answer.
    try {
        def r = Queue.getInstance().schedule2(job, 0, [new CauseAction(cause)])
        sb << "\${label}: refused=\${r.isRefused()} created=\${r.isCreated()}\n"
    } catch (Throwable t) {
        sb << "\${label}: refused by \${t.getClass().simpleName} - \${t.message}\n"
    }
    // Keep the environment clean: drop whatever the probe queued.
    Queue.getInstance().clear()
}
probe('TIMER', new TimerTrigger.TimerTriggerCause())
probe('SCM  ', new SCMTrigger.SCMTriggerCause('e2e probe'))
probe('USER ', new Cause.UserIdCause('requester'))
return sb.toString()
GROOVY
echo "--- which causes pass the gate on $A (approval required, blockTimer=false):"
bc_script "$OUT_DIR/d31-causes.groovy" | sed 's/^/    /'

# --- C: a real timer run
make_config '' '
    <hudson.triggers.TimerTrigger>
      <spec>* * * * *</spec>
    </hudson.triggers.TimerTrigger>'
echo "--- POST /createItem?name=$C (admin, one-minute timer) -> HTTP $(create_job "$C")"
report_property "$C"
echo "--- waiting up to 80s for the timer to run the approval-required job"
built=no
for _ in $(seq 1 27); do
  bc_get admin "$OUT_DIR/d31-timer.json" "/job/$C/api/json?tree=builds[number,result]" > /dev/null
  if grep -q '"number"' "$OUT_DIR/d31-timer.json"; then built=yes; break; fi
  sleep 3
done
echo "    timer produced a build: $built  $(cat "$OUT_DIR/d31-timer.json")"

# --- D: run control off
cat > "$OUT_DIR/d31-switch.groovy" <<'GROOVY'
import jenkins.model.Jenkins
def c = Jenkins.get().pluginManager.uberClassLoader
        .loadClass('io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration')
        .getMethod('get').invoke(null)
c.setRunControlEnabled(RUN_CONTROL)
c.save()
return "runControlEnabled = ${c.isRunControlEnabled()}"
GROOVY
sed 's/RUN_CONTROL/false/' "$OUT_DIR/d31-switch.groovy" > "$OUT_DIR/d31-switch-off.groovy"
sed 's/RUN_CONTROL/true/'  "$OUT_DIR/d31-switch.groovy" > "$OUT_DIR/d31-switch-on.groovy"
echo "--- $(bc_script "$OUT_DIR/d31-switch-off.groovy")"
make_config '' ''
echo "--- POST /createItem?name=$D (admin, run control OFF) -> HTTP $(create_job "$D")"
report_property "$D"
echo "--- $(bc_script "$OUT_DIR/d31-switch-on.groovy")"

# --- cleanup
for job in "$A" "$B" "$C" "$D"; do
  status=$(bc_post admin "$OUT_DIR/d31-delete.html" "/job/$job/doDelete")
  echo "--- POST /job/$job/doDelete (admin) -> HTTP $status"
done
bc_get admin "$OUT_DIR/d31-jobs.json" "/api/json?tree=jobs[name]" > /dev/null
echo "jobs left: $(cat "$OUT_DIR/d31-jobs.json")"
