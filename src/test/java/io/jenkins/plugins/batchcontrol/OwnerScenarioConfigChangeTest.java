package io.jenkins.plugins.batchcontrol;

import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.ParametersAction;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.StringParameterDefinition;
import hudson.model.StringParameterValue;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.model.RunRecord;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.store.FileStore;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.TestExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Owner scenario S-5 — "if the job configuration changes while a build is running, does the
 * next run pick it up?". Matrix row T-OS-09.
 *
 * The change is made through a real product path (an administrator POSTs {@code config.xml})
 * while a build is provably in flight, and three things are asserted: the running build keeps
 * the configuration it started with and completes normally, the change is written to the change
 * history with who/when/what, and the next run uses the new configuration.
 *
 * Accounts: administrator {@code admin} makes the change; {@code u1} is the ordinary user whose
 * batch is running. Change control is on, so the change must be recorded (SPEC item 9).
 *
 * Written from docs/SPEC.md and docs/TEST-MATRIX.md only (no src/main knowledge).
 */
@WithJenkins
public class OwnerScenarioConfigChangeTest {

    private static final String ADMIN = "admin";
    private static final String REQUESTER = "u1";
    private static final String START_CONFIG = "start-config";
    private static final String NEXT_CONFIG = "next-config";

    private JenkinsRule j;

