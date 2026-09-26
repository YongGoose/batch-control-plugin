/*
 * Batch Control global configuration for the e2e run.
 *
 * Applied ONCE, guarded by a marker file: later changes made through the
 * administration screen (for example lowering the grant window to 1 minute for
 * the expiry scenarios) must survive a container restart.
 */
import jenkins.model.Jenkins

def marker = new File(Jenkins.get().getRootDir(), '.batch-control-e2e-config-applied')
def log = java.util.logging.Logger.getLogger('batch-control-e2e')
if (marker.exists()) {
    log.info('e2e: global configuration already applied, leaving it as it is')
    return
}

def uber = Jenkins.get().pluginManager.uberClassLoader
def configClass = uber.loadClass('io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration')
def config = configClass.getMethod('get').invoke(null)

// Both switches on: the e2e pass exercises run control and change control.
config.setRunControlEnabled(true)
config.setChangeControlEnabled(true)

// Who may decide. 'admin' is included so a blocked scenario can always be
// unblocked without editing configuration by hand.
config.setApprovers(['approver', 'admin'])

// 1 minute is offered as a duration option so the grant-expiry scenarios
// (T-E2E-03, T-E2E-06) can be observed inside a test run instead of waiting
// out the 15-minute default.
config.setGrantDurationOptions([1, 15, 30, 60])
config.setMaxGrantMinutes(240)

config.save()

marker.createNewFile()
log.info('e2e: Batch Control global configuration applied (run+change control on, approvers=[approver, admin])')
