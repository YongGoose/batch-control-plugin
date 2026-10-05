"""Fixture precondition checks (e2e-16), the last setup step of ci/shard.py: the seeded state the drivers assume is
asserted before any unit runs, so a silently wrong fixture (e2e-15: a multibranch seed without branches) fails the
setup (every unit of the shard is then BLOCKED) instead of making later checks pass for the wrong reason.

Read-only: REST as admin and the script console used only to read. One "PASS {...}" / "FAIL {...}" line per check;
exit 1 on any FAIL."""
import json
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent.parent / "r14"))
from lib import groovy, api  # noqa: E402

N = {"pass": 0, "fail": 0}


def check(name, ok, **kw):
    N["pass" if ok else "fail"] += 1
    print(("PASS " if ok else "FAIL ") + json.dumps(dict(check=name, ok=bool(ok), **kw), default=str)[:1200], flush=True)


def gv(script):
    out = groovy(script)
    return out[len("Result:"):].strip() if out.startswith("Result:") else out.strip()


# 1. plugins the scenarios need, active
plugins = {p["shortName"]: p for p in api("admin", "/pluginManager/api/json?tree=plugins[shortName,version,active]").json()["plugins"]}
need = ["batch-control", "file-parameters", "cloudbees-folder", "matrix-auth", "workflow-job", "workflow-multibranch", "git",
        "role-strategy", "job-dsl"]
missing = [n for n in need if not (plugins.get(n) or {}).get("active")]
check("plugins active", not missing, missing=missing, versions={n: (plugins.get(n) or {}).get("version") for n in need})

# 2. global configuration and strategy
cfg = json.loads(gv("""def c = io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration.get()
def j = jenkins.model.Jenkins.get()
return groovy.json.JsonOutput.toJson([run: c.runControlEnabled, change: c.changeControlEnabled, approvers: c.approvers,
  strategy: j.authorizationStrategy.class.name, realm: j.securityRealm.class.name, pendingHours: c.pendingTimeoutHours])"""))
check("switches on, approvers, Batch Control strategy", cfg["run"] and cfg["change"] and "approver-1" in cfg["approvers"]
      and "batchcontrol" in cfg["strategy"].lower(), **cfg)

# 3. accounts: the permission profile each driver assumes (casc/jenkins.yaml + r7/r8 arrangement), item permissions read
#    on batch-pipeline, which no seeded window names (requester holds a seeded CONFIGURE window on batch-daily)
PROFILE = {  # account: (must hold, must not hold)
    "requester": (["Item.BUILD", "BC.REQUEST", "BC.REQUEST_GRANT"], ["BC.VIEW_HISTORY", "BC.APPROVE", "Item.CONFIGURE"]),
    "reqonly": (["BC.REQUEST", "Item.READ"], ["Item.BUILD", "BC.VIEW_HISTORY"]),
    "approver-1": (["BC.APPROVE", "BC.VIEW_HISTORY", "Item.READ"], ["BC.REQUEST", "Item.BUILD"]),
    "manager": (["BC.MANAGE", "BC.APPROVE"], ["Jenkins.ADMINISTER", "Item.BUILD"]),  # Manage implies the other BC permissions
    "nobc": (["Item.BUILD", "Item.READ"], ["BC.REQUEST", "BC.VIEW_HISTORY", "BC.APPROVE", "BC.MANAGE"]),
    "configurer": (["Item.CONFIGURE", "Item.DELETE"], ["Jenkins.ADMINISTER"]),
    "auditor": (["BC.VIEW_HISTORY"], ["BC.REQUEST", "Item.BUILD"]),
    "admin": (["Jenkins.ADMINISTER"], []),
}
perm_rows = json.loads(gv("""import hudson.model.*
def BC = io.jenkins.plugins.batchcontrol.security.BatchControlPermissions
def P = ['Item.BUILD': Item.BUILD, 'Item.READ': Item.READ, 'Item.CONFIGURE': Item.CONFIGURE, 'Item.DELETE': Item.DELETE,
  'Jenkins.ADMINISTER': jenkins.model.Jenkins.ADMINISTER, 'BC.REQUEST': BC.REQUEST, 'BC.REQUEST_GRANT': BC.REQUEST_GRANT,
  'BC.VIEW_HISTORY': BC.VIEW_HISTORY, 'BC.APPROVE': BC.APPROVE, 'BC.MANAGE': BC.MANAGE]
def j = jenkins.model.Jenkins.get(); def job = j.getItemByFullName('batch-pipeline')  // no seeded window names it
def out = [:]
['requester', 'reqonly', 'approver-1', 'manager', 'nobc', 'configurer', 'auditor', 'admin'].each { id ->
  def u = User.getById(id, false)
  if (u == null) { out[id] = null; return }
  def a = u.impersonate2()
  out[id] = P.collectEntries { k, p -> [k, (k.startsWith('Item') ? job.getACL() : j.getACL()).hasPermission2(a, p)] }
}
return groovy.json.JsonOutput.toJson(out)"""))
for acct, (must, mustnot) in PROFILE.items():
    row = perm_rows.get(acct)
    bad = ["missing account"] if row is None else [f"lacks {p}" for p in must if not row.get(p)] + [f"holds {p}" for p in mustnot if row.get(p)]
    check(f"account profile {acct}", not bad, problems=bad)

