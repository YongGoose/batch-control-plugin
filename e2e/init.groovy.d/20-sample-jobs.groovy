/*
 * Four sample jobs for the e2e scenarios. Created once (a job that already
 * exists is left alone, so builds and history survive a restart).
 *
 *  batch-daily    parameterized Freestyle, approval required  -> T-E2E-01..07
 *  batch-pipeline Pipeline, approval required                 -> "both job types
 *                                                                are recorded"
 *  batch-cron     Freestyle on a one-minute timer, NOT approval required, so it
 *                 keeps feeding the run dashboard with TIMER-caused records
 *                 (T-10-06 paging needs volume).
 *  batch-failing  Freestyle that always fails, NOT approval required, so the
 *                 incident lifecycle (auto-registration -> ACKNOWLEDGED ->
 *                 comment -> RESOLVED) can be exercised on demand.
 *
 * Note on D-31 / D-34 / P-14: while run control is on, EVERY newly created job
 * starts under the activation lock - approvalRequired=true, blockTimer=true,
 * blockUpstream=true, allowedUpstreamJobs emptied - including the ones created
 * here, because the listener fires on the programmatic path too. Two consequences
 * for this file, and one trap it used to fall into:
 *
 *  1. The two jobs that must run unattended opt out explicitly right after
 *     creation (uncontrol()); without that, batch-cron stops producing dashboard
 *     volume and batch-failing can never fail on demand. Under D-34 that is no
 *     longer only about approvalRequired: blockTimer=true on its own would stop
 *     batch-cron's timer even if approval were not required. uncontrol() removes
 *     the whole property, so it takes all three switches with it.
 *  2. For the two controlled jobs the property has to be REPLACED, not added.
 *     Core's Job#addProperty appends and Job#getProperty returns the first
 *     match, so addProperty() on a job that already carries the creation-time
 *     lock leaves the fixture's own copy shadowed: the plugin goes on reading the
 *     lock, and what this file says the job is configured with is not what is in
 *     force. That is why the two controlled jobs go through control(), which
 *     removes any existing copy first. The shadowing used to be invisible - both
 *     copies said approvalRequired=true and neither job has a timer - but the
 *     fixture was stating a property nothing read.
 *
 * The D-34 default itself is asserted in scripts/rest-new-job-default.sh, on
 * throwaway jobs. This file states each sample job's intended property outright,
 * so a scenario that reads batch-daily's configuration sees what this file says
 * rather than whatever the creation default happened to install.
 */
import hudson.model.Cause
import hudson.model.ChoiceParameterDefinition
import hudson.model.FreeStyleProject
import hudson.model.ParametersDefinitionProperty
import hudson.model.StringParameterDefinition
import hudson.tasks.Shell
import hudson.triggers.TimerTrigger
import jenkins.model.Jenkins

def jenkins = Jenkins.get()
def uber = jenkins.pluginManager.uberClassLoader
def log = java.util.logging.Logger.getLogger('batch-control-e2e')

def propertyClass = uber.loadClass('io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty')
def requireApproval = { boolean value -> propertyClass.getConstructor(boolean).newInstance(value) }

/*
 * Removes the activation lock D-31/D-34 apply at creation time. Only for the jobs
 * whose whole purpose is to run without a request.
 *
 * removeProperty takes the property as a whole, so all three switches
 * (approvalRequired, blockTimer, blockUpstream) go with it - which is what these
 * two jobs need under D-34, where blockTimer alone would be enough to stop
 * batch-cron's timer. Asserted at runtime by scripts/rest-sample-jobs-unattended.sh.
 */
def uncontrol = { job ->
    if (job.getProperty(propertyClass) != null) {
        job.removeProperty(propertyClass)
        job.save()
        log.info("e2e: removed the D-31/D-34 activation lock from '${job.fullName}'")
    }
}

/*
 * Installs `property` as THE batch-control property of `job`, replacing the one
 * the creation default (D-31/D-34) has already installed.
 *
 * Adding without removing would only shadow it: core appends in addProperty and
 * returns the first match in getProperty, so the plugin would keep reading the
 * creation-time lock and this file's intent would be decorative. See the note at
 * the top of the file.
 */
