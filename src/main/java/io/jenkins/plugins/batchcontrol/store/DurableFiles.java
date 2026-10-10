package io.jenkins.plugins.batchcontrol.store;

import hudson.Functions;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * #34: the forced writes every store write goes through, as core's {@code AtomicFileWriter} does
 * them. A file that replaces another is written and forced to disk before the atomic rename, and
 * its directory is forced after it, so a power loss leaves either the old or the new file, never a
 * zero-length one; an appended record is forced before the operation that wrote it reports success.
 *
 * <p>Forces go through {@link FileChannel#force(boolean)} (which the JDK reports as the
 * {@code jdk.FileForce} event), never {@code FileDescriptor.sync()}.
 */
@Restricted(NoExternalUse.class)
public final class DurableFiles {

    private static final Logger LOGGER = Logger.getLogger(DurableFiles.class.getName());

    private DurableFiles() {
    }

    /**
     * Writes {@code bytes} to the (new, empty) temporary file {@code tmp} and forces them to disk; the
     * caller then renames it onto its target and calls {@link #forceDirectory}.
     */
    public static void writeForced(Path tmp, byte[] bytes) throws IOException {
        try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
    }

    /**
     * Forces the directory entries of {@code dir} (a rename into it, a file created in it) to disk.
     * Windows cannot open a directory as a channel, so it is skipped there, as core's
     * {@code AtomicFileWriter} does; anywhere else a refusal is logged and does not fail the write,
     * which has already taken effect.
     */
    public static void forceDirectory(Path dir) {
        if (dir == null || Functions.isWindows()) {
            return;
        }
        try (FileChannel channel = FileChannel.open(dir, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | UnsupportedOperationException e) {
            LOGGER.log(Level.FINE, e, () -> "Could not force the directory " + dir + " to disk");
        }
    }
}
