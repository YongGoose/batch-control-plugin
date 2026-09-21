package io.jenkins.plugins.batchcontrol.ops;

import hudson.Extension;
import hudson.model.PeriodicWork;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import io.jenkins.plugins.batchcontrol.store.FileStore;
import java.time.YearMonth;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Daily retention cleanup (SPEC item 12): deletes every month bucket older than the configured
 * {@code retentionMonths} — the runs and changes JSONL files, that month's diff patches, and
 * that month's incidents (index + XMLs) — and records each deleted month as
 * {@code ChangeRecord(type=RETENTION)} identifying the month as {@code YYYY-MM}.
 *
 * <p>A month is deleted when it lies strictly before {@code currentMonth - retentionMonths},
 * so the current (partial) month plus the last {@code retentionMonths} full months are always
 * kept (conservative deletion).
 *
 * <p>This is a synchronous {@link PeriodicWork} (not {@code AsyncPeriodicWork}, whose
 * {@code doRun()} is {@code public final} and merely starts a background thread): the test
 * contract calls {@link #doRun()} directly and asserts the cleanup has completed on return,
 * and a once-a-day directory sweep is cheap enough to run inline.
 */
@Extension
@Restricted(NoExternalUse.class)
public class RetentionPeriodicWork extends PeriodicWork {

    private static final Logger LOGGER = Logger.getLogger(RetentionPeriodicWork.class.getName());

    @Override
    public long getRecurrencePeriod() {
        return TimeUnit.DAYS.toMillis(1);
    }

    @Override
    public void doRun() {
        if (Jenkins.getInstanceOrNull() == null) {
            return;
        }
        int retentionMonths = BatchControlGlobalConfiguration.get().getRetentionMonths();
        YearMonth currentMonth = YearMonth.from(
                BatchClock.now().atZone(BatchClock.clock().getZone()));
        YearMonth oldestKept = currentMonth.minusMonths(retentionMonths);
        FileStore store = FileStore.get();
        for (YearMonth month : store.listStoredMonths()) {
            if (!month.isBefore(oldestKept)) {
                continue;
            }
            try {
                if (store.deleteMonth(month)) {
                    // The deletion itself is auditable (SPEC item 12): target identifies the
                    // deleted month as YYYY-MM.
                    store.appendChangeRecord(ChangeRecord.create(ChangeType.RETENTION,
                            month.toString(), Jenkins.getAuthentication2().getName(),
                            "Deleted the " + month + " run records, change records and incidents:"
                                    + " older than retentionMonths=" + retentionMonths));
                    LOGGER.info(() -> "Retention cleanup deleted the " + month + " month bucket"
                            + " (retentionMonths=" + retentionMonths + ")");
                }
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, e, () -> "Retention cleanup failed for month " + month
                        + "; it will be retried on the next run");
            }
        }
    }
}
