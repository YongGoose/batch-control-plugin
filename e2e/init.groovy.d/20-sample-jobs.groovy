/*
 * Sample jobs, folders and the agent for the e2e checklist (section 1.3).
 * Created once: an item that already exists is left alone, so builds, history
 * and anything changed in the UI survive a restart (the re-boot is idempotent,
 * checklist E-08).
 *
 * Accounts, the authorization strategy and the Batch Control global
 * configuration are NOT set here any more: they come from JCasC
 * (e2e/casc/jenkins.yaml), which runs before this file. So run control is
 * already ON when these jobs are created, which has two consequences that the
 * checklist relies on and that are deliberately NOT worked around here:
 *
 *  1. D-31/D-34 lock: every job created here starts approvalRequired=true,
 *     blockTimer=true, blockUpstream=true. This file then states each job's
 *     intended property outright (control()/uncontrol()), replacing the lock.
 *     REPLACE, not add: Job#addProperty appends and Job#getProperty returns the
 *     first match, so adding would leave the creation-time lock in force.
 *  2. SPEC 6a / D-39 activation: every job created here starts NOT ACTIVATED,
 *     whatever its property says (D-46a). Nothing in a job's configuration can
 *     activate it, so this file does not try. The jobs that must run
 *     unattended (batch-cron's timer, the downstream targets of upstream
 *     triggers) are activated in the pre-flight through the real ACTIVATE
 *     request flow in the browser: requester -> "Request activation" on the
 *     job page -> approver-1 approves (checklist E-10). Jobs started only by a
 *     person (Build Now on an uncontrolled job, or an approved run request) do
 *     not need activation.
 *
 * The plugin-specific configuration of the Section E jobs (customize-build-now,
 * naginator, lockable resource, throttle category, build token, parameterized
 * trigger, authorize-project) is NOT seeded: Section E configures each one in
 * the browser, as an administrator would. The jobs are created here empty so
 * they carry the same creation-time state as any job made under run control.
 */
import hudson.model.ChoiceParameterDefinition
import hudson.model.FreeStyleProject
import hudson.model.ParametersDefinitionProperty
import hudson.model.PasswordParameterDefinition
import hudson.model.StringParameterDefinition
import hudson.tasks.Shell
import hudson.triggers.TimerTrigger
import jenkins.model.Jenkins

def jenkins = Jenkins.get()
def uber = jenkins.pluginManager.uberClassLoader
def log = java.util.logging.Logger.getLogger('batch-control-e2e')

def propertyClass = uber.loadClass('io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty')

/* Property with approvalRequired=<value>, blockTimer=false, blockUpstream=false. */
def requireApproval = { boolean value -> propertyClass.getConstructor(boolean).newInstance(value) }

/* Removes the creation-time lock entirely (approvalRequired, blockTimer, blockUpstream). */
def uncontrol = { job ->
    if (job.getProperty(propertyClass) != null) {
        job.removeProperty(propertyClass)
        job.save()
    }
    log.info("e2e: '${job.fullName}' has no batch-control property (not approval-required)")
}

/* Installs `property` as THE batch-control property of `job` (see note 1 above). */
def control = { job, property ->
    if (job.getProperty(propertyClass) != null) {
        job.removeProperty(propertyClass)
    }
    job.addProperty(property)
    job.save()
    def applied = job.getProperty(propertyClass)
    log.info("e2e: '${job.fullName}' batch-control property approvalRequired=${applied.isApprovalRequired()} "
            + "blockTimer=${applied.isBlockTimer()} blockUpstream=${applied.isBlockUpstream()}")
}

def freestyle = { parent, String name, String description, String script ->
    def job = parent.createProject(FreeStyleProject, name)
    job.setDescription(description)
    job.getBuildersList().add(new Shell(script))
    job.save()
    log.info("e2e: created job ${job.fullName}")
    return job
}

def workflow = { parent, String name, String description, String script ->
    def jobClass = uber.loadClass('org.jenkinsci.plugins.workflow.job.WorkflowJob')
    def cpsClass = uber.loadClass('org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition')
    def job = parent.createProject(jobClass, name)
    job.setDescription(description)
    job.setDefinition(cpsClass.getConstructor(String, boolean).newInstance(script, true))
    job.save()
    log.info("e2e: created job ${job.fullName}")
    return job
}

// forFolder: folders take matrix-auth's folder property class, jobs the job one
// (Folder.addProperty does not accept the job property).
def matrixProperty = { Map<String, List<String>> userPermissions, boolean blockInheritance, boolean forFolder = false ->
    def propClass = uber.loadClass(forFolder
            ? 'com.cloudbees.hudson.plugins.folder.properties.AuthorizationMatrixProperty'
            : 'hudson.security.AuthorizationMatrixProperty')
    def entryClass = uber.loadClass('org.jenkinsci.plugins.matrixauth.PermissionEntry')
    def typeClass = uber.loadClass('org.jenkinsci.plugins.matrixauth.AuthorizationType')
    def prop = propClass.getConstructor(List).newInstance([])
    userPermissions.each { user, perms ->
        perms.each { permId ->
            def p = hudson.security.Permission.fromId(permId)
            if (p == null) {
                throw new IllegalStateException("e2e: unknown permission ${permId}")
            }
            prop.add(p, entryClass.getConstructor(typeClass, String).newInstance(Enum.valueOf(typeClass, 'USER'), user))
        }
    }
    if (blockInheritance) {
        def strategy = uber.loadClass('org.jenkinsci.plugins.matrixauth.inheritance.NonInheritingStrategy').getConstructor().newInstance()
        prop.setInheritanceStrategy(strategy)
    }
    return prop
}

