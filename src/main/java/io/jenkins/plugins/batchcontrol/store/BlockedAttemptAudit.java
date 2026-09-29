package io.jenkins.plugins.batchcontrol.store;

import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import java.lang.ref.WeakReference;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import jenkins.util.SystemProperties;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * The single writer of the "an attempt was refused" audit records ({@link
 * ChangeType#MARKER_REUSE_BLOCKED}, {@link ChangeType#REMOTE_RUN_BLOCKED},
 * {@link ChangeType#TRIGGER_BLOCKED}), with a bound on how often one repeated attempt may append
 * (S-21, #21).
 *
 * <p><b>Why a bound is needed.</b> Both callers run inside queue scheduling, and core takes the
 * global queue lock <em>before</em> it runs the {@code Queue.QueueDecisionHandler}s
 * ({@code hudson/model/Queue#schedule2}: {@code lock.lock()} precedes the handler loop). A
 * synchronous JSONL append therefore happens with the whole instance's scheduling blocked. An
 * unbounded append on that path is a self-inflicted denial of service as soon as anything retries
 * in a loop, and the {@link ChangeType#REMOTE_RUN_BLOCKED} caller makes that reachable over plain
 * HTTP: a build-token holder can POST {@code /job/X/build?token=…} as fast as the network allows,
 * and every one of those refusals wants a record.
 *
 * <p><b>Why coalescing rather than a cap.</b> A global cap ("stop after N records") would silently
 * drop <em>new and different</em> attempts once a noisy one had filled the budget, which is the
 * opposite of what an audit trail is for. What repeats carries no new information, so this class
 * keeps every distinct attempt and merges repeats of one: the first attempt under a key always
 * writes, further attempts under the same key inside {@link #COOLDOWN} do not. A merged attempt is
 * still logged, so nothing becomes invisible — it just stops appending to a file under a global
 * lock.
 *
 * <p>The tracking map is itself bounded ({@link #MAX_TRACKED_KEYS}, least recently written key
 * evicted first), so an actor cycling job names cannot grow it without limit; evicting a key only
 * means the next attempt under it writes a record, which is the safe direction. The map is dropped
 * when the {@link Jenkins} instance changes, so a restart starts from a clean slate rather than
 * merging away the first post-restart attempt (the instance-scoped cache pattern
 * {@code security.GrantService} already uses).
 *
 * <p>This class does not decide <em>whether</em> an attempt is worth recording, only how often the
 * same one may be written; the callers own that judgement.
 */
@Restricted(NoExternalUse.class)
public final class BlockedAttemptAudit {

    private static final Logger LOGGER = Logger.getLogger(BlockedAttemptAudit.class.getName());

    /**
     * How long one attempt key stays merged. Long enough that a script retrying every few seconds
     * writes once a minute instead of hundreds of times; short enough that an operator watching the
     * history sees a persistent attempt keep reappearing rather than one row they might read as a
     * one-off.
     */
    private static final Duration COOLDOWN = Duration.ofMinutes(1);

    /** Maximum number of distinct keys tracked at once; the least recently written is evicted. */
    private static final int MAX_TRACKED_KEYS = 512;

    /**
     * Bound of the coalesced (per target, long cooldown) keys of {@link #recordCoalesced}. Larger
     * than {@link #MAX_TRACKED_KEYS} because every locked job with a cron is one key for the
     * whole hour: an instance with thousands of locked generated jobs (D-34) must not evict keys
     * inside their hour and so write a record per job per minute. A key is about 100 bytes, so
     * the bound caps the map at roughly a megabyte.
     *
     * <p>The default; the system property {@code <this class>.maxCoalescedKeys} overrides it, read
     * at every use ({@link #maxCoalescedKeys()}), so it can be set before Jenkins starts or at run time.
     */
    static final int MAX_COALESCED_KEYS = 10_000;

    /** The coalesced-key bound in force: the system property, else {@link #MAX_COALESCED_KEYS}. */
    static int maxCoalescedKeys() {
        Integer configured = SystemProperties.getInteger(BlockedAttemptAudit.class.getName() + ".maxCoalescedKeys",
                MAX_COALESCED_KEYS);
        return configured == null || configured < 1 ? MAX_COALESCED_KEYS : configured;
    }

    /** Key separator; a character no id, job full name or user id contains. */
    private static final String KEY_SEPARATOR = "";

    private static final BlockedAttemptAudit INSTANCE = new BlockedAttemptAudit();

    private final Store store = Store.get();

    /**
     * Key to the instant of the last record written under it. Insertion-ordered and re-inserted on
     * every write, so the front of the map is always the least recently written key.
     */
    private final Map<String, Instant> lastWritten = new LinkedHashMap<>();

    /** As {@link #lastWritten}, for the keys of {@link #recordCoalesced} (no user in the key). */
    private final Map<String, Instant> lastCoalesced = new LinkedHashMap<>();

    /** The Jenkins instance {@link #lastWritten} belongs to; a change drops the whole map. */
    private WeakReference<Jenkins> trackedFor = new WeakReference<>(null);

    private BlockedAttemptAudit() {
    }

    public static BlockedAttemptAudit get() {
        return INSTANCE;
    }

    /**
     * Appends one refused-attempt record unless the same attempt by the same account was already
     * recorded inside {@link #COOLDOWN}.
     *
     * @param type       the refusal type; one of the {@code *_BLOCKED} types
     * @param attemptKey what makes two attempts "the same" for merging purposes, chosen by the
     *                   caller: everything that would make a second record say something new
     *                   belongs in it (for a blocked marker re-use that is the request id as well
     *                   as the job — two different approvals replayed on one job are two facts)
     * @param target     the item the attempt was aimed at (the record's {@code target})
     * @param user       the account that made the attempt
     * @param detail     the human-readable description stored on the record
     * @return {@code true} if a record was appended, {@code false} if it was merged into an
     *         earlier one
     */
    public synchronized boolean record(ChangeType type, String attemptKey, String target,
                                       String user, String detail) {
        return record(type, attemptKey, target, user, detail, null);
    }

    /**
     * As {@link #record(ChangeType, String, String, String, String)}, naming the grant involved
     * (D-40: a refused CREATE of a name the grant's restriction does not allow).
     */
    public synchronized boolean record(ChangeType type, String attemptKey, String target,
                                       String user, String detail, String grantId) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(attemptKey, "attemptKey");
        forgetOtherInstance();
        String key = type.name() + KEY_SEPARATOR + attemptKey + KEY_SEPARATOR + user;
        return append(lastWritten, MAX_TRACKED_KEYS, key, COOLDOWN, type, target, user, detail, grantId);
    }

    /**
     * Appends one refused-attempt record unless one was already written under the same
     * {@code type} and {@code attemptKey} inside {@code cooldown}, <em>whoever</em> made the
     * attempt (#21). Used for refusals where the account says nothing new: a blocked timer run is
     * always {@code SYSTEM}, and a blocked upstream run is whatever the upstream build ran as, so
     * a per-user key would neither shorten the history nor add information.
     *
     * @param cooldown how long a written key merges further attempts
     * @return {@code true} if a record was appended, {@code false} if it was merged
     */
    public synchronized boolean recordCoalesced(ChangeType type, String attemptKey, Duration cooldown,
                                                String target, String user, String detail) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(attemptKey, "attemptKey");
        Objects.requireNonNull(cooldown, "cooldown");
        forgetOtherInstance();
        String key = type.name() + KEY_SEPARATOR + attemptKey;
        return append(lastCoalesced, maxCoalescedKeys(), key, cooldown, type, target, user, detail, null);
    }

    private boolean append(Map<String, Instant> tracked, int bound, String key, Duration cooldown,
                           ChangeType type, String target, String user, String detail, String grantId) {
        Instant now = BatchClock.now();
        Instant previous = tracked.get(key);
        if (previous != null && !previous.plus(cooldown).isBefore(now)) {
            LOGGER.fine(() -> "Not appending a second " + type + " record for '" + target
                    + "' by user '" + user + "' within " + cooldown + " of the last one"
                    + "; the attempt stays in the log: " + detail);
            return false;
        }
        ChangeRecord record = ChangeRecord.create(type, target, user, detail);
        record.setGrantId(grantId);
        store.appendChangeRecord(record);
        tracked.remove(key);
        tracked.put(key, now);
        evictOldest(tracked, bound);
        return true;
    }

    /** Drops the tracking map when it belongs to a previous {@link Jenkins} instance. */
    private void forgetOtherInstance() {
        Jenkins current = Jenkins.getInstanceOrNull();
        if (trackedFor.get() != current) {
            lastWritten.clear();
            lastCoalesced.clear();
            trackedFor = new WeakReference<>(current);
        }
    }

    /** Keeps a map at {@code bound} entries by dropping the least recently written keys. */
    private static void evictOldest(Map<String, Instant> tracked, int bound) {
        Iterator<Map.Entry<String, Instant>> it = tracked.entrySet().iterator();
        while (tracked.size() > bound && it.hasNext()) {
            it.next();
            it.remove();
        }
    }
}
