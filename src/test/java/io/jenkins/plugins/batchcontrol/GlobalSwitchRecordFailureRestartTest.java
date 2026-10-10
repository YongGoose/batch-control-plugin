package io.jenkins.plugins.batchcontrol;

import hudson.model.Item;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.Grant;
import io.jenkins.plugins.batchcontrol.model.GrantAction;
import io.jenkins.plugins.batchcontrol.model.GrantRequest;
import io.jenkins.plugins.batchcontrol.model.GrantScope;
import io.jenkins.plugins.batchcontrol.policy.GrantRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.security.GrantService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.JenkinsSessionExtension;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bug hunt A R3-01 (TEST-MATRIX note 301), the boot path: a JCasC apply at startup that turns change
 * control off while {@code batch-control/changes/} refuses writes does not stop Jenkins from starting,
 * and the switch change completes (off in memory and in the saved configuration, the open window
 * revoked). Matrix row T-01-19. The form and direct-setter rows are {@link GlobalSwitchRecordFailureTest}.
 *
 * <p>Basis: SPEC 1 (D-42: a direct setter call, as JCasC makes, never throws; it applies the value),
 * SPEC 2 (the global configuration round-trips through JCasC with the symbol {@code batchControl}),
 * P-15 (change control off revokes the open windows), ARCHITECTURE 5. The YAML reaches JCasC through
 * the system property {@code casc.jenkins.config}, set only for the second session, as in
 * {@link SnapshotRefreshCascStartupGapTest}.
 *
 * <p>Written from docs/SPEC.md, docs/DECISIONS.md and docs/ARCHITECTURE.md only (no src/main knowledge).
 */
@Tag("core")
public class GlobalSwitchRecordFailureRestartTest {

    static final String CASC_PROPERTY = "casc.jenkins.config";

    @RegisterExtension
    final JenkinsSessionExtension session = new JenkinsSessionExtension();

    @TempDir
    Path tmp;

    /**
     * T-01-19 (P0, D-42): session 1: change control on (saved), u1 holds an active CONFIGURE window on
     * {@code batch-x}. Between the sessions {@code batch-control/changes/} is made unwritable and JCasC is
     * given {@code unclassified: batchControl: changeControlEnabled: false}. Session 2 starts (no
     * "Failed to initialize Jenkins"); change control is off in memory and in the saved configuration; the
     * window is revoked ({@code revokedAt} stored, no longer active).
     */
    @Test
    public void t_01_19_jcascTurningChangeControlOffAtBootStartsAndRevokesWhenTheRecordCannotBeWritten() throws Throwable {
        Path yaml = tmp.resolve("batch-control-casc.yaml");
        Files.writeString(yaml, "unclassified:\n  batchControl:\n    changeControlEnabled: false\n", StandardCharsets.UTF_8);
        AtomicReference<String> grantId = new AtomicReference<>();
        AtomicReference<Path> home = new AtomicReference<>();

        session.then(r -> {
            secure(r);
            r.createFreeStyleProject("batch-x");
            BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
            cfg.setChangeControlEnabled(true);
            cfg.setApprovers(Arrays.asList("a1"));
            cfg.save();
            GrantRequest request;
            try (ACLContext ignored = as("u1")) {
                request = GrantRequestService.get().create(new GrantScope(GrantScope.Type.ITEM, "batch-x"),
                        Arrays.asList(GrantAction.CONFIGURE), 120, "scheduled maintenance", "a1");
            }
            Grant grant;
            try (ACLContext ignored = as("a1")) {
                grant = GrantRequestService.get().approve(request.getId(), "ok");
            }
            assertNotNull(grant, "fixture: the grant must have been issued");
            assertTrue(GrantService.get().hasActiveGrant("u1", "batch-x", Item.CONFIGURE), "premise: the window is active");
            grantId.set(grant.getId());
            home.set(r.jenkins.getRootDir().toPath());
        });

        String previous = System.getProperty(CASC_PROPERTY);
        RecordFaultFixtures.Fault fault = RecordFaultFixtures.makeUnwritable(RecordFaultFixtures.changesDir(home.get()));
        System.setProperty(CASC_PROPERTY, yaml.toString());
        try {
            session.then(r -> {
                assertFalse(BatchControlGlobalConfiguration.get().isChangeControlEnabled(),
                        "R3-01, D-42: JCasC turned change control off at startup although the record could not be written");
                BatchControlGlobalConfiguration.get().load();
                assertFalse(BatchControlGlobalConfiguration.get().isChangeControlEnabled(),
                        "R3-01: change control must be off in the saved configuration (reloaded from disk)");
                assertFalse(GrantService.get().hasActiveGrant("u1", "batch-x", Item.CONFIGURE),
                        "R3-01, P-15: the window must be revoked when JCasC turns change control off at startup");
                assertNull(WindowStateFixtures.active(grantId.get()), "R3-01: the window must no longer be listed as active");
                String stored = WindowStateFixtures.storedGrant(r, grantId.get());
                assertTrue(stored.contains("<revokedAt"), "R3-01: the stored window must carry revokedAt: " + stored.replaceAll("\\s+", " "));
            });
        } finally {
            if (previous == null) {
                System.clearProperty(CASC_PROPERTY);
            } else {
                System.setProperty(CASC_PROPERTY, previous);
            }
            fault.close();
        }
    }

    private static void secure(JenkinsRule r) throws Exception {
        r.jenkins.setSecurityRealm(r.createDummySecurityRealm());
        BatchControlMatrixAuthorizationStrategy strategy = new BatchControlMatrixAuthorizationStrategy();
        strategy.add(Jenkins.ADMINISTER, PermissionEntry.user("admin"));
        for (String userId : new String[] {"u1", "a1"}) {
            strategy.add(Jenkins.READ, PermissionEntry.user(userId));
            strategy.add(Item.READ, PermissionEntry.user(userId));
        }
        strategy.add(BatchControlPermissions.REQUEST_GRANT, PermissionEntry.user("u1"));
        strategy.add(BatchControlPermissions.APPROVE, PermissionEntry.user("a1"));
        r.jenkins.setAuthorizationStrategy(strategy);
        r.jenkins.save();
    }

    private static ACLContext as(String userId) {
        return ACL.as2(User.getById(userId, true).impersonate2());
    }
}