    private FreeStyleProject job;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to(ADMIN)
                .grant(Jenkins.READ, Item.READ, Item.BUILD, BatchControlPermissions.REQUEST)
                        .everywhere().to(REQUESTER));

        // The job is created BEFORE run control is switched on, on purpose: D-31 gives every job
        // created under run control an approvalRequired=true property, and this scenario needs a
        // job whose runs are not gated (what it measures is the change record and which run picks
        // the change up). Creating it first also keeps its own setup out of the change history, so
        // the only CONFIGURE record on config-x is the administrator's edit below.
        job = j.createFreeStyleProject("config-x");
        job.addProperty(new ParametersDefinitionProperty(
                new StringParameterDefinition("DATE", START_CONFIG)));

        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setChangeControlEnabled(true);
        cfg.save();
    }

    /**
     * T-OS-09 (owner scenario S-5): the administrator edits the job while build #1 is running.
     * Build #1 finishes with the configuration it started from, the edit is recorded with user,
     * time and diff, and build #2 runs with the new configuration.
     */
    @Test
    public void t_os_09_configChangeDuringRunAppliesFromTheNextRunAndIsRecorded() throws Exception {
        String gate = "t-os-09";
        job.getBuildersList().add(new PausingBuilder(gate));

        // build #1 starts with the configuration as it stands now (DATE default = start-config)
        Page firstTrigger = postBuildWithParameters(REQUESTER);
        assertTrue(firstTrigger.getWebResponse().getStatusCode() < 400, "the first run must be allowed, got HTTP "
                        + firstTrigger.getWebResponse().getStatusCode());
        PausingBuilder.awaitStarted(gate);
        FreeStyleBuild inFlight = job.getBuildByNumber(1);
        assertNotNull(inFlight, "build #1 must have started");
        assertTrue(inFlight.isBuilding(), "test precondition: build #1 must be in flight");
        assertEquals(START_CONFIG, parameterOf(inFlight), "build #1 must have started from the original configuration");

        // the administrator changes the job configuration while build #1 is still running
        String changedXml = job.getConfigFile().asString()
                .replace("<defaultValue>" + START_CONFIG + "</defaultValue>",
                        "<defaultValue>" + NEXT_CONFIG + "</defaultValue>");
        assertFalse(changedXml.equals(job.getConfigFile().asString()), "test precondition: the configuration text must really change");
        assertEquals(200, postConfigXml(ADMIN, changedXml), "the administrator's configuration change must be accepted");

        // (1) the build in flight is untouched by the change and completes normally
        assertTrue(inFlight.isBuilding(), "changing the configuration must not abort the running build");
        PausingBuilder.release(gate);
        j.waitForCompletion(inFlight);
        j.assertBuildStatusSuccess(inFlight);
        assertEquals(START_CONFIG, parameterOf(inFlight), "the finished build must still report the configuration it started with");
        RunRecord firstRecord = record("config-x#1");
        assertNotNull(firstRecord, "the completed run must be recorded");
        assertEquals("SUCCESS", firstRecord.getResult());

        // (2) the change itself is recorded: who, when, what
        ChangeRecord change = lastConfigureRecord("config-x");
        assertNotNull(change, "the configuration change must be recorded while change control is on");
        assertEquals(ADMIN, change.getUser(), "the change must name the administrator who made it");
        assertNotNull(change.getAt(), "the change must carry a timestamp");
        String diff = change.getDiff();
        assertNotNull(diff, "a CONFIGURE record must carry a unified diff");
        assertTrue(diff.contains("@@"), "the diff must be in unified format (hunk markers)");
        assertTrue(diff.contains(START_CONFIG), "the diff must show the removed old value");
        assertTrue(diff.contains(NEXT_CONFIG), "the diff must show the added new value");

        // (3) the next run picks the change up
        Page secondTrigger = postBuildWithParameters(REQUESTER);
        assertTrue(secondTrigger.getWebResponse().getStatusCode() < 400, "the next run must be allowed, got HTTP "
                        + secondTrigger.getWebResponse().getStatusCode());
        j.waitUntilNoActivity();
        FreeStyleBuild afterChange = job.getBuildByNumber(2);
        assertNotNull(afterChange, "build #2 must have run");
        j.assertBuildStatusSuccess(afterChange);
        assertEquals(NEXT_CONFIG, parameterOf(afterChange), "build #2 must use the changed configuration");
        assertNotNull(record("config-x#2"), "build #2 must be recorded too");
    }

    // ---------------------------------------------------------------- helpers

    private static String parameterOf(FreeStyleBuild build) {
        ParametersAction parameters = build.getAction(ParametersAction.class);
        assertNotNull(parameters, "the build must carry its parameters");
        StringParameterValue value = (StringParameterValue) parameters.getParameter("DATE");
        assertNotNull(value, "the DATE parameter must be present on the build");
        return value.getValue();
    }

    /** No request parameters are sent, so Jenkins fills in the definition's current defaults. */
    private Page postBuildWithParameters(String userId) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient()
                .withThrowExceptionOnFailingStatusCode(false)
                .login(userId);
        return wc.getPage(new WebRequest(
                wc.createCrumbedUrl(job.getUrl() + "buildWithParameters"), HttpMethod.POST));
    }

    private int postConfigXml(String userId, String xml) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient()
                .withThrowExceptionOnFailingStatusCode(false)
                .login(userId);
        WebRequest request = new WebRequest(
                wc.createCrumbedUrl(job.getUrl() + "config.xml"), HttpMethod.POST);
        request.setAdditionalHeader("Content-Type", "application/xml; charset=UTF-8");
        request.setRequestBody(xml);
        return wc.getPage(request).getWebResponse().getStatusCode();
    }

    private RunRecord record(String runId) {
        return FileStore.get().listRunRecords(YearMonth.now()).stream()
                .filter(rec -> runId.equals(rec.getRunId()))
                .findFirst().orElse(null);
    }

    private ChangeRecord lastConfigureRecord(String target) {
        List<ChangeRecord> matching = FileStore.get().listChangeRecords(YearMonth.now()).stream()
                .filter(rec -> rec.getType() == ChangeType.CONFIGURE)
                .filter(rec -> target.equals(rec.getTarget()))
                .collect(Collectors.toList());
        return matching.isEmpty() ? null : matching.get(matching.size() - 1);
    }

    /**
     * A build step that blocks until the test releases it, so the job configuration can be
     * changed while a build is provably in flight. Only the gate id is persisted, so the step
     * survives the {@code config.xml} round trip; the latches live in static maps keyed by it.
     */
    public static class PausingBuilder extends Builder {

        private static final Map<String, CountDownLatch> STARTED = new ConcurrentHashMap<>();
        private static final Map<String, CountDownLatch> RELEASED = new ConcurrentHashMap<>();

        private final String gateId;

        public PausingBuilder(String gateId) {
            this.gateId = gateId;
            STARTED.put(gateId, new CountDownLatch(1));
            RELEASED.put(gateId, new CountDownLatch(1));
        }

        public String getGateId() {
            return gateId;
        }

        static void awaitStarted(String gateId) throws InterruptedException {
            assertTrue(STARTED.get(gateId).await(60, TimeUnit.SECONDS), "the paused build step must start within 60s");
        }

        static void release(String gateId) {
            RELEASED.get(gateId).countDown();
        }

        @Override
        public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener)
                throws InterruptedException {
            CountDownLatch started = STARTED.get(gateId);
            CountDownLatch released = RELEASED.get(gateId);
            if (started == null || released == null) {
                listener.getLogger().println("gate " + gateId + " is unknown, passing through");
                return true;
            }
            started.countDown();
            listener.getLogger().println("waiting on gate " + gateId);
            return released.await(120, TimeUnit.SECONDS);
        }

        @TestExtension
        public static class DescriptorImpl extends BuildStepDescriptor<Builder> {

            @Override
            public boolean isApplicable(Class<? extends AbstractProject> jobType) {
                return true;
            }

            @Override
            public String getDisplayName() {
                return "Pausing test build step";
            }
        }
    }
}
