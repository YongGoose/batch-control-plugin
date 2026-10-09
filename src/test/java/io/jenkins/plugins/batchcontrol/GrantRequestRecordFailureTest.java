package io.jenkins.plugins.batchcontrol;

import hudson.model.Item;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.security.BatchControlMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Level;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import jenkins.model.Jenkins;
import org.htmlunit.WebResponse;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.LogRecorder;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.excerpt;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.grantRequestIds;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.submitGrant;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A failed change-record append never turns the intended refusal of a permission-window request into a
 * server error (TEST-MATRIX note 305, row T-08-200). With change control off the Grants screen is closed
 * and a request is refused with a message that says why, plus a {@code GRANT_REQUEST_BLOCKED} record
 * (LIMITATIONS 29, T-GAP-346). With {@code batch-control/changes/} unwritable that record cannot be
 * appended; the refusal must still be the same 4xx naming change control, nothing may be stored, and the
 * failure is logged at WARNING or SEVERE.
 *
 * <p>Basis: SPEC 1 (change control can be switched off on its own; with it off no change-control UI or
 * blocking appears), SPEC 8 (requesting a permission window), LIMITATIONS 29 ("With change control off the
 * Grants screen is closed ... requesting ... a window is refused with a message that says why, plus a
 * GRANT_REQUEST_BLOCKED record"), the contract of bug hunt A R3-01 and D-42 (a failure to append the
 * change record never stops, reverses or half-applies the operation it records; it is logged at WARNING
 * or above; the request does not answer 500), ARCHITECTURE 5. The same POST with a writable store is the
 * guard.
 *
 * <p>Written from docs/SPEC.md items 1 and 8, docs/LIMITATIONS.md 29, docs/DECISIONS.md D-42 and
 * docs/ARCHITECTURE.md section 5 only (no src/main knowledge).
 */
@WithJenkins
public class GrantRequestRecordFailureTest {

    private static final Pattern NAMES_CHANGE_CONTROL = Pattern.compile("(?i)change control");

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        BatchControlMatrixAuthorizationStrategy strategy = new BatchControlMatrixAuthorizationStrategy();
        strategy.add(Jenkins.ADMINISTER, PermissionEntry.user("admin"));
        for (String userId : new String[] {"u1", "a1"}) {
            strategy.add(Jenkins.READ, PermissionEntry.user(userId));
            strategy.add(Item.READ, PermissionEntry.user(userId));
        }
        strategy.add(BatchControlPermissions.REQUEST_GRANT, PermissionEntry.user("u1"));
        strategy.add(BatchControlPermissions.APPROVE, PermissionEntry.user("a1"));
        j.jenkins.setAuthorizationStrategy(strategy);
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setChangeControlEnabled(false);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
        j.createFreeStyleProject("job-k");
    }

    /**
     * T-08-200 (P0): run control on, change control off. Guard: u1 POSTs {@code batch-control/grants/create}
     * for a CONFIGURE window on the existing {@code job-k} (a request that would be valid with change control
     * on) with a writable store: 4xx naming change control, no grant request stored, one
     * {@code GRANT_REQUEST_BLOCKED} record. Then the same POST with {@code batch-control/changes/} unwritable:
     * 4xx (not 500) with a plain message naming change control, no grant request stored, and a WARNING or
     * SEVERE log record.
     */
    @Test
    public void t_08_200_closedGrantsRefusalStaysPlainWhenTheRecordCannotBeWritten() throws Exception {
        Set<String> before = grantRequestIds();
        int blocked = ApproverFormFixtures.records(ChangeType.GRANT_REQUEST_BLOCKED).size();
        WebResponse guard = request();
        assertRefusedNamingChangeControl("guard: with a writable store", guard);
        assertEquals(before, grantRequestIds(), "guard: nothing is stored");
        assertEquals(blocked + 1, ApproverFormFixtures.records(ChangeType.GRANT_REQUEST_BLOCKED).size(),
                "guard (LIMITATIONS 29): the refused request writes one GRANT_REQUEST_BLOCKED record");

        List<String> problems;
        try (RecordFaultFixtures.Fault ignored = RecordFaultFixtures.makeUnwritable(
                RecordFaultFixtures.changesDir(j.jenkins.getRootDir().toPath()));
             LogRecorder log = new LogRecorder().record("io.jenkins.plugins.batchcontrol", Level.WARNING).capture(200)) {
            WebResponse refused = request();
            assertRefusedNamingChangeControl("R3-01: with the GRANT_REQUEST_BLOCKED record unwritable", refused);
            problems = log.getRecords().stream()
                    .filter(r -> r.getLevel().intValue() >= Level.WARNING.intValue())
                    .map(r -> r.getLevel() + " " + r.getMessage())
                    .collect(Collectors.toList());
        }
        assertEquals(before, grantRequestIds(), "R3-01: nothing may be stored");
        assertFalse(problems.isEmpty(), "R3-01: the failed GRANT_REQUEST_BLOCKED record must be logged at WARNING or SEVERE");
    }

    // ---------------------------------------------------------------- helpers

    private WebResponse request() throws Exception {
        return submitGrant(j, "u1", "job-k", List.of("CONFIGURE"), 30, "work on job-k", null, "a1");
    }

    private static void assertRefusedNamingChangeControl(String what, WebResponse response) {
        int code = response.getStatusCode();
        String text = plain(response.getContentAsString());
        assertTrue(code >= 400 && code < 500, what + ": the request must be refused with 4xx (not a server error), got HTTP "
                + code + ": " + excerpt(text));
        UsabilityFixtures.assertPlainRefusal(what, text, NAMES_CHANGE_CONTROL);
        String lower = text.toLowerCase(Locale.ROOT);
        assertTrue(lower.contains(" off") || lower.contains("disabled") || lower.contains("not enabled")
                || lower.contains("turned off") || lower.contains("switched off"),
                what + ": the refusal must say that change control is off: " + excerpt(text));
    }

    private static String plain(String html) {
        return html == null ? "" : html.replaceAll("(?s)<script.*?</script>", " ").replaceAll("<[^>]*>", " ")
                .replace("&#039;", "'").replace("&#39;", "'").replace("&quot;", "\"").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&amp;", "&").replaceAll("\\s+", " ").trim();
    }
}
