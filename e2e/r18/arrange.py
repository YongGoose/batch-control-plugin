"""e2e-18 arrangement (script console and REST as admin; arrangement only, no assertion about the plugin besides the
fixture check at the end).

Account w18: Overall/Read, Item/Read, View/Read, BatchControl/RequestGrant (no standing Configure, Create or Delete), with
the address w18@e2e.local: the requester and window holder of this pass. Items (Freestyle):
  r18/          regular folder: src-a (copied through the New Item page), old-a / old-b (changed while recording is off),
                rec-c (deleted and re-created while recording is off), blk (the refused approval), vis-y (moved into the
                vault before its GRANT_EXPIRING notice), iso-a / iso-b (expiry notice isolation)
  r18-vault/    folder whose matrix blocks inheritance: only administrators can read it (D-75 (1))
Idempotent: existing items are left alone; R18_RESET=1 first deletes what an earlier run created or moved (r18/copy-b,
r18-vault/vis-y, ...) and re-creates the originals. w18's leftover windows are revoked."""
import json
import os
import sys

sys.path.insert(0, __file__.rsplit("/", 1)[0])
from lib import groovy, gv, ENV, revoke_all  # noqa: E402

pw = ENV["BC_OTHER_PASSWORD"]
reset = "true" if os.environ.get("R18_RESET") == "1" else "false"
print(groovy(r'''
import jenkins.model.Jenkins
import hudson.model.*
import hudson.security.*
import org.jenkinsci.plugins.matrixauth.*
def j = Jenkins.get()
def realm = j.getSecurityRealm()
def BC = 'io.jenkins.plugins.batchcontrol.security.BatchControlPermissions.'
def s = j.getAuthorizationStrategy()
if (User.getById('w18', false) == null) realm.createAccount('w18', "''' + pw + r'''")
User.getById('w18', true).addProperty(new hudson.tasks.Mailer.UserProperty('w18@e2e.local'))
['hudson.model.Hudson.Read', 'hudson.model.Item.Read', 'hudson.model.View.Read', BC + 'RequestGrant'].each { pid ->
  s.add(Permission.fromId(pid), new PermissionEntry(AuthorizationType.USER, 'w18')) }
j.save()
def F = com.cloudbees.hudson.plugins.folder.Folder
if (''' + reset + r''') {
  ['r18/copy-b', 'r18-vault/vis-y', 'r18/vis-y', 'r18/rec-c', 'r18/old-a', 'r18/old-b'].each { n ->
    def i = j.getItemByFullName(n); if (i != null) i.delete() }
}
def job = { parent, n -> if (parent.getItem(n) == null) { def p = parent.createProject(FreeStyleProject, n); p.setDescription('e2e-18 v0 ' + n); p.save() } }
def r18 = j.getItem('r18') ?: j.createProject(F, 'r18')
['src-a', 'old-a', 'old-b', 'rec-c', 'blk', 'vis-y', 'iso-a', 'iso-b'].each { job(r18, it) }
def vault = j.getItem('r18-vault')
if (vault == null) {
  vault = j.createProject(F, 'r18-vault')
  def uber = j.pluginManager.uberClassLoader
  def prop = uber.loadClass('com.cloudbees.hudson.plugins.folder.properties.AuthorizationMatrixProperty').getConstructor(List).newInstance([])
  prop.setInheritanceStrategy(uber.loadClass('org.jenkinsci.plugins.matrixauth.inheritance.NonInheritingStrategy').getConstructor().newInstance())
  vault.addProperty(prop)
  vault.save()
}
return "r18=${r18.items*.name} vault=${vault.items*.name}"
'''))

print("revoked leftover windows:", {"w18": revoke_all("w18")})
facts = json.loads(gv(r'''
import jenkins.model.Jenkins
import hudson.model.*
def j = Jenkins.get()
def perms = { uid, item, p -> def u = User.getById(uid, false); u == null ? null : item.getACL().hasPermission2(u.impersonate2(), p) }
def blk = j.getItemByFullName('r18/blk')
def vault = j.getItem('r18-vault')
def BCP = io.jenkins.plugins.batchcontrol.security.BatchControlPermissions
return groovy.json.JsonOutput.toJson([
  w18_read: perms('w18', blk, Item.READ), w18_configure: perms('w18', blk, Item.CONFIGURE), w18_create: perms('w18', j.getItem('r18'), Item.CREATE),
  w18_request_grant: perms('w18', j, BCP.REQUEST_GRANT), w18_vault_read: perms('w18', vault, Item.READ),
  w18_mail: User.getById('w18', false)?.getProperty(hudson.tasks.Mailer.UserProperty)?.address,
  approver_vault_read: perms('approver-1', vault, Item.READ), admin_vault_read: perms('admin', vault, Item.READ),
  configurer_configure: perms('configurer', blk, Item.CONFIGURE), approver_history: perms('approver-1', j, BCP.VIEW_HISTORY)])'''))
ok = (facts.get("w18_read") is True and facts.get("w18_configure") is False and facts.get("w18_create") is False
      and facts.get("w18_request_grant") is True and facts.get("w18_vault_read") is False and facts.get("w18_mail") == "w18@e2e.local"
      and facts.get("approver_vault_read") is False and facts.get("admin_vault_read") is True
      and facts.get("configurer_configure") is True and facts.get("approver_history") is True)
print(("PASS " if ok else "FAIL ") + json.dumps({"sec": "arrange", "step": "r18 fixture preconditions", "ok": ok, **facts}))
sys.exit(0 if ok else 1)
