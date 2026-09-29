package io.jenkins.plugins.batchcontrol.security;

import hudson.model.Action;
import hudson.model.FreeStyleProject;
import hudson.model.Queue;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import jenkins.security.QueueItemAuthenticator;
import jenkins.security.QueueItemAuthenticatorProvider;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.springframework.security.core.Authentication;

/**
 * D-50, D-50a (SPEC item 2, e2e-03 DEF-37, security-21): whether builds on this instance may run
 * as SYSTEM although change control is on.
 *
 * <p>A job's own build authorization does not protect it (a Configure holder can remove it, and a
 * strategy that follows the triggering user leaves timer and SCM builds as SYSTEM). So one
 * instance-wide question is asked: would a build of a job that has no build authorization of its
 * own and no user cause get an identity other than SYSTEM from the configured authenticators?
 *
 * <p>The question is asked the way the queue asks it, {@link Queue.Item#authenticate2()}: every
 * {@link QueueItemAuthenticatorProvider}'s authenticators in order, the first identity wins, and
 * without one the task's default, SYSTEM for a job. The probe is an in-memory
 * {@link FreeStyleProject} that is never added to Jenkins, saved or scheduled, wrapped in a
 * {@link Queue.WaitingItem} that never enters the queue and carries no cause. Nothing is
 * scheduled and no listener fires. Constructing the item takes one queue id from
 * {@code QueueIdStrategy} (core's own {@code QueueItemAuthenticator#authenticate2(Queue.Task)}
 * does the same, and {@code Queue.Item} cannot be subclassed outside core); with the cache below
 * that is at most one id per {@value #CACHE_TTL_MINUTES} minutes (S-21-07).
 *
 * <p>The probe runs in one fixed context, as anonymous, whoever renders the page, so an
 * authenticator that consults the current authentication answers the same on every path
 * (S-21-07). No {@code ACL.SYSTEM2} switch is made. An authenticator that fails counts as
 * "SYSTEM" (fail-safe). The answer is cached for {@value #CACHE_TTL_MINUTES} minutes and
 * recomputed at once when the set of configured authenticators changes (a save of the global
 * security page replaces them), so the monitor's {@code isActivated()} does not probe on every
 * page (S-21-06).
 */
@Restricted(NoExternalUse.class)
public final class SystemBuildCheck {

    private static final Logger LOGGER = Logger.getLogger(SystemBuildCheck.class.getName());

    static final long CACHE_TTL_MINUTES = 5;

    /** The probe job's name: it is never registered, so it names nothing. */
    private static final String PROBE_NAME = "batch-control-system-build-probe";

    private static final class Cached {
        final Jenkins owner;
        final List<QueueItemAuthenticator> authenticators;
        final boolean value;
        final long atNanos;

        Cached(Jenkins owner, List<QueueItemAuthenticator> authenticators, boolean value, long atNanos) {
            this.owner = owner;
            this.authenticators = authenticators;
            this.value = value;
            this.atNanos = atNanos;
        }

        boolean fresh(Jenkins jenkins, List<QueueItemAuthenticator> current, long now) {
            if (owner != jenkins || now - atNanos >= TimeUnit.MINUTES.toNanos(CACHE_TTL_MINUTES)
                    || authenticators.size() != current.size()) {
                return false;
            }
            for (int i = 0; i < current.size(); i++) {
                if (authenticators.get(i) != current.get(i)) {
                    return false;
                }
            }
            return true;
        }
    }

    private static volatile Cached cached;

    private SystemBuildCheck() {
    }

    /**
     * Whether, with change control on, a build of a job without its own build authorization and
     * without a user cause would get no identity other than SYSTEM from the configured
     * authenticators. {@code false} while change control is off. Cached; never throws.
     */
    public static boolean buildsMayRunAsSystem() {
        try {
            if (!BatchControlGlobalConfiguration.get().isChangeControlEnabled()) {
                return false;
            }
            Jenkins jenkins = Jenkins.get();
            List<QueueItemAuthenticator> current = authenticators();
            long now = System.nanoTime();
            Cached c = cached;
            if (c != null && c.fresh(jenkins, current, now)) {
                return c.value;
            }
            boolean value = probe(jenkins);
            cached = new Cached(jenkins, current, value, now);
            return value;
        } catch (RuntimeException | LinkageError e) {
            LOGGER.log(Level.WARNING, "Could not tell whether builds run as SYSTEM; assuming they may", e);
            return true;
        }
    }

    /** Drops the cached answer (the next call probes again). */
    public static void invalidate() {
        cached = null;
    }

    private static List<QueueItemAuthenticator> authenticators() {
        List<QueueItemAuthenticator> list = new ArrayList<>();
        for (QueueItemAuthenticator authenticator : QueueItemAuthenticatorProvider.authenticators()) {
            list.add(authenticator);
        }
        return list;
    }

    private static boolean probe(Jenkins jenkins) {
        // A fixed, unprivileged context for third-party authenticators (S-21-07); not SYSTEM.
        try (ACLContext ignored = ACL.as2(Jenkins.ANONYMOUS2)) {
            FreeStyleProject job = new FreeStyleProject(jenkins, PROBE_NAME);
            List<Action> noCause = Collections.emptyList();
            Queue.WaitingItem item = new Queue.WaitingItem(Calendar.getInstance(), job, noCause);
            Authentication identity = item.authenticate2();
            return identity == null || ACL.SYSTEM2.equals(identity)
                    || ACL.SYSTEM_USERNAME.equals(identity.getName());
        } catch (RuntimeException | LinkageError e) {
            LOGGER.log(Level.WARNING, "A build authenticator failed on the SYSTEM-build probe; assuming builds may"
                    + " run as SYSTEM", e);
            return true;
        }
    }
}
