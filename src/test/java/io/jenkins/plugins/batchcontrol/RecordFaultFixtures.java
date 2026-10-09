package io.jenkins.plugins.batchcontrol;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Makes the change-record directory {@code $JENKINS_HOME/batch-control/changes/} (ARCHITECTURE 5:
 * {@code changes/YYYY-MM.jsonl}, {@code changes/diff/<id>.patch}) refuse every write, so no change
 * record can be appended (bug hunt A R3-01, TEST-MATRIX note 301).
 *
 * <p>The directory and every directory below it become {@code r-xr-xr-x}, every file {@code r--r--r--}.
 * The fault is proven from outside the plugin: creating a file in the directory and appending to an
 * existing month file must both be refused. Where the platform cannot refuse its owner a write
 * (Windows, root) the row is skipped ({@link PlatformFixtures#assumeCanMakeUnwritable()}, note 291).
 * {@link Fault#close()} restores the permissions it found.
 *
 * <p>Written from docs/ARCHITECTURE.md section 5 only (no src/main knowledge).
 */
final class RecordFaultFixtures {

    private RecordFaultFixtures() {
    }

    /** The restore handle of one injected fault. */
    static final class Fault implements AutoCloseable {
        private final Map<Path, Set<PosixFilePermission>> original;

        private Fault(Map<Path, Set<PosixFilePermission>> original) {
            this.original = original;
        }

        @Override
        public void close() throws IOException {
            // walk order (top down), so every directory is reachable again before its files
            for (Map.Entry<Path, Set<PosixFilePermission>> e : original.entrySet()) {
                Files.setPosixFilePermissions(e.getKey(), e.getValue());
            }
        }
    }

    /** {@code $JENKINS_HOME/batch-control/changes} of the given Jenkins home. */
    static Path changesDir(Path jenkinsHome) {
        return jenkinsHome.resolve("batch-control").resolve("changes");
    }

    /**
     * Makes {@code dir} (which must exist and hold at least one record file) and everything below it
     * unwritable, proves it, and returns the handle that undoes it.
     */
    static Fault makeUnwritable(Path dir) throws IOException {
        assertTrue(Files.isDirectory(dir), "fixture: " + dir + " must exist before it is made unwritable (ARCHITECTURE 5)");
        PlatformFixtures.assumeCanMakeUnwritable();
        List<Path> all;
        try (Stream<Path> walk = Files.walk(dir)) {
            all = walk.collect(Collectors.toList());
        }
        assertTrue(all.stream().anyMatch(p -> p.getFileName().toString().endsWith(".jsonl")),
                "fixture: " + dir + " must already hold a month file (a change record was written before the fault): " + all);
        Map<Path, Set<PosixFilePermission>> original = new LinkedHashMap<>();
        for (Path p : all) {
            try {
                original.put(p, Files.getPosixFilePermissions(p, LinkOption.NOFOLLOW_LINKS));
            } catch (UnsupportedOperationException e) {
                assumeTrue(false, "POSIX permissions are needed to make " + dir + " unwritable");
            }
        }
        Fault fault = new Fault(original);
        // files first, then directories bottom up
        List<Path> reversed = new ArrayList<>(all);
        Collections.reverse(reversed);
        for (Path p : reversed) {
            Files.setPosixFilePermissions(p, PosixFilePermissions.fromString(Files.isDirectory(p) ? "r-xr-xr-x" : "r--r--r--"));
        }
        boolean refusedCreate;
        try {
            Files.writeString(dir.resolve("probe-" + System.nanoTime() + ".jsonl"), "probe\n", StandardCharsets.UTF_8);
            refusedCreate = false;
        } catch (IOException expected) {
            refusedCreate = true;
        }
        boolean refusedAppend = true;
        for (Path p : all) {
            if (Files.isRegularFile(p) && p.getFileName().toString().endsWith(".jsonl")) {
                try {
                    Files.writeString(p, "", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
                    refusedAppend = false;
                } catch (IOException expected) {
                    // refused, as intended
                }
            }
        }
        if (!refusedCreate || !refusedAppend) {
            fault.close();
            assumeTrue(false, "the platform did not refuse writes to " + dir + " (root?), so the fault cannot be built here");
        }
        return fault;
    }
}
