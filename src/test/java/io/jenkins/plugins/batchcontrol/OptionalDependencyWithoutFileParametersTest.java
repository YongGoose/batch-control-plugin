package io.jenkins.plugins.batchcontrol;

import hudson.model.FileParameterDefinition;
import hudson.model.FileParameterValue;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.ParameterValue;
import hudson.model.ParametersAction;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.StringParameterDefinition;
import hudson.model.StringParameterValue;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.queue.ApprovedCause;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.RealJenkinsExtension;

import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.CORE_TMP_DIR;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.added;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.bytes;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.fileDisplay;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.payload;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.readable;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.stillThere;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.submitRequest;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.tempFiles;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.under;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.uploadFile;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-74 (2), matrix row T-02-123 (note 271): a real Jenkins started WITHOUT file-parameters, which
 * became a compile-time optional dependency with D-74. Batch Control loads and no plugin fails;
 * the common absence-boot checks of T-02-84..86 hold (root page, {@code /manage}, the Batch
 * Control screen, a gated Build Now, an approved parameterless run); and core's own {@code file}
 * parameter still works end to end through the Request Run form (SPEC item 5, D-72): the request
 * shows {@code [file] <original name>} and the plain value verbatim, the approved build receives
 * the file byte for byte in its workspace, and a rejected request's core temporary file under
 * {@code $JENKINS_HOME/fileParameterValueFiles} is deleted with nothing run.
 *
 * <p>This class deliberately references no file-parameters type: its code runs in the JVM that
 * lacks the plugin. The shared steps live in {@link OptionalDependencyFixtures} and
 * {@link TypedParameterFixtures}, which (with the fixtures they call) link core, test-harness,
 * JDK and batch-control types only.
 *
 * <p>Written from docs/SPEC.md item 5, docs/DECISIONS.md D-72 and D-74, docs/TEST-MATRIX.md and
 * docs/reports/dependency-01.md only (no src/main knowledge).
 */
public class OptionalDependencyWithoutFileParametersTest {

    private static final String MARKER = "core-file-no-fp-marker-Kd73";
    private static final String REJECTED_MARKER = "core-file-no-fp-rejected-Pw18";
    private static final String DATE = "2026-10-06-month-end";

    @RegisterExtension
    final RealJenkinsExtension rr = new RealJenkinsExtension().omitPlugins("file-parameters");

    /**
     * T-02-123: without file-parameters Batch Control loads, a core file request from the form is
     * approved and run with the file intact, and a rejected request's core temporary file is deleted.
     */
    @Test
    public void t_02_123_withoutFileParametersCoreFileRequestRunsAndRejectedFileIsDeleted() throws Throwable {
        rr.then(OptionalDependencyWithoutFileParametersTest::boot);
    }

    private static void boot(JenkinsRule r) throws Throwable {
        OptionalDependencyFixtures.rootPageAndApprovedRunWork(r, "file-parameters");
        assertEquals(List.of(), r.jenkins.getPluginManager().getFailedPlugins().stream().map(f -> f.name).toList(),
                "no plugin may fail to load without file-parameters: " + r.jenkins.getPluginManager().getFailedPlugins());

        FreeStyleProject job = r.createFreeStyleProject("upload-no-fp");
        job.addProperty(new ParametersDefinitionProperty(
                new FileParameterDefinition("UPLOAD", "the input file"),
                new StringParameterDefinition("DATE", "2000-01-01", "the batch date")));
        BatchControlFixtures.setBatchControl(job, new BatchControlJobProperty(true));

        approvedCoreFileRuns(r, job);
        rejectedCoreFileIsDeleted(r, job);
    }

