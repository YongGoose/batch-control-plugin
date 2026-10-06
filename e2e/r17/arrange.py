"""e2e-17 arrangement (script console and REST as admin; arrangement only, no assertion about the plugin besides the
fixture check at the end).

Account w17: Overall/Read, Item/Read, View/Read, BatchControl/RequestGrant (no standing Configure, Create or Delete): the
window holder of this pass. Items (Freestyle unless noted):
  r17/                   regular folder: rt-a (renamed twice), dc-job (deleted and re-created), vis-x (moved into the
                         vault), u-job (startup fail-closed), ctl (control window, never touched), nf-a and nf-x
                         (failed follow: nf-a renamed while the grants directory is read-only, nf-x renamed onto nf-a)
  r17-vault/             folder whose matrix blocks inheritance: only administrators can read it (D-75 (1))
  r17-dc-top             top-level job deleted and re-created under another letter case
  r17-gone, r17-gone2    top-level jobs whose directories are removed on disk before a reload (documented gap path)
  r17-other              renamed onto the name r17-gone after the reload
  r17-down               top-level job whose directory is removed before a restart (startup end, D-74 (3))
Idempotent: existing items are left alone (R17_PERMS_ONLY=1: no item is created or deleted); R17_RESET=1 first deletes what an earlier run of the drivers renamed or
created (RT-C, r17/rt-b, r17/rt-c, R17-GONE, ...) and re-creates the originals. R17_KEEP_WINDOWS=1 keeps w17's windows
(r17/disk.py re-arranges after a restart); otherwise w17's leftover windows are revoked."""
import json
import os
import sys

sys.path.insert(0, __file__.rsplit("/", 1)[0])
from lib import api, groovy, gv, ENV, revoke_all  # noqa: E402

pw = ENV["BC_OTHER_PASSWORD"]
reset = "true" if os.environ.get("R17_RESET") == "1" else "false"
# R17_PERMS_ONLY=1 (r17/disk.py after a reload or restart): the account and its permissions only. Creating a missing item
# here would end the windows whose items the driver removed on disk (a new item at a window's name ends it).
items = "false" if os.environ.get("R17_PERMS_ONLY") == "1" else "true"
print(groovy(r'''
import jenkins.model.Jenkins
import hudson.model.*
import hudson.security.*
import org.jenkinsci.plugins.matrixauth.*
def j = Jenkins.get()
def realm = j.getSecurityRealm()
def BC = 'io.jenkins.plugins.batchcontrol.security.BatchControlPermissions.'
def s = j.getAuthorizationStrategy()
if (User.getById('w17', false) == null) realm.createAccount('w17', "''' + pw + r'''")
User.getById('w17', true).addProperty(new hudson.tasks.Mailer.UserProperty('w17@e2e.local'))
['hudson.model.Hudson.Read', 'hudson.model.Item.Read', 'hudson.model.View.Read', BC + 'RequestGrant'].each { pid ->
  s.add(Permission.fromId(pid), new PermissionEntry(AuthorizationType.USER, 'w17')) }
j.save()
if (!''' + items + r''') return 'permissions only (R17_PERMS_ONLY=1)'
def F = com.cloudbees.hudson.plugins.folder.Folder
if (''' + reset + r''') {
  ['r17/rt-b', 'r17/rt-c', 'r17/RT-C', 'r17/rt-a', 'r17/dc-job', 'r17-vault/vis-x', 'r17/vis-x', 'R17-DC-TOP', 'r17-dc-top',
   'R17-GONE', 'R17-GONE2', 'r17-gone', 'r17-gone2', 'r17-other', 'r17-down', 'r17/u-job', 'r17/nf-a', 'r17/nf-b', 'r17/nf-x'].each { n ->
    def i = j.getItemByFullName(n); if (i != null) i.delete() }
}
def job = { parent, n -> if (parent.getItem(n) == null) { def p = parent.createProject(FreeStyleProject, n); p.setDescription('e2e-17'); p.save() } }
def r17 = j.getItem('r17') ?: j.createProject(F, 'r17')
['rt-a', 'dc-job', 'vis-x', 'u-job', 'ctl', 'nf-a', 'nf-x'].each { job(r17, it) }
def vault = j.getItem('r17-vault')
if (vault == null) {
  vault = j.createProject(F, 'r17-vault')
  def uber = j.pluginManager.uberClassLoader
  def prop = uber.loadClass('com.cloudbees.hudson.plugins.folder.properties.AuthorizationMatrixProperty').getConstructor(List).newInstance([])
  prop.setInheritanceStrategy(uber.loadClass('org.jenkinsci.plugins.matrixauth.inheritance.NonInheritingStrategy').getConstructor().newInstance())
  vault.addProperty(prop)
  vault.save()
}
['r17-dc-top', 'r17-gone', 'r17-gone2', 'r17-other', 'r17-down'].each { job(j, it) }
return "r17=${r17.items*.name} vault=${vault.items*.name} top=${['r17-dc-top', 'r17-gone', 'r17-gone2', 'r17-other', 'r17-down'].findAll { j.getItem(it) != null }}"
'''))

# Fixture check: what the r17 drivers assume. w17 holds Item/Read and RequestGrant only; nobody but administrators reads
# the vault; every job starts approval-required (created while run control is on, D-31) - not needed by the drivers but
# shows the plugin is on.
if os.environ.get("R17_KEEP_WINDOWS") == "1":
    print("leftover windows kept (R17_KEEP_WINDOWS=1)")
else:
    print("revoked leftover windows:", {"w17": revoke_all("w17")})
facts = json.loads(gv(r'''
import jenkins.model.Jenkins
import hudson.model.*
import hudson.security.ACL
def j = Jenkins.get()
def perms = { uid, item, p -> def u = User.getById(uid, false); u == null ? null : item.getACL().hasPermission2(u.impersonate2(), p) }
def ctl = j.getItemByFullName('r17/ctl')
def vault = j.getItem('r17-vault')
return groovy.json.JsonOutput.toJson([
  w17_read: perms('w17', ctl, Item.READ), w17_configure: perms('w17', ctl, Item.CONFIGURE), w17_create: perms('w17', j.getItem('r17'), Item.CREATE),
  w17_delete: perms('w17', ctl, Item.DELETE), w17_vault_read: perms('w17', vault, Item.READ),
  approver_vault_read: perms('approver-1', vault, Item.READ), admin_vault_read: perms('admin', vault, Item.READ),
  approver_r17_read: perms('approver-1', ctl, Item.READ)])'''))
keep = os.environ.get("R17_KEEP_WINDOWS") == "1"  # kept windows may confer Configure on r17/ctl (the control window)
ok = (facts.get("w17_read") is True and (keep or facts.get("w17_configure") is False) and facts.get("w17_create") is False
      and facts.get("w17_delete") is False and facts.get("w17_vault_read") is False and facts.get("approver_vault_read") is False
      and facts.get("admin_vault_read") is True and facts.get("approver_r17_read") is True)
print(("PASS " if ok else "FAIL ") + json.dumps({"sec": "arrange", "step": "r17 fixture preconditions", "ok": ok, **facts}))
sys.exit(0 if ok else 1)
