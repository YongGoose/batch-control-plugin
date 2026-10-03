"""Arrange state for e2e-07 (script console, admin): extra users, folders, jobs. Arrangement only."""
from lib import groovy, ENV
pw = ENV["BC_OTHER_PASSWORD"]
print(groovy(r'''
import jenkins.model.Jenkins
import hudson.model.*
import hudson.security.*
import org.jenkinsci.plugins.matrixauth.*
def j = Jenkins.get()
def realm = j.getSecurityRealm()
def R = ['hudson.model.Hudson.Read','hudson.model.Item.Read','hudson.model.View.Read']
def BC = 'io.jenkins.plugins.batchcontrol.security.BatchControlPermissions.'
def users = [
  'mover1': R + ['hudson.model.Item.Move', BC+'RequestGrant'],
  'mover2': R + ['hudson.model.Item.Move','hudson.model.Item.Create', BC+'RequestGrant'],
  'mover3': R + ['hudson.model.Item.Move', BC+'RequestGrant'],
  'folderreq': R,
  'reqhist': R + [BC+'Request', BC+'ViewHistory'],
]
def s = j.getAuthorizationStrategy()
def out = []
users.each { u, perms ->
  if (User.getById(u, false) == null) realm.createAccount(u, "''' + pw + r'''")
  def usr = User.getById(u, true)
  usr.addProperty(new hudson.tasks.Mailer.UserProperty(u + '@e2e.local'))
  perms.each { pid ->
    def p = Permission.fromId(pid)
    if (p == null) { out << "unknown ${pid}"; return }
    s.add(p, new PermissionEntry(AuthorizationType.USER, u))
  }
}
j.save()
def F = com.cloudbees.hudson.plugins.folder.Folder
def team = j.getItemByFullName('team')
// folderreq: BatchControl/Request only on team/ (folder matrix)
def fp = team.getProperties().get(com.cloudbees.hudson.plugins.folder.properties.AuthorizationMatrixProperty)
fp.add(Permission.fromId(BC+'Request'), new PermissionEntry(AuthorizationType.USER, 'folderreq'))
team.save()
def sub = team.getItem('sub') ?: team.createProject(F, 'sub')
if (sub.getItem('deep-job') == null) sub.createProject(FreeStyleProject, 'deep-job')
def prod = j.getItemByFullName('prod') ?: j.createProject(F, 'prod')
['x','y','z','ok-move'].each { n -> if (prod.getItem(n) == null) { def p = prod.createProject(FreeStyleProject, n); p.setDescription("prod/${n} original"); p.save() } }
out << "strategy=${s.getClass().name}"
out << "team perms folderreq=${fp.hasExplicitPermission(new PermissionEntry(AuthorizationType.USER,'folderreq'), Permission.fromId(BC+'Request'))}"
out << "prod items=${prod.getItems()*.name}"
return out.join('\n')
'''))
