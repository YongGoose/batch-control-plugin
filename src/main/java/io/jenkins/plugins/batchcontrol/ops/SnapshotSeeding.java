package io.jenkins.plugins.batchcontrol.ops;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.model.AbstractItem;
import hudson.model.Item;
import hudson.model.Items;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.util.DaemonThreadFactory;
import hudson.util.NamingThreadFactory;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.listener.ConfigSnapshotListener;
import io.jenkins.plugins.batchcontrol.listener.ConfigSnapshotListener.Refresh;
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
 * Keeps the configuration snapshots (the CONFIGURE diff baselines, ARCHITECTURE section 5) in step
 * with the items when change recording becomes active (SPEC item 9).
 *
 * <ul>
 *   <li><b>A switch turns recording on</b> (no switch was on before): nothing kept the snapshots
 *       current while recording was off, so the snapshot of <em>every</em> item is refreshed to its
 *       current configuration, not only the missing ones. Otherwise a change made while recording was
 *       off would appear in the diff of whoever saves the item first, and an item created at a deleted
 *       item's name would be compared with the deleted item's configuration. The first
 *       {@value #SEEDED_ON_CALLER} items are refreshed on the calling thread, so the items of a usual
 *       instance are current when the switch change returns, the rest in the background. Until the
 *       refresh reaches an item, a save that changed it is recorded without a diff
 *       ({@link ConfigSnapshotListener.Refresh}).</li>
 *   <li><b>Startup</b>, after every item is loaded ({@link InitMilestone#JOB_CONFIG_ADAPTED}) and only
 *       while recording is active, in the background so startup is not delayed: the snapshots that
 *       are missing are seeded; existing ones are kept, because recording was on when Jenkins stopped.
 *       When a switch turned recording on while Jenkins started (JCasC applies its configuration before
 *       the items are loaded), recording was off in the configuration the previous run saved, so every
 *       snapshot is refreshed instead, as for a switch-on. Recording turned on by editing the saved
 *       configuration while Jenkins was stopped cannot be told apart from recording that stayed on: it
 *       gets the missing snapshots only.</li>
 * </ul>
 *
 * <p>Bounded work: a pass visits every item once. Seeding looks up whether an item's snapshot exists
 * (one file system lookup) and reads the configuration only of an item that has none; a refresh reads
 * the configuration and the snapshot of each item still waiting for it and writes only a snapshot that
 * differs. There is no periodic scan: passes run only at startup and when recording becomes active, at
 * most one runs in the background and at most one more waits for it (requests in between are folded
 * into the one waiting, which works on the latest refresh). A pass stops when recording is switched off
 * or a newer refresh starts. Each pass logs one summary line.
 *
 * <p>A save that comes before the seeding reached its item, or whose item could not be seeded, is
 * recorded without a diff ({@link ConfigSnapshotListener}), so no change is lost meanwhile.
 */
@Restricted(NoExternalUse.class)
public final class SnapshotSeeding {

    private static final Logger LOGGER = Logger.getLogger(SnapshotSeeding.class.getName());

    /** Items seeded or refreshed on the thread that turned recording on before the rest moves to the background. */
    static final int SEEDED_ON_CALLER = 1_000;

    /** One background thread at most, which ends when idle; tasks wait in an unbounded queue. */
    private static final ExecutorService BACKGROUND = new ThreadPoolExecutor(0, 1, 60, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(),
            new NamingThreadFactory(new DaemonThreadFactory(), "Batch Control snapshot seeding"));

    /** Whether a background pass is waiting to start (a further request is folded into it). */
    private static final AtomicBoolean WAITING = new AtomicBoolean();

    /** The Jenkins session whose startup seeding has been decided; before that, switch changes leave seeding to it. */
    private static volatile WeakReference<Jenkins> startedFor = new WeakReference<>(null);

    /** The starting Jenkins session in which a switch turned recording on before the startup pass was decided. */
    private static volatile WeakReference<Jenkins> turnedOnWhileStarting = new WeakReference<>(null);

    private SnapshotSeeding() {
    }

    /** The counts of one pass. */
    private static final class Pass {
        final boolean refreshing;
        int seeded;
        int present;
        int skipped;
        int failed;
        boolean stopped;
        boolean superseded;
        boolean limited;

        Pass(boolean refreshing) {
            this.refreshing = refreshing;
        }
    }

    /**
     * Startup: every item is loaded. Seeds the missing snapshots in the background when recording is
     * active, or refreshes every snapshot when a switch turned recording on while Jenkins started.
     * Switch changes made before this point (JCasC applies its configuration earlier) are covered by
     * this pass.
     */
    @Initializer(after = InitMilestone.JOB_CONFIG_ADAPTED)
    public static void atStartup() {
        Jenkins jenkins = Jenkins.getInstanceOrNull();
        boolean turnedOn = jenkins != null && turnedOnWhileStarting.get() == jenkins;
        turnedOnWhileStarting = new WeakReference<>(null);
        ConfigSnapshotListener.clearRefresh(); // a refresh belongs to one session
        startedFor = new WeakReference<>(jenkins);
        if (jenkins == null || !recordingActive()) {
            return;
        }
        if (turnedOn) {
            startRefresh(jenkins);
            inBackground("at startup after a switch turned change recording on while Jenkins started");
        } else {
            inBackground("at startup");
        }
    }

    /**
     * A switch turned recording on (no switch was on before). Refreshes the snapshot of every item: on
     * the calling thread up to {@value #SEEDED_ON_CALLER} items, the rest in the background. Before the
     * startup pass of this session was decided it only notes the switch-on, and that pass refreshes.
     * Never throws.
     */
    public static void recordingTurnedOn() {
        try {
            Jenkins jenkins = Jenkins.getInstanceOrNull();
            if (jenkins == null) {
                return;
            }
            if (startedFor.get() != jenkins) {
                turnedOnWhileStarting = new WeakReference<>(jenkins);
                return;
            }
            Refresh refresh = startRefresh(jenkins);
            Pass pass = run(jenkins, SEEDED_ON_CALLER, refresh);
            log("after a switch turned change recording on", pass);
            if (pass.limited) {
                inBackground("after a switch turned change recording on (the remaining items)");
            }
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "Could not refresh the configuration snapshots of the existing items; a change"
                    + " saved before an item's snapshot is refreshed is recorded without a diff", e);
        }
    }

    private static boolean recordingActive() {
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        return cfg.isRunControlEnabled() || cfg.isChangeControlEnabled();
    }

    /** Starts the refresh of every item of {@code jenkins}; collects names only (no file is read). */
    private static Refresh startRefresh(Jenkins jenkins) {
        // Every item as SYSTEM sees it (no switch of the thread's authentication): the refresh must cover
        // every item, whoever turned the switch on. Only item names are collected and nothing is decided
        // for any user; the switch change that started it was permission-checked by its caller (or is the
        // configuration applied while Jenkins starts).
        return ConfigSnapshotListener.startRefresh(jenkins, Items.allItems2(ACL.SYSTEM2, jenkins, Item.class));
    }

    /**
     * Queues a background pass unless one is already waiting to start. The pass refreshes when a
     * refresh of this session is current when it starts (the latest switch-on), and seeds the missing
     * snapshots otherwise.
     */
    private static void inBackground(String when) {
        if (!WAITING.compareAndSet(false, true)) {
            return; // the waiting pass visits every item and works on the latest refresh
        }
        try {
            BACKGROUND.execute(() -> {
                WAITING.set(false);
                try {
                    Jenkins jenkins = Jenkins.getInstanceOrNull();
                    if (jenkins != null && jenkins == startedFor.get() && recordingActive()) {
                        log(when, run(jenkins, Integer.MAX_VALUE, ConfigSnapshotListener.currentRefresh()));
                    }
                } catch (RuntimeException e) {
                    LOGGER.log(Level.WARNING, "Seeding the configuration snapshots " + when + " failed; a configuration"
                            + " change of an item without a current snapshot is recorded without a diff", e);
                }
            });
        } catch (RejectedExecutionException e) {
            WAITING.set(false);
            LOGGER.log(Level.WARNING, "Could not start seeding the configuration snapshots " + when, e);
        }
    }

    /**
     * One pass over every item of {@code jenkins}: refreshes the items still waiting for
     * {@code refresh}, or (without one) seeds the missing snapshots, handling at most {@code limit}
     * items that need any file to be read. Stops when recording is switched off, the Jenkins session
     * ends or a newer refresh starts.
     */
    private static Pass run(Jenkins jenkins, int limit, @CheckForNull Refresh refresh) {
        Pass pass = new Pass(refresh != null);
        int handled = 0;
        // ACL.SYSTEM2 switch: the pass must see every item, whoever turned the switch on (or the
        // background thread, which runs as nobody). Nothing is decided for any user here: it only
        // stores the current configuration of items as their baseline, and the switch change that
        // started it was permission-checked by its caller.
        try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) {
            for (Item item : jenkins.allItems(Item.class)) {
                if (!recordingActive() || Jenkins.getInstanceOrNull() != jenkins) {
                    pass.stopped = true;
                    if (refresh != null) {
                        ConfigSnapshotListener.endRefresh(refresh); // the next switch-on starts a new one
                    }
                    break;
                }
                if (refresh != null && !ConfigSnapshotListener.isCurrent(refresh)) {
                    pass.superseded = true;
                    break;
                }
                if (refresh != null && (!(item instanceof AbstractItem) || !refresh.isPending(item.getFullName()))) {
                    pass.present++; // already current (or nothing to refresh): no file is read
                    continue;
                }
                if (handled >= limit) {
                    pass.limited = true;
                    break;
                }
                ConfigSnapshotListener.Seeding outcome = refresh != null
                        ? ConfigSnapshotListener.refresh(item, refresh)
                        : ConfigSnapshotListener.seedIfMissing(item);
                switch (outcome) {
                    case SEEDED:
                        pass.seeded++;
                        handled++;
                        break;
                    case PRESENT:
                        pass.present++;
                        handled += refresh != null ? 1 : 0;
                        break;
                    case FAILED:
                        pass.failed++;
                        handled++;
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
                : pass.superseded ? "; stopped early because change recording was turned on again (a new pass covers"
                        + " every item)"
                : pass.limited ? "; the remaining items are handled in the background" : "";
        Level level = pass.failed > 0 ? Level.WARNING : Level.INFO;
        if (pass.refreshing) {
            LOGGER.log(level, () -> "Configuration snapshots refreshed " + when + ": " + pass.seeded + " items"
                    + " were brought up to date, " + pass.present + " were already current, " + pass.skipped
                    + " were skipped and " + pass.failed + " could not be refreshed (a configuration change of an"
                    + " item whose snapshot is not refreshed is recorded without a diff)" + outcome);
        } else {
            LOGGER.log(level, () -> "Configuration snapshots seeded " + when + ": " + pass.seeded + " items had none"
                    + " and were seeded, " + pass.present + " already had one, " + pass.skipped + " were skipped and "
                    + pass.failed + " could not be seeded (their first configuration change is recorded without a"
                    + " diff)" + outcome);
        }
    }
}
