package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.regex.Pattern;
import jenkins.model.Jenkins;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.JenkinsSessionExtension;

import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.assertClientError;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.assertSuccess;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.decideRun;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC item 3, D-37 upgrade criterion, matrix row T-03-21: a run request stored before D-37,
 * with a single {@code approver}, loads as a one-element set, keeps the compatibility
 * {@code approver} value, and is decided by that approver alone.
 *
 * <p>How the legacy file is made: the request is created by the current plugin (ARCHITECTURE
 * section 5: {@code requests/run/<id>.xml}, XStream), and its designated-set element is then
 * replaced by the pre-D-37 shape — a single {@code <approver>a1</approver>} element and no
 * {@code decidedBy} — before the controller restarts and reads the file again. The premise
 * (the rewritten file really is the legacy shape) is asserted before the restart.
 *
 * <p>Written from docs/SPEC.md, docs/ARCHITECTURE.md section 5 and docs/TEST-MATRIX.md only
 * (no src/main knowledge).
 */
public class MultiApproverLegacyLoadTest {

    private static final String JOB = "batch-x";

    @RegisterExtension
    final JenkinsSessionExtension session = new JenkinsSessionExtension();

    private String requestId;

    /** T-03-21: a stored single-approver request loads as [a1] after a restart and only a1 may decide. */
    @Test
    public void t_03_21_legacySingleApproverRequestLoadsAsOneElementSet() throws Throwable {
        session.then(r -> {
            setUpSecurity(r);
            FreeStyleProject job = r.createFreeStyleProject(JOB);
            setBatchControl(job, new BatchControlJobProperty(true));

            RunRequest request;
            try (ACLContext ignored = ACL.as2(User.getById("u1", true).impersonate2())) {
                request = RunRequestService.get().create(job, new LinkedHashMap<>(), "legacy batch", "a1");
            }
            requestId = request.getId();

            Path file = r.jenkins.getRootDir().toPath()
                    .resolve("batch-control/requests/run/" + requestId + ".xml");
            assertTrue(Files.isRegularFile(file), "premise: the request must be stored at " + file);
            String xml = Files.readString(file, StandardCharsets.UTF_8);
            assertTrue(xml.contains("<approvers"), "premise: the current format stores the designated set in an <approvers> element; file was: "
                    + ApproverFormFixtures.excerpt(xml));

            String legacy = Pattern.compile("<approvers>.*?</approvers>|<approvers/>", Pattern.DOTALL)
                    .matcher(xml).replaceAll("");
            legacy = Pattern.compile("<decidedBy>.*?</decidedBy>|<decidedBy/>", Pattern.DOTALL)
                    .matcher(legacy).replaceAll("");
            if (!legacy.contains("<approver>a1</approver>")) {
                legacy = legacy.replaceFirst("(<requester>u1</requester>)", "$1<approver>a1</approver>");
            }
            assertFalse(legacy.contains("<approvers"), "premise: the legacy file must not carry a set");
            assertTrue(legacy.contains("<approver>a1</approver>"), "premise: the legacy file must carry the single approver; file is: "
                    + ApproverFormFixtures.excerpt(legacy));
            Files.writeString(file, legacy, StandardCharsets.UTF_8);
        });
        session.then(r -> {
            setUpSecurity(r); // the mock realm and strategy are re-installed, not restored
            RunRequest loaded = RunRequestService.get().load(requestId);
            assertNotNull(loaded, "the legacy request must load after the restart");
            assertEquals(RequestStatus.PENDING, loaded.getStatus());
            assertEquals(Collections.singletonList("a1"), loaded.getApprovers(), "a single stored approver loads as a one-element set");
            assertEquals("a1", loaded.getApprover(), "the compatibility approver value is the first (only) member");
            assertNull(loaded.getDecidedBy());

            // the loaded set is effective: a2, a listed approver outside it, is refused ...
            assertClientError(decideRun(r, "a2", requestId, "approve", "stepping in"), "approval by a2, outside the loaded set");
            assertEquals(RequestStatus.PENDING, RunRequestService.get().load(requestId).getStatus());

            // ... and the one member decides
            assertSuccess(decideRun(r, "a1", requestId, "approve", "ok"), "approval by the loaded member a1");
            r.waitUntilNoActivity();
            RunRequest decided = RunRequestService.get().load(requestId);
            assertEquals(RequestStatus.EXECUTED, decided.getStatus());
            assertEquals("a1", decided.getDecidedBy());
            FreeStyleProject job = r.jenkins.getItemByFullName(JOB, FreeStyleProject.class);
            assertEquals(1, job.getBuilds().size());
        });
    }

    private static void setUpSecurity(JenkinsRule r) throws Exception {
        r.jenkins.setSecurityRealm(r.createDummySecurityRealm());
        r.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, Item.BUILD, BatchControlPermissions.REQUEST).everywhere().to("u1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1", "a2"));
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1", "a2"));
        cfg.save();
        r.jenkins.save();
    }
}
