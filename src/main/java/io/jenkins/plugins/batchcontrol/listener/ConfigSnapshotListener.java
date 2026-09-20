package io.jenkins.plugins.batchcontrol.listener;

import hudson.Extension;
import hudson.XmlFile;
import hudson.model.Item;
import hudson.model.Saveable;
import hudson.model.listeners.SaveableListener;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.model.GrantAction;
import io.jenkins.plugins.batchcontrol.store.FileStore;
import io.jenkins.plugins.batchcontrol.store.SecretMasker;
import io.jenkins.plugins.batchcontrol.store.Store;
import io.jenkins.plugins.batchcontrol.store.UnifiedDiff;
import java.io.IOException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Records CONFIGURE changes with a unified diff (SPEC item 9) and maintains the per-item config
 * snapshots ({@code snapshots/<encoded fullName>.xml}, latest only — ARCHITECTURE section 5).
 *
 * <p>{@link SaveableListener} is the one hook every config-writing path goes through — UI form
 * submit ({@code save()}), REST {@code config.xml} POST and CLI {@code update-job}
 * ({@code updateByXml}), Job DSL, and programmatic saves — so nothing escapes the record
 * (SPEC item 9: "regardless of the path").
 *
 * <p>Both diff sides are masked with {@link SecretMasker} BEFORE diffing, so no hunk can ever
 * carry a secret. When only secret payloads changed (both sides mask to the same text), a
 * masked-change note is stored instead of an empty diff.
 *
 * <p>The diff-and-swap of a snapshot runs under one lock so rapid consecutive saves chain
 * baseline-consistently (each diff is previous-config vs new-config, T-RT-20).
 */
@Extension
@Restricted(NoExternalUse.class)
public class ConfigSnapshotListener extends SaveableListener {

    private static final Logger LOGGER = Logger.getLogger(ConfigSnapshotListener.class.getName());

    /** Serializes snapshot read → diff → snapshot write so records never lose a revision. */
    private static final ReentrantLock LOCK = new ReentrantLock();

    @Override
    public void onChange(Saveable o, XmlFile file) {
        if (!(o instanceof Item)) {
            return;
        }
        if (ChangeRecording.isSuppressed() || !ChangeRecording.isActive()) {
            return;
        }
        Item item = (Item) o;
        String fullName = item.getFullName();
        LOCK.lock();
        try {
            String newXml;
            try {
                newXml = file.asString();
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Cannot read saved config of '" + fullName + "'", e);
                return;
            }
            Store store = FileStore.get();
            String oldXml = store.loadConfigSnapshot(fullName);
            if (oldXml == null) {
                // First sighting: the creation-time initial save (the CREATE record comes from
                // the ItemListener) or an item predating the switch. Establish the baseline.
                store.saveConfigSnapshot(fullName, newXml);
                return;
            }
            if (oldXml.equals(newXml)) {
                return; // no-op save, nothing changed
            }
            // Mask BOTH sides before diffing so no hunk can ever carry a secret.
            String maskedOld = SecretMasker.mask(oldXml);
            String maskedNew = SecretMasker.mask(newXml);
            String diff;
            if (maskedOld.equals(maskedNew)) {
                diff = "Only secret values changed; secrets are stored as "
                        + SecretMasker.MASK + " and never in plaintext.";
            } else {
                diff = UnifiedDiff.diff(maskedOld, maskedNew);
            }
            String user = ChangeRecording.currentUser();
            ChangeRecord record = ChangeRecord.create(ChangeType.CONFIGURE, fullName, user, null);
            record.setDiff(diff);
            record.setGrantId(ChangeRecording.activeGrantIdFor(user, fullName, GrantAction.CONFIGURE));
            store.saveConfigSnapshot(fullName, newXml);
            store.appendChangeRecord(record);
        } finally {
            LOCK.unlock();
        }
    }
}
