package io.jenkins.plugins.batchcontrol.poc;

import hudson.Extension;
import hudson.model.Failure;
import hudson.model.Item;
import hudson.model.listeners.ItemListener;

/**
 * PoC delete veto for design assumption B.
 * When enabled, every deletion attempt is rejected by throwing {@link Failure}
 * from {@link ItemListener#onCheckDelete(Item)}.
 */
@Extension
public class PocDeleteVetoListener extends ItemListener {

    public static volatile boolean vetoEnabled = false;

    public static volatile String message =
            "Deletion blocked by batch-control PoC: request a temporary DELETE grant first.";

    public static void reset() {
        vetoEnabled = false;
    }

    @Override
    public void onCheckDelete(Item item) throws Failure {
        if (vetoEnabled) {
            throw new Failure(message);
        }
    }
}
