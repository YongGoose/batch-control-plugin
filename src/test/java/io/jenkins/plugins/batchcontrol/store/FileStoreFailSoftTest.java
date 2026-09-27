package io.jenkins.plugins.batchcontrol.store;

import io.jenkins.plugins.batchcontrol.model.CauseType;
import io.jenkins.plugins.batchcontrol.model.RunRecord;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** One unparseable or unknown-enum JSONL line is skipped; the rest of the month still lists. */
@WithJenkins
public class FileStoreFailSoftTest {

    @Test
    public void corruptJsonlLinesAreSkipped(JenkinsRule j) throws Exception {
        Instant at = Instant.parse("2026-09-10T12:00:00Z");
        YearMonth month = YearMonth.from(at.atZone(BatchClock.clock().getZone()));
        FileStore store = FileStore.get();
        store.appendRunRecord(new RunRecord("r1", "job-a", 1, CauseType.USER, "SUCCESS", at, 10));

        Path runs = j.jenkins.getRootDir().toPath().resolve("batch-control").resolve("runs")
                .resolve(String.format("%04d-%02d.jsonl", month.getYear(), month.getMonthValue()));
        String bad = "{not json\n"
                + "{\"runId\":\"r9\",\"jobFullName\":\"job-a\",\"number\":9,\"causeType\":\"NO_SUCH_CAUSE\","
                + "\"result\":\"SUCCESS\",\"startedAt\":\"" + at + "\",\"durationMs\":1}\n";
        Files.writeString(runs, bad, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        store.appendRunRecord(new RunRecord("r2", "job-a", 2, CauseType.TIMER, "SUCCESS", at, 10));

        List<String> ids = store.listRunRecords(month).stream()
                .map(RunRecord::getRunId).collect(Collectors.toList());
        assertEquals(List.of("r1", "r2"), ids, "both bad lines must be skipped, the good ones kept");

        Path changes = runs.getParent().getParent().resolve("changes").resolve(runs.getFileName());
        Files.createDirectories(changes.getParent());
        Files.writeString(changes, "{\"id\":\"c1\",\"type\":\"NO_SUCH_TYPE\"}\n{broken\n",
                StandardCharsets.UTF_8);
        assertTrue(store.listChangeRecords(month).isEmpty(), "a month of only bad change lines must list as empty");
        assertTrue(store.listStoredMonths().contains(month));
        assertTrue(store.deleteMonth(month), "retention must still be able to delete the month");
    }
}
