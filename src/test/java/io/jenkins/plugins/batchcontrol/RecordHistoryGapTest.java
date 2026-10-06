package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.regex.Pattern;
import jenkins.model.Jenkins;
import org.htmlunit.WebResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.excerpt;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.get;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.post;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage lane 1, scenario L1-19: history endpoints. Matrix rows T-GAP-173 and T-GAP-174 (note
 * 276).
 *
 * <p>Basis: SPEC 12 "월 단위 집계 화면", "월별 집계에 ... 요청 승인/반려 수가 나온다", the four CSV
 * exports; LIMITATIONS 28 (monthly summary edge cases); SPEC 6 usability (a refusal says why and
 * what to do instead; no crash page; no link to a 404); SPEC 4 #17 (month buckets follow the plugin
 * clock). The frozen endpoint contract (HistoryWebTest): {@code GET batch-control/history/},
 * {@code GET history/summary?month=}, {@code GET history/{runs,incidents,changes,requests}.csv}.
 * The exact status of a POST to a read-only endpoint is not documented (note 276); the rows ask
 * for a refusal (4xx).
 *
 * <p>Written from docs/SPEC.md, docs/LIMITATIONS.md and the existing history rows only (no
 * src/main knowledge).
 */
@WithJenkins
public class RecordHistoryGapTest {

    private static final Instant LAST_MONTH = Instant.parse("2026-08-14T10:00:00Z");
    private static final Instant THIS_MONTH = Instant.parse("2026-09-16T10:00:00Z");

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.VIEW_HISTORY).everywhere().to("viewer")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.REQUEST).everywhere().to("u1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1"));
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
    }

    @AfterEach
    public void tearDown() {
        BatchClock.reset();
    }

    /**
     * T-GAP-173 (L1-19): a POST to the summary and to {@code runs.csv} is refused (4xx); the summary
     * for {@code month=2026-13} answers 400 with a plain message that names the month parameter and
     * the form YYYY-MM; {@code history/foo.csv} answers 404; {@code history/?month=garbage} renders
     * (200). Guard: the summary of a valid month answers 200.
     */
    @Test
    public void t_gap_173_historyEndpointsRefuseWhatTheyDoNotServe() throws Exception {
        int postSummary = post(j, "viewer", "batch-control/history/summary", List.of()).getStatusCode();
        assertTrue(postSummary >= 400 && postSummary < 500, "a POST to the summary must be refused, got " + postSummary);
        int postCsv = post(j, "viewer", "batch-control/history/runs.csv", List.of()).getStatusCode();
        assertTrue(postCsv >= 400 && postCsv < 500, "a POST to runs.csv must be refused, got " + postCsv);

        WebResponse badMonth = get(j, "viewer", "batch-control/history/summary?month=2026-13");
        assertEquals(400, badMonth.getStatusCode(), "an impossible month answers 400: " + excerpt(badMonth.getContentAsString()));
        UsabilityFixtures.assertPlainRefusal("the bad month", badMonth.getContentAsString(), Pattern.compile("(?i)month"));
        assertTrue(badMonth.getContentAsString().contains("YYYY-MM"), "the message names the expected form YYYY-MM: "
                + excerpt(badMonth.getContentAsString()));

        assertEquals(404, get(j, "viewer", "batch-control/history/foo.csv").getStatusCode(), "an unknown export answers 404");
        WebResponse garbage = get(j, "viewer", "batch-control/history/?month=garbage");
        assertEquals(200, garbage.getStatusCode(), "the history screen renders with a garbage month: " + excerpt(garbage.getContentAsString()));
        UsabilityFixtures.assertPlainRefusal("the history screen with a garbage month", garbage.getContentAsString(), null);

        assertEquals(200, get(j, "viewer", "batch-control/history/summary?month=" + YearMonth.now(BatchClock.clock())).getStatusCode(),
                "guard: a valid month's summary answers 200");
    }

    /**
     * T-GAP-174 (L1-19; SPEC 12 monthly counts, SPEC 4 #17): with the plugin clock in August 2026 one
     * request is approved and one rejected; with the clock in September the September summary counts
     * neither, and the August summary counts one approved and one rejected request.
     */
    @Test
    public void t_gap_174_decisionsCountInTheMonthTheyWereMade() throws Exception {
        FreeStyleProject job = j.createFreeStyleProject("gap-summary-month");
        setBatchControl(job, new BatchControlJobProperty(true));
        BatchClock.setForTest(Clock.fixed(LAST_MONTH, ZoneOffset.UTC));
        j.jenkins.doQuietDown(); // keep the approved run from starting: only the decision is counted here
        try {
            String approved = create(job, "approved last month");
            String rejected = create(job, "rejected last month");
            try (ACLContext ignored = as("a1")) {
                RunRequestService.get().approve(approved, "ok");
                RunRequestService.get().reject(rejected, "not now");
            }
            BatchClock.setForTest(Clock.fixed(THIS_MONTH, ZoneOffset.UTC));
            String september = get(j, "viewer", "batch-control/history/summary?month=2026-09").getContentAsString();
            assertCount(september, "requestsApproved", 0);
            assertCount(september, "requestsRejected", 0);
            String august = get(j, "viewer", "batch-control/history/summary?month=2026-08").getContentAsString();
            assertCount(august, "requestsApproved", 1);
            assertCount(august, "requestsRejected", 1);
        } finally {
            j.jenkins.doCancelQuietDown();
            j.jenkins.getQueue().clear();
        }
    }

    private String create(FreeStyleProject job, String reason) {
        try (ACLContext ignored = as("u1")) {
            return RunRequestService.get().create(job, new LinkedHashMap<>(), reason, "a1").getId();
        }
    }

    private static void assertCount(String body, String key, int expected) {
        assertTrue(Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*" + expected + "\\b").matcher(body).find(),
                "the monthly summary must report " + key + "=" + expected + ": " + body);
    }

    private static ACLContext as(String userId) {
        return ACL.as2(User.getById(userId, true).impersonate2());
    }
}