// ---------------------------------------------------------------- batch-daily

if (jenkins.getItemByFullName('batch-daily') == null) {
    def job = jenkins.createProject(FreeStyleProject, 'batch-daily')
    job.setDescription('Daily batch job for the nightly data load.')
    job.addProperty(new ParametersDefinitionProperty(
            new StringParameterDefinition('DATE', new java.text.SimpleDateFormat('yyyy-MM-dd').format(new Date()),
                    'Target business date (YYYY-MM-DD).'),
            new ChoiceParameterDefinition('MODE', ['full', 'partial'] as String[],
                    'full = reload everything, partial = deltas only.'),
            new PasswordParameterDefinition('SECRET', 'default-secret', 'Credential for the source system.')))
    job.getBuildersList().add(new Shell('echo "batch-daily DATE=$DATE MODE=$MODE"\nsleep 2\necho done'))
    job.save()
    control(job, requireApproval(true))
}

// ---------------------------------------------------------------- batch-pipeline

if (jenkins.getItemByFullName('batch-pipeline') == null) {
    def job = workflow(jenkins, 'batch-pipeline', 'Pipeline batch. Approval required.', '''pipeline {
  agent any
  parameters {
    string(name: 'DATE', defaultValue: '', description: 'Business date to process (YYYY-MM-DD)')
  }
  stages {
    stage('run') {
      steps {
        echo "batch-pipeline DATE=${params.DATE}"
        sleep 5
      }
    }
  }
}
''')
    control(job, requireApproval(true))
}

// ---------------------------------------------------------------- batch-cron

if (jenkins.getItemByFullName('batch-cron') == null) {
    def job = jenkins.createProject(FreeStyleProject, 'batch-cron')
    job.setDescription('One-minute timer job, no approval required. Feeds the run dashboard once activated.')
    def timer = new TimerTrigger('* * * * *')
    job.addTrigger(timer)
    job.getBuildersList().add(new Shell('echo "batch-cron tick $(date -Is)"'))
    job.save()
    // A trigger added programmatically only fires once start() bound it to the job.
    timer.start(job, true)
    uncontrol(job)
}

// ---------------------------------------------------------------- batch-failing / batch-unstable

if (jenkins.getItemByFullName('batch-failing') == null) {
    def job = jenkins.createProject(FreeStyleProject, 'batch-failing')
    job.setDescription('Always fails, so an incident is registered automatically.')
    job.addProperty(new ParametersDefinitionProperty(
            new PasswordParameterDefinition('TOKEN', 'tok-e2e-secret-4711', 'Printed on purpose (log-tail masking).')))
    job.getBuildersList().add(new Shell('echo "batch-failing: token=$TOKEN"; echo "simulating a broken upstream feed"; exit 1'))
    job.save()
    uncontrol(job)
}

if (jenkins.getItemByFullName('batch-unstable') == null) {
    def job = freestyle(jenkins, 'batch-unstable', 'Ends UNSTABLE (exit code 3 is mapped to unstable).',
            'echo "batch-unstable: partial data"; exit 3')
    job.getBuildersList().clear()
    def shell = new Shell('echo "batch-unstable: partial data"; exit 3')
    shell.setUnstableReturn(3)
    job.getBuildersList().add(shell)
    job.save()
    uncontrol(job)
}

// ---------------------------------------------------------------- upstream / self

if (jenkins.getItemByFullName('batch-upstream') == null) {
    def job = workflow(jenkins, 'batch-upstream', 'Calls batch-daily through a build step (upstream path).', '''
build job: 'batch-daily', parameters: [string(name: 'DATE', value: '2026-01-01')], wait: true
''')
    uncontrol(job)
}

if (jenkins.getItemByFullName('batch-self') == null) {
    def job = workflow(jenkins, 'batch-self', 'Triggers itself once (security-07 S-01).', '''
// getBuildCauses() returns maps with the cause class in '_class'; only the approved
// (non-upstream) run triggers the child, so the job runs exactly twice per request.
if (!currentBuild.getBuildCauses().any { it._class?.contains('UpstreamCause') }) {
  build job: env.JOB_NAME, wait: false
}
echo 'batch-self done'
''')
    control(job, requireApproval(true))
}

// ---------------------------------------------------------------- Section E jobs (configured in the browser)

