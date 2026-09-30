package io.jenkins.plugins.batchcontrol.store;

import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import java.lang.ref.WeakReference;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
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

    private volatile Store store = Store.get();

    /**
     * Test seam (S-24-04): replaces the store this class appends to and returns the previous one,
     * so a test can make appends fail and restore the real store afterwards. Not for production use.
     */
    @Restricted(NoExternalUse.class)
    public static Store swapStoreForTesting(Store replacement) {
        // S-25-04: refused outside unit tests, and every swap is logged.
        if (!hudson.Main.isUnitTest) {
            throw new IllegalStateException("swapStoreForTesting is only available in unit tests");
        }
        Store previous = INSTANCE.store;
        INSTANCE.store = Objects.requireNonNull(replacement, "replacement");
        LOGGER.warning(() -> "The refused-attempt audit store was replaced by " + replacement.getClass().getName()
                + " (unit test)");
        return previous;
    }

    /**
     * Key to the instant of the last record written under it. Insertion-ordered and re-inserted on
     * every write, so the front of the map is always the least recently written key.
     */
    private final Map<String, Instant> lastWritten = new LinkedHashMap<>();

    /** As {@link #lastWritten}, for the keys of {@link #recordCoalesced} (no user in the key). */
    private final Map<String, Instant> lastCoalesced = new LinkedHashMap<>();

    /** Per-attempt keys of {@link #recordPersonRefusal} (one-minute merge), bounded like the coalesced map. */
    private final Map<String, Instant> lastPersonAttempt = new LinkedHashMap<>();

    /**
     * Closing count records whose append failed, retried by the next {@link #flushPersonSummaries()}
     * (S-23-03). Bounded at {@link #MAX_PENDING_CLOSING}; beyond that a record's text is only in
     * the WARNING log.
     */
    private final List<ChangeRecord> pendingClosing = new ArrayList<>();

    static final int MAX_PENDING_CLOSING = 1_000;

    /** Set by {@link #flushAtShutdown()}: no summary is opened any more (S-23-06). */
    private boolean shutDown;

    /** Per-user budgets of {@link #recordPersonRefusal} (D-51a). */
    private final Map<String, PersonBudget> personBudgets = new LinkedHashMap<>();

    /** Per user, at most this many per-attempt re-run records per {@link #PERSON_WINDOW} (D-51a). */
    static final int PERSON_BUDGET = 20;

    /** The rolling window of {@link #PERSON_BUDGET} (D-51a). */
    static final Duration PERSON_WINDOW = Duration.ofMinutes(10);

    /** Bound of {@link #lastPersonAttempt} and {@link #personBudgets} (D-51a). */
    static final int MAX_PERSON_KEYS = 10_000;

    /**
     * Budget key used when {@link #personBudgets} is full of users still inside their window: they
     * are counted together, so no user's budget is reopened by eviction (D-51a).
     */
    private static final String OVERFLOW_USER = "*";

    /** A user's per-attempt writes inside the window, and the open summary once they are used up. */
    private static final class PersonBudget {
        final java.util.ArrayDeque<Instant> writes = new java.util.ArrayDeque<>();
        /** When the open summary's window ends; {@code null} when none is open. */
        Instant summaryUntil;
        Instant summaryFrom;
        String summaryTarget;
        String summaryUser;
        ChangeType summaryType;
        int summaryCount;
        /** How many of the counted attempts the build list represents (S-22-06). */
        int summaryListed;
        final java.util.Set<String> summaryBuilds = new java.util.LinkedHashSet<>();

        void forgetOld(Instant now) {
            while (!writes.isEmpty() && !writes.peekFirst().plus(PERSON_WINDOW).isAfter(now)) {
                writes.pollFirst();
            }
        }

        boolean idle(Instant now) {
            forgetOld(now);
            return writes.isEmpty() && summaryUntil == null;
        }
    }

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

    /**
     * D-51, D-51a: appends the record of a re-run a person submitted and was refused. A repeat of
     * the same attempt ({@code attemptKey}) by the same user within {@link #COOLDOWN} is merged.
     * Per user at most {@link #PERSON_BUDGET} such records are written per rolling
     * {@link #PERSON_WINDOW}; the next refusal in that window writes one summary record, and the
     * ones after it are counted. The store is append-only (JSONL), so the count is written as a
     * closing record when the summary's window ends ({@link #flushPersonSummaries()} on the
     * per-minute work, the user's own next refusal, or {@link #flushAtShutdown()}); until then each
     * counted refusal is logged at INFO.
     *
     * <p>Both maps are bounded at {@link #MAX_PERSON_KEYS}. Evicting an attempt key only lets that
     * attempt write again, which still spends the user's budget. A user's budget is evicted only
     * once it is idle (no write inside the window, no open summary); when the map is full of users
     * still inside their window, a new user is counted under one shared overflow budget, so no
     * budget is ever reopened by eviction.
     *
     * <p>The bookkeeping is constant-time per call (S-22-04): the only other work is closing the
     * caller's own expired summary, at most one more append. Other users' summaries are closed and
     * idle budgets removed by {@link #flushPersonSummaries()} on the per-minute work, so the queue
     * lock this runs under (see the class comment) is held no longer than the hand-off to the store
     * (D-51a, S-21-05).
     *
     * @param build the re-run build's number as text ({@code ""} when unknown), named in a summary
     * @return {@code true} if a record (per-attempt or summary) was appended
     */
    public synchronized boolean recordPersonRefusal(ChangeType type, String attemptKey, String target,
                                                    String user, String build, String detail) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(attemptKey, "attemptKey");
        forgetOtherInstance();
        Instant now = BatchClock.now();
        String key = type.name() + KEY_SEPARATOR + attemptKey + KEY_SEPARATOR + user;
        Instant previous = lastPersonAttempt.get(key);
        if (previous != null && !previous.plus(COOLDOWN).isBefore(now)) {
            LOGGER.fine(() -> "Merged a repeated refused re-run by '" + user + "' on '" + target + "': " + detail);
            return false;
        }
        lastPersonAttempt.remove(key);
        lastPersonAttempt.put(key, now);
        evictOldest(lastPersonAttempt, MAX_PERSON_KEYS);

        String budgetKey = user;
        PersonBudget budget = personBudgets.get(budgetKey);
        if (budget == null) {
            // S-22-04: idle budgets are removed by the per-minute work, never here (constant time).
            if (personBudgets.size() >= MAX_PERSON_KEYS) {
                budgetKey = OVERFLOW_USER;
                budget = personBudgets.get(OVERFLOW_USER);
            }
            if (budget == null) {
                budget = new PersonBudget();
                personBudgets.put(budgetKey, budget);
            }
        }
        // S-22-04: only the caller's own expired summary is closed here (at most one extra append);
        // other users' summaries are closed by the per-minute work, off the queue lock.
        ChangeRecord closing = budget.summaryUntil != null && !budget.summaryUntil.isAfter(now)
                ? close(budgetKey, budget, null) : null;
        if (closing != null) {
            // S-23-03: a failed closing append never stops the caller's own record; it is retried.
            appendOrKeep(closing);
        }
        budget.forgetOld(now);
        if (shutDown && budget.summaryUntil == null) {
            // S-23-06: after the shutdown flush no summary is opened, since nothing would close it.
            // S-24-01: the budget still applies; beyond it the refusal is only logged.
            if (budget.writes.size() < PERSON_BUDGET && !OVERFLOW_USER.equals(budgetKey)) {
                budget.writes.addLast(now);
                store.appendChangeRecord(ChangeRecord.create(type, target, user, detail));
                return true;
            }
            LOGGER.info(() -> "Refused re-run by '" + user + "' during shutdown, over the record budget (not"
                    + " recorded): " + detail);
            return false;
        }
        if (budget.summaryUntil == null && budget.writes.size() < PERSON_BUDGET && !OVERFLOW_USER.equals(budgetKey)) {
            budget.writes.addLast(now);
            store.appendChangeRecord(ChangeRecord.create(type, target, user, detail));
            return true;
        }
        if (budget.summaryUntil == null) {
            budget.summaryFrom = now;
            budget.summaryUntil = now.plus(PERSON_WINDOW);
            budget.summaryTarget = target;
            budget.summaryUser = user;
            budget.summaryType = type;
            budget.summaryCount = 1;
            budget.summaryListed = 0;
            budget.summaryBuilds.clear();
            addBuild(budget, target, build);
            String who = OVERFLOW_USER.equals(budgetKey) ? "further users" : "'" + user + "'";
            store.appendChangeRecord(ChangeRecord.create(type, target, user,
                    "Further refused re-runs by " + who + " in the next " + PERSON_WINDOW.toMinutes()
                            + " minutes are counted, not listed (more than " + PERSON_BUDGET + " in "
                            + PERSON_WINDOW.toMinutes() + " minutes); the first: " + detail));
            return true;
        }
        budget.summaryCount++;
        addBuild(budget, target, build);
        int count = budget.summaryCount;
        LOGGER.info(() -> "Counted refused re-run " + count + " by '" + user + "' (not listed): " + detail);
        return false;
    }

    private static void addBuild(PersonBudget budget, String target, String build) {
        if (budget.summaryBuilds.size() < 50) {
            budget.summaryBuilds.add(build == null || build.isEmpty() ? target : target + " #" + build);
            budget.summaryListed++; // S-22-06: repeats of a listed build count as listed
        }
    }

    /**
     * The per-minute work (S-22-03, S-22-04): collects, under this object's lock, the closing count
     * records of every summary whose window has ended and removes idle budgets, then appends the
     * records outside the lock, so a queue thread refusing something meanwhile never waits behind
     * these appends. Never runs on the queue lock.
     */
    public void flushPersonSummaries() {
        List<ChangeRecord> closing = new ArrayList<>();
        synchronized (this) {
            forgetOtherInstance();
            closing.addAll(pendingClosing); // S-23-03: retry what failed last time, first
            pendingClosing.clear();
            Instant now = BatchClock.now();
            Iterator<Map.Entry<String, PersonBudget>> it = personBudgets.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, PersonBudget> e = it.next();
                PersonBudget b = e.getValue();
                if (b.summaryUntil != null && !b.summaryUntil.isAfter(now)) {
                    closing.add(close(e.getKey(), b, null));
                }
                if (b.idle(now)) {
                    it.remove();
                }
            }
        }
        int failed = 0;
        for (ChangeRecord record : closing) {
            // S-23-03: one failure never drops the records after it. S-24-02: one stack trace per flush.
            if (!appendOrKeep(record, failed == 0)) {
                failed++;
            }
        }
        if (failed > 1) {
            int count = failed;
            LOGGER.warning(() -> count + " refused re-run count records could not be written in this flush");
        }
    }

    /**
     * Appends a closing count record; on failure logs its full text at WARNING and keeps it for
     * the next flush (S-23-03), so the count is never silently lost.
     */
    private void appendOrKeep(ChangeRecord record) {
        appendOrKeep(record, true);
    }

    /**
     * As {@link #appendOrKeep(ChangeRecord)}; {@code withTrace} logs the stack trace, otherwise
     * only the message (S-24-02). The message says what actually happens to the record: kept for
     * the next flush, or dropped because the pending list is full.
     *
     * @return {@code true} if the record was written
     */
    private boolean appendOrKeep(ChangeRecord record, boolean withTrace) {
        try {
            store.appendChangeRecord(record);
            return true;
        } catch (RuntimeException e) {
            boolean kept;
            synchronized (this) {
                kept = pendingClosing.size() < MAX_PENDING_CLOSING;
                if (kept) {
                    pendingClosing.add(record);
                }
            }
            String message = "Could not write a refused re-run count; " + (kept
                    ? "it is kept for the next flush" : "it is dropped (the pending list is full)")
                    + ": " + record.getDetail();
            if (withTrace) {
                LOGGER.log(java.util.logging.Level.WARNING, message, e);
            } else {
                LOGGER.warning(message + " (" + e + ")");
            }
            return false;
        }
    }

    /**
     * S-22-03: writes the closing count record of every open summary before Jenkins stops, so no
     * summary is left promising a count that never follows. A crash still loses an open count
     * (the records are append-only; documented).
     */
    @hudson.init.Terminator
    public static void flushAtShutdown() {
        INSTANCE.closeAllOpen();
    }

    private void closeAllOpen() {
        List<ChangeRecord> closing = new ArrayList<>();
        synchronized (this) {
            forgetOtherInstance(); // S-24-05: before the flag, so a later instance check cannot reset it
            shutDown = true; // S-23-06
            closing.addAll(pendingClosing);
            pendingClosing.clear();
            Instant now = BatchClock.now();
            for (Map.Entry<String, PersonBudget> e : personBudgets.entrySet()) {
                PersonBudget b = e.getValue();
                if (b.summaryUntil != null) {
                    closing.add(close(e.getKey(), b, b.summaryUntil.isAfter(now) ? now : null));
                }
            }
        }
        for (ChangeRecord record : closing) {
            try {
                store.appendChangeRecord(record);
            } catch (RuntimeException ex) {
                LOGGER.log(java.util.logging.Level.WARNING, ex, () -> "Could not write a refused re-run count at"
                        + " shutdown: " + record.getDetail());
            }
        }
    }

    /**
     * The closing count record of {@code b}'s summary, and resets it. {@code endedEarly} is the
     * shutdown time when the window was cut short, else {@code null}.
     *
     * <p>S-22-06: an overflow summary (the shared budget of {@link #OVERFLOW_USER}) carries the id
     * of the first user who overflowed in its {@code user} field, and says "further users" in its
     * text; the other users' attempts are in the build list and the controller log only.
     */
    private static ChangeRecord close(String budgetKey, PersonBudget b, Instant endedEarly) {
        String who = OVERFLOW_USER.equals(budgetKey) ? "further users" : "'" + budgetKey + "'";
        String until = endedEarly == null ? String.valueOf(b.summaryUntil)
                : endedEarly + " (the window ended early because Jenkins was shutting down)";
        ChangeRecord record = ChangeRecord.create(b.summaryType, b.summaryTarget, b.summaryUser,
                b.summaryCount + " refused re-runs by " + who + " between " + b.summaryFrom + " and "
                        + until + " were counted, not listed: " + String.join(", ", b.summaryBuilds)
                        + (b.summaryListed < b.summaryCount ? ", ..." : ""));
        b.summaryUntil = null;
        b.summaryFrom = null;
        b.summaryTarget = null;
        b.summaryUser = null;
        b.summaryType = null;
        b.summaryCount = 0;
        b.summaryListed = 0;
        b.summaryBuilds.clear();
        b.writes.clear(); // the window that was counted is closed; the budget starts again
        return record;
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
            lastPersonAttempt.clear();
            personBudgets.clear();
            pendingClosing.clear();
            shutDown = false;
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
