package io.jenkins.plugins.batchcontrol;

import hudson.model.Item;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import io.jenkins.plugins.batchcontrol.store.FileStore;
import java.net.URL;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Arrays;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.WebResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC item 4 ("month bucket names ... use the plugin clock's zone ... Dates on screens are
 * rendered in the plugin clock's zone", #17) and item 12 (the change list), applied to the
 * Changes screen's month selector (#22: it defaulted to {@code YearMonth.now()} instead of the
 * plugin clock). Matrix row T-12-11 (note 88).
 *
 * <p>Two CREATE records are written a month apart: one at the real time, one with the plugin
 * clock fixed in March 2025. With no month chosen, {@code batch-control/changes/} lists the
 * record of the plugin clock's month and not the other; with the clock reset it is the other
 * way round, so neither a screen that lists everything nor one that lists nothing passes.
 *
 * Written from docs/SPEC.md, issue #22 and docs/TEST-MATRIX.md only (no src/main knowledge).
 */
@WithJenkins
public class ChangesMonthDefaultTest {

    private static final Instant CLOCK_AT = Instant.parse("2025-03-15T12:00:00Z");

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.VIEW_HISTORY)
                        .everywhere().to("viewer"));
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true); // recording on, so job creation leaves a CREATE record
        cfg.setApprovers(Arrays.asList("admin"));
        cfg.save();
    }

    @AfterEach
    public void resetClock() {
        BatchClock.reset();
    }

    /** T-12-11 (#22): the Changes screen's default month is the plugin clock's month. */
    @Test
    public void t_12_11_changesMonthSelectorDefaultsToThePluginClockMonth() throws Exception {
        YearMonth realMonth = YearMonth.now(ZoneOffset.UTC);
        j.createFreeStyleProject("real-month-job");
        BatchClock.setForTest(Clock.fixed(CLOCK_AT, ZoneOffset.UTC));
        j.createFreeStyleProject("clock-month-job");

        assertTrue(hasCreate(YearMonth.of(2025, 3), "clock-month-job"),
                "fixture: the offset CREATE record is stored in the plugin clock's month");
        assertTrue(hasCreate(realMonth, "real-month-job") || hasCreate(realMonth.minusMonths(1), "real-month-job")
                        || hasCreate(realMonth.plusMonths(1), "real-month-job"),
                "fixture: the real-time CREATE record is stored in the real month");
        assertFalse(YearMonth.of(2025, 3).equals(realMonth), "fixture: the two months differ");

        String offset = changes();
        assertTrue(offset.contains("clock-month-job"),
                "with no month chosen the Changes screen must show the plugin clock's month (2025-03)");
        assertFalse(offset.contains("real-month-job"),
                "with no month chosen the Changes screen must not show the real month's records");

        BatchClock.reset();
        String real = changes();
        assertTrue(real.contains("real-month-job"), "control: with the clock reset the real month is shown");
        assertFalse(real.contains("clock-month-job"), "control: with the clock reset the 2025-03 record is not shown");
    }

    private static boolean hasCreate(YearMonth month, String target) {
        return FileStore.get().listChangeRecords(month).stream()
                .anyMatch(r -> r.getType() == ChangeType.CREATE && target.equals(r.getTarget()));
    }

    private String changes() throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login("viewer");
        WebResponse response = wc.getPage(new WebRequest(new URL(j.getURL(), "batch-control/changes/"), HttpMethod.GET))
                .getWebResponse();
        assertEquals(200, response.getStatusCode(), "GET batch-control/changes/");
        return response.getContentAsString();
    }
}
