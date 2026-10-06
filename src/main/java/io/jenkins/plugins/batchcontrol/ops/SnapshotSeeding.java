package io.jenkins.plugins.batchcontrol.ops;

import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.model.Item;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.util.DaemonThreadFactory;
import hudson.util.NamingThreadFactory;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.listener.ConfigSnapshotListener;
import java.lang.ref.WeakReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Seeds the configuration snapshots (the CONFIGURE diff baselines, ARCHITECTURE section 5) of the
 * items that have none while change recording is active (SPEC item 9). Snapshots are otherwise
 * seeded only when an item is created, so without this the first configuration change of every
 * item that existed before recording became active (the plugin installed or a switch turned on
 * later) would have no baseline.
 *
 * <ul>
 *   <li>At startup, after every item is loaded ({@link InitMilestone#JOB_CONFIG_ADAPTED}) and only
 *       while recording is active, in the background, so startup is not delayed.</li>
 *   <li>When a switch turns recording on: on the calling thread for the first
 *       {@value #SEEDED_ON_CALLER} items it seeds, so the items of a usual instance have their baseline
 *       when the switch change returns, and in the background for the rest.</li>
 * </ul>
 *
 * <p>Bounded work: a pass visits every item once, looks up whether its snapshot exists (one file
 * system lookup) and reads the configuration file only of an item that has none. There is no
 * periodic scan: passes run only at startup and when recording becomes active, at most one runs in
 * the background and at most one more waits for it (requests in between are folded into the one
 * waiting). A pass stops when recording is switched off. Each pass logs one summary line.
 *
 * <p>A save that comes before the seeding reached its item, or whose item could not be seeded, is
 * recorded without a diff ({@link ConfigSnapshotListener}), so no change is lost meanwhile.
 */
@Restricted(NoExternalUse.class)
public final class SnapshotSeeding {

    private static final Logger LOGGER = Logger.getLogger(SnapshotSeeding.class.getName());

    /** Items seeded on the thread that turned recording on before the rest moves to the background. */
    static final int SEEDED_ON_CALLER = 1_000;

    /** One background thread at most, which ends when idle; tasks wait in an unbounded queue. */
    private static final ExecutorService BACKGROUND = new ThreadPoolExecutor(0, 1, 60, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(),
            new NamingThreadFactory(new DaemonThreadFactory(), "Batch Control snapshot seeding"));

    /** Whether a background pass is waiting to start (a further request is folded into it). */
    private static final AtomicBoolean WAITING = new AtomicBoolean();

    /** The Jenkins session whose startup seeding has been decided; before that, switch changes leave seeding to it. */
    private static volatile WeakReference<Jenkins> startedFor = new WeakReference<>(null);

    private SnapshotSeeding() {
    }

    /** The counts of one pass. */
    private static final class Pass {
        int seeded;
        int present;
        int skipped;
        int failed;
        boolean stopped;
        boolean limited;
    }

    /**
     * Startup: every item is loaded. Seeds in the background when recording is active. Switch changes
     * made before this point (JCasC applies its configuration earlier) are covered by this pass.
     */
    @Initializer(after = InitMilestone.JOB_CONFIG_ADAPTED)
    public static void atStartup() {
        Jenkins jenkins = Jenkins.getInstanceOrNull();
        startedFor = new WeakReference<>(jenkins);
        if (jenkins != null && recordingActive()) {
            inBackground("at startup");
        }
    }

    /**
     * A switch turned recording on (no switch was on before). Seeds on the calling thread up to
     * {@value #SEEDED_ON_CALLER} items and hands the rest to the background. Before the startup pass of
     * this session was decided it does nothing (that pass covers it). Never throws.
     */
    public static void recordingTurnedOn() {
        try {
            Jenkins jenkins = Jenkins.getInstanceOrNull();
            if (jenkins == null || startedFor.get() != jenkins) {
                return;
            }
            Pass pass = run(jenkins, SEEDED_ON_CALLER);
            log("after a switch turned change recording on", pass);
            if (pass.limited) {
                inBackground("after a switch turned change recording on (the remaining items)");
            }
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "Could not seed the configuration snapshots of the existing items; their first"
                    + " configuration change is recorded without a diff", e);
        }
    }

    private static boolean recordingActive() {
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        return cfg.isRunControlEnabled() || cfg.isChangeControlEnabled();
    }

    /** Queues a background pass unless one is already waiting to start. */
    private static void inBackground(String when) {
        if (!WAITING.compareAndSet(false, true)) {
            return; // the waiting pass visits every item
        }
        try {
            BACKGROUND.execute(() -> {
                WAITING.set(false);
                try {
                    Jenkins jenkins = Jenkins.getInstanceOrNull();
                    if (jenkins != null && jenkins == startedFor.get() && recordingActive()) {
                        log(when, run(jenkins, Integer.MAX_VALUE));
                    }
                } catch (RuntimeException e) {
                    LOGGER.log(Level.WARNING, "Seeding the configuration snapshots " + when + " failed; the first"
                            + " configuration change of an item without a snapshot is recorded without a diff", e);
                }
            });
        } catch (RejectedExecutionException e) {
            WAITING.set(false);
            LOGGER.log(Level.WARNING, "Could not start seeding the configuration snapshots " + when, e);
        }
    }

    /**
     * One pass over every item of {@code jenkins}, seeding at most {@code limit} snapshots. Stops when
     * recording is switched off or the Jenkins session ends.
     */
    private static Pass run(Jenkins jenkins, int limit) {
        Pass pass = new Pass();
        // ACL.SYSTEM2 switch: the pass must see every item, whoever turned the switch on (or the
        // background thread, which runs as nobody). Nothing is decided for any user here: it only
        // stores the current configuration of items that have no baseline, and the switch change that
        // started it was permission-checked by its caller.
        try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) {
            for (Item item : jenkins.allItems(Item.class)) {
                if (!recordingActive() || Jenkins.getInstanceOrNull() != jenkins) {
                    pass.stopped = true;
                    break;
                }
                if (pass.seeded >= limit) {
                    pass.limited = true;
                    break;
                }
                switch (ConfigSnapshotListener.seedIfMissing(item)) {
                    case SEEDED:
                        pass.seeded++;
                        break;
                    case PRESENT:
                        pass.present++;
                        break;
                    case FAILED:
                        pass.failed++;
                        break;
                    default:
                        pass.skipped++;
                        break;
                }
            }
        }
        return pass;
    }

    private static void log(String when, Pass pass) {
        String outcome = pass.stopped ? "; stopped early because change recording was switched off"
                : pass.limited ? "; the remaining items are seeded in the background" : "";
        Level level = pass.failed > 0 ? Level.WARNING : Level.INFO;
        LOGGER.log(level, () -> "Configuration snapshots seeded " + when + ": " + pass.seeded + " items had none and"
                + " were seeded, " + pass.present + " already had one, " + pass.skipped + " were skipped and "
                + pass.failed + " could not be seeded (their first configuration change is recorded without a diff)"
                + outcome);
    }
}
