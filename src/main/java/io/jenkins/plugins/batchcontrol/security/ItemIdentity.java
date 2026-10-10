package io.jenkins.plugins.batchcontrol.security;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import io.jenkins.plugins.batchcontrol.store.DurableFiles;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * security-13 S-13-09, D-80: the identity of an item's directory, recorded with an activation state
 * so that a state left behind by a deleted item is not taken for a new item of the same name.
 *
 * <p>The identity is a random id kept in a marker file, {@value #MARKER_FILE}, inside the item's
 * root directory. It is written the first time an activation state is stored or seeded for the
 * item ({@link #ensure}) and read on every check ({@link #of}). Because it is part of the
 * directory's content:
 * <ul>
 *   <li>copying, restoring or remounting {@code $JENKINS_HOME} (new inodes, same content) keeps it,
 *       so activations survive a backup restore or a move to another volume;</li>
 *   <li>a rename or move keeps it: core moves the directory, and its fallback copy (Ant's
 *       {@code Copy} with default excludes) does not exclude this name;</li>
 *   <li>a job re-created under a deleted job's name, or created with Jenkins' copy (core copies only
 *       {@code config.xml}), has no marker until its own state is stored, with a new id.</li>
 * </ul>
 * A missing, unreadable or malformed marker gives no identity, and the caller counts the item as
 * not activated (fail closed). Permission windows do not use it: they follow their item through
 * item events (D-74, {@link WindowItemListener}).
 *
 * <p>The marker name starts with a dot and names the plugin, so it cannot collide with a file core
 * or a job type keeps in an item directory ({@code config.xml}, {@code nextBuildNumber},
 * {@code builds/}, {@code jobs/}, {@code configurations/}, {@code modules/}, {@code branches/},
 * the atomic writer's {@code atomic*.tmp}); it is never read as configuration and never shown.
 *
 * <p>Reads are cached per marker path and revalidated with one {@code stat} (file key, modification
 * time, size) and a readability check, so a check does not read the file again unless it changed;
 * a marker that went missing or became unreadable is noticed on the next check.
 */
@Restricted(NoExternalUse.class)
public final class ItemIdentity {

    private static final Logger LOGGER = Logger.getLogger(ItemIdentity.class.getName());

    /** The marker file in an item's root directory (D-80). */
    public static final String MARKER_FILE = ".batch-control-activation-id";

    /**
     * The prefix of an identity read from a marker. Identities stored before D-80 (the
     * directory's file key, {@code key:...} or {@code created:...}) never equal one, and are not
     * converted (D-69).
     */
    static final String PREFIX = "marker:";

    /** A marker holds one UUID and a line end; anything longer is not a marker. */
    private static final int MAX_BYTES = 64;

    private static final Pattern ID =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    /** Bound on the read cache; it is simply cleared when full. */
    private static final int MAX_CACHE_ENTRIES = 10_000;

    /** What a marker looked like when it was last read, and the identity it held. */
    private record Seen(Object fileKey, long modifiedMillis, long size, String identity) {
        boolean matches(BasicFileAttributes attributes) {
            return Objects.equals(fileKey, attributes.fileKey())
                    && modifiedMillis == attributes.lastModifiedTime().toMillis()
                    && size == attributes.size();
        }
    }

    private static final ConcurrentMap<Path, Seen> CACHE = new ConcurrentHashMap<>();

    private static final Object WRITE_LOCK = new Object();

    private ItemIdentity() {
    }

    /**
     * The identity held by the marker in {@code rootDir}, or {@code null} when the directory or the
     * marker does not exist, cannot be read, or does not hold an id.
     */
    @CheckForNull
    public static String of(@CheckForNull File rootDir) {
        if (rootDir == null) {
            return null;
        }
        Path marker = rootDir.toPath().resolve(MARKER_FILE);
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(marker, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException | RuntimeException e) {
            CACHE.remove(marker);
            return null;
        }
        if (!attributes.isRegularFile()) {
            CACHE.remove(marker);
            return null;
        }
        Seen seen = CACHE.get(marker);
        if (seen != null && seen.matches(attributes) && Files.isReadable(marker)) {
            return seen.identity();
        }
        String identity = read(marker);
        if (identity == null) {
            CACHE.remove(marker);
            return null;
        }
        if (CACHE.size() >= MAX_CACHE_ENTRIES) {
            CACHE.clear();
        }
        CACHE.put(marker, new Seen(attributes.fileKey(), attributes.lastModifiedTime().toMillis(),
                attributes.size(), identity));
        return identity;
    }

    /**
     * The identity of {@code rootDir}, writing a marker with a new random id first when there is no
     * readable one (a missing or malformed marker is replaced). Called when an activation state is
     * stored or seeded. {@code null} when the marker cannot be written or read back; the state
     * stored with it then counts as not activated.
     */
    @CheckForNull
    public static String ensure(@CheckForNull File rootDir) {
        if (rootDir == null) {
            return null;
        }
        synchronized (WRITE_LOCK) {
            String existing = of(rootDir);
            if (existing != null) {
                return existing;
            }
            Path dir = rootDir.toPath();
            if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
                return null;
            }
            Path marker = dir.resolve(MARKER_FILE);
            Path temp = null;
            try {
                // Atomic: a temporary file in the same directory, moved over the marker.
                temp = Files.createTempFile(dir, MARKER_FILE, ".tmp");
                // #34: forced before the rename, and the directory after it.
                DurableFiles.writeForced(temp, (UUID.randomUUID() + "\n").getBytes(StandardCharsets.US_ASCII));
                Files.move(temp, marker, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                temp = null;
                DurableFiles.forceDirectory(dir);
            } catch (IOException | RuntimeException e) {
                LOGGER.log(Level.WARNING, e, () -> "Could not write the activation marker " + marker);
                return null;
            } finally {
                if (temp != null) {
                    try {
                        Files.deleteIfExists(temp);
                    } catch (IOException | RuntimeException e) {
                        LOGGER.log(Level.FINE, "Could not delete " + temp, e);
                    }
                }
            }
            CACHE.remove(marker);
            return of(rootDir);
        }
    }

    /** The identity held by {@code marker}, or {@code null} when it cannot be read or holds no id. */
    @CheckForNull
    private static String read(Path marker) {
        try (SeekableByteChannel channel = Files.newByteChannel(marker,
                Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
            ByteBuffer buffer = ByteBuffer.allocate(MAX_BYTES + 1);
            while (buffer.hasRemaining() && channel.read(buffer) >= 0) {
                // keep reading until full or end of file
            }
            if (!buffer.hasRemaining()) {
                return null; // longer than any marker
            }
            String text = new String(buffer.array(), 0, buffer.position(), StandardCharsets.US_ASCII).trim();
            return ID.matcher(text).matches() ? PREFIX + text : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
