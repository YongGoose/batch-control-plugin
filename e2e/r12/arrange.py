"""e2e-11 arrangement (script console, admin; arrangement only).
classic: requester's global permissions, new job page turned off (classic sidebar checks).
fonly: Read + Item/Move + RequestGrant globally (FOLDER_ONLY window holder, D-65).
ops/a (job), ops/sub (folder) with ops/sub/b, ops/mb (multibranch), ops/sub2 (target for moves),
fast (non-approval job, `true`) for the dashboard seed (R4-13)."""
from lib import groovy, ENV
pw = ENV["BC_OTHER_PASSWORD"]
print(groovy(r'''
import jenkins.model.Jenkins
import hudson.model.*
import hudson.security.*
import org.jenkinsci.plugins.matrixauth.*
def j = Jenkins.get()
def realm = j.getSecurityRealm()
def BC = 'io.jenkins.plugins.batchcontrol.security.BatchControlPermissions.'
def R = ['hudson.model.Hudson.Read','hudson.model.Item.Read','hudson.model.View.Read']
def users = [
  'classic': R + ['hudson.model.Item.Build', BC+'Request', BC+'RequestGrant'],
  'fonly': R + ['hudson.model.Item.Move', BC+'RequestGrant'],
]
def s = j.getAuthorizationStrategy()
users.each { u, perms ->
  if (User.getById(u, false) == null) realm.createAccount(u, "''' + pw + r'''")
  User.getById(u, true).addProperty(new hudson.tasks.Mailer.UserProperty(u + '@e2e.local'))
  perms.each { pid -> s.add(Permission.fromId(pid), new PermissionEntry(AuthorizationType.USER, u)) }
}
j.save()
def F = com.cloudbees.hudson.plugins.folder.Folder
def ops = j.getItemByFullName('ops')
if (ops.getItem('a') == null) ops.createProject(FreeStyleProject, 'a')
def sub = ops.getItem('sub') ?: ops.createProject(F, 'sub')
if (sub.getItem('b') == null) sub.createProject(FreeStyleProject, 'b')
if (ops.getItem('mb') == null) ops.createProject(org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject, 'mb')
if (j.getItem('fast') == null) { def p = j.createProject(FreeStyleProject, 'fast'); p.getBuildersList().add(new hudson.tasks.Shell('true')); p.save() }
return "ops=${ops.items*.name} sub=${sub.items*.name} strategy=${s.getClass().simpleName}"
'''))
