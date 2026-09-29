package io.jenkins.plugins.batchcontrol.ops;

import hudson.init.InitMilestone;
import hudson.init.Initializer;
import io.jenkins.plugins.batchcontrol.policy.ActivationService;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * One-time upgrade seeding of activation approval (SPEC item 6a, DESIGN-ACTIVATION-APPROVAL
 * section 6): jobs that exist when this version is first installed are recorded as activated, so
 * installing or upgrading never stops an existing schedule. Keyed by the stored marker
 * {@code activations/.schema}, never by whether the directory is empty, so it runs exactly once.
 *
 * <p>Runs after {@link InitMilestone#JOB_CONFIG_ADAPTED}, when every job is loaded and before
 * the instance is up and accepting new jobs. Initializers run as SYSTEM, so every job is seen.
 * On failure the marker is not written and the seeding is retried at the next start; until then
 * the jobs it did not reach are not activated (fail closed), which the log says.
 */
@Restricted(NoExternalUse.class)
public final class ActivationSeeding {

    private static final Logger LOGGER = Logger.getLogger(ActivationSeeding.class.getName());

    private ActivationSeeding() {
    }

    @Initializer(after = InitMilestone.JOB_CONFIG_ADAPTED)
    public static void seed() {
        try {
            ActivationService.get().seedExistingJobs();
        } catch (RuntimeException e) {
            LOGGER.log(Level.SEVERE, "Seeding existing jobs as activated failed; it is retried at the next "
                    + "start. Until then the timer and upstream triggers of the jobs it did not reach are "
                    + "refused on run-controlled jobs (SPEC item 6a)", e);
        }
    }
}
