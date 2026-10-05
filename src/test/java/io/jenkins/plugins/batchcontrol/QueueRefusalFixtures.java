package io.jenkins.plugins.batchcontrol;

import hudson.model.Action;
import hudson.model.Item;
import hudson.model.Queue;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import jenkins.model.queue.ItemDeletion;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two public ways to make Jenkins' queue refuse the submission of an approved run request, so a
 * row can reach "approved, but the run was not queued" without cancelling a queue item (matrix
 * note 265; corrects note 260's "no public input makes the queue refuse an approved request").
 *
 * <ul>
 *   <li>{@link #refusedBeforeTheGate}: a {@link Queue.QueueDecisionHandler} of the test refuses the
 *       armed job. Each test class that uses it declares
 *       {@code @TestExtension public static final class RefuseBeforeGate extends
 *       QueueRefusalFixtures.RefusingHandler {}}. Handlers of equal ordinal are consulted in class
 *       name order (core's {@code ExtensionComponent#compareTo}), and a nested class of a test in
 *       {@code io.jenkins.plugins.batchcontrol} sorts before any class in a sub-package, so this
 *       handler is consulted before Batch Control's queue gate. Queue scheduling stops at the first
 *       refusal, so the gate never sees the submission.</li>
 *   <li>{@link #refusedAfterTheGate}: core's own {@link ItemDeletion} veto (an item being deleted is
 *       never scheduled). Its class sorts after every {@code io.jenkins} class, so Batch Control's
 *       gate has already accepted the approved submission when core refuses it.</li>
 * </ul>
 *
 * <p>Either way the request is APPROVED and nothing is queued. The SPEC requirement is the same for
 * both orderings (item 5: the files of a request that ends without a run are disposed of; item 4:
 * an approved request not yet queued is submitted after a restart), so a row stays valid even if
 * the ordering premise were ever reversed. Neither cancels a queue item: D-72b (7) makes a cancelled
 * approved queue item a different case (it is not resubmitted).
 *
 * <p>Written from docs/SPEC.md items 4, 5 and 7, docs/DECISIONS.md D-72 and D-72b, and
 * docs/reports/spec-review-S7.md M-1/M-2 (the two orderings) only (no src/main knowledge).
 */
final class QueueRefusalFixtures {

    private static final Set<String> REFUSED = ConcurrentHashMap.newKeySet();
    private static final Map<String, AtomicInteger> REFUSALS = new ConcurrentHashMap<>();

    private QueueRefusalFixtures() {
        // utility class
    }

    /** A step that may throw. */
    interface Step {
        void run() throws Exception;
    }

    /**
     * Refuses every submission of an armed job and counts the refusals. Subclass it as a
     * {@code @TestExtension} nested in the test class (see the class comment).
     */
    public abstract static class RefusingHandler extends Queue.QueueDecisionHandler {
        @Override
        public boolean shouldSchedule(Queue.Task p, List<Action> actions) {
            if (p instanceof Item item && REFUSED.contains(item.getFullName())) {
                REFUSALS.computeIfAbsent(item.getFullName(), k -> new AtomicInteger()).incrementAndGet();
                return false;
            }
            return true;
        }
    }

    /**
     * Runs {@code step} while the test's own queue handler refuses {@code job}; asserts that the
     * handler was consulted (so the {@code @TestExtension} is loaded and the step did try to queue).
     */
    static void refusedBeforeTheGate(Item job, Step step) throws Exception {
        String name = job.getFullName();
        int before = refusals(name);
        REFUSED.add(name);
        try {
            step.run();
        } finally {
            REFUSED.remove(name);
        }
        assertTrue(refusals(name) > before, "fixture: the test's queue handler must have refused a submission of " + name
                + " (is the RefuseBeforeGate @TestExtension declared in the test class?)");
    }

    /** Runs {@code step} while core's item-deletion veto refuses every submission of {@code job}. */
    static void refusedAfterTheGate(Item job, Step step) throws Exception {
        assertTrue(ItemDeletion.register(job), "fixture: " + job.getFullName() + " must not already be marked as being deleted");
        try {
            step.run();
        } finally {
            ItemDeletion.deregister(job);
        }
    }

    private static int refusals(String name) {
        AtomicInteger n = REFUSALS.get(name);
        return n == null ? 0 : n.get();
    }
}
