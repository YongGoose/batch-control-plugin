package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleProject;
import hudson.model.Item;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.Grant;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.policy.ActivationService;
import io.jenkins.plugins.batchcontrol.security.BatchControlMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.security.GrantService;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.ActivationFixtures.decideActivation;
import static io.jenkins.plugins.batchcontrol.ActivationFixtures.isActivated;
import static io.jenkins.plugins.batchcontrol.ActivationFixtures.submitActivationOk;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.assertSuccess;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.decideGrant;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.submitGrantOk;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.submitRunOk;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Issue #34 (TEST-MATRIX note 341, rows T-04-70 .. T-04-72): store writes are forced to disk. Every store write
 * that replaces a file forces the temporary file's content before the atomic rename and forces the directory
 * after it where the platform allows; every append that records a revocation or a decision is forced before the
 * operation reports success. Behaviour is otherwise unchanged.
 *
 * <p>How the force is observed: a power loss cannot be simulated in JenkinsRule, so the rows record the JDK's own
 * Flight Recorder event {@code jdk.FileForce} (threshold 0) while the operation runs. The JDK emits it for every
 * {@code FileChannel.force} call, with the path of the channel; it is not emitted for {@code FileDescriptor.sync()}
 * (checked on JDK 17 and 25). A forced temporary file is recognised black-box as a file in the target's directory
 * that was forced and no longer exists once the operation has answered (it was renamed onto the target); a
 * forced directory as a force event whose path is the directory itself; a forced append as a force event on the
 * month file {@code changes/YYYY-MM.jsonl}. Where the platform refuses to force a directory (Windows) the
 * directory assertions are skipped by a probe; the rows need no storage fault, so they also run on Windows.
 *
 * <p>Basis: SPEC 4 ("controller restart keeps pending requests and valid grants"; records are append-only),
 * SPEC 8 (a revoked window confers nothing), ARCHITECTURE 5 (atomic tmp → rename writes; {@code grants/},
 * {@code requests/grant/}, {@code requests/run/}, {@code activations/}, {@code activation-requests/},
 * {@code changes/YYYY-MM.jsonl}), issue #34 and the wave-B contract (main session, 2026-10-10).
 *
 * <p>Written from docs/SPEC.md items 4 and 8, docs/ARCHITECTURE.md section 5, issue #34 and the wave-B contract
 * only (no src/main knowledge).
 */
@WithJenkins
public class StoreForceTest {

    private static final String MONTH_FILE = "[0-9]{4}-[0-9]{2}\\.jsonl";

    private JenkinsRule j;
    private FreeStyleProject job;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        BatchControlMatrixAuthorizationStrategy strategy = new BatchControlMatrixAuthorizationStrategy();
        strategy.add(Jenkins.ADMINISTER, PermissionEntry.user("admin"));
        for (String userId : new String[] {"u1", "a1"}) {
            strategy.add(Jenkins.READ, PermissionEntry.user(userId));
            strategy.add(Item.READ, PermissionEntry.user(userId));
        }
        strategy.add(BatchControlPermissions.REQUEST_GRANT, PermissionEntry.user("u1"));
        strategy.add(BatchControlPermissions.REQUEST, PermissionEntry.user("u1"));
        strategy.add(BatchControlPermissions.APPROVE, PermissionEntry.user("a1"));
        j.jenkins.setAuthorizationStrategy(strategy);

        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setChangeControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();

