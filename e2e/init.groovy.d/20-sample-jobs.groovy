/*
 * Three sample jobs for the e2e scenarios. Created once (a job that already
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
 * Note on D-31: while run control is on, EVERY newly created job gets
 * approvalRequired=true, including the ones created here - the listener fires on
 * the programmatic path too. The two jobs that must run unattended therefore opt
 * out explicitly right after creation (uncontrol()); without that, batch-cron
 * stops producing dashboard volume and batch-failing can never fail on demand.
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
 * Removes the approvalRequired default D-31 applies at creation time. Only for the
 * jobs whose whole purpose is to run without a request.
 */
def uncontrol = { job ->
    if (job.getProperty(propertyClass) != null) {
        job.removeProperty(propertyClass)
        job.save()
        log.info("e2e: removed the D-31 approvalRequired default from '${job.fullName}'")
    }
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
    job.addProperty(requireApproval(true))
    job.getBuildersList().add(new Shell('echo "batch-daily DATE=$DATE MODE=$MODE"\nsleep 2\necho done'))
    job.save()
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
    job.addProperty(requireApproval(true))
    job.save()
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
