"""Item 2 server state after the moves: activation, the job property, builds, HELD / MOVE records."""
import re
from lib import groovy, api, clean, log
out = groovy(r'''
def s = io.jenkins.plugins.batchcontrol.policy.ActivationService.get()
def cl = jenkins.model.Jenkins.get().pluginManager.uberClassLoader.loadClass('io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty')
['team/mv-job','team/mvf/inner-job','team/adm-job','team/admf/adm-inner'].collect { n ->
  def j = jenkins.model.Jenkins.get().getItemByFullName(n)
  def p = j.getProperty(cl)
  def st = s.getState(n)
  "${n}: activated=${st?.isActivated()} deactivatedBy=${st?.getDeactivatedBy()} approvalRequired=${p?.isApprovalRequired()} blockTimer=${p?.isBlockTimer()} blockUpstream=${p?.isBlockUpstream()} allowedUpstream=${p?.getAllowedUpstreamJobs()} builds=${j.getBuilds()*.number} last=${j.getLastBuild()?.getTime()?.format('HH:mm:ss')}"
}.join('\n')
''')
print(out)
t = clean(api("admin", "/batch-control/changes/").text)
recs = [m.group(0) for m in re.finditer(r"\d{4}-\d\d-\d\d \d\d:\d\d:\d\d KST (HELD|MOVE|TRIGGER_BLOCKED) .{0,330}?(?=\d{4}-\d\d-\d\d \d\d:\d\d:\d\d KST|Page \d)", t)]
for r in recs[:16]:
    print(" -", r)
log("mvstate", {"state": out, "records": recs[:16]})