def control = { job, property ->
    if (job.getProperty(propertyClass) != null) {
        job.removeProperty(propertyClass)
    }
    job.addProperty(property)
    job.save()
    def applied = job.getProperty(propertyClass)
    log.info("e2e: '${job.fullName}' batch-control property is now "
            + "approvalRequired=${applied.isApprovalRequired()} "
            + "blockTimer=${applied.isBlockTimer()} blockUpstream=${applied.isBlockUpstream()}")
}

// ---------------------------------------------------------------- batch-daily

if (jenkins.getItemByFullName('batch-daily') == null) {
    def job = jenkins.createProject(FreeStyleProject, 'batch-daily')
    // These descriptions are what an approver reads on the decision screen, so
    // they are written the way a real job would word them rather than as
    // placeholders.
    job.setDescription('Daily batch job for the nightly data load.')
    job.addProperty(new ParametersDefinitionProperty(
            new StringParameterDefinition('DATE', new java.text.SimpleDateFormat('yyyy-MM-dd').format(new Date()),
                    'Target business date (YYYY-MM-DD).'),
            new ChoiceParameterDefinition('MODE', ['full', 'partial'] as String[],
                    'full = reload everything, partial = deltas only.')))
    job.getBuildersList().add(new Shell('echo "batch-daily DATE=$DATE MODE=$MODE"\nsleep 2\necho done'))
    job.save()
    // Replaces the creation-time activation lock with what this job is for:
    // approval required, timers and upstream triggers not blocked (it has
    // neither, and leaving the lock in place would make the fixture's own
    // statement of intent unreadable from the job's configuration).
    control(job, requireApproval(true))
    log.info('e2e: created job batch-daily')
}

// ---------------------------------------------------------------- batch-pipeline

if (jenkins.getItemByFullName('batch-pipeline') == null) {
    def workflowJobClass = uber.loadClass('org.jenkinsci.plugins.workflow.job.WorkflowJob')
    def cpsClass = uber.loadClass('org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition')
    def job = jenkins.createProject(workflowJobClass, 'batch-pipeline')
    job.setDescription('E2E sample: Pipeline batch. Approval required.')
    job.setDefinition(cpsClass.getConstructor(String, boolean).newInstance(
            '''pipeline {
  agent any
  parameters {
    string(name: 'DATE', defaultValue: '', description: 'Business date to process (YYYY-MM-DD)')
  }
  stages {
    stage('run') {
      steps {
        echo "batch-pipeline DATE=${params.DATE}"
        sleep 2
      }
    }
  }
}
''', true))
    job.save()
    control(job, requireApproval(true))
    log.info('e2e: created job batch-pipeline')
}

// ---------------------------------------------------------------- batch-cron

if (jenkins.getItemByFullName('batch-cron') == null) {
    def job = jenkins.createProject(FreeStyleProject, 'batch-cron')
    job.setDescription('E2E sample: one-minute timer job, no approval required. Feeds the run dashboard.')
    def timer = new TimerTrigger('* * * * *')
    job.addTrigger(timer)
    job.getBuildersList().add(new Shell('echo "batch-cron tick $(date -Is)"'))
    job.save()
    // A trigger added programmatically is registered on the job but never
    // fires until it is started: Trigger.Cron only runs triggers whose start()
    // has bound them to a job. Without this the job sits at "builds: []"
    // forever and the dashboard gets no TIMER-caused records.
    timer.start(job, true)
    uncontrol(job)
    log.info('e2e: created job batch-cron')
}

// ---------------------------------------------------------------- batch-failing

if (jenkins.getItemByFullName('batch-failing') == null) {
    def job = jenkins.createProject(FreeStyleProject, 'batch-failing')
    job.setDescription('E2E sample: always fails, so an incident is registered automatically.')
    job.getBuildersList().add(new Shell('echo "batch-failing: simulating a broken upstream feed"; exit 1'))
    job.save()
    uncontrol(job)
    log.info('e2e: created job batch-failing')
}