        job = j.createFreeStyleProject("batch-x");
        BatchControlJobProperty property = new BatchControlJobProperty(true);
        property.setBlockTimer(false);
        property.setBlockUpstream(false);
        setBatchControl(job, property);
    }

    /**
     * T-04-70 (P0, #34): a1 approves u1's CONFIGURE window on {@code batch-x}: a temporary file in
     * {@code requests/grant/} and one in {@code grants/} are forced (each gone once the approval answered), and
     * {@code grants/} itself is forced where the platform allows. The administrator then revokes the window: the
     * GRANT_REVOKE append to {@code changes/YYYY-MM.jsonl} is forced, a temporary file in {@code grants/} is
     * forced, and {@code grants/} is forced where the platform allows. Guard: the window is active after the
     * approval and not active after the revocation.
     */
    @Test
    public void t_04_70_grantApprovalAndRevocationAreForcedToDisk() throws Exception {
        String request = submitGrantOk(j, "u1", "batch-x", List.of("CONFIGURE"), 30, "fix the nightly", null, "a1");

        List<String> approval = forcedPaths(() ->
                assertSuccess(decideGrant(j, "a1", request, "approve", "ok"), "fixture: a1's approval of the window"));
        Grant grant = activeGrantOf("u1");
        assertForcedTemporaryFile(approval, store("requests", "grant"), "the grant request's APPROVED state");
        assertForcedTemporaryFile(approval, store("grants"), "the new grant file");
        assertForcedDirectoryWhereAllowed(approval, store("grants"), "the new grant file");

        List<String> revocation = forcedPaths(() -> assertSuccess(
                ApproverFormFixtures.post(j, "admin", "batch-control/grants/active/" + grant.getId() + "/revoke", List.of()),
                "fixture: the administrator's revocation"));
        assertFalse(GrantService.get().listActive().stream().anyMatch(g -> g.getId().equals(grant.getId())),
                "guard (SPEC 8): the revoked window is no longer active");
        assertForcedMonthFile(revocation, store("changes"), "the GRANT_REVOKE record");
        assertForcedTemporaryFile(revocation, store("grants"), "the revoked grant file");
        assertForcedDirectoryWhereAllowed(revocation, store("grants"), "the revoked grant file");
    }

    /**
     * T-04-71 (P1, #34): u1's run request on {@code batch-x} forces a temporary file in {@code requests/run/}
     * (and the directory where allowed); a1's approval of u1's ACTIVATE request on {@code batch-x} forces a
     * temporary file in {@code activations/} and one in {@code activation-requests/}, the directory
     * {@code activations/} where allowed, and the ACTIVATED decision record's append to
     * {@code changes/YYYY-MM.jsonl}. Guard: the request is stored PENDING; the job is activated.
     */
    @Test
    public void t_04_71_runRequestAndActivationDecisionAreForcedToDisk() throws Exception {
        List<String> submission = forcedPaths(() -> submitRunOk(j, "u1", job, "nightly rerun", "a1"));
        assertForcedTemporaryFile(submission, store("requests", "run"), "the new run request");
        assertForcedDirectoryWhereAllowed(submission, store("requests", "run"), "the new run request");

        String activation = submitActivationOk(j, "u1", job, "ACTIVATE", "go live", "a1");
        List<String> decision = forcedPaths(() ->
                assertSuccess(decideActivation(j, "a1", activation, "approve", "ok"), "fixture: a1's approval of the ACTIVATE request"));
        assertTrue(isActivated(job), "guard: batch-x is activated");
        assertEquals(RequestStatus.APPROVED, ActivationService.get().load(activation).getStatus(), "guard: the request is APPROVED");
        assertForcedTemporaryFile(decision, store("activations"), "the new activation state");
        assertForcedTemporaryFile(decision, store("activation-requests"), "the ACTIVATE request's APPROVED state");
        assertForcedDirectoryWhereAllowed(decision, store("activations"), "the new activation state");
        assertForcedMonthFile(decision, store("changes"), "the ACTIVATED record");
    }

    /**
     * T-04-72 (P1, #34 guard, passes on main): the same operations behave as before: the window is active after
     * the approval and gone after the revocation, the run request and the ACTIVATE request read back with their
     * states, the job is activated, and no temporary file is left in {@code grants/}, {@code requests/grant/},
     * {@code requests/run/}, {@code activations/} or {@code activation-requests/} (every file there is a stored
     * {@code .xml} entity or the {@code activations/.schema} marker, ARCHITECTURE 5).
     */
    @Test
    public void t_04_72_forcedWritesBehaveAsBefore() throws Exception {
        String request = submitGrantOk(j, "u1", "batch-x", List.of("CONFIGURE"), 30, "fix the nightly", null, "a1");
        assertSuccess(decideGrant(j, "a1", request, "approve", "ok"), "a1's approval of the window");
        Grant grant = activeGrantOf("u1");
        assertSuccess(ApproverFormFixtures.post(j, "admin", "batch-control/grants/active/" + grant.getId() + "/revoke", List.of()),
                "the administrator's revocation");
        assertFalse(GrantService.get().listActive().stream().anyMatch(g -> g.getId().equals(grant.getId())),
                "the revoked window is no longer active");

        submitRunOk(j, "u1", job, "nightly rerun", "a1");
        String activation = submitActivationOk(j, "u1", job, "ACTIVATE", "go live", "a1");
        assertSuccess(decideActivation(j, "a1", activation, "approve", "ok"), "a1's approval of the ACTIVATE request");
        assertTrue(isActivated(job), "batch-x is activated");
        assertEquals(RequestStatus.APPROVED, ActivationService.get().load(activation).getStatus(), "the ACTIVATE request reads back APPROVED");

        for (Path dir : List.of(store("grants"), store("requests", "grant"), store("requests", "run"), store("activations"),
                store("activation-requests"))) {
            assertTrue(Files.isDirectory(dir), "premise: " + dir + " exists");
            List<String> stray;
            try (Stream<Path> files = Files.list(dir)) {
                stray = files.filter(Files::isRegularFile).map(p -> p.getFileName().toString())
                        .filter(n -> !n.endsWith(".xml") && !n.equals(".schema"))
                        .collect(Collectors.toList());
            }
            assertTrue(stray.isEmpty(), "no temporary file may be left in " + dir + ": " + stray);
        }
    }

    // ------------------------------------------------------------------ helpers

    @FunctionalInterface
    private interface Operation {
        void run() throws Exception;
    }

    /**
     * The paths of every {@code jdk.FileForce} event (any thread) while {@code op} runs. A scratch file forced
     * inside the same window must be seen (premise), so a missing store force is never a recorder failure.
     */
    private static List<String> forcedPaths(Operation op) throws Exception {
        assumeTrue(FlightRecorder.isAvailable(), "Java Flight Recorder is needed to observe FileChannel.force");
        Path dump = Files.createTempFile("store-force", ".jfr");
        Path control = Files.createTempFile("store-force-control", ".bin");
        try {
            try (Recording recording = new Recording()) {
                recording.enable("jdk.FileForce").withThreshold(Duration.ZERO);
                recording.start();
                try (FileChannel channel = FileChannel.open(control, StandardOpenOption.WRITE)) {
                    channel.write(ByteBuffer.wrap(new byte[] {1}));
                    channel.force(true);
                }
                op.run();
                recording.stop();
                recording.dump(dump);
            }
            List<String> out = new ArrayList<>();
            for (RecordedEvent event : RecordingFile.readAllEvents(dump)) {
                if ("jdk.FileForce".equals(event.getEventType().getName()) && event.getString("path") != null) {
                    out.add(event.getString("path"));
                }
            }
            Path realControl = control.toRealPath();
            assertTrue(out.stream().map(Path::of).anyMatch(p -> {
                try {
                    return realControl.equals(p.toRealPath());
                } catch (IOException e) {
                    return false;
                }
            }), "premise: the recorder sees a FileChannel.force made in this JVM; forced paths: " + out);
            return out;
        } finally {
            Files.deleteIfExists(dump);
            Files.deleteIfExists(control);
        }
    }

    /** The forced paths under {@code batch-control/}, and how many others (core's own forced writes) there were. */
    private String describe(List<String> forced) {
        String root = store().toAbsolutePath().normalize().toString();
        List<String> ours = forced.stream().filter(p -> Path.of(p).toAbsolutePath().normalize().toString().startsWith(root))
                .collect(Collectors.toList());
        return ours + " (plus " + (forced.size() - ours.size()) + " forced paths outside batch-control/, such as core's own writes)";
    }

    private void assertForcedTemporaryFile(List<String> forced, Path dir, String what) throws IOException {
        Path real = dir.toRealPath();
        boolean found = forced.stream().map(Path::of)
                .anyMatch(p -> real.equals(realParent(p)) && !Files.exists(p));
        assertTrue(found, "#34: writing " + what + " must force its temporary file in " + dir
                + " before the rename (a forced file there that is gone afterwards); forced store paths: " + describe(forced));
    }

    private void assertForcedMonthFile(List<String> forced, Path dir, String what) throws IOException {
        Path real = dir.toRealPath();
        boolean found = forced.stream().map(Path::of)
                .anyMatch(p -> real.equals(realParent(p)) && p.getFileName().toString().matches(MONTH_FILE));
        assertTrue(found, "#34: appending " + what + " must force " + dir + "/YYYY-MM.jsonl before the operation answers;"
                + " forced store paths: " + describe(forced));
    }

    private void assertForcedDirectoryWhereAllowed(List<String> forced, Path dir, String what) throws IOException {
        if (!platformForcesDirectories()) {
            System.out.println("T-04-70/71: this platform cannot force a directory; the directory assertion for " + dir + " is skipped");
            return;
        }
        Path real = dir.toRealPath();
        boolean found = forced.stream().map(Path::of).anyMatch(p -> {
            try {
                return Files.isDirectory(p) && real.equals(p.toRealPath());
            } catch (IOException e) {
                return false;
            }
        });
        assertTrue(found, "#34: after the rename of " + what + " the directory " + dir + " must be forced (the platform allows it);"
                + " forced store paths: " + describe(forced));
    }

    private static Path realParent(Path p) {
        Path parent = p.toAbsolutePath().normalize().getParent();
        if (parent == null) {
            return null;
        }
        try {
            return parent.toRealPath();
        } catch (IOException e) {
            return parent;
        }
    }

    /** Whether this platform lets a process force a directory (POSIX yes, Windows no). */
    private static boolean platformForcesDirectories() {
        try {
            Path probe = Files.createTempDirectory("dir-force-probe");
            try (FileChannel channel = FileChannel.open(probe, StandardOpenOption.READ)) {
                channel.force(true);
            } finally {
                Files.deleteIfExists(probe);
            }
            return true;
        } catch (IOException | UnsupportedOperationException e) {
            return false;
        }
    }

    private Path store(String... parts) {
        Path p = j.jenkins.getRootDir().toPath().resolve("batch-control");
        for (String part : parts) {
            p = p.resolve(part);
        }
        return p;
    }

    private static Grant activeGrantOf(String userId) {
        Grant grant = GrantService.get().listActive().stream().filter(g -> userId.equals(g.getUser())).findFirst().orElse(null);
        assertNotNull(grant, "fixture: " + userId + "'s approved window is active");
        return grant;
    }
}
