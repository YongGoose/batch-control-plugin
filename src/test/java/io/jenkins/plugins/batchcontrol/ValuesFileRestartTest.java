package io.jenkins.plugins.batchcontrol;

import hudson.FilePath;
import hudson.model.FileParameterDefinition;
import hudson.model.FileParameterValue;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.ParameterValue;
import hudson.model.ParametersAction;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.PasswordParameterDefinition;
import hudson.model.PasswordParameterValue;
import hudson.model.Run;
import hudson.model.StringParameterDefinition;
import hudson.model.StringParameterValue;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.security.GlobalMatrixAuthorizationStrategy;
import hudson.util.Secret;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.queue.ApprovedCause;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.file_parameters.Base64FileParameterDefinition;
import io.jenkins.plugins.file_parameters.Base64FileParameterValue;
import io.jenkins.plugins.file_parameters.StashedFileParameterDefinition;
import io.jenkins.plugins.file_parameters.StashedFileParameterValue;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.JenkinsSessionExtension;

import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.bytes;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.fileItem;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.payload;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.storeFilesContaining;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.uploadFile;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC item 4 ("an approved request not yet queued is submitted after a restart") combined with item
 * 5 (D-72: "the approved build receives exactly those values, including the original value of a
 * password ... and the original file"; D-74: typed values live in {@code requests/run/<id>.values.xml},
 * which startup recovery reads, and "a request with parameters whose values file is missing or
 * unreadable cannot be approved or run"). Matrix rows T-05-130 (coverage inventory G-H2) and
 * T-05-131 (G-H4, recovery fails closed), note 270.
 *
 * <p>"Approved, not queued" is reached by approving while the test's queue handler refuses the job
 * before Batch Control's gate ({@link QueueRefusalFixtures#refusedBeforeTheGate}). A test build step
 * does not survive a restart, so the rows read what each build received from its own
 * {@code ParametersAction} and workspace. Persistable security as in RestartRecoveryTest (a matrix
 * strategy, the dummy realm).
 *
 * <p>Written from docs/SPEC.md items 4 and 5, docs/DECISIONS.md D-72, D-72b and D-74, and
 * docs/ARCHITECTURE.md section 5 only (no src/main knowledge).
 */
@Tag("core")
public class ValuesFileRestartTest {

    private static final String SECRET = "rs-s3cr3t-d74-Mv7";
    private static final byte[] CORE_BYTES = payload("rs-core-marker-Hc30", 6000);
    private static final byte[] STASH_BYTES = payload("rs-stash-marker-Hs30", 4000);
    private static final byte[] B64_BYTES = payload("rs-b64-marker-Hb30", 3000);

    @RegisterExtension
    final JenkinsSessionExtension session = new JenkinsSessionExtension();

    private final Map<String, String> ids = new LinkedHashMap<>();

    /** Refuses armed jobs before Batch Control's queue gate (QueueRefusalFixtures). */
    @TestExtension
    public static final class RefuseBeforeGate extends QueueRefusalFixtures.RefusingHandler {
    }

    /**
     * T-05-130 (G-H2): three typed requests are approved while the queue refuses them before Batch
     * Control's gate, so each is APPROVED and not queued, with its values file on disk (premise): a
     * Freestyle job with a core {@code file} UPLOAD ({@code data.csv}), a password TOKEN and a string
     * DATE; a Pipeline with a {@code stashedFile} DATA ({@code report.bin}); a Pipeline with a
     * {@code base64File} B64 ({@code payload.bin}). No store file holds the password in plaintext.
     * After a restart, recovery submits each exactly once (one build, EXECUTED, the build's
     * {@code ApprovedCause} names the request): the Freestyle workspace's UPLOAD holds exactly the
     * uploaded bytes and the build's TOKEN is a password value whose plaintext is the original
     * secret; {@code unstash} restores exactly the stashed bytes; {@code withFileParameter} exposes
     * exactly the Base64 file's bytes. Afterwards every values file is gone, no store file holds the
     * password in plaintext and no store file holds the Base64 text any more.
     */
    @Test
    public void t_05_130_restartBetweenApprovalAndRunSubmitsTypedValuesExactlyOnce() throws Throwable {
        String base64 = Base64.getEncoder().encodeToString(B64_BYTES);
        session.then(r -> {
            prepare(r);
            FreeStyleProject core = r.createFreeStyleProject("rs-core");
            core.addProperty(new ParametersDefinitionProperty(new FileParameterDefinition("UPLOAD", "the input file"),
                    new PasswordParameterDefinition("TOKEN", Secret.fromString("rs-d3fault"), "token"),
                    new StringParameterDefinition("DATE", "2000-01-01")));
            setBatchControl(core, new BatchControlJobProperty(true));
            WorkflowJob stash = pipeline(r, "rs-stash", "node {\n  unstash 'DATA'\n}\n",
                    new StashedFileParameterDefinition("DATA"), new StringParameterDefinition("DATE", "2000-01-01"));
            WorkflowJob b64 = pipeline(r, "rs-b64", "node {\n  withFileParameter('B64') {\n"
                    + "    writeFile file: 'b64-copy.bin', text: readFile(file: env.B64, encoding: 'Base64'), encoding: 'Base64'\n  }\n}\n",
                    new Base64FileParameterDefinition("B64"), new StringParameterDefinition("DATE", "2000-01-01"));

            List<ParameterValue> coreValues = new ArrayList<>();
            coreValues.add(new FileParameterValue("UPLOAD", uploadFile("data.csv", CORE_BYTES), "data.csv"));
            coreValues.add(new PasswordParameterValue("TOKEN", SECRET));
            coreValues.add(new StringParameterValue("DATE", "2026-10-06"));
            ids.put("rs-core", createAsU1(core, coreValues));
            List<ParameterValue> stashValues = new ArrayList<>();
            stashValues.add(new StashedFileParameterValue("DATA", fileItem("report.bin", STASH_BYTES)));
            stashValues.add(new StringParameterValue("DATE", "2026-10-06"));
            ids.put("rs-stash", createAsU1(stash, stashValues));
            Base64FileParameterValue b64Value = new Base64FileParameterValue("B64");
            b64Value.setFile(fileItem("payload.bin", B64_BYTES));
            ids.put("rs-b64", createAsU1(b64, List.of(b64Value, new StringParameterValue("DATE", "2026-10-06"))));

            for (Map.Entry<String, String> e : ids.entrySet()) {
                Item job = r.jenkins.getItemByFullName(e.getKey());
                QueueRefusalFixtures.refusedBeforeTheGate(job, () -> approveAsA1(e.getValue()));
            }
            r.waitUntilNoActivity();
            for (Map.Entry<String, String> e : ids.entrySet()) {
                assertEquals(RequestStatus.APPROVED, RunRequestService.get().load(e.getValue()).getStatus(),
                        "premise: the request on " + e.getKey() + " is approved, not queued");
                assertTrue(r.jenkins.getItemByFullName(e.getKey(), Job.class).getBuilds().isEmpty(), "premise: nothing ran on " + e.getKey());
                assertTrue(Files.isRegularFile(valuesPath(r, e.getValue())), "premise (D-74): the approved request on " + e.getKey()
                        + " keeps its values file until it runs");
            }
            assertEquals(List.of(), storeFilesContaining(r, SECRET), "premise: no store file holds the password in plaintext");
        });
        session.then(r -> {
            r.waitUntilNoActivity();
            for (Map.Entry<String, String> e : ids.entrySet()) {
                Job<?, ?> job = r.jenkins.getItemByFullName(e.getKey(), Job.class);
                assertEquals(1, job.getBuilds().size(), "SPEC 4: recovery submits the approved request on " + e.getKey() + " exactly once");
                Run<?, ?> build = job.getBuildByNumber(1);
                r.assertBuildStatusSuccess(build);
                ApprovedCause cause = build.getCause(ApprovedCause.class);
                assertNotNull(cause, "the recovered build on " + e.getKey() + " carries the approval");
                assertEquals(e.getValue(), cause.getRequestId(), "the recovered build on " + e.getKey() + " names its request");
                assertEquals(RequestStatus.EXECUTED, RunRequestService.get().load(e.getValue()).getStatus());
                assertFalse(Files.exists(valuesPath(r, e.getValue())), "D-74: the values file of " + e.getKey() + " is deleted once the run started");
            }
            assertTrue(r.jenkins.getQueue().isEmpty(), "nothing is left in the queue");

            FreeStyleBuild coreBuild = r.jenkins.getItemByFullName("rs-core", FreeStyleProject.class).getBuildByNumber(1);
            assertArrayEquals(CORE_BYTES, bytes(coreBuild.getWorkspace().child("UPLOAD")),
                    "the recovered Freestyle build receives exactly the uploaded core file");
            ParameterValue token = coreBuild.getAction(ParametersAction.class).getParameter("TOKEN");
            assertTrue(token instanceof PasswordParameterValue, "the recovered build's TOKEN is a password value, was " + token);
            assertEquals(SECRET, ((Secret) token.getValue()).getPlainText(), "the recovered build receives the original secret");

            WorkflowJob stash = r.jenkins.getItemByFullName("rs-stash", WorkflowJob.class);
            FilePath stashWs = r.jenkins.getWorkspaceFor(stash);
            assertArrayEquals(STASH_BYTES, bytes(stashWs.child("DATA")), "unstash in the recovered Pipeline restores exactly the stashed bytes");
            WorkflowJob b64 = r.jenkins.getItemByFullName("rs-b64", WorkflowJob.class);
            WorkflowRun b64Run = b64.getBuildByNumber(1);
            assertNotNull(b64Run);
            assertArrayEquals(B64_BYTES, bytes(r.jenkins.getWorkspaceFor(b64).child("b64-copy.bin")),
                    "withFileParameter in the recovered Pipeline exposes exactly the Base64 file's bytes");

            assertEquals(List.of(), storeFilesContaining(r, SECRET), "no store file holds the password in plaintext");
            assertEquals(List.of(), storeFilesContaining(r, base64.substring(0, 32)), "D-74: the Base64 text left the store with the values file");
        });
    }

    /**
     * T-05-131 (G-H4, recovery fails closed; SPEC item 5 "a request with parameters whose values file
     * is missing or unreadable cannot be approved or run"; a repeated name at recovery is T-05-82):
     * three requests for TARGET, each on its own job, are approved while the queue refuses them
     * before the gate (premise: APPROVED, nothing ran, values file on disk). While Jenkins is down,
     * the first one's values file is deleted and the second one's replaced by bytes that are not XML.
     * After the restart, recovery submits neither: no build, no build number consumed, nothing queued,
     * not EXECUTED. Guard: the unedited third request is submitted exactly once with its own value.
     */
    @Test
    public void t_05_131_recoveryNeverSubmitsAnApprovedRequestWhoseValuesFileIsMissingOrUnreadable() throws Throwable {
        session.then(r -> {
            prepare(r);
            for (String name : new String[] {"rf-missing", "rf-garbage", "rf-guard"}) {
                FreeStyleProject job = r.createFreeStyleProject(name);
                job.addProperty(new ParametersDefinitionProperty(new StringParameterDefinition("TARGET", "default-target")));
                setBatchControl(job, new BatchControlJobProperty(true));
                String id = createAsU1(job, List.of(new StringParameterValue("TARGET", "rf-guard".equals(name) ? "guarded" : "staging")));
                ids.put(name, id);
                QueueRefusalFixtures.refusedBeforeTheGate(job, () -> approveAsA1(id));
                assertEquals(RequestStatus.APPROVED, RunRequestService.get().load(id).getStatus(), "premise: approved, not queued on " + name);
                assertTrue(Files.isRegularFile(valuesPath(r, id)), "premise (D-74): the approved request on " + name + " has its values file");
            }
            r.waitUntilNoActivity();
            Files.delete(valuesPath(r, ids.get("rf-missing")));
            Files.writeString(valuesPath(r, ids.get("rf-garbage")), "\u0000<garbage d74", StandardCharsets.UTF_8);
        });
        session.then(r -> {
            r.waitUntilNoActivity();
            FreeStyleProject guard = r.jenkins.getItemByFullName("rf-guard", FreeStyleProject.class);
            assertEquals(1, guard.getBuilds().size(), "guard: recovery submits the unedited approval exactly once");
            assertEquals("guarded", guard.getBuildByNumber(1).getAction(ParametersAction.class).getParameter("TARGET").getValue(),
                    "guard: the recovered run receives its own value");
            assertEquals(RequestStatus.EXECUTED, RunRequestService.get().load(ids.get("rf-guard")).getStatus());

            for (String name : new String[] {"rf-missing", "rf-garbage"}) {
                FreeStyleProject job = r.jenkins.getItemByFullName(name, FreeStyleProject.class);
                assertTrue(job.getBuilds().isEmpty(), "D-74: recovery must not submit the approval on " + name + " (values file "
                        + ("rf-missing".equals(name) ? "missing" : "unreadable") + ")");
                assertEquals(1, job.getNextBuildNumber(), "no build number may have been consumed on " + name);
                assertNotEquals(RequestStatus.EXECUTED, RunRequestService.get().load(ids.get(name)).getStatus(), name + " must never run");
            }
            assertTrue(r.jenkins.getQueue().isEmpty(), "nothing may be queued");
        });
    }

    // ---------------------------------------------------------------- helpers

    private static Path valuesPath(JenkinsRule r, String id) {
        return r.jenkins.getRootDir().toPath().resolve("batch-control").resolve("requests").resolve("run").resolve(id + ".values.xml");
    }

    private static WorkflowJob pipeline(JenkinsRule r, String name, String script, hudson.model.ParameterDefinition... definitions)
            throws Exception {
        WorkflowJob job = r.createProject(WorkflowJob.class, name);
        job.setDefinition(new CpsFlowDefinition(script, true));
        job.addProperty(new ParametersDefinitionProperty(definitions));
        setBatchControl(job, new BatchControlJobProperty(true));
        return job;
    }

    private static String createAsU1(Job<?, ?> job, List<ParameterValue> values) {
        try (ACLContext ignored = ACL.as2(User.getById("u1", true).impersonate2())) {
            return RunRequestService.get().create(job, values, "restart between approval and run (D-74)", "a1").getId();
        }
    }

    private static void approveAsA1(String id) {
        try (ACLContext ignored = ACL.as2(User.getById("a1", true).impersonate2())) {
            RunRequestService.get().approve(id, "approved before the restart");
        }
    }

    private static void prepare(JenkinsRule r) throws Exception {
        r.jenkins.setSecurityRealm(r.createDummySecurityRealm());
        GlobalMatrixAuthorizationStrategy strategy = new GlobalMatrixAuthorizationStrategy();
        strategy.add(Jenkins.ADMINISTER, PermissionEntry.user("admin"));
        for (String userId : new String[] {"u1", "a1"}) {
            strategy.add(Jenkins.READ, PermissionEntry.user(userId));
            strategy.add(Item.READ, PermissionEntry.user(userId));
        }
        strategy.add(BatchControlPermissions.REQUEST, PermissionEntry.user("u1"));
        strategy.add(BatchControlPermissions.APPROVE, PermissionEntry.user("a1"));
        r.jenkins.setAuthorizationStrategy(strategy);
        r.jenkins.save();

        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
    }
}
