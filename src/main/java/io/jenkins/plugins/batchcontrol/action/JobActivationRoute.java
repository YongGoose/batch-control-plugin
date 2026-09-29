package io.jenkins.plugins.batchcontrol.action;

import hudson.model.Item;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.Stapler;
import org.kohsuke.stapler.StaplerRequest2;

/**
 * The URL space {@code <item>/batch-control/activation[/...]} (SPEC item 6a) of a job or a
 * computed folder (D-46c), handed over by {@link JobRequestAction#getTarget()} and
 * {@link ComputedFolderActivationAction#getTarget()}. Deliberately not a
 * {@link hudson.model.ModelObject}: core's breadcrumb bar skips it, so the activation form reads
 * {@code <item> > Activation} and no crumb opens another page (e2e re-audit DEF-06).
 *
 * <p>Only reachable through those actions, so it is absent (404) without
 * {@code BatchControl/Request} like the rest of that URL space; {@link JobActivationForm#doSubmit}
 * re-checks the permissions. It holds no state.
 */
@Restricted(NoExternalUse.class)
public final class JobActivationRoute {

    private final Item item;

    JobActivationRoute(Item item) {
        this.item = item;
    }

    public Item getItem() {
        return item;
    }

    /** Stapler: {@code activation/} and {@code activation/submit}. */
    public JobActivationForm getActivation() {
        return new JobActivationForm(item);
    }

    /**
     * Whether the rest of the current request, below the owning action's {@code batch-control}
     * segment, is {@code /activation} or lies beneath it.
     */
    static boolean isActivationRequest() {
        StaplerRequest2 req = Stapler.getCurrentRequest2();
        String rest = req == null ? null : req.getRestOfPath();
        return rest != null && (rest.equals("/activation") || rest.startsWith("/activation/"));
    }
}
