"""Arrange state for e2e-06 (script console, admin): extra users, prod/ folder."""
import sys
from lib import groovy, ENV
pw = ENV["BC_OTHER_PASSWORD"]
print(groovy(r'''
import jenkins.model.Jenkins
import hudson.model.*
import hudson.security.*
import org.jenkinsci.plugins.matrixauth.*
def j = Jenkins.get()
def realm = j.getSecurityRealm()
def users = [
  'mover1': ['hudson.model.Hudson.Read','hudson.model.Item.Read','hudson.model.View.Read','hudson.model.Item.Move','io.jenkins.plugins.batchcontrol.security.BatchControlPermissions.RequestGrant'],
  'mover2': ['hudson.model.Hudson.Read','hudson.model.Item.Read','hudson.model.View.Read','hudson.model.Item.Move','hudson.model.Item.Create','io.jenkins.plugins.batchcontrol.security.BatchControlPermissions.RequestGrant'],
  'mover3': ['hudson.model.Hudson.Read','hudson.model.Item.Read','hudson.model.View.Read','hudson.model.Item.Move','io.jenkins.plugins.batchcontrol.security.BatchControlPermissions.RequestGrant'],
]
def s = j.getAuthorizationStrategy()
def out = []
users.each { u, perms ->
  if (User.getById(u, false) == null) realm.createAccount(u, "''' + pw + r'''")
  perms.each { pid ->
    def p = Permission.fromId(pid)
    if (p == null) { out << "unknown ${pid}"; return }
    s.add(p, new PermissionEntry(AuthorizationType.USER, u))
  }
}
j.save()
def folderClass = com.cloudbees.hudson.plugins.folder.Folder
def prod = j.getItemByFullName('prod') ?: j.createProject(folderClass, 'prod')
['x','y','z'].each { n -> if (prod.getItem(n) == null) { def p = prod.createProject(FreeStyleProject, n); p.setDescription("prod/${n} original"); p.save() } }
out << "strategy=${s.getClass().name}"
out << "prod items=${prod.getItems()*.name}"
return out.join('\n')
'''))
