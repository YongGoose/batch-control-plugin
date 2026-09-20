package io.jenkins.plugins.batchcontrol.poc;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.User;
import hudson.security.GlobalMatrixAuthorizationStrategy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Set;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockFolder;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.springframework.security.core.Authentication;

/**
 * Design assumption C: a delegating {@link hudson.security.AuthorizationStrategy} wrapping
 * {@link GlobalMatrixAuthorizationStrategy} can grant temporary Item permissions on top of the
 * delegate and denies them from the first check at/after the expiry instant. Expiry is tested
 * with an injected {@link Clock}; system time is never changed.
 */
@WithJenkins
class AssumptionCTemporaryGrantTest {

    /** Test clock whose instant is set explicitly. */
    private static final class SettableClock extends Clock {
        private volatile Instant instant;

        SettableClock(Instant initial) {
            this.instant = initial;
        }

        void set(Instant i) {
            this.instant = i;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @AfterEach
    void tearDown() {
        PocGrantStore.reset();
    }

    private SettableClock installDelegatingStrategy(JenkinsRule j) {
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        GlobalMatrixAuthorizationStrategy matrix = new GlobalMatrixAuthorizationStrategy();
        matrix.add(Jenkins.READ, PermissionEntry.user("alice"));
        matrix.add(Item.READ, PermissionEntry.user("alice"));
        matrix.add(Jenkins.READ, PermissionEntry.user("bob"));
        matrix.add(Item.READ, PermissionEntry.user("bob"));
        matrix.add(Item.CONFIGURE, PermissionEntry.user("bob"));
        j.jenkins.setAuthorizationStrategy(new PocDelegatingAuthorizationStrategy(matrix));
        SettableClock clock = new SettableClock(T0);
        PocGrantStore.setClock(clock);
        return clock;
    }

    private static Authentication auth(String id) {
        return User.getById(id, true).impersonate2();
    }

    @Test
    void grantConfigureThenDenyAtExpiry(JenkinsRule j) throws Exception {
        SettableClock clock = installDelegatingStrategy(j);
        FreeStyleProject job = j.createFreeStyleProject("team-job");
        Authentication alice = auth("alice");

        assertFalse(job.getACL().hasPermission2(alice, Item.CONFIGURE), "no grant yet: denied");

        Instant expiresAt = T0.plus(Duration.ofMinutes(30));
        PocGrantStore.addGrant("alice", "team-job", false, Set.of(Item.CONFIGURE), expiresAt);

        assertTrue(job.getACL().hasPermission2(alice, Item.CONFIGURE), "granted immediately");
        assertFalse(job.getACL().hasPermission2(alice, Item.DELETE), "only granted actions apply");

        clock.set(expiresAt.minusSeconds(1));
        assertTrue(job.getACL().hasPermission2(alice, Item.CONFIGURE), "still valid just before expiry");

        clock.set(expiresAt);
        assertFalse(job.getACL().hasPermission2(alice, Item.CONFIGURE),
                "denied from the first check at the expiry instant");

        clock.set(expiresAt.plus(Duration.ofMinutes(1)));
        assertFalse(job.getACL().hasPermission2(alice, Item.CONFIGURE), "denied after expiry");
    }

    @Test
    void delegateBehaviorIsPreserved(JenkinsRule j) throws Exception {
        installDelegatingStrategy(j);
        FreeStyleProject job = j.createFreeStyleProject("team-job");

        Authentication bob = auth("bob");
        Authentication alice = auth("alice");

        // permissions coming from the wrapped Matrix strategy still work with no grants at all
        assertTrue(job.getACL().hasPermission2(bob, Item.CONFIGURE), "delegate-granted permission preserved");
        assertTrue(job.getACL().hasPermission2(alice, Item.READ), "delegate READ preserved");
        assertFalse(job.getACL().hasPermission2(alice, Item.CONFIGURE), "delegate denial preserved");
        assertFalse(job.getACL().hasPermission2(alice, Jenkins.ADMINISTER), "root-level denial preserved");
    }

    @Test
    void jobScopeGrantIsExactMatchOnly(JenkinsRule j) throws Exception {
        SettableClock clock = installDelegatingStrategy(j);
        FreeStyleProject job = j.createFreeStyleProject("team-job");
        FreeStyleProject other = j.createFreeStyleProject("team-job2");
        Authentication alice = auth("alice");

        PocGrantStore.addGrant("alice", "team-job", false, Set.of(Item.CONFIGURE),
                clock.instant().plus(Duration.ofMinutes(30)));

        assertTrue(job.getACL().hasPermission2(alice, Item.CONFIGURE));
        assertFalse(other.getACL().hasPermission2(alice, Item.CONFIGURE),
                "job-scope grant must not leak to a prefix-named sibling");
    }

    @Test
    void folderScopeGrantRespectsPathBoundary(JenkinsRule j) throws Exception {
        SettableClock clock = installDelegatingStrategy(j);
        MockFolder team = j.createFolder("team");
        MockFolder batch = team.createProject(MockFolder.class, "batch");
        FreeStyleProject inner = batch.createProject(FreeStyleProject.class, "jobA"); // team/batch/jobA
        FreeStyleProject sibling = team.createProject(FreeStyleProject.class, "batch-other"); // team/batch-other
        Authentication alice = auth("alice");

        PocGrantStore.addGrant("alice", "team/batch", true, Set.of(Item.CONFIGURE, Item.CREATE),
                clock.instant().plus(Duration.ofMinutes(30)));

        assertTrue(inner.getACL().hasPermission2(alice, Item.CONFIGURE),
                "grant on folder team/batch must cover team/batch/jobA");
        assertFalse(sibling.getACL().hasPermission2(alice, Item.CONFIGURE),
                "grant on folder team/batch must NOT cover team/batch-other (boundary check)");

        // CREATE is checked on the folder's own ACL
        assertTrue(batch.getACL().hasPermission2(alice, Item.CREATE),
                "CREATE granted on the scoped folder itself");
        assertFalse(team.getACL().hasPermission2(alice, Item.CREATE),
                "CREATE must not apply to the parent folder");
    }
}