# 4. seed jobs and items
items = json.loads(gv("""def j = jenkins.model.Jenkins.get()
def cl = j.pluginManager.uberClassLoader.loadClass('io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty')
def info = { n -> def i = j.getItemByFullName(n); if (i == null) return null
  def m = [cls: i.class.simpleName]
  if (i instanceof hudson.model.Job) { m.approvalRequired = i.getProperty(cl)?.approvalRequired
    m.params = i.getProperty(hudson.model.ParametersDefinitionProperty)?.parameterDefinitions?.collect { it.name + ':' + it.class.simpleName }
    m.timer = i.respondsTo('getTriggers') ? i.triggers.values().collect { it.class.simpleName } : [] }
  if (i instanceof com.cloudbees.hudson.plugins.folder.AbstractFolder) m.children = i.items*.name
  m }
return groovy.json.JsonOutput.toJson(['batch-daily', 'batch-pipeline', 'batch-cron', 'batch-failing', 'team', 'team/app-1', 'ops',
  'ops/job-a', 'prod', 'prod/x', 'team-mb', 'team/sub/deep-job'].collectEntries { [it, info(it)] })"""))
bd = items.get("batch-daily") or {}
check("batch-daily: approval-required Freestyle with DATE (string), MODE (choice), SECRET (password)",
      bd.get("approvalRequired") is True and {"DATE:StringParameterDefinition", "MODE:ChoiceParameterDefinition",
                                               "SECRET:PasswordParameterDefinition"} <= set(bd.get("params") or []), batch_daily=bd)
check("batch-cron has a timer trigger", "TimerTrigger" in ((items.get("batch-cron") or {}).get("timer") or []), batch_cron=items.get("batch-cron"))
check("batch-pipeline is a Pipeline", (items.get("batch-pipeline") or {}).get("cls") == "WorkflowJob", batch_pipeline=items.get("batch-pipeline"))
check("folders team, ops, prod with their jobs", all(items.get(n) for n in ("team", "team/app-1", "ops", "ops/job-a", "prod", "prod/x",
                                                                       "team/sub/deep-job")),
      missing=[n for n in ("team", "team/app-1", "ops", "ops/job-a", "prod", "prod/x", "team/sub/deep-job") if not items.get(n)])
check("team-mb is a multibranch project", (items.get("team-mb") or {}).get("cls") == "WorkflowMultiBranchProject", team_mb=items.get("team-mb"))

# 5. the seed: every id in r14/out/ids.json resolves, with the status its name says
ids_file = pathlib.Path(__file__).resolve().parent.parent / "r14" / "out" / "ids.json"
ids = json.loads(ids_file.read_text()) if ids_file.exists() else {}
check("seed ids written by r14/seed.py", bool(ids), keys=len(ids))
EXPECT = {"req_pending": "PENDING", "req_rejected": "REJECTED", "req_cancelled": "CANCELLED", "act_pending": "PENDING",
          "act_rejected": "REJECTED", "g_rejected": "REJECTED", "g_active_job": "APPROVED"}
kinds = {"r": "requests/run", "a": "activation-requests", "g": "requests/grant"}
for key, want in EXPECT.items():
    rid = ids.get(key)
    if not rid:
        check(f"seed {key}", False, problem="not in ids.json")
        continue
    st = gv(f"""def f = new File(jenkins.model.Jenkins.get().rootDir, 'batch-control/{kinds[key[0]]}/{rid}.xml')
return f.exists() ? new XmlSlurper().parse(f).status.text() : 'missing'""")
    check(f"seed {key} is {want}", st == want, id=rid, status=st)

# 6. the markup probe of ci/seed_markup.py is stored
probe = gv("""def d = new File(jenkins.model.Jenkins.get().rootDir, 'batch-control/requests/run')
return d.listFiles().any { it.name.endsWith('.xml') && it.getText('UTF-8').contains('bc-markup-probe') } ? 'yes' : 'no'""")
check("the markup probe is seeded", probe == "yes", probe=probe)

print("SUMMARY preconditions", N, flush=True)
sys.exit(1 if N["fail"] else 0)
