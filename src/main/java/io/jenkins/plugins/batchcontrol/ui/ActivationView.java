package io.jenkins.plugins.batchcontrol.ui;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.model.Item;
import io.jenkins.plugins.batchcontrol.model.ActivationRequest;
import io.jenkins.plugins.batchcontrol.model.ActivationState;
import io.jenkins.plugins.batchcontrol.policy.ActivationService;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Read-only display helpers for activation state (SPEC item 6a, D-46), shared by the job page,
 * the computed-folder page and the activation screens. Nothing here changes state or decides
 * whether anything may run; the queue gate and {@link ActivationService} do that.
 */
@Restricted(NoExternalUse.class)
public final class ActivationView {

    private ActivationView() {
    }

    /**
     * The item that carries the activation of a computed child (D-46c,
     * {@link ActivationService#activationSubject}), or {@code null} when the item carries its own.
     */
    @CheckForNull
    public static Item carrierOf(Item item) {
        Item subject = ActivationService.activationSubject(item);
        return subject == item ? null : subject;
    }

    /** Whether an activated state is stored for this very item (the service's truthful read). */
    public static boolean isActivated(Item item) {
        return ActivationService.get().isActivated(item);
    }

    /** Whether an approved hold took the item out of service (as opposed to never activated). */
    public static boolean isHeld(Item item) {
        if (isActivated(item)) {
            return false;
        }
        ActivationState state = getState(item);
        return state != null && state.getDeactivatedBy() != null;
    }

    /** The stored state, or {@code null}; a state that cannot be read is shown as none. */
    @CheckForNull
    public static ActivationState getState(Item item) {
        try {
            return ActivationService.get().getState(item.getFullName());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** "activated", "on hold" or "not activated", as the screens word the three states. */
    public static String stateLabel(Item item) {
        if (isActivated(item)) {
            return "activated";
        }
        return isHeld(item) ? "on hold" : "not activated";
    }

    /**
     * S-13-08: the two request kinds, worded so they cannot be mistaken for each other on any
     * list or detail screen.
     */
    public static String actionLabel(@CheckForNull ActivationRequest.Action action) {
        if (action == ActivationRequest.Action.HOLD) {
            return "HOLD (take out of service)";
        }
        if (action == ActivationRequest.Action.ACTIVATE) {
            return "ACTIVATE (put into service)";
        }
        return "";
    }
}
