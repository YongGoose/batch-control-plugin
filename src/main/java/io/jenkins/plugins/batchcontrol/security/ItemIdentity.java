package io.jenkins.plugins.batchcontrol.security;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.attribute.BasicFileAttributes;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * S-09: an opaque marker of an item's directory on disk, recorded with a D-35c created-item
 * record so that an item deleted (on disk or otherwise unseen) and recreated under the same name
 * is not taken for the created one.
 *
 * <p>The file key (device and inode on POSIX file systems) is used where the file system has one:
 * it survives restarts, renames and moves inside {@code $JENKINS_HOME}, and a recreated directory
 * gets a new one. Without a file key (Windows) the creation time is used, which is a real birth
 * time there. A directory copied back from a backup gets a new marker, which only takes the
 * grant's Read/Configure on it away (fail-safe).
 */
@Restricted(NoExternalUse.class)
public final class ItemIdentity {

    private ItemIdentity() {
    }

    /** The marker of {@code rootDir}, or {@code null} when it does not exist or cannot be read. */
    @CheckForNull
    public static String of(@CheckForNull File rootDir) {
        if (rootDir == null) {
            return null;
        }
        try {
            BasicFileAttributes attributes = Files.readAttributes(rootDir.toPath(), BasicFileAttributes.class);
            Object key = attributes.fileKey();
            return key != null ? "key:" + key : "created:" + attributes.creationTime().toMillis();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
