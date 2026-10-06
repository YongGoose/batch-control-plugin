package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.ParameterValue;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.StringParameterDefinition;
import hudson.model.StringParameterValue;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import jenkins.model.Jenkins;
import org.htmlunit.WebResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.assertSuccess;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.decideRun;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.excerpt;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.editValuesFile;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.valuesFile;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.valuesPath;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage lane 1, scenario L1-09 (F): values file faults when a request ends. Matrix rows
 * T-GAP-140 .. T-GAP-142 (note 276).
 *
 * <p>Basis: SPEC 5 D-74 "A request with parameters whose values file is missing or unreadable
 * cannot be approved or run. Typed values live in a separate values file per request, which is
 * deleted when ... the request ends"; LIMITATIONS 51 "approval ... refused for a request whose
 * stored values ... include a value Jenkins can no longer load ... A refused approval leaves the
 * request PENDING with the reason shown above the form, and the approver can still reject it";
 * SPEC 7 (the requester may cancel a PENDING request). Faults: a values file swapped for another
 * request's, replaced by a non-empty directory, or hand-edited (ARCHITECTURE 5 layout).
 *
 * <p>Written from docs/SPEC.md, docs/DECISIONS.md D-72b/D-74, docs/LIMITATIONS.md and
 * docs/ARCHITECTURE.md section 5 only (no src/main knowledge).
 */
@WithJenkins
public class RequestValuesFileGapTest {

    private JenkinsRule j;
    private FreeStyleProject job;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.REQUEST).everywhere().to("u1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1"));
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
        job = j.createFreeStyleProject("gap-values");
        job.addProperty(new ParametersDefinitionProperty(new StringParameterDefinition("DAY", "2000-01-01")));
        setBatchControl(job, new BatchControlJobProperty(true));
    }

    /**
     * T-GAP-140 (L1-09 case 1): A's values file is replaced by B's values file and u1 cancels A:
     * A ends CANCELLED (the end is not blocked) and its values file is gone. Guard: approving a
     * third request C after the same swap is refused (C stays PENDING, nothing runs).
     */
    @Test
    public void t_gap_140_swappedValuesFileDoesNotBlockCancel() throws Exception {
        String a = create("2026-10-01");
        String b = create("2026-10-02");
        String c = create("2026-10-03");
        String bXml = Files.readString(valuesFile(j, b), StandardCharsets.UTF_8);
        Files.writeString(valuesFile(j, a), bXml, StandardCharsets.UTF_8);
        Files.writeString(valuesFile(j, c), bXml, StandardCharsets.UTF_8);

        try (ACLContext ignored = as("u1")) {
            RunRequestService.get().cancel(a);
        }
        assertEquals(RequestStatus.CANCELLED, RunRequestService.get().load(a).getStatus(), "the cancel is not blocked by the swapped file");
        assertTrue(Files.isRegularFile(valuesPath(j, b)), "B's own values file is untouched by A's end");

        WebResponse refused = decideRun(j, "a1", c, "approve", "ok");
        assertTrue(refused.getStatusCode() < 500, "the refused approval is not a server error: " + excerpt(refused.getContentAsString()));
        assertNotEquals(RequestStatus.APPROVED, RunRequestService.get().load(c).getStatus(), "guard: C with B's values is not approved");
        assertNotEquals(RequestStatus.EXECUTED, RunRequestService.get().load(c).getStatus());
        j.waitUntilNoActivity();
        assertTrue(job.getBuilds().isEmpty(), "nothing ran");
    }

    /**
     * T-GAP-141 (L1-09 case 2): A's values file is replaced by a non-empty directory and a1 rejects
     * A: A ends REJECTED and A's request page renders.
     */
    @Test
    public void t_gap_141_valuesFileReplacedByADirectoryDoesNotBlockReject() throws Exception {
        String a = create("2026-10-01");
        Path values = valuesFile(j, a);
        Files.delete(values);
        Files.createDirectories(values);
        Files.writeString(values.resolve("blocker.txt"), "a non-empty directory where the values file was");

        assertSuccess(decideRun(j, "a1", a, "reject", "not this month"), "a1's rejection");
        assertEquals(RequestStatus.REJECTED, RunRequestService.get().load(a).getStatus(), "the rejection is not blocked");
        WebResponse page = ApproverFormFixtures.get(j, "u1", "batch-control/requests/" + a + "/");
        assertEquals(200, page.getStatusCode(), "A's request page renders: " + excerpt(page.getContentAsString()));
        UsabilityFixtures.assertPlainRefusal("A's request page", page.getContentAsString(), null);
    }

    /**
     * T-GAP-142 (L1-09 case 3; LIMITATIONS 51): one value in A's values file loses its
     * {@code <name>} element. a1's approval is refused with a plain message (no server error) and A
     * stays PENDING with nothing run; rejecting it still works. Guard: B, unedited, is approved and
     * runs.
     */
    @Test
    public void t_gap_142_valueWithoutANameCannotBeApprovedButCanBeRejected() throws Exception {
        String a = create("2026-10-01");
        String b = create("2026-10-02");
        editValuesFile(j, a, xml -> xml.replaceFirst("<name>DAY</name>", ""));

        WebResponse refused = decideRun(j, "a1", a, "approve", "ok");
        assertTrue(refused.getStatusCode() >= 400 && refused.getStatusCode() < 500, "the approval must be refused with 4xx, got "
                + refused.getStatusCode() + ": " + excerpt(refused.getContentAsString()));
        UsabilityFixtures.assertPlainRefusal("the approval of a request whose value lost its name", refused.getContentAsString(), null);
        assertEquals(RequestStatus.PENDING, RunRequestService.get().load(a).getStatus(), "A stays PENDING");
        j.waitUntilNoActivity();
        assertTrue(job.getBuilds().isEmpty(), "nothing ran for A");

        assertSuccess(decideRun(j, "a1", a, "reject", "values damaged"), "the rejection still works");
        assertEquals(RequestStatus.REJECTED, RunRequestService.get().load(a).getStatus());

        assertSuccess(decideRun(j, "a1", b, "approve", "ok"), "guard: the unedited B is approved");
        j.waitUntilNoActivity();
        assertEquals(1, job.getBuilds().size(), "guard: B runs");
    }

    // ------------------------------------------------------------------ helpers

    private String create(String day) {
        List<ParameterValue> values = List.of(new StringParameterValue("DAY", day));
        try (ACLContext ignored = as("u1")) {
            return RunRequestService.get().create(job, values, "values file faults", "a1").getId();
        }
    }

    private static ACLContext as(String userId) {
        return ACL.as2(User.getById(userId, true).impersonate2());
    }
}
