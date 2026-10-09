package io.jenkins.plugins.batchcontrol;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;

/**
 * Fixtures the platform may not be able to build (matrix note 291).
 *
 * <p>The storage-fault rows simulate a store that cannot be read or written by taking permissions
 * away from a file or directory, with {@link java.io.File#setReadable(boolean, boolean)} /
 * {@link java.io.File#setWritable(boolean, boolean)} or {@link Files#setPosixFilePermissions}. Where
 * that has no effect for the owner (Windows: {@code setReadable(false)} is refused, a directory cannot
 * be made read-only and POSIX permissions are unsupported; any platform when the process runs as root)
 * the fault cannot be built, so the row is skipped there. Whether the platform can is decided by a
 * real probe, not by the OS name: a scratch directory and file are given the same treatment the
 * fixtures use, and the probe checks that reads (or writes) are then actually refused. The answer is
 * computed once per JVM.
 *
 * <p>The rows call {@link #assumeCanMakeUnreadable()} or {@link #assumeCanMakeUnwritable()} just
 * before their fixture takes a permission away; on a platform that can build the fault they run
 * exactly as before.
 */
final class PlatformFixtures {

    private static final String UNREADABLE_MESSAGE = "the platform cannot make a file or directory unreadable to its owner"
            + " (Windows, or a process running as root), so this storage fault cannot be built here (TEST-MATRIX note 291)";
    private static final String UNWRITABLE_MESSAGE = "the platform cannot make a file or directory unwritable to its owner"
            + " (Windows, or a process running as root), so this storage fault cannot be built here (TEST-MATRIX note 291)";

    private PlatformFixtures() {
    }

    /** Skips the row where this process cannot be refused reads of a file or directory it owns. */
    static void assumeCanMakeUnreadable() {
        Assumptions.assumeTrue(Probes.UNREADABLE, UNREADABLE_MESSAGE);
    }

    /** Skips the row where this process cannot be refused writes to a file or directory it owns. */
    static void assumeCanMakeUnwritable() {
        Assumptions.assumeTrue(Probes.UNWRITABLE, UNWRITABLE_MESSAGE);
    }

    /** Lazily computed probe answers (only rows that build a storage fault pay for them). */
    private static final class Probes {
        static final boolean UNREADABLE = probe(false);
        static final boolean UNWRITABLE = probe(true);
    }

    /**
     * Both mechanisms the fixtures use, each on a directory and on a file: java.io.File's setters and
     * POSIX permissions ({@code ---------} for reads, {@code r-xr-xr-x} / {@code r--r--r--} for writes).
     * True only if every one of them succeeds and the access is then actually refused.
     */
    private static boolean probe(boolean writes) {
        Path root;
        try {
            root = Files.createTempDirectory("batch-control-permission-probe");
        } catch (IOException e) {
            throw new AssertionError("fixture: cannot create the permission probe directory", e);
        }
        Path ioDir = root.resolve("io-dir");
        Path ioFile = root.resolve("io-file");
        Path posixDir = root.resolve("posix-dir");
        Path posixFile = root.resolve("posix-file");
        try {
            for (Path dir : List.of(ioDir, posixDir)) {
                Files.createDirectory(dir);
            }
            for (Path file : List.of(ioFile, posixFile)) {
                Files.writeString(file, "probe", StandardCharsets.UTF_8);
            }
            if (writes) {
                if (!ioDir.toFile().setWritable(false, false) || !ioFile.toFile().setWritable(false, false)) {
                    return false;
                }
                Files.setPosixFilePermissions(posixDir, PosixFilePermissions.fromString("r-xr-xr-x"));
                Files.setPosixFilePermissions(posixFile, PosixFilePermissions.fromString("r--r--r--"));
                return writesRefused(ioDir, ioFile) && writesRefused(posixDir, posixFile);
            }
            if (!ioDir.toFile().setReadable(false, false) || !ioFile.toFile().setReadable(false, false)) {
                return false;
            }
            Files.setPosixFilePermissions(posixDir, PosixFilePermissions.fromString("---------"));
            Files.setPosixFilePermissions(posixFile, PosixFilePermissions.fromString("---------"));
            return readsRefused(ioDir, ioFile) && readsRefused(posixDir, posixFile);
        } catch (UnsupportedOperationException | IOException | SecurityException e) {
            return false; // the platform has no POSIX permissions, or refuses to change them
        } finally {
            restoreAndDelete(root, List.of(ioDir, ioFile, posixDir, posixFile));
        }
    }

    private static boolean readsRefused(Path dir, Path file) {
        if (Files.isReadable(dir) || Files.isReadable(file)) {
            return false;
        }
        try {
            Files.newDirectoryStream(dir).close();
            return false;
        } catch (IOException expected) {
            // refused, as the fixture needs
        }
        try {
            Files.readAllBytes(file);
            return false;
        } catch (IOException expected) {
            return true;
        }
    }

    private static boolean writesRefused(Path dir, Path file) {
        if (Files.isWritable(dir) || Files.isWritable(file)) {
            return false;
        }
        try {
            Files.createFile(dir.resolve("created"));
            return false;
        } catch (IOException expected) {
            // refused, as the fixture needs
        }
        try {
            Files.writeString(file, "more", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
            return false;
        } catch (IOException expected) {
            return true;
        }
    }

    private static void restoreAndDelete(Path root, List<Path> probed) {
        for (Path p : probed) {
            p.toFile().setReadable(true, false);
            p.toFile().setWritable(true, false);
            p.toFile().setExecutable(true, false);
        }
        try (Stream<Path> tree = Files.walk(root)) {
            for (Path p : tree.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        } catch (IOException | RuntimeException e) {
            // a left-over scratch directory in the temp dir does not affect any row
        }
    }
}
