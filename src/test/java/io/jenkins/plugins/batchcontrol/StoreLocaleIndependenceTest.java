package io.jenkins.plugins.batchcontrol;

import hudson.ExtensionList;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.CauseType;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.model.RunRecord;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import io.jenkins.plugins.batchcontrol.ops.RetentionPeriodicWork;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import io.jenkins.plugins.batchcontrol.store.FileStore;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.stream.Collectors;
import java.util.stream.Stream;
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
 * SPEC item 4, "stored file names, month bucket names and record ids do not depend on the
 * controller's default locale or time zone" (#17). Matrix rows T-04-08, T-04-09 and T-04-10
 * (note 63). The screen-date half is T-04-11 in {@code ui.DatesClockZoneTest}.
 *
 * <p>The default locale and time zone are changed for the duration of each test and restored in
 * {@link #restoreDefaults()}. Each row asserts its premise: the locale really formats numbers
 * with non-ASCII digits, the default zone really puts the instant on another date.
 *
 * Written from docs/SPEC.md, docs/ARCHITECTURE.md section 5, issue #17 and docs/TEST-MATRIX.md
 * only (no src/main knowledge).
 */
@WithJenkins
public class StoreLocaleIndependenceTest {

    /** Locales whose default numbering system is not ASCII. */
    static final String[] LOCALES = {"ar-EG", "hi-IN-u-nu-deva"};

    private static final String MONTH_FILE = "[0-9]{4}-[0-9]{2}\\.jsonl";
    private static final String ID = "[0-9]{8}-[0-9]{6}-[A-Za-z0-9]{6}";

    private JenkinsRule j;
    private Locale savedDefault;
    private Locale savedFormat;
    private Locale savedDisplay;
    private TimeZone savedZone;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        savedDefault = Locale.getDefault();
        savedFormat = Locale.getDefault(Locale.Category.FORMAT);
        savedDisplay = Locale.getDefault(Locale.Category.DISPLAY);
        savedZone = TimeZone.getDefault();

        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.VIEW_HISTORY)
                        .everywhere().to("viewer"));
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("admin"));
        cfg.save();
    }

    @AfterEach
    public void restoreDefaults() {
        Locale.setDefault(savedDefault);
        Locale.setDefault(Locale.Category.FORMAT, savedFormat);
        Locale.setDefault(Locale.Category.DISPLAY, savedDisplay);
        TimeZone.setDefault(savedZone);
        BatchClock.reset();
    }

    /**
     * T-04-08 (#17): under a non-ASCII-digit default locale, a build's run record, a job's CREATE
     * record and a run request are written; every month file name and record id uses ASCII
     * digits; the new records and a record written before the locale change are both listed by
     * the store and on the history screen.
     */
    @Test
    public void t_04_08_recordsAndNamesUnderANonAsciiDigitLocale() throws Exception {
        YearMonth now = YearMonth.now(BatchClock.clock());
        YearMonth previous = now.minusMonths(1);
        Instant before = previous.atDay(15).atTime(12, 0).atZone(BatchClock.clock().getZone()).toInstant();
        FileStore.get().appendRunRecord(new RunRecord("pre-locale-job#1", "pre-locale-job", 1,
                CauseType.USER, "SUCCESS", before, 10L));
        j.createFreeStyleProject("pre-locale-job");

        for (String tag : LOCALES) {
            Locale locale = Locale.forLanguageTag(tag);
            useDefaultLocale(locale);

            String jobName = "locale-job-" + tag.substring(0, 2);
            FreeStyleProject job = BatchControlFixtures.uncontrolled(j.createFreeStyleProject(jobName));
            BatchControlFixtures.activateAsAdmin(job); // D-46: a cause-less submission needs an activation (note 109)
            j.buildAndAssertSuccess(job);
            j.waitUntilNoActivity();
            RunRequest request = RunRequest.create(jobName, new LinkedHashMap<>(), "locale probe", "u1", "admin");
            FileStore.get().saveRunRequest(request);

            assertTrue(request.getId().matches(ID), "under " + tag + " a request id must use ASCII digits: " + request.getId());
            List<ChangeRecord> creates = FileStore.get().listChangeRecords(now).stream()
                    .filter(rec -> rec.getType() == ChangeType.CREATE && jobName.equals(rec.getTarget()))
                    .collect(Collectors.toList());
            assertEquals(1, creates.size(), "under " + tag + " the CREATE record must be listed in " + now);
            assertTrue(creates.get(0).getId().matches(ID), "under " + tag + " a change record id must use ASCII digits: " + creates.get(0).getId());
            assertTrue(FileStore.get().listRunRecords(now).stream()
                    .anyMatch(rec -> jobName.equals(rec.getJobFullName())), "under " + tag + " the build's run record must be listed in " + now);
            assertTrue(FileStore.get().listRunRecords(previous).stream()
                    .anyMatch(rec -> "pre-locale-job".equals(rec.getJobFullName())), "under " + tag + " a record written before the locale change must still be listed");
            assertTrue(FileStore.get().listStoredMonths().containsAll(List.of(previous, now)), "under " + tag + " both months must be recognised as stored months");

            assertAsciiMonthFiles("runs", tag);
            assertAsciiMonthFiles("changes", tag);
            assertAsciiNames(StoreDataFixtures.storeDir().resolve("requests").resolve("run"), tag);

            String from = previous.atDay(1).toString();
            String to = LocalDate.now(BatchClock.clock()).toString();
            WebResponse page = viewerGet("batch-control/history/?from=" + from + "&to=" + to);
            assertEquals(200, page.getStatusCode(), "under " + tag + " the history screen must answer");
            String body = page.getContentAsString();
            assertTrue(body.contains(jobName), "under " + tag + " the history screen must list the new run");
            assertTrue(body.contains("pre-locale-job"), "under " + tag + " the history screen must list the run written before the change");
        }
    }

    /**
     * T-04-09 (#17): under a non-ASCII-digit default locale, retention still recognises and deletes
     * a month past retentionMonths (the month written under that locale). No file of that month
     * is left behind under any digit form.
     */
    @Test
    public void t_04_09_retentionDeletesAnExpiredMonthUnderANonAsciiDigitLocale() throws Exception {
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRetentionMonths(1);
        cfg.save();
        useDefaultLocale(Locale.forLanguageTag("ar-EG"));

        YearMonth oldMonth = YearMonth.now(ZoneOffset.UTC).minusMonths(3);
        Instant oldInstant = oldMonth.atDay(15).atTime(12, 0).atZone(ZoneOffset.UTC).toInstant();
        BatchClock.setForTest(Clock.fixed(oldInstant, ZoneOffset.UTC));
        FileStore.get().appendRunRecord(new RunRecord("old-locale#1", "old-locale", 1,
                CauseType.USER, "SUCCESS", oldInstant, 10L));
        j.createFreeStyleProject("old-locale-created"); // CREATE record in the old month
        assertFalse(FileStore.get().listRunRecords(oldMonth).isEmpty(), "fixture: the old month holds a run record");
        assertFalse(FileStore.get().listChangeRecords(oldMonth).isEmpty(), "fixture: the old month holds a change record");

        BatchClock.reset();
        ExtensionList.lookupSingleton(RetentionPeriodicWork.class).doRun();

        assertTrue(FileStore.get().listRunRecords(oldMonth).isEmpty(), "retention must delete the old month's run records under ar-EG");
        assertTrue(FileStore.get().listChangeRecords(oldMonth).isEmpty(), "retention must delete the old month's change records under ar-EG");
        assertFalse(FileStore.get().listStoredMonths().contains(oldMonth), "the deleted month must no longer be a stored month");
        for (String dir : new String[] {"runs", "changes"}) {
            assertNoFileForMonth(dir, oldMonth);
        }
        assertTrue(FileStore.get().listChangeRecords(YearMonth.now(ZoneOffset.UTC)).stream()
                .anyMatch(rec -> rec.getType() == ChangeType.RETENTION), "the deletion must be recorded as RETENTION");
    }

    /**
     * T-04-10 (#17): month buckets and ids follow the plugin clock's zone, not the controller's
     * default time zone. With the clock in UTC at 2026-09-30T20:00Z and the default zone at
     * UTC+14 (already 1 October there), records go to 2026-09 and ids carry 20260930-200000.
     */
    @Test
    public void t_04_10_bucketsAndIdsFollowThePluginClockZone() throws Exception {
        Instant at = Instant.parse("2026-09-30T20:00:00Z");
        TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Kiritimati"));
        assertEquals(LocalDate.of(2026, 10, 1), LocalDate.ofInstant(at, ZoneId.systemDefault()), "premise: in the default zone the instant is already on another date and month");
        BatchClock.setForTest(Clock.fixed(at, ZoneOffset.UTC));

        RunRequest request = RunRequest.create("zone-job", new LinkedHashMap<>(), "zone probe", "u1", "admin");
        assertTrue(request.getId().startsWith("20260930-200000-"), "the id must carry the plugin clock's local time: " + request.getId());

        FileStore.get().appendRunRecord(new RunRecord("zone-job#1", "zone-job", 1,
                CauseType.USER, "SUCCESS", at, 10L));
        j.createFreeStyleProject("zone-job");

        YearMonth september = YearMonth.of(2026, 9);
        YearMonth october = YearMonth.of(2026, 10);
        assertTrue(FileStore.get().listRunRecords(september).stream()
                .anyMatch(rec -> "zone-job".equals(rec.getJobFullName())), "the run record must be in the plugin clock's month 2026-09");
        assertTrue(FileStore.get().listRunRecords(october).stream()
                .noneMatch(rec -> "zone-job".equals(rec.getJobFullName())), "the run record must not be in the default zone's month 2026-10");
        List<ChangeRecord> creates = FileStore.get().listChangeRecords(september).stream()
                .filter(rec -> rec.getType() == ChangeType.CREATE && "zone-job".equals(rec.getTarget()))
                .collect(Collectors.toList());
        assertEquals(1, creates.size(), "the CREATE record must be in 2026-09");
        assertTrue(creates.get(0).getId().startsWith("20260930-"), "the change record id must carry the plugin clock's date: " + creates.get(0).getId());
        assertTrue(Files.exists(StoreDataFixtures.runsFile(september)), "runs/2026-09.jsonl must exist");
        assertFalse(Files.exists(StoreDataFixtures.runsFile(october)), "runs/2026-10.jsonl must not exist");
    }

    // ---------------------------------------------------------------------------------------

    private static void useDefaultLocale(Locale locale) {
        Locale.setDefault(locale);
        Locale.setDefault(Locale.Category.FORMAT, locale);
        String formatted = String.format("%02d", 9);
        assertFalse(formatted.matches("[0-9]+"), "premise: " + locale.toLanguageTag() + " formats with non-ASCII digits, got " + formatted);
    }

    private static void assertAsciiMonthFiles(String dir, String tag) throws Exception {
        Path path = StoreDataFixtures.storeDir().resolve(dir);
        try (Stream<Path> files = Files.list(path)) {
            for (Path file : files.filter(Files::isRegularFile).collect(Collectors.toList())) {
                String name = file.getFileName().toString();
                assertTrue(name.matches(MONTH_FILE), "under " + tag + " month file " + dir + "/" + name + " must be named YYYY-MM.jsonl in ASCII digits");
            }
        }
    }

    private static void assertAsciiNames(Path dir, String tag) throws Exception {
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.collect(Collectors.toList())) {
                String name = file.getFileName().toString();
                assertTrue(name.chars().allMatch(c -> c < 0x80), "under " + tag + " stored file " + name + " must have an ASCII name");
            }
        }
    }

    /** No file in {@code dir} names {@code month}, whatever digits it was written with. */
    private static void assertNoFileForMonth(String dir, YearMonth month) throws Exception {
        Path path = StoreDataFixtures.storeDir().resolve(dir);
        String wanted = StoreDataFixtures.monthName(month) + ".jsonl";
        try (Stream<Path> files = Files.list(path)) {
            for (Path file : files.collect(Collectors.toList())) {
                assertFalse(asciiDigits(file.getFileName().toString()).equals(wanted), dir + "/" + file.getFileName() + " is a file of the deleted month " + month
                                + " left behind by retention");
            }
        }
    }

    private static String asciiDigits(String s) {
        StringBuilder out = new StringBuilder();
        s.codePoints().forEach(cp -> {
            int digit = Character.digit(cp, 10);
            out.append(digit >= 0 ? String.valueOf((char) ('0' + digit)) : new String(Character.toChars(cp)));
        });
        return out.toString();
    }

    private WebResponse viewerGet(String path) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false);
        wc.getOptions().setJavaScriptEnabled(false);
        return wc.login("viewer").getPage(new WebRequest(new URL(j.getURL(), path), HttpMethod.GET))
                .getWebResponse();
    }
}
