package io.jenkins.plugins.batchcontrol;

import hudson.model.Item;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.CauseType;
import io.jenkins.plugins.batchcontrol.model.RunRecord;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import io.jenkins.plugins.batchcontrol.store.FileStore;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.regex.Pattern;
import jenkins.model.Jenkins;
import org.htmlunit.WebResponse;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.JenkinsSessionExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #33 (TEST-MATRIX note 343, rows T-04-74 and T-04-75): a date-range query does not depend on the JVM
 * default time zone. The History listings and the CSV exports for run records and change records return every
 * record whose timestamp lies in the requested days and no record outside them, whatever the default time zone
 * was when the record was written and is when it is read; files written before the zone changed keep working
 * without migration.
 *
 * <p>The controller's zone change is a restart with another default time zone ({@link TimeZone#setDefault}
 * between the {@link JenkinsSessionExtension} sessions, restored afterwards). In each session the plugin clock is
 * set, through {@link BatchClock#setForTest}, to a fixed instant in that session's default zone, as a fresh JVM's
 * system clock would be. The requested days are days in the plugin clock's zone, the zone the screens render
 * dates in (SPEC 4). Records are written near a month boundary, so the writing zone and the reading zone put them
 * in different months: a run record through the store with its start time and a job's CREATE record by creating
 * the job, both at that instant.
 *
 * <p>Basis: SPEC 4 ("stored file names, month bucket names and record ids do not depend on the controller's
 * default locale or time zone ... so retention and the history screens keep working"; "Dates on screens are
 * rendered in the plugin clock's zone"; "screens and exports never silently disagree"), SPEC 12 (period filter
 * and CSV export for run records and change records), SPEC 9 (CREATE recorded), ARCHITECTURE 5 (month files),
 * issue #33 and the wave-B contract (main session, 2026-10-10).
 *
 * <p>Written from docs/SPEC.md items 4, 9 and 12, docs/ARCHITECTURE.md section 5, issue #33 and the wave-B
 * contract only (no src/main knowledge).
 */
public class MonthBucketZoneTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final ZoneId UTC = ZoneId.of("UTC");

    /** A mid-month instant: the same day (2026-09-15) in both zones. */
    private static final Instant MID = Instant.parse("2026-09-15T05:00:00Z");
    /** 2026-09-30 in UTC, already 2026-10-01 00:30 in Seoul. */
    private static final Instant WEST = Instant.parse("2026-09-30T15:30:00Z");
    /** 2026-10-31 in UTC, already 2026-11-01 05:00 in Seoul. */
    private static final Instant EAST = Instant.parse("2026-10-31T20:00:00Z");
    /** When the reading session's clock stands. */
    private static final Instant READ_AT = Instant.parse("2026-11-05T12:00:00Z");

    private static final Pattern FIXTURE_JOB = Pattern.compile("\\bzone-(west|east|mid)\\b");

    @RegisterExtension
    final JenkinsSessionExtension session = new JenkinsSessionExtension();

    private TimeZone savedZone;

    @BeforeEach
    public void saveZone() {
        savedZone = TimeZone.getDefault();
    }

    @AfterEach
    public void restoreZone() {
        TimeZone.setDefault(savedZone);
        BatchClock.reset();
    }

    /**
     * T-04-74 (P0, #33): session 1 with the default zone Asia/Seoul writes {@code zone-mid} at 2026-09-15 05:00Z
     * and {@code zone-west} at 2026-09-30 15:30Z (2026-10-01 in Seoul). Session 2 (restart) with the default zone
     * UTC reads the run listing, {@code runs.csv}, the change listing and {@code changes.csv}: for 2026-09-30 all
     * four list {@code zone-west}. Guards: for 2026-10-01 none lists {@code zone-west} (outside the range in UTC),
     * and for 2026-09-15 all four list {@code zone-mid}.
     */
    @Test
    public void t_04_74_recordsWrittenUnderAnEasternZoneAreFoundAfterTheZoneMovesWest() throws Throwable {
        TimeZone.setDefault(TimeZone.getTimeZone(SEOUL));
        session.then(r -> {
            secure(r);
            assertEquals(LocalDate.of(2026, 10, 1), LocalDate.ofInstant(WEST, ZoneId.systemDefault()),
                    "premise: in the writing zone zone-west is already in October");
            write(r, "zone-mid", MID);
            write(r, "zone-west", WEST);
        });
        TimeZone.setDefault(TimeZone.getTimeZone(UTC));
        session.then(r -> {
            secure(r);
            BatchClock.setForTest(Clock.fixed(READ_AT, ZoneId.systemDefault()));
            assertEquals(LocalDate.of(2026, 9, 30), LocalDate.ofInstant(WEST, BatchClock.clock().getZone()),
                    "premise: in the reading zone zone-west is on 2026-09-30");
            assertOnEverySurface(r, "zone-mid", "2026-09-15", true, "guard: a mid-month record written under Asia/Seoul, read under UTC");
            assertOnEverySurface(r, "zone-west", "2026-10-01", false,
                    "guard: zone-west (2026-09-30 in UTC) lies outside 2026-10-01 and must not be listed");
            assertOnEverySurface(r, "zone-west", "2026-09-30", "2026-10-01", true,
                    "premise: zone-west is stored and readable (a range spanning both months lists it)");
            assertOnEverySurface(r, "zone-west", "2026-09-30", true,
                    "#33: zone-west, written under Asia/Seoul at 2026-09-30T15:30Z, lies in 2026-09-30 when read under UTC");
        });
    }

    /**
     * T-04-75 (P0, #33): the mirror. Session 1 with the default zone UTC writes {@code zone-mid} and
     * {@code zone-east} at 2026-10-31 20:00Z. Session 2 (restart) with the default zone Asia/Seoul: for 2026-11-01
     * the four surfaces list {@code zone-east}. Guards: for 2026-10-31 none lists it (outside the range in Seoul),
     * and for 2026-09-15 all four list {@code zone-mid}.
     */
    @Test
    public void t_04_75_recordsWrittenUnderAWesternZoneAreFoundAfterTheZoneMovesEast() throws Throwable {
        TimeZone.setDefault(TimeZone.getTimeZone(UTC));
        session.then(r -> {
            secure(r);
            assertEquals(LocalDate.of(2026, 10, 31), LocalDate.ofInstant(EAST, ZoneId.systemDefault()),
                    "premise: in the writing zone zone-east is still in October");
            write(r, "zone-mid", MID);
            write(r, "zone-east", EAST);
        });
        TimeZone.setDefault(TimeZone.getTimeZone(SEOUL));
        session.then(r -> {
            secure(r);
            BatchClock.setForTest(Clock.fixed(READ_AT, ZoneId.systemDefault()));
            assertEquals(LocalDate.of(2026, 11, 1), LocalDate.ofInstant(EAST, BatchClock.clock().getZone()),
                    "premise: in the reading zone zone-east is on 2026-11-01");
            assertOnEverySurface(r, "zone-mid", "2026-09-15", true, "guard: a mid-month record written under UTC, read under Asia/Seoul");
            assertOnEverySurface(r, "zone-east", "2026-10-31", false,
                    "guard: zone-east (2026-11-01 in Seoul) lies outside 2026-10-31 and must not be listed");
            assertOnEverySurface(r, "zone-east", "2026-10-31", "2026-11-01", true,
                    "premise: zone-east is stored and readable (a range spanning both months lists it)");
            assertOnEverySurface(r, "zone-east", "2026-11-01", true,
                    "#33: zone-east, written under UTC at 2026-10-31T20:00Z, lies in 2026-11-01 when read under Asia/Seoul");
        });
    }

    // ------------------------------------------------------------------ helpers

    /** A run record started at {@code at} and the job's CREATE record, both with the plugin clock at {@code at}. */
    private static void write(JenkinsRule r, String job, Instant at) throws Exception {
        BatchClock.setForTest(Clock.fixed(at, ZoneId.systemDefault()));
        r.createFreeStyleProject(job);
        FileStore.get().appendRunRecord(new RunRecord(job + "#1", job, 1, CauseType.USER, "SUCCESS", at, 1_000L));
    }

    /**
     * Whether {@code job} is named on the run listing, {@code runs.csv}, the change listing and
     * {@code changes.csv} for the single day {@code day}; asserts that every surface answers {@code expected}.
     */
    private static void assertOnEverySurface(JenkinsRule r, String job, String day, boolean expected, String what) throws Exception {
        assertOnEverySurface(r, job, day, day, expected, what);
    }

    /** As above for the days {@code from} .. {@code to}. */
    private static void assertOnEverySurface(JenkinsRule r, String job, String from, String to, boolean expected, String what)
            throws Exception {
        String range = "from=" + from + "&to=" + to;
        String day = from.equals(to) ? from : from + ".." + to;
        Map<String, Boolean> named = new LinkedHashMap<>();
        named.put("runs listing", listed(r, "batch-control/history/?kind=runs&" + range).contains(job));
        named.put("runs.csv", exported(r, "batch-control/history/runs.csv?" + range).contains(job));
        named.put("changes listing", listed(r, "batch-control/history/?kind=changes&" + range).contains(job));
        named.put("changes.csv", exported(r, "batch-control/history/changes.csv?" + range).contains(job));
        List<String> wrong = new ArrayList<>();
        named.forEach((surface, has) -> {
            if (has != expected) {
                wrong.add(surface);
            }
        });
        assertTrue(wrong.isEmpty(), what + ": for " + day + " (plugin clock zone " + BatchClock.clock().getZone() + ") "
                + job + " must " + (expected ? "" : "not ") + "be listed; wrong on " + wrong + " (named: " + named + ")");
    }

    /** The fixture jobs named in the table rows of a listing, as the viewer sees it. */
    private static List<String> listed(JenkinsRule r, String path) throws Exception {
        HtmlPage page = UsabilityFixtures.htmlPage(r, "viewer", path);
        assertEquals(200, page.getWebResponse().getStatusCode(), "the listing " + path + " opens");
        List<String> out = new ArrayList<>();
        for (DomElement tr : page.getElementsByTagName("tr")) {
            out.addAll(fixtureJobs(tr.asNormalizedText()));
        }
        return out;
    }

    private static List<String> exported(JenkinsRule r, String path) throws Exception {
        WebResponse csv = ApproverFormFixtures.get(r, "viewer", path);
        assertEquals(200, csv.getStatusCode(), "the export " + path + " answers 200");
        List<String> out = new ArrayList<>();
        for (String line : csv.getContentAsString().split("\\R")) {
            out.addAll(fixtureJobs(line));
        }
        return out;
    }

    private static List<String> fixtureJobs(String text) {
        List<String> out = new ArrayList<>();
        java.util.regex.Matcher m = FIXTURE_JOB.matcher(text);
        while (m.find()) {
            out.add(m.group());
        }
        return out;
    }

    /** Run control on (records are written while a switch is on, SPEC 1/9); a viewer with ViewHistory. */
    private static void secure(JenkinsRule r) throws Exception {
        r.jenkins.setSecurityRealm(r.createDummySecurityRealm());
        r.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.VIEW_HISTORY).everywhere().to("viewer"));
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("admin"));
        cfg.save();
    }
}
