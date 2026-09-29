package io.jenkins.plugins.batchcontrol.security;

import hudson.model.Cause;
import hudson.model.CauseAction;
import hudson.model.Item;
import hudson.model.ItemGroup;
import hudson.model.Items;
import hudson.model.Job;
import hudson.model.Queue;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.Grant;
import io.jenkins.plugins.batchcontrol.model.GrantAction;
import io.jenkins.plugins.batchcontrol.model.GrantRequest;
import io.jenkins.plugins.batchcontrol.model.GrantScope;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.store.Store;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.springframework.security.core.Authentication;

/**
 * D-50 (SPEC item 2, e2e-03 DEF-37): whether builds of a job run as SYSTEM under the configured
 * build authenticators, and which jobs in the scope of a pending or active CONFIGURE grant do.
 *
 * <p>A configured authenticator does not mean a job's builds run as a user: with Authorize
 * Project's per-project setting, a job without its own strategy still runs as SYSTEM. So the
 * question is asked the way the queue asks it. A probe {@link Queue.WaitingItem} of the job, never
 * scheduled, carrying the cause of a person pressing Build Now, is passed to
 * {@link Queue.Item#authenticate2()}: every {@link jenkins.security.QueueItemAuthenticatorProvider}'s
 * authenticators in order (each defaults to core's generic
 * {@code QueueItemAuthenticator#authenticate2(Queue.Task)}; Authorize Project answers per item),
 * the first identity wins, and without one the task's default, which is SYSTEM for a job. The item
 * form is used because Authorize Project implements only that form; asking the task form alone
 * would call every job SYSTEM.
 *
 * <p>Bounded and exception-safe: at most {@value #MAX_SCANNED} jobs are examined and
 * {@value #MAX_RESULTS} names returned per call; the scopes come from the active grants and the
 * open-request index, so no closed request is read; nothing is cached, so a change to a job's
 * build authorization shows at once; any failure of an authenticator counts as "cannot tell",
 * never as an exception to the caller.
 */
@Restricted(NoExternalUse.class)
public final class SystemBuildCheck {

    private static final Logger LOGGER = Logger.getLogger(SystemBuildCheck.class.getName());

    /** At most this many names are returned. */
    static final int MAX_RESULTS = 50;

    /** At most this many jobs are examined per computation. */
    static final int MAX_SCANNED = 500;

    /** The probe's user id: never a real account, so it names nobody. */
    private static final String PROBE_USER = "batch-control:system-build-probe";

    private SystemBuildCheck() {
    }

    /**
     * Whether the configured build authenticators give builds of {@code job} no identity other
     * than SYSTEM. {@code false} for a job that cannot be queued, and when an authenticator fails
     * (logged at FINE).
     */
    public static boolean runsAsSystem(Job<?, ?> job) {
        if (!(job instanceof Queue.Task)) {
            return false;
        }
        try {
            List<hudson.model.Action> actions = new ArrayList<>();
            actions.add(new CauseAction(new Cause.UserIdCause(PROBE_USER)));
            // A WaitingItem is a plain object: constructing it does not touch the queue.
            Queue.WaitingItem probe = new Queue.WaitingItem(Calendar.getInstance(), (Queue.Task) job, actions);
            Authentication identity = probe.authenticate2();
            return identity == null || ACL.SYSTEM2.equals(identity)
                    || ACL.SYSTEM_USERNAME.equals(identity.getName());
        } catch (RuntimeException | LinkageError e) {
            LOGGER.log(Level.FINE, e, () -> "Cannot tell whether builds of '" + job.getFullName() + "' run as SYSTEM");
            return false;
        }
    }

    /**
     * Full names of the jobs in the scope of a pending or active CONFIGURE grant (a folder scope
     * expands to the jobs below it) whose builds {@linkplain #runsAsSystem run as SYSTEM}; sorted,
     * at most {@value #MAX_RESULTS}. Empty while change control is off. Names are not filtered by
     * the caller's permissions: callers show only what their viewer may read.
     */
    public static List<String> jobsRunningAsSystem() {
        try {
            if (!BatchControlGlobalConfiguration.get().isChangeControlEnabled()) {
                return Collections.emptyList();
            }
            return Collections.unmodifiableList(compute(Jenkins.get()));
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "Could not determine the jobs whose builds run as SYSTEM", e);
            return Collections.emptyList();
        }
    }

    private static List<String> compute(Jenkins jenkins) {
        Set<GrantScope> scopes = configureScopes();
        if (scopes.isEmpty()) {
            return new ArrayList<>();
        }
        TreeSet<String> found = new TreeSet<>();
        int[] scanned = {0};
        // ACL.SYSTEM2 (D-50): a read-only walk of item names below the grant scopes, so the list
        // does not depend on who asks. Nothing is changed and no permission is decided here; the
        // callers are an administrator-only monitor and the grant request page (reached only after
        // its section's permission check), and they name only jobs their viewer may read.
        try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) {
            for (GrantScope scope : scopes) {
                if (scanned[0] >= MAX_SCANNED) {
                    break;
                }
                Item item = jenkins.getItemByFullName(scope.getFullName());
                if (item instanceof Job) {
                    consider((Job<?, ?>) item, found, scanned);
                } else if (scope.getType() == GrantScope.Type.FOLDER && item instanceof ItemGroup) {
                    for (Job<?, ?> job : Items.allItems2(ACL.SYSTEM2, (ItemGroup<?>) item, Job.class)) {
                        if (scanned[0] >= MAX_SCANNED) {
                            break;
                        }
                        consider(job, found, scanned);
                    }
                }
            }
        }
        if (scanned[0] >= MAX_SCANNED) {
            LOGGER.warning(() -> "SYSTEM-build check stopped after " + MAX_SCANNED + " jobs; some jobs in"
                    + " Configure grant scopes were not checked");
        }
        List<String> out = new ArrayList<>(found);
        return out.size() > MAX_RESULTS ? new ArrayList<>(out.subList(0, MAX_RESULTS)) : out;
    }

    private static void consider(Job<?, ?> job, Set<String> found, int[] scanned) {
        scanned[0]++;
        if (runsAsSystem(job)) {
            found.add(job.getFullName());
        }
    }

    /** The scopes of pending CONFIGURE requests and active CONFIGURE grants. */
    private static Set<GrantScope> configureScopes() {
        Set<GrantScope> scopes = new LinkedHashSet<>();
        for (Grant grant : GrantService.get().listActive()) {
            if (grant.getActions() != null && grant.getActions().contains(GrantAction.CONFIGURE)
                    && grant.getScope() != null) {
                scopes.add(grant.getScope());
            }
        }
        // The open-request index: no scan of closed requests (#13).
        for (GrantRequest request : Store.get().listOpenGrantRequests()) {
            if (request.getStatus() == RequestStatus.PENDING && request.getActions() != null
                    && request.getActions().contains(GrantAction.CONFIGURE) && request.getScope() != null) {
                scopes.add(request.getScope());
            }
        }
        return scopes;
    }
}
