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
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * The single writer of the "an attempt was refused" audit records ({@link
 * ChangeType#MARKER_REUSE_BLOCKED}, {@link ChangeType#REMOTE_RUN_BLOCKED}), with a bound on how
 * often one repeated attempt may append (S-21).
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

    /** Key separator; a character no id, job full name or user id contains. */
    private static final String KEY_SEPARATOR = "";

    private static final BlockedAttemptAudit INSTANCE = new BlockedAttemptAudit();

    private final Store store = FileStore.get();

    /**
     * Key to the instant of the last record written under it. Insertion-ordered and re-inserted on
     * every write, so the front of the map is always the least recently written key.
     */
    private final Map<String, Instant> lastWritten = new LinkedHashMap<>();

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
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(attemptKey, "attemptKey");
        Instant now = BatchClock.now();
        forgetOtherInstance();
        String key = type.name() + KEY_SEPARATOR + attemptKey + KEY_SEPARATOR + user;
        Instant previous = lastWritten.get(key);
        if (previous != null && !previous.plus(COOLDOWN).isBefore(now)) {
            LOGGER.fine(() -> "Not appending a second " + type + " record for '" + attemptKey
                    + "' by user '" + user + "' within " + COOLDOWN + " of the last one"
                    + " (S-21 bound); the attempt stays in the log: " + detail);
            return false;
        }
        store.appendChangeRecord(ChangeRecord.create(type, target, user, detail));
        lastWritten.remove(key);
        lastWritten.put(key, now);
        evictOldest();
        return true;
    }

    /** Drops the tracking map when it belongs to a previous {@link Jenkins} instance. */
    private void forgetOtherInstance() {
        Jenkins current = Jenkins.getInstanceOrNull();
        if (trackedFor.get() != current) {
            lastWritten.clear();
            trackedFor = new WeakReference<>(current);
        }
    }

    /** Keeps the map at {@link #MAX_TRACKED_KEYS} by dropping the least recently written keys. */
    private void evictOldest() {
        Iterator<Map.Entry<String, Instant>> it = lastWritten.entrySet().iterator();
        while (lastWritten.size() > MAX_TRACKED_KEYS && it.hasNext()) {
            it.next();
            it.remove();
        }
    }
}
