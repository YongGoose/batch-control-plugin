"""Arrange state for e2e-08 (script console, admin; arrangement only).
opsreq: Overall/Read globally, BatchControl/Request + Item/Read only on folder ops/ (D-38b).
mover1: Item/Move + RequestGrant globally (D-59a). Jobs: ops/job-a, ops/cron-a, prod/mv-job,
prod/mvf/inner-job, prod/adm-job, prod/admf/adm-inner (one-minute timers, timer not blocked)."""
from lib import groovy, ENV
pw = ENV["BC_OTHER_PASSWORD"]
print(groovy(r'''
import jenkins.model.Jenkins
import hudson.model.*
import hudson.security.*
import hudson.tasks.Shell
import hudson.triggers.TimerTrigger
import org.jenkinsci.plugins.matrixauth.*
import com.cloudbees.hudson.plugins.folder.properties.AuthorizationMatrixProperty as FAMP
def j = Jenkins.get()
def realm = j.getSecurityRealm()
def BC = 'io.jenkins.plugins.batchcontrol.security.BatchControlPermissions.'
def users = [
  'mover1': ['hudson.model.Hudson.Read','hudson.model.Item.Read','hudson.model.View.Read','hudson.model.Item.Move', BC+'RequestGrant'],
  'opsreq': ['hudson.model.Hudson.Read'],
]
def s = j.getAuthorizationStrategy()
def out = []
users.each { u, perms ->
  if (User.getById(u, false) == null) realm.createAccount(u, "''' + pw + r'''")
  User.getById(u, true).addProperty(new hudson.tasks.Mailer.UserProperty(u + '@e2e.local'))
  perms.each { pid -> s.add(Permission.fromId(pid), new PermissionEntry(AuthorizationType.USER, u)) }
}
j.save()
def F = com.cloudbees.hudson.plugins.folder.Folder

def ops = j.getItemByFullName('ops') ?: j.createProject(F, 'ops')
def fp = ops.getProperties().get(FAMP)
if (fp == null) { fp = new FAMP([:]); ops.addProperty(fp) }
fp.add(Permission.fromId(BC+'Request'), new PermissionEntry(AuthorizationType.USER, 'opsreq'))
fp.add(Item.READ, new PermissionEntry(AuthorizationType.USER, 'opsreq'))
ops.save()
def cl = j.pluginManager.uberClassLoader.loadClass('io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty')
def mk = { parent, String n, boolean timer, boolean approval ->
  def p = parent.getItem(n)
  if (p == null) {
    p = parent.createProject(FreeStyleProject, n)
    p.getBuildersList().add(new Shell('echo tick'))
    if (timer) { def t = new TimerTrigger('* * * * *'); p.addTrigger(t); p.save(); t.start(p, true) }
    p.removeProperty(cl)
    p.addProperty(cl.getConstructor(boolean).newInstance(approval))   // blockTimer=false, blockUpstream=false
    p.save()
  }
  def pr = p.getProperty(cl)
  out << "${p.fullName}: approval=${pr.isApprovalRequired()} blockTimer=${pr.isBlockTimer()} blockUpstream=${pr.isBlockUpstream()}"
}
mk(ops, 'job-a', false, true)
mk(ops, 'cron-a', true, false)
def prod = j.getItemByFullName('prod') ?: j.createProject(F, 'prod')
mk(prod, 'mv-job', true, false)
def mvf = prod.getItem('mvf') ?: prod.createProject(F, 'mvf')
mk(mvf, 'inner-job', true, false)
mk(prod, 'adm-job', true, false)
def admf = prod.getItem('admf') ?: prod.createProject(F, 'admf')
mk(admf, 'adm-inner', true, false)
out << "strategy=${s.getClass().name}"
return out.join('\n')
'''))
