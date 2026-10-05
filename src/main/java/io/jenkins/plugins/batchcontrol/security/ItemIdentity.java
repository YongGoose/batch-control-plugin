package io.jenkins.plugins.batchcontrol.security;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.attribute.BasicFileAttributes;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * security-13 S-13-09: an opaque marker of an item's directory on disk, recorded with an
 * activation state so that a state left behind by a deleted item is not taken for a new item of
 * the same name.
 *
 * <p>The file key (device and inode on POSIX file systems) is used where the file system has one:
 * it survives restarts, renames and moves inside {@code $JENKINS_HOME}, and a recreated directory
 * normally gets a new one. Without a file key (Windows) the creation time is used. Permission
 * windows do not use it: they follow their item through item events (D-74, {@link WindowItemListener}).
 */
@Restricted(NoExternalUse.class)
public final class ItemIdentity {

    private ItemIdentity() {
    }

    /** The marker of {@code rootDir}, or {@code null} when it does not exist or cannot be read. Uncached. */
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
