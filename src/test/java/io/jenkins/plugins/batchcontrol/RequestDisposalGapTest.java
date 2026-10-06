package io.jenkins.plugins.batchcontrol;

import hudson.model.FileParameterDefinition;
import hudson.model.FileParameterValue;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.Queue;
import hudson.model.StringParameterDefinition;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.file_parameters.StashedFileParameterDefinition;
import io.jenkins.plugins.file_parameters.StashedFileParameterValue;
import java.io.File;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import jenkins.model.Jenkins;
import org.htmlunit.WebResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.decideRun;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.excerpt;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.CORE_TMP_DIR;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.added;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.payload;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.stillThere;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.tempFiles;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.under;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.uploadFile;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage lane 1, scenario L1-13 (F): a failing disposal listener. Matrix rows T-GAP-143 and
 * T-GAP-144 (note 276).
 *
 * <p>Basis: SPEC 5 D-72 "When a request ends without a run (rejected, cancelled, expired,
 * invalidated, or its approved run could not be queued) Batch Control disposes of those temporary
 * files"; ARCHITECTURE 2 (temporary parameter files are disposed of through core's
 * {@code FileParameterValue.CancelledQueueListener} and file-parameters'
 * {@code StashedFileParameterValue.CancelledQueueListener}); SPEC 6 usability (no error page from
 * our own code). Fault injection: a test extension subclassing each listener whose
 * {@code onLeft} throws (the scenario's option 4), registered for T-GAP-143 only.
 *
 * <p>Written from docs/SPEC.md, docs/DECISIONS.md D-72/D-74 and docs/ARCHITECTURE.md section 2 only
 * (no src/main knowledge).
 */
@WithJenkins
public class RequestDisposalGapTest {

    static final AtomicInteger THROWN = new AtomicInteger();

    private JenkinsRule j;
    private FreeStyleProject job;

    /** Core's cancelled-queue-item listener, made to fail (T-GAP-143 only). */
    @TestExtension("t_gap_143_throwingDisposalListenerDoesNotBreakTheRejection")
    public static class ThrowingCoreListener extends FileParameterValue.CancelledQueueListener {
        @Override
        public void onLeft(Queue.LeftItem li) {
            THROWN.incrementAndGet();
            throw new IllegalStateException("test: the core file disposal listener failed");
        }
    }

    /** file-parameters' stashed-file listener, made to fail (T-GAP-143 only). */
    @TestExtension("t_gap_143_throwingDisposalListenerDoesNotBreakTheRejection")
    public static class ThrowingStashListener extends StashedFileParameterValue.CancelledQueueListener {
        @Override
        public void onLeft(Queue.LeftItem li) {
            THROWN.incrementAndGet();
            throw new IllegalStateException("test: the stashed file disposal listener failed");
        }
    }

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        THROWN.set(0);
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.REQUEST).everywhere().to("u1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1"));
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
        job = j.createFreeStyleProject("gap-dispose");
        job.addProperty(new ParametersDefinitionProperty(new FileParameterDefinition("UPLOAD", "input"),
                new StashedFileParameterDefinition("DATA"), new StringParameterDefinition("DAY", "2000-01-01")));
        setBatchControl(job, new BatchControlJobProperty(true));
    }

    /**
     * T-GAP-143 (L1-13): with both disposal listeners throwing, a1 rejects u1's request that holds
     * a core file and a stashed file: the request is REJECTED and the reject answer is the normal
     * one (below 400, no crash page). The throwing listeners were consulted (premise).
     */
    @Test
    public void t_gap_143_throwingDisposalListenerDoesNotBreakTheRejection() throws Exception {
        String id = submitWithFiles();
        WebResponse rejected = decideRun(j, "a1", id, "reject", "not this month");
        assertTrue(rejected.getStatusCode() < 400, "the rejection must answer normally, got HTTP " + rejected.getStatusCode()
                + ": " + excerpt(rejected.getContentAsString()));
        UsabilityFixtures.assertPlainRefusal("the reject answer", rejected.getContentAsString(), null);
        assertEquals(RequestStatus.REJECTED, RunRequestService.get().load(id).getStatus(), "the request is REJECTED");
        assertTrue(THROWN.get() > 0, "premise: a throwing disposal listener was consulted for the rejected request's files");
    }

    /**
     * T-GAP-144 (L1-13 guard; SPEC 5 D-72): without the throwing listeners the same rejection
     * deletes the core file's temporary copy under {@code fileParameterValueFiles/}.
     */
    @Test
    public void t_gap_144_rejectionDisposesOfTheTemporaryFile() throws Exception {
        Set<Path> before = tempFiles(j);
        String id = submitWithFiles();
        Set<Path> core = under(j, added(before, tempFiles(j)), CORE_TMP_DIR);
        assertFalse(core.isEmpty(), "premise: the submission kept the core file's copy under " + CORE_TMP_DIR);
        WebResponse rejected = decideRun(j, "a1", id, "reject", "not this month");
        assertTrue(rejected.getStatusCode() < 400, "the rejection answers normally, got HTTP " + rejected.getStatusCode());
        assertEquals(RequestStatus.REJECTED, RunRequestService.get().load(id).getStatus());
        assertTrue(stillThere(core).isEmpty(), "the rejected request's temporary core file must be disposed of: " + stillThere(core));
        assertEquals(0, THROWN.get(), "premise: no throwing listener is registered here");
    }

    private String submitWithFiles() throws Exception {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("DAY", "2026-10-01");
        Map<String, File> files = new LinkedHashMap<>();
        files.put("UPLOAD", uploadFile("upload.csv", payload("gap-dispose-core", 2048)));
        files.put("DATA", uploadFile("data.bin", payload("gap-dispose-stash", 2048)));
        return TypedParameterFixtures.submitRequest(j, "u1", job, values, files);
    }
}
