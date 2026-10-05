package io.jenkins.plugins.batchcontrol;

import com.michelin.cio.hudson.plugins.rolestrategy.RoleBasedAuthorizationStrategy;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.security.AuthorizationMatrixProperty;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.GrantAction;
import io.jenkins.plugins.batchcontrol.security.BatchControlMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.security.BatchControlRoleBasedAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Set;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.jenkinsci.plugins.matrixauth.inheritance.InheritParentStrategy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.jvnet.hudson.test.junit.jupiter.JenkinsSessionExtension;

import static io.jenkins.plugins.batchcontrol.StrategyFixtures.T0;
import static io.jenkins.plugins.batchcontrol.StrategyFixtures.has;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC item 2, D-35a: the global configuration and the strategy round-trip through config.xml
 * and a restart. Matrix rows T-02-18 (matrix-auth subclass, PoC-5 row 7) and T-02-19
 * (role-strategy subclass, PoC-5 row 7). PoC-5 found that without the subclass's own XStream
 * converter {@code Jenkins.save()} throws, so the first session asserts the save as well.
 *
 * <p>Written from docs/SPEC.md, docs/DECISIONS.md D-35a and docs/POC-RESULTS.md PoC-5 only
 * (no src/main knowledge).
 */
public class StrategyPersistenceTest {

    @RegisterExtension
    final JenkinsSessionExtension session = new JenkinsSessionExtension();

    private Set<String> matrixBefore;
    private Set<String> rolesBefore;

    @AfterEach
    public void resetClock() {
        BatchClock.reset();
    }

    private static String configXml(Jenkins jenkins) throws Exception {
        return Files.readString(new File(jenkins.getRootDir(), "config.xml").toPath(), StandardCharsets.UTF_8);
    }

    /**
     * T-02-18: the Batch Control matrix strategy with its entries, a job's own authorization
     * property, the global configuration (change control, approvers) and an active grant all
     * survive a restart; config.xml names the subclass. Guard: bob, who holds nothing but the
     * grant, gets nothing from the job property, and carol (no grant) has no Configure.
     */
    @Test
    public void t_02_18_matrixStrategyRoundTripsThroughRestart() throws Throwable {
        session.then(r -> {
            BatchClock.setForTest(Clock.fixed(T0, ZoneOffset.UTC));
            r.jenkins.setSecurityRealm(r.createDummySecurityRealm());
            BatchControlMatrixAuthorizationStrategy s = StrategyFixtures.matrix(new BatchControlMatrixAuthorizationStrategy());
            r.jenkins.setAuthorizationStrategy(s);
            StrategyFixtures.changeControlOn();
            FreeStyleProject p = r.createFreeStyleProject("job");
            AuthorizationMatrixProperty amp = new AuthorizationMatrixProperty(new HashMap<>(), new InheritParentStrategy());
            amp.add(Item.CONFIGURE, PermissionEntry.user("alice"));
            p.addProperty(amp);
            r.createFreeStyleProject("granted");
            StrategyFixtures.grant("bob", "granted", Arrays.asList(GrantAction.CONFIGURE));
            r.jenkins.save();
            matrixBefore = StrategyFixtures.describeMatrix(s.getGrantedPermissionEntries());
            assertTrue(configXml(r.jenkins).contains(BatchControlMatrixAuthorizationStrategy.class.getName()),
                    "config.xml must name the Batch Control matrix strategy");
        });

        BatchClock.setForTest(Clock.fixed(T0.plus(Duration.ofMinutes(5)), ZoneOffset.UTC));

        session.then(r -> {
            assertSame(BatchControlMatrixAuthorizationStrategy.class, r.jenkins.getAuthorizationStrategy().getClass(),
                    "after a restart the installed strategy must still be the Batch Control matrix strategy");
            BatchControlMatrixAuthorizationStrategy s = (BatchControlMatrixAuthorizationStrategy) r.jenkins.getAuthorizationStrategy();
            assertEquals(matrixBefore, StrategyFixtures.describeMatrix(s.getGrantedPermissionEntries()), "every global entry must be kept");
            assertTrue(has(r.jenkins, "admin", Jenkins.ADMINISTER));
            FreeStyleProject p = r.jenkins.getItemByFullName("job", FreeStyleProject.class);
            assertTrue(has(p, "alice", Item.CONFIGURE), "the job's own authorization property must be effective after the restart");
            assertFalse(has(p, "bob", Item.CONFIGURE), "guard: the job property names alice only");
            FreeStyleProject granted = r.jenkins.getItemByFullName("granted", FreeStyleProject.class);
            assertTrue(has(granted, "bob", Item.CONFIGURE), "an active grant must confer after the restart");
            assertFalse(has(granted, "carol", Item.CONFIGURE), "guard: carol holds no grant");

            BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
            assertTrue(cfg.isChangeControlEnabled(), "the global configuration must survive the restart");
            assertEquals(Arrays.asList("a1"), cfg.getApprovers());
        });
    }

    /**
     * T-02-19: the Batch Control role strategy with every role (global, item, agent) and its
     * assignments survives a restart; config.xml names the subclass; item roles and an active
     * grant are effective afterwards.
     */
    @Test
    public void t_02_19_roleStrategyRoundTripsThroughRestart() throws Throwable {
        session.then(r -> {
            BatchClock.setForTest(Clock.fixed(T0, ZoneOffset.UTC));
            r.jenkins.setSecurityRealm(r.createDummySecurityRealm());
            BatchControlRoleBasedAuthorizationStrategy s =
                    new BatchControlRoleBasedAuthorizationStrategy(StrategyFixtures.roles(), Collections.emptySet());
            r.jenkins.setAuthorizationStrategy(s);
            StrategyFixtures.changeControlOn();
            r.createFreeStyleProject("team-a");
            r.createFreeStyleProject("other");
            StrategyFixtures.grant("carol", "other", Arrays.asList(GrantAction.CONFIGURE));
            r.jenkins.save();
            rolesBefore = StrategyFixtures.describeRoles(s);
            assertTrue(configXml(r.jenkins).contains(BatchControlRoleBasedAuthorizationStrategy.class.getName()),
                    "config.xml must name the Batch Control role strategy");
        });

        BatchClock.setForTest(Clock.fixed(T0.plus(Duration.ofMinutes(5)), ZoneOffset.UTC));

        session.then(r -> {
            assertSame(BatchControlRoleBasedAuthorizationStrategy.class, r.jenkins.getAuthorizationStrategy().getClass(),
                    "after a restart the installed strategy must still be the Batch Control role strategy");
            assertEquals(rolesBefore, StrategyFixtures.describeRoles((RoleBasedAuthorizationStrategy) r.jenkins.getAuthorizationStrategy()),
                    "every role and assignment must be kept");
            assertTrue(has(r.jenkins.getItemByFullName("team-a"), "bob", Item.CONFIGURE), "item roles must be effective after the restart");
            assertTrue(has(r.jenkins.getItemByFullName("other"), "carol", Item.CONFIGURE), "an active grant must confer after the restart");
            assertFalse(has(r.jenkins.getItemByFullName("other"), "bob", Item.CONFIGURE), "guard: bob has neither a role nor a grant on 'other'");
        });
    }
}