    /** The approved half: display, detail page, one build with the workspace file byte for byte. */
    private static void approvedCoreFileRuns(JenkinsRule r, FreeStyleProject job) throws Exception {
        byte[] content = payload(MARKER, 6000);
        String id = submitRequest(r, "u1", job, Map.of("DATE", DATE), Map.of("UPLOAD", uploadFile("data.csv", content)));

        RunRequest pending = RunRequestService.get().load(id);
        assertEquals(RequestStatus.PENDING, pending.getStatus(), "the form submission must leave a pending request");
        assertEquals(fileDisplay("data.csv"), pending.getParameters().get("UPLOAD"),
                "without file-parameters the stored display form of a core file value is [file] <original name>");
        assertEquals(DATE, pending.getParameters().get("DATE"), "the plain value is stored verbatim");
        assertTrue(job.getBuilds().isEmpty(), "premise: nothing runs before the approval");

        String detail = readable(r, "a1", "batch-control/requests/" + id + "/");
        assertTrue(detail.contains(fileDisplay("data.csv")), "the request detail must show " + fileDisplay("data.csv"));
        assertTrue(detail.contains(DATE), "guard: the request detail shows the plain value");
        assertFalse(detail.contains(MARKER), "the request detail must not show the file content");
        assertFalse(detail.contains("NoClassDefFoundError") || detail.contains("ClassNotFoundException"),
                "the request detail must not show a class-loading error");

        try (ACLContext ignored = as("a1")) {
            RunRequestService.get().approve(id, "checked the input file");
        }
        r.waitUntilNoActivity();

        assertEquals(1, job.getBuilds().size(), "the approved request must run exactly once");
        FreeStyleBuild build = job.getBuildByNumber(1);
        assertNotNull(build, "the approved request must have run as build #1");
        r.assertBuildStatusSuccess(build);
        assertArrayEquals(content, bytes(build.getWorkspace().child("UPLOAD")),
                "the workspace file at the parameter's location must hold exactly the uploaded bytes");
        ParametersAction parameters = build.getAction(ParametersAction.class);
        assertNotNull(parameters, "the approved build must carry its parameters");
        ParameterValue upload = parameters.getParameter("UPLOAD");
        assertTrue(upload instanceof FileParameterValue, "the build must receive a core file value, got " + upload);
        assertEquals("data.csv", ((FileParameterValue) upload).getOriginalFileName());
        ParameterValue date = parameters.getParameter("DATE");
        assertTrue(date instanceof StringParameterValue, "the build must receive the plain value, got " + date);
        assertEquals(DATE, ((StringParameterValue) date).getValue(), "guard: the plain value travels next to the file");
        ApprovedCause cause = build.getCause(ApprovedCause.class);
        assertNotNull(cause, "the build must carry the ApprovedCause");
        assertEquals(id, cause.getRequestId(), "the ApprovedCause must name the request");
        assertEquals(RequestStatus.EXECUTED, RunRequestService.get().load(id).getStatus(),
                "the approved request must be executed");
    }

    /** The rejected half: the core temporary file that appeared with the submission is gone; nothing ran. */
    private static void rejectedCoreFileIsDeleted(JenkinsRule r, FreeStyleProject job) throws Exception {
        int next = job.getNextBuildNumber();
        int builds = job.getBuilds().size();
        Set<Path> before = tempFiles(r);
        String id = submitRequest(r, "u1", job, Map.of("DATE", "2026-10-07"),
                Map.of("UPLOAD", uploadFile("wrong.csv", payload(REJECTED_MARKER, 3000))));
        Set<Path> held = under(r, added(before, tempFiles(r)), CORE_TMP_DIR);
        assertFalse(held.isEmpty(), "premise: the core file value is held under " + CORE_TMP_DIR + " while pending: "
                + added(before, tempFiles(r)));
        assertEquals(RequestStatus.PENDING, RunRequestService.get().load(id).getStatus());
        assertEquals(fileDisplay("wrong.csv"), RunRequestService.get().load(id).getParameters().get("UPLOAD"));

        try (ACLContext ignored = as("a1")) {
            RunRequestService.get().reject(id, "wrong input file");
        }
        assertEquals(RequestStatus.REJECTED, RunRequestService.get().load(id).getStatus(), "the request must be rejected");
        assertEquals(Set.of(), stillThere(held),
                "without file-parameters a rejected request must still leave none of its core temporary files behind");

        r.waitUntilNoActivity();
        assertTrue(r.jenkins.getQueue().isEmpty(), "a rejected request must leave the queue empty");
        assertEquals(next, job.getNextBuildNumber(), "a rejected request must not allocate a build number");
        assertEquals(builds, job.getBuilds().size(), "a rejected request must not run");
    }

    private static ACLContext as(String userId) {
        return ACL.as2(User.getById(userId, true).impersonate2());
    }
}