[
        ['batch-cbn', 'Section E: customize-build-now is configured on this job in the browser.'],
        ['batch-rebuild', 'Section E: Rebuild plugin on an approved build.'],
        ['batch-nag', 'Section E: naginator retry is configured on this job in the browser.'],
        ['batch-token', 'Section E: build token / build-token-root configured in the browser.'],
        ['batch-lock', 'Section E: lockable resource configured in the browser.'],
        ['batch-throttle', 'Section E: throttle-concurrents category configured in the browser.'],
        ['batch-authz', 'Section E: authorize-project configured in the browser.'],
        ['batch-jch', 'Section E: jobConfigHistory records next to Batch Control.'],
        ['batch-up-target', 'Section E: downstream target of batch-pt-source (parameterized-trigger).'],
].each { pair ->
    if (jenkins.getItemByFullName(pair[0]) == null) {
        def job = freestyle(jenkins, pair[0], pair[1], "echo \"${pair[0]} ran\"")
        control(job, requireApproval(true))
    }
}
// The parameterized-trigger source: not approval-required, a person starts it.
if (jenkins.getItemByFullName('batch-pt-source') == null) {
    def job = freestyle(jenkins, 'batch-pt-source',
            'Section E: parameterized-trigger source (not approval-required); the trigger to batch-up-target is added in the browser.',
            'echo "batch-pt-source ran"')
    uncontrol(job)
}

// ---------------------------------------------------------------- team/ folder

if (jenkins.getItemByFullName('team') == null) {
    def folderClass = uber.loadClass('com.cloudbees.hudson.plugins.folder.Folder')
    def folder = jenkins.createProject(folderClass, 'team')
    folder.setDescription('Sample folder (folder scope, per-item authorization).')
    // approver-disc: Discover only below team/ (checklist 1.2, #26).
    folder.addProperty(matrixProperty(['approver-disc': ['hudson.model.Item.Discover']], false, true))
    folder.save()
    def app = folder.createProject(FreeStyleProject, 'app-1')
    app.getBuildersList().add(new Shell('echo app-1'))
    app.save()
    control(app, requireApproval(true))
    def secret = folder.createProject(FreeStyleProject, 'secret-job')
    secret.setDescription('Only admin reads this job; approver-disc discovers it.')
    secret.getBuildersList().add(new Shell('echo secret-job'))
    secret.addProperty(matrixProperty(['approver-disc': ['hudson.model.Item.Discover']], true))
    secret.save()
    control(secret, requireApproval(true))
    log.info('e2e: created folder team with team/app-1 and team/secret-job')
}

// ---------------------------------------------------------------- agent-1

if (jenkins.getNode('agent-1') == null) {
    try {
        def launcher = new hudson.slaves.JNLPLauncher()
        def agent = new hudson.slaves.DumbSlave('agent-1', '/home/jenkins/agent', launcher)
        agent.setNumExecutors(1)
        agent.setNodeDescription('Permanent agent without a launcher (per-agent authorization, A-01).')
        jenkins.addNode(agent)
        log.info('e2e: created agent agent-1')
    } catch (Exception e) {
        log.warning("e2e: could not create agent-1: ${e}")
    }
}

// ---------------------------------------------------------------- team-mb (multibranch over a local repo)

if (jenkins.getItemByFullName('team-mb') == null) {
    try {
        def repo = new File(jenkins.getRootDir(), 'repos/demo.git')
        def work = new File(jenkins.getRootDir(), 'repos/demo-work')
        if (!repo.exists()) {
            def run = { File dir, List<String> cmd ->
                def p = new ProcessBuilder(cmd).directory(dir).redirectErrorStream(true).start()
                def out = p.inputStream.text
                if (p.waitFor() != 0) {
                    throw new IllegalStateException("e2e: ${cmd} failed: ${out}")
                }
            }
            repo.mkdirs(); work.mkdirs()
            run(repo, ['git', 'init', '--bare', '-b', 'main'])
            run(work, ['git', 'init', '-b', 'main'])
            new File(work, 'Jenkinsfile').text = "echo \"branch ${'$'}{env.BRANCH_NAME}\"\n"
            run(work, ['git', '-c', 'user.name=e2e', '-c', 'user.email=e2e@e2e.local', 'add', '.'])
            run(work, ['git', '-c', 'user.name=e2e', '-c', 'user.email=e2e@e2e.local', 'commit', '-m', 'init'])
            run(work, ['git', 'checkout', '-b', 'feature-1'])
            run(work, ['git', 'remote', 'add', 'origin', repo.absolutePath])
            run(work, ['git', 'push', 'origin', 'main', 'feature-1'])
        }
        def mbClass = uber.loadClass('org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject')
        def mb = jenkins.createProject(mbClass, 'team-mb')
        def sourceClass = uber.loadClass('jenkins.plugins.git.GitSCMSource')
        def source = sourceClass.getConstructor(String).newInstance('file://' + repo.absolutePath)
        def branchSourceClass = uber.loadClass('jenkins.branch.BranchSource')
        mb.getSourcesList().add(branchSourceClass.getConstructor(uber.loadClass('jenkins.scm.api.SCMSource')).newInstance(source))
        mb.save()
        log.info('e2e: created multibranch team-mb over ' + repo.absolutePath + ' (not indexed; index it from the UI)')
    } catch (Exception e) {
        log.warning("e2e: could not create team-mb: ${e}")
    }
}
