package io.jenkins.plugins.batchcontrol.poc;

import hudson.Extension;
import hudson.model.Action;
import hudson.model.Cause;
import hudson.model.CauseAction;
import hudson.model.Failure;
import hudson.model.Item;
import hudson.model.Queue;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PoC queue gate for design assumption A.
 *
 * <p>Blocks every scheduling attempt for jobs listed in {@link #BLOCKED_JOBS} unless the
 * submission carries a {@link PocMarkerAction} (the "approved run" marker). Observed causes
 * of blocked attempts are recorded so tests can prove the handler was actually consulted
 * for each scheduling path (UI, REST, CLI, Replay, build step).
 */
@Extension
public class PocQueueDecisionHandler extends Queue.QueueDecisionHandler {

    /** Full names of jobs whose scheduling should be blocked. Test-controlled. */
    public static final Set<String> BLOCKED_JOBS = ConcurrentHashMap.newKeySet();

    /** When true, blocking throws {@link Failure} (user guidance) instead of silently returning false. */
    public static volatile boolean throwFailure = false;

    /** Message used when {@link #throwFailure} is set. */
    public static volatile String failureMessage =
            "Approval required: submit a run request via the Batch Control page.";

    /** Cause lists observed on blocked attempts, in order. */
    public static final List<List<Cause>> observedBlockedCauses =
            Collections.synchronizedList(new ArrayList<>());

    public static void reset() {
        BLOCKED_JOBS.clear();
        throwFailure = false;
        observedBlockedCauses.clear();
    }

    @Override
    public boolean shouldSchedule(Queue.Task p, List<Action> actions) {
        if (!(p instanceof Item)) {
            return true;
        }
        String fullName = ((Item) p).getFullName();
        if (!BLOCKED_JOBS.contains(fullName)) {
            return true;
        }
        for (Action a : actions) {
            if (a instanceof PocMarkerAction) {
                return true; // approved run marker passes
            }
        }
        List<Cause> causes = new ArrayList<>();
        for (Action a : actions) {
            if (a instanceof CauseAction) {
                causes.addAll(((CauseAction) a).getCauses());
            }
        }
        observedBlockedCauses.add(causes);
        if (throwFailure) {
            throw new Failure(failureMessage);
        }
        return false;
    }
}
