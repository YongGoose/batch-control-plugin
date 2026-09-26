/*
 * Security bootstrap for the e2e Jenkins: three local accounts and a matrix
 * authorization strategy wrapped by the plugin's delegating strategy.
 *
 * Re-applied on every boot (cheap, and it keeps the environment deterministic
 * after a manual change made while exploring the UI). Accounts are only created
 * when missing, so their passwords are never rotated under a running session.
 *
 * Passwords come from the container environment (e2e/.env), never from this file.
 */
import hudson.model.Item
import hudson.model.View
import hudson.security.AuthorizationStrategy
import hudson.security.HudsonPrivateSecurityRealm
import hudson.security.Permission
import hudson.security.ProjectMatrixAuthorizationStrategy
import jenkins.model.Jenkins

def jenkins = Jenkins.get()
def uber = jenkins.pluginManager.uberClassLoader
def log = java.util.logging.Logger.getLogger('batch-control-e2e')

// ---------------------------------------------------------------- accounts

def realm = jenkins.getSecurityRealm()
if (!(realm instanceof HudsonPrivateSecurityRealm)) {
    realm = new HudsonPrivateSecurityRealm(false, false, null)
    jenkins.setSecurityRealm(realm)
    log.info('e2e: installed HudsonPrivateSecurityRealm')
}

def password = { String key ->
    def value = System.getenv(key)
    if (value == null || value.trim().isEmpty()) {
        throw new IllegalStateException("e2e: environment variable ${key} is not set; copy e2e/.env.example to e2e/.env")
    }
    return value
}

def ensureUser = { String id, String envKey, String fullName ->
    if (realm.getAllUsers().any { it.id == id }) {
        log.info("e2e: account '${id}' already exists")
        return
    }
    def user = realm.createAccount(id, password(envKey))
    user.setFullName(fullName)
    user.save()
    log.info("e2e: created account '${id}'")
}

ensureUser('admin', 'BC_ADMIN_PASSWORD', 'E2E Administrator')
ensureUser('approver', 'BC_APPROVER_PASSWORD', 'E2E Approver')
ensureUser('requester', 'BC_REQUESTER_PASSWORD', 'E2E Requester')

// ---------------------------------------------------------------- permissions

/*
 * The five plugin permissions are read off the plugin class through the uber
 * class loader: an init script is compiled by Jenkins itself, so a direct
 * import would not resolve against the plugin's own class loader.
 */
def permissions = uber.loadClass('io.jenkins.plugins.batchcontrol.security.BatchControlPermissions')
def bc = { String field -> (Permission) permissions.getField(field).get(null) }

def matrix = new ProjectMatrixAuthorizationStrategy()

/*
 * matrix-auth 3.x prefers add(Permission, PermissionEntry); the String overload
 * is deprecated but still present. Try the typed API first and fall back, so
 * this script survives either matrix-auth generation.
 */
def grant = { Permission permission, String sid ->
    try {
        def entryClass = uber.loadClass('org.jenkinsci.plugins.matrixauth.PermissionEntry')
        def typeClass = uber.loadClass('org.jenkinsci.plugins.matrixauth.AuthorizationType')
        def entry = entryClass.getConstructor(typeClass, String)
                .newInstance(Enum.valueOf(typeClass, 'USER'), sid)
        matrix.getClass().getMethod('add', Permission, entryClass).invoke(matrix, permission, entry)
    } catch (Throwable t) {
        matrix.add(permission, sid)
    }
}

// admin: everything (Overall/Administer implies BatchControl/Manage).
grant(Jenkins.ADMINISTER, 'admin')

// requester: can see and build jobs, can ask for runs and for JIT permissions.
// Deliberately WITHOUT Item/Configure - that is what a CONFIGURE grant adds.
grant(Jenkins.READ, 'requester')
grant(View.READ, 'requester')
grant(Item.READ, 'requester')
grant(Item.BUILD, 'requester')
grant(bc('REQUEST'), 'requester')
grant(bc('REQUEST_GRANT'), 'requester')

// approver: decides requests and reads history. Deliberately WITHOUT
// BatchControl/Request, so "approver submits a run request" must be a 403.
grant(Jenkins.READ, 'approver')
grant(View.READ, 'approver')
grant(Item.READ, 'approver')
grant(bc('APPROVE'), 'approver')
grant(bc('VIEW_HISTORY'), 'approver')

/*
 * The plugin's delegating strategy wraps matrix-auth: without it no grant can
 * ever add Item/Configure, so the JIT change-control rows would be untestable
 * (SPEC item 8, "implementation": administrators select it in the global
 * security screen).
 */
def wrapperClass = uber.loadClass('io.jenkins.plugins.batchcontrol.security.BatchControlAuthorizationStrategy')
def wrapper = wrapperClass.getConstructor(AuthorizationStrategy).newInstance(matrix)
jenkins.setAuthorizationStrategy(wrapper)

jenkins.save()
log.info('e2e: authorization strategy = BatchControl(wrapping ProjectMatrixAuthorizationStrategy)')
