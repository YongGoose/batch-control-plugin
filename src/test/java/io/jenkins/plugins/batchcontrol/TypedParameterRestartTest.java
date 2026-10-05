package io.jenkins.plugins.batchcontrol;

import hudson.FilePath;
import hudson.model.FileParameterDefinition;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.ParameterValue;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.StringParameterDefinition;
import hudson.model.StringParameterValue;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.security.GlobalMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.queue.ApprovedCause;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.file_parameters.StashedFileParameterDefinition;
import io.jenkins.plugins.file_parameters.StashedFileParameterValue;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.JenkinsSessionExtension;

import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.bytes;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.fileDisplay;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.payload;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC item 4 (a PENDING request survives a restart) combined with item 5, D-72 (the approved build
 * receives the submitted file): a controller restart between the submission and the approval must
 * not lose the file. Matrix rows T-05-51 and T-05-52 (note 260).
 *
 * <p>Persistable security as in RestartRecoveryTest (a matrix strategy, the dummy realm), so the
 * second session sees the same users and permissions.
 *
 * <p>Written from docs/SPEC.md items 4 and 5, docs/DECISIONS.md D-72 and the frozen D-72 contract
 * only (no src/main knowledge).
 */
public class TypedParameterRestartTest {

    private static final String CORE_MARKER = "restart-core-marker-Pp31";
    private static final String STASH_MARKER = "restart-stash-marker-Ty64";

    @RegisterExtension
    final JenkinsSessionExtension session = new JenkinsSessionExtension();

    private String requestId;
    private Set<Path> held;

    /**
     * T-05-51: a request with a core file, submitted through the Request Run form, is still PENDING
     * after a restart with its temporary file kept, and once approved the Freestyle build's workspace
     * file holds exactly the uploaded bytes.
     */
    @Test
    public void t_05_51_coreFileSurvivesARestartBeforeTheApproval() throws Throwable {
        byte[] content = payload(CORE_MARKER, 7000);
        session.then(r -> {
            prepare(r);
            FreeStyleProject job = r.createFreeStyleProject("restart-core");
            job.addProperty(new ParametersDefinitionProperty(new FileParameterDefinition("UPLOAD", "the input file"),
                    new StringParameterDefinition("DATE", "2000-01-01")));
            setBatchControl(job, new BatchControlJobProperty(true));
            Set<Path> before = TypedParameterFixtures.tempFiles(r);
            requestId = TypedParameterFixtures.submitRequest(r, "u1", job, Map.of("DATE", "2026-09-30"),
                    Map.of("UPLOAD", TypedParameterFixtures.uploadFile("data.csv", content)));
            held = TypedParameterFixtures.added(before, TypedParameterFixtures.tempFiles(r));
            assertFalse(held.isEmpty(), "premise: the pending request holds a temporary file");
        });
        session.then(r -> {
            RunRequest pending = RunRequestService.get().load(requestId);
            assertEquals(RequestStatus.PENDING, pending.getStatus(), "the request must still be PENDING after the restart");
            assertEquals(fileDisplay("data.csv"), pending.getParameters().get("UPLOAD"));
            assertEquals(held, TypedParameterFixtures.stillThere(held), "the pending request's temporary file must survive the restart");

            approveAsA1(requestId);
            r.waitUntilNoActivity();
            FreeStyleProject job = r.jenkins.getItemByFullName("restart-core", FreeStyleProject.class);
            FreeStyleBuild build = job.getBuildByNumber(1);
            assertNotNull(build, "the approval after the restart must run the build");
            r.assertBuildStatusSuccess(build);
            assertArrayEquals(content, bytes(build.getWorkspace().child("UPLOAD")),
                    "the build after the restart must receive exactly the uploaded bytes");
            assertEquals(requestId, build.getCause(ApprovedCause.class).getRequestId());
            assertEquals(1, job.getBuilds().size(), "exactly one run");
        });
    }

    /**
     * T-05-52: a request with a file-parameters {@code stashedFile} (typed service overload) is still
     * PENDING after a restart, and once approved the Pipeline's {@code unstash} restores exactly the
     * submitted bytes and {@code DATA_FILENAME} is the original name.
     */
    @Test
    public void t_05_52_stashedFileSurvivesARestartBeforeTheApproval() throws Throwable {
        byte[] content = payload(STASH_MARKER, 4000);
        session.then(r -> {
            prepare(r);
            WorkflowJob job = r.createProject(WorkflowJob.class, "restart-stash");
            job.setDefinition(new CpsFlowDefinition(
                    "node {\n  unstash 'DATA'\n  writeFile file: 'filename.txt', text: env.DATA_FILENAME\n}\n", true));
            job.addProperty(new ParametersDefinitionProperty(new StashedFileParameterDefinition("DATA"),
                    new StringParameterDefinition("DATE", "2000-01-01")));
            setBatchControl(job, new BatchControlJobProperty(true));
            Set<Path> before = TypedParameterFixtures.tempFiles(r);
            List<ParameterValue> values = new ArrayList<>();
            values.add(new StashedFileParameterValue("DATA", TypedParameterFixtures.fileItem("report.bin", content)));
            values.add(new StringParameterValue("DATE", "2026-09-30"));
            try (ACLContext ignored = ACL.as2(User.getById("u1", true).impersonate2())) {
                requestId = RunRequestService.get().create(job, values, "restart durability of a stashed file", "a1").getId();
            }
            held = TypedParameterFixtures.added(before, TypedParameterFixtures.tempFiles(r));
            assertFalse(held.isEmpty(), "premise: the pending request holds a temporary file");
        });
        session.then(r -> {
            RunRequest pending = RunRequestService.get().load(requestId);
            assertEquals(RequestStatus.PENDING, pending.getStatus(), "the request must still be PENDING after the restart");
            assertEquals(fileDisplay("report.bin"), pending.getParameters().get("DATA"));
            assertEquals(held, TypedParameterFixtures.stillThere(held), "the pending request's temporary file must survive the restart");

            approveAsA1(requestId);
            r.waitUntilNoActivity();
            WorkflowJob job = r.jenkins.getItemByFullName("restart-stash", WorkflowJob.class);
            WorkflowRun run = job.getBuildByNumber(1);
            assertNotNull(run, "the approval after the restart must run the Pipeline");
            r.assertBuildStatusSuccess(run);
            FilePath ws = r.jenkins.getWorkspaceFor(job);
            assertArrayEquals(content, bytes(ws.child("DATA")), "unstash after the restart must restore exactly the submitted bytes");
            assertEquals("report.bin", ws.child("filename.txt").readToString());
            assertTrue(TypedParameterFixtures.stillThere(held).isEmpty(), "the run consumed the temporary file");
        });
    }

    // ---------------------------------------------------------------- helpers

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

    private static void approveAsA1(String id) {
        try (ACLContext ignored = ACL.as2(User.getById("a1", true).impersonate2())) {
            RunRequestService.get().approve(id, "approved after the restart");
        }
    }
}
