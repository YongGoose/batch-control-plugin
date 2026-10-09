package io.jenkins.plugins.batchcontrol.listener;

import com.cloudbees.hudson.plugins.folder.computed.ComputedFolder;
import hudson.Extension;
import hudson.model.Item;
import hudson.model.ItemGroup;
import hudson.model.Job;
import hudson.model.listeners.ItemListener;
import io.jenkins.plugins.batchcontrol.policy.ActivationService;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Keeps the activation store in step with the items (SPEC item 6a): a rename or move keeps the
 * job's activation under its new full name, a deletion removes it, and a newly created job never
 * inherits a state left under its name; a job created while run control is off is recorded as
 * activated (D-45). Computed folders carry the activation of their children (D-46) and are
 * handled like jobs. Runs regardless of the switches: this is bookkeeping, so that turning run
 * control on later finds the right state for every job.
 *
 * <p>{@code onLocationChanged} fires for renames and moves, and recursively for the children of a
 * renamed or moved folder, so every job's state follows it.
 *
 * <p>D-82: a job's sub-item (a matrix configuration, a Maven module) carries no state of its own:
 * {@link ActivationService#onItemCreated} stores none for it (and drops one left under its name),
 * seeding and the D-59a start-over skip it, and a relocation only moves what was already stored.
 */
@Extension
@Restricted(NoExternalUse.class)
public class ActivationItemListener extends ItemListener {

    private static final Logger LOGGER = Logger.getLogger(ActivationItemListener.class.getName());

    @Override
    public void onCreated(Item item) {
        // Every item: a subject gets its initial state, anything else loses a stale one (S-14-03).
        try {
            ActivationService.get().onItemCreated(item);
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, e, () -> "Could not check the activation state of the new job '"
                    + item.getFullName() + "'");
        }
    }

    @Override
    public void onLocationChanged(Item item, String oldFullName, String newFullName) {
        if (!(item instanceof Job) && !(item instanceof ComputedFolder)) {
            return;
        }
        try {
            ActivationService.get().relocate(oldFullName, newFullName);
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, e, () -> "Could not move the activation state of '" + oldFullName
                    + "' to '" + newFullName + "'");
        }
    }

    @Override
    public void onDeleted(Item item) {
        if (!(item instanceof Job) && !(item instanceof ItemGroup)) {
            return;
        }
        try {
            ActivationService.get().remove(item.getFullName(), item instanceof ItemGroup);
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, e, () -> "Could not remove the activation state of '"
                    + item.getFullName() + "'");
        }
    }
}
