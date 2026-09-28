package io.jenkins.plugins.batchcontrol.poc.auth;

import static io.jenkins.plugins.batchcontrol.poc.auth.Poc5Support.T0;
import static io.jenkins.plugins.batchcontrol.poc.auth.Poc5Support.has;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.michelin.cio.hudson.plugins.rolestrategy.RoleBasedAuthorizationStrategy;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.security.AuthorizationMatrixProperty;
import hudson.security.ProjectMatrixAuthorizationStrategy;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.jenkinsci.plugins.matrixauth.inheritance.InheritParentStrategy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.jvnet.hudson.test.junit.jupiter.JenkinsSessionExtension;

/**
 * PoC-5 restart and crash scenarios. A "crash" is simulated by ending the session after the
 * first durable write of a two-write operation; each write is synchronous, so the on-disk state
 * is exactly what a JVM kill at that point leaves behind. The clock is moved between sessions.
 */
class Poc5RestartTest {

    private static final Instant END = T0.plus(Duration.ofHours(1));
    private static final Instant LATER = T0.plus(Duration.ofHours(3));

    @RegisterExtension
    private final JenkinsSessionExtension sessions = new JenkinsSessionExtension();

    private final Poc5Support.SettableClock clock = new Poc5Support.SettableClock(T0);

    @Test
    void native_matrix_expiredWhileDown_removedBeforeCompletedInit() throws Throwable {
        PocNativeGrants.clock = clock;
        sessions.then(j -> {
            j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
            j.jenkins.setAuthorizationStrategy(Poc5Support.matrix(new ProjectMatrixAuthorizationStrategy()));
            FreeStyleProject p = j.createFreeStyleProject("job");
            FreeStyleProject q = j.createFreeStyleProject("job2");
            PocNativeGrants.grantMatrix("g1", "bob", p, Item.CONFIGURE, END, true, false);
            PocNativeGrants.grantMatrix("g2", "bob", q, Item.CONFIGURE, LATER, true, false);
            assertTrue(has(p, "bob", Item.CONFIGURE));
        });
        clock.set(T0.plus(Duration.ofHours(2))); // g1 expires while Jenkins is down
        sessions.then(j -> {
            // Nothing called sweep() in this session: only the boot initializer ran.
            FreeStyleProject p = j.jenkins.getItemByFullName("job", FreeStyleProject.class);
            FreeStyleProject q = j.jenkins.getItemByFullName("job2", FreeStyleProject.class);
            assertFalse(p.getConfigFile().asString().contains(":bob"), "expired entry gone from disk at boot");
            assertTrue(q.getConfigFile().asString().contains("USER:hudson.model.Item.Configure:bob"), "open window survives");
            assertEquals(1, PocNativeGrants.read().size());
            j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
            assertFalse(has(p, "bob", Item.CONFIGURE));
            assertTrue(has(q, "bob", Item.CONFIGURE));
        });
    }

    @Test
    void native_matrix_crashAfterRecordBeforeAdd_isHarmless() throws Throwable {
        PocNativeGrants.clock = clock;
        sessions.then(j -> {
            j.jenkins.setAuthorizationStrategy(Poc5Support.matrix(new ProjectMatrixAuthorizationStrategy()));
            FreeStyleProject p = j.createFreeStyleProject("job");
            PocNativeGrants.grantMatrix("g1", "bob", p, Item.CONFIGURE, END, true, true); // "crash"
            assertEquals(1, PocNativeGrants.read().size());
        });
        clock.set(T0.plus(Duration.ofHours(2)));
        sessions.then(j -> {
            FreeStyleProject p = j.jenkins.getItemByFullName("job", FreeStyleProject.class);
            assertTrue(PocNativeGrants.read().isEmpty(), "record cleaned up at boot");
            assertFalse(p.getConfigFile().asString().contains(":bob"));
        });
    }

    @Test
    void native_matrix_crashAfterAddBeforeRecord_leaksPermanently() throws Throwable {
        PocNativeGrants.clock = clock;
        sessions.then(j -> {
            j.jenkins.setAuthorizationStrategy(Poc5Support.matrix(new ProjectMatrixAuthorizationStrategy()));
            FreeStyleProject p = j.createFreeStyleProject("job");
            PocNativeGrants.grantMatrix("g1", "bob", p, Item.CONFIGURE, END, false, true); // add first, "crash"
        });
        clock.set(T0.plus(Duration.ofHours(2)));
        sessions.then(j -> {
            FreeStyleProject p = j.jenkins.getItemByFullName("job", FreeStyleProject.class);
            assertTrue(PocNativeGrants.read().isEmpty());
            assertTrue(p.getConfigFile().asString().contains("USER:hudson.model.Item.Configure:bob"),
                    "an unrecorded native entry is indistinguishable from an administrator's and stays forever");
        });
    }

    @Test
    void native_role_expiredWhileDown_removedAtBoot() throws Throwable {
        PocNativeGrants.clock = clock;
        sessions.then(j -> {
            j.jenkins.setAuthorizationStrategy(new RoleBasedAuthorizationStrategy(Poc5Support.roles()));
            j.createFreeStyleProject("other");
            PocNativeGrants.grantRole("g1", "carol", "other", Item.CONFIGURE, END);
        });
        clock.set(T0.plus(Duration.ofHours(2)));
        sessions.then(j -> {
            String cfg = new java.io.File(j.jenkins.getRootDir(), "config.xml").toString();
            assertFalse(java.nio.file.Files.readString(java.nio.file.Path.of(cfg)).contains("bc-g1"), "role gone at boot");
            assertTrue(PocNativeGrants.read().isEmpty());
        });
    }

    @Test
    void subclass_matrix_restartKeepsSubclassEntriesAndItemProperty() throws Throwable {
        sessions.then(j -> {
            j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
            j.jenkins.setAuthorizationStrategy(Poc5Support.matrix(new PocGrantMatrixStrategy()));
            FreeStyleProject p = j.createFreeStyleProject("job");
            AuthorizationMatrixProperty amp = new AuthorizationMatrixProperty(new HashMap<>(), new InheritParentStrategy());
            amp.add(Item.CONFIGURE, PermissionEntry.user("alice"));
            p.addProperty(amp);
        });
        sessions.then(j -> {
            assertSame(PocGrantMatrixStrategy.class, j.jenkins.getAuthorizationStrategy().getClass());
            j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
            assertTrue(has(j.jenkins, "admin", Jenkins.ADMINISTER));
            assertTrue(has(j.jenkins.getItemByFullName("job"), "alice", Item.CONFIGURE));
        });
    }

    @Test
    void subclass_role_restartKeepsSubclassAndRoles() throws Throwable {
        sessions.then(j -> {
            j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
            j.jenkins.setAuthorizationStrategy(new PocGrantRoleStrategy(Poc5Support.roles()));
            j.createFreeStyleProject("team-a");
        });
        sessions.then(j -> {
            assertSame(PocGrantRoleStrategy.class, j.jenkins.getAuthorizationStrategy().getClass());
            j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
            assertTrue(has(j.jenkins.getItemByFullName("team-a"), "bob", Item.CONFIGURE));
        });
    }
}
