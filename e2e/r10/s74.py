"""#74: Move to a Discover-only destination: folders' own answer, no Batch Control 403, nothing moved, no record."""
import re
from lib import Session, close, log, groovy, api, ENV, clean
res = {}
res["arrange"] = groovy(r'''
import hudson.model.*
import hudson.security.*
import org.jenkinsci.plugins.matrixauth.*
import com.cloudbees.hudson.plugins.folder.properties.AuthorizationMatrixProperty as FAMP
def j = jenkins.model.Jenkins.get()
if (User.getById('moverd', false) == null) j.getSecurityRealm().createAccount('moverd', "''' + ENV["BC_OTHER_PASSWORD"] + r'''")
def s = j.getAuthorizationStrategy()
['hudson.model.Hudson.Read','hudson.model.View.Read'].each { s.add(Permission.fromId(it), new PermissionEntry(AuthorizationType.USER, 'moverd')) }
j.save()
def prop = { f -> def p = f.getProperties().get(FAMP); if (p == null) { p = new FAMP([:]); f.addProperty(p) }; p }
def prod = j.getItemByFullName('prod'); def pp = prop(prod)
[Item.READ, Permission.fromId('hudson.model.Item.Move'), Item.DELETE].each { pp.add(it, new PermissionEntry(AuthorizationType.USER, 'moverd')) }; prod.save()
def ops = j.getItemByFullName('ops'); def op = prop(ops)
op.add(Item.DISCOVER, new PermissionEntry(AuthorizationType.USER, 'moverd')); ops.save()
def U = User.getById('moverd', true).impersonate2()
return "ops read=" + ops.getACL().hasPermission2(U, Item.READ) + " discover=" + ops.getACL().hasPermission2(U, Item.DISCOVER) + " x move=" + j.getItemByFullName('prod/x').getACL().hasPermission2(U, Permission.fromId('hudson.model.Item.Move'))
''')
before = api("admin", "/batch-control/changes/").text
s = Session("moverd")
r = s.go("/job/prod/job/x/move/")
res["move page"] = r.status
res["options"] = s.page.locator('select[name="destination"] option').all_inner_texts() if r.status == 200 else None
s.shot("#main-panel", "74-01-move-page")
# the browser select does not offer /ops, so post the form value the way a crafted request would, from the same session
resp = s.page.evaluate("""async () => {
  const f = document.querySelector('form[action$="move"]') || document.querySelector('form');
  const fd = new FormData(f); fd.set('destination', '/ops');
  const r = await fetch(f.action, {method: 'POST', body: new URLSearchParams(fd), redirect: 'manual'});
  return {status: r.status, type: r.type, url: r.url, body: (await r.text()).slice(0, 4000)};
}""")
res["post /ops status"] = resp["status"]
res["post body"] = clean(resp["body"])[:500]
res["batch control wording in body"] = bool(re.search(r"Batch Control|permission window|Request a permission", resp["body"]))
s.page.set_content(resp["body"] or "<p>empty</p>")
s.page.screenshot(path=str(__import__("lib").SHOTS / "74-02-response.png"), full_page=True)
# compare: a destination that does not exist at all (pure folders behaviour)
resp2 = s.page.evaluate("""async (b) => { const c = await (await fetch(b + '/crumbIssuer/api/json')).json();
  const r = await fetch(b + '/job/prod/job/x/move/move', {method:'POST', headers:{[c.crumbRequestField]: c.crumb, 'Content-Type':'application/x-www-form-urlencoded'}, body:'destination=%2Fno-such-folder', redirect:'manual'});
  return {status: r.status, body: (await r.text()).slice(0, 3000)}; }""", __import__("lib").BASE)
res["post /no-such-folder status"] = resp2["status"]
res["post /no-such body"] = clean(resp2["body"])[:300]
s.done(); close()
res["x at source"] = api("admin", "/job/prod/job/x/api/json").status_code
res["x at ops"] = api("admin", "/job/ops/job/x/api/json").status_code
after = api("admin", "/batch-control/changes/").text
ids = lambda h: set(re.findall(r"changes/([\w-]+)/", h))
res["new change records"] = sorted(ids(after) - ids(before))
res["moverd in changes"] = "moverd" in clean(after)
for k, v in res.items(): print(k, "=>", v)
log("s74", res)
