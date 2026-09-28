package io.jenkins.plugins.batchcontrol;

import com.michelin.cio.hudson.plugins.rolestrategy.RoleBasedAuthorizationStrategy;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.security.AuthorizationMatrixProperty;
import hudson.security.AuthorizationStrategy;
import hudson.security.FullControlOnceLoggedInAuthorizationStrategy;
import hudson.security.ProjectMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.model.GrantAction;
import io.jenkins.plugins.batchcontrol.model.GrantScope;
import io.jenkins.plugins.batchcontrol.security.BatchControlMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.security.BatchControlRoleBasedAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.jenkinsci.plugins.matrixauth.inheritance.InheritParentStrategy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.StrategyFixtures.LEGACY_WRAPPER;
import static io.jenkins.plugins.batchcontrol.StrategyFixtures.T0;
import static io.jenkins.plugins.batchcontrol.StrategyFixtures.has;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC item 2, D-35a upgrade path: "a saved configuration of the withdrawn wrapper loads as the
 * matching Batch Control strategy with every entry kept". Matrix rows T-02-26 (matrix-auth
 * delegate), T-02-27 (role-strategy delegate), T-02-28 (any other delegate is unwrapped to
 * itself and the batch-control-strategy monitor says grants need a supported strategy) and
 * T-02-29 (a nested wrapper, the load-time successor of T-SEC-13).
 *
 * <p>How the legacy config.xml is produced: the wrapper class no longer exists as an API, so the
 * test never references it in Java. It installs the plain strategy, saves, and rewrites the
 * saved {@code <authorizationStrategy>} element into the legacy shape
 * {@code <authorizationStrategy class="...BatchControlAuthorizationStrategy"><delegate class="<plain>">...</delegate></authorizationStrategy>},
 * keeping the plain strategy's own serialised body byte for byte (for matrix-auth that is the
 * {@code <permission>TYPE:id:sid</permission>} list). Then {@code Jenkins.reload()} reads it.
 *
 * <p>Written from docs/SPEC.md, docs/DECISIONS.md D-35a and docs/ARCHITECTURE.md section 4 only
 * (no src/main knowledge).
 */
@WithJenkins
public class StrategyUpgradeTest {

    private static final Pattern STRATEGY_ELEMENT = Pattern.compile(
            "<authorizationStrategy class=\"([^\"]+)\"([^>]*?)(?:/>|>(.*?)</authorizationStrategy>)", Pattern.DOTALL);

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        BatchClock.setForTest(Clock.fixed(T0, ZoneOffset.UTC));
        StrategyFixtures.configureBuildAuthenticator(); // D-35d: isolate the strategy half of the monitor (note 53)
    }

    @AfterEach
    public void resetClock() {
        BatchClock.reset();
    }

    private Path configXml() {
        return j.jenkins.getRootDir().toPath().resolve("config.xml");
    }

    /**
     * Saves {@code plain}, rewrites it on disk as the withdrawn wrapper around it
     * ({@code depth} wrappers deep) and reloads Jenkins from disk.
     */
    private void loadAsLegacyWrapper(AuthorizationStrategy plain, int depth) throws Exception {
        j.jenkins.setAuthorizationStrategy(plain);
        j.jenkins.save();
        String xml = Files.readString(configXml(), StandardCharsets.UTF_8);
        Matcher m = STRATEGY_ELEMENT.matcher(xml);
        assertTrue(m.find(), "fixture: config.xml must carry an <authorizationStrategy> element:\n" + xml);
        assertEquals(plain.getClass().getName(), m.group(1), "fixture: the saved strategy must be the plain one");
        String body = m.group(3) == null ? "" : m.group(3);
        String element = "<delegate class=\"" + m.group(1) + "\"" + m.group(2) + ">" + body + "</delegate>";
        for (int i = 1; i < depth; i++) {
            element = "<delegate class=\"" + LEGACY_WRAPPER + "\">" + element + "</delegate>";
        }
        String legacy = xml.substring(0, m.start())
                + "<authorizationStrategy class=\"" + LEGACY_WRAPPER + "\">" + element + "</authorizationStrategy>"
                + xml.substring(m.end());
        Files.writeString(configXml(), legacy, StandardCharsets.UTF_8);
        assertTrue(Files.readString(configXml(), StandardCharsets.UTF_8).contains(LEGACY_WRAPPER),
                "fixture: config.xml must now hold the legacy wrapper");
        j.jenkins.reload();
    }

    private void assertSavedWithoutLegacyClass(Class<?> expected) throws Exception {
        j.jenkins.save();
        String saved = Files.readString(configXml(), StandardCharsets.UTF_8);
        assertFalse(saved.contains(LEGACY_WRAPPER), "after the next save config.xml must no longer name the withdrawn wrapper");
        assertTrue(saved.contains("<authorizationStrategy class=\"" + expected.getName() + "\""),
                "after the next save config.xml must name " + expected.getName());
    }

    /**
     * T-02-26: a saved wrapper around matrix-auth's ProjectMatrixAuthorizationStrategy loads as
     * BatchControlMatrixAuthorizationStrategy with every global entry kept; a job's own
     * authorization property stays effective, grants confer again, and the next save writes the
     * subclass. Guard: carol (no grant) gets no Configure.
     */
    @Test
    public void t_02_26_legacyMatrixWrapperLoadsAsMatrixSubclass() throws Exception {
        ProjectMatrixAuthorizationStrategy plain = StrategyFixtures.matrix(new ProjectMatrixAuthorizationStrategy());
        Set<String> before = StrategyFixtures.describeMatrix(plain.getGrantedPermissionEntries());
        FreeStyleProject p = j.createFreeStyleProject("job");
        AuthorizationMatrixProperty amp = new AuthorizationMatrixProperty(new HashMap<>(), new InheritParentStrategy());
        amp.add(Item.CONFIGURE, PermissionEntry.user("alice"));
        p.addProperty(amp);

        loadAsLegacyWrapper(plain, 1);

        assertSame(BatchControlMatrixAuthorizationStrategy.class, j.jenkins.getAuthorizationStrategy().getClass(),
                "a legacy wrapper around matrix-auth must load as the Batch Control matrix strategy");
        assertEquals(before, StrategyFixtures.describeMatrix(
                ((BatchControlMatrixAuthorizationStrategy) j.jenkins.getAuthorizationStrategy()).getGrantedPermissionEntries()),
                "every global entry must be kept");
        FreeStyleProject reloaded = j.jenkins.getItemByFullName("job", FreeStyleProject.class);
        assertTrue(has(reloaded, "alice", Item.CONFIGURE), "the job's own property must be effective after the upgrade");

        StrategyFixtures.changeControlOn();
        assertFalse(has(reloaded, "bob", Item.CONFIGURE), "premise: bob has no Configure before a grant");
        StrategyFixtures.grant("bob", GrantScope.Type.JOB, "job", Arrays.asList(GrantAction.CONFIGURE));
        assertTrue(has(reloaded, "bob", Item.CONFIGURE), "grants must confer after the upgrade");
        assertFalse(has(reloaded, "carol", Item.CONFIGURE), "guard: carol holds no grant");
        assertFalse(StrategyFixtures.strategyMonitor().isActivated(), "the monitor must stay quiet after a supported upgrade");

        assertSavedWithoutLegacyClass(BatchControlMatrixAuthorizationStrategy.class);
    }

    /**
     * T-02-27: a saved wrapper around role-strategy loads as
     * BatchControlRoleBasedAuthorizationStrategy with every role and assignment kept; item roles
     * and grants are effective and the next save writes the subclass.
     */
    @Test
    public void t_02_27_legacyRoleWrapperLoadsAsRoleSubclass() throws Exception {
        RoleBasedAuthorizationStrategy plain = new RoleBasedAuthorizationStrategy(StrategyFixtures.roles(), Collections.emptySet());
        Set<String> before = StrategyFixtures.describeRoles(plain);
        j.createFreeStyleProject("team-a");
        j.createFreeStyleProject("other");

        loadAsLegacyWrapper(plain, 1);

        assertSame(BatchControlRoleBasedAuthorizationStrategy.class, j.jenkins.getAuthorizationStrategy().getClass(),
                "a legacy wrapper around role-strategy must load as the Batch Control role strategy");
        assertEquals(before, StrategyFixtures.describeRoles((RoleBasedAuthorizationStrategy) j.jenkins.getAuthorizationStrategy()),
                "every role and assignment must be kept");
        assertTrue(has(j.jenkins.getItemByFullName("team-a"), "bob", Item.CONFIGURE), "item roles must be effective after the upgrade");

        StrategyFixtures.changeControlOn();
        StrategyFixtures.grant("carol", GrantScope.Type.JOB, "other", Arrays.asList(GrantAction.CONFIGURE));
        assertTrue(has(j.jenkins.getItemByFullName("other"), "carol", Item.CONFIGURE), "grants must confer after the upgrade");
        assertFalse(has(j.jenkins.getItemByFullName("other"), "bob", Item.CONFIGURE), "guard: bob holds neither a role nor a grant on 'other'");

        assertSavedWithoutLegacyClass(BatchControlRoleBasedAuthorizationStrategy.class);
    }

    /**
     * T-02-28: a saved wrapper around an unsupported strategy
     * (FullControlOnceLoggedInAuthorizationStrategy, anonymous read denied) is unwrapped to that
     * strategy with its setting kept, and with change control on the batch-control-strategy
     * monitor shows that grants need a supported strategy.
     */
    @Test
    public void t_02_28_legacyWrapperAroundOtherStrategyIsUnwrapped() throws Exception {
        FullControlOnceLoggedInAuthorizationStrategy plain = new FullControlOnceLoggedInAuthorizationStrategy();
        plain.setAllowAnonymousRead(false);

        loadAsLegacyWrapper(plain, 1);

        assertSame(FullControlOnceLoggedInAuthorizationStrategy.class, j.jenkins.getAuthorizationStrategy().getClass(),
                "a legacy wrapper around an unsupported strategy must be unwrapped to that strategy");
        assertFalse(((FullControlOnceLoggedInAuthorizationStrategy) j.jenkins.getAuthorizationStrategy()).isAllowAnonymousRead(),
                "the unwrapped strategy's own setting must be kept");
        assertFalse(j.jenkins.getACL().hasPermission2(Jenkins.ANONYMOUS2, Jenkins.READ), "anonymous read must stay denied");
        assertTrue(has(j.jenkins, "admin", Jenkins.ADMINISTER), "a logged-in user keeps full control");

        StrategyFixtures.changeControlOn();
        assertTrue(StrategyFixtures.strategyMonitor().isActivated(),
                "with change control on, the monitor must say that grants need a supported strategy");
        assertSavedWithoutLegacyClass(FullControlOnceLoggedInAuthorizationStrategy.class);
    }

    /**
     * T-02-36 (rewritten by D-35d / security-05 S-04; SPEC 8 Implementation line): a saved wrapper
     * around matrix-auth's GlobalMatrixAuthorizationStrategy is unwrapped to exactly that class
     * with every entry kept, NOT converted. A stale job property (carol Item/Configure) therefore
     * stays ineffective. With change control on, the batch-control-strategy monitor is activated,
     * offers the migration, and warns that per-item properties become effective. The next save
     * writes the global matrix, not the wrapper.
     */
    @Test
    public void t_02_36_legacyGlobalMatrixWrapperIsUnwrappedNotConverted() throws Exception {
        hudson.security.GlobalMatrixAuthorizationStrategy plain =
                StrategyFixtures.matrix(new hudson.security.GlobalMatrixAuthorizationStrategy());
        Set<String> before = StrategyFixtures.describeMatrix(plain.getGrantedPermissionEntries());
        FreeStyleProject p = j.createFreeStyleProject("job");
        AuthorizationMatrixProperty stale = new AuthorizationMatrixProperty(new HashMap<>(), new InheritParentStrategy());
        stale.add(Item.CONFIGURE, PermissionEntry.user("carol"));
        p.addProperty(stale);

        loadAsLegacyWrapper(plain, 1);

        assertSame(hudson.security.GlobalMatrixAuthorizationStrategy.class, j.jenkins.getAuthorizationStrategy().getClass(),
                "a legacy wrapper around the global matrix must be unwrapped to exactly GlobalMatrixAuthorizationStrategy");
        assertEquals(before, StrategyFixtures.describeMatrix(
                ((hudson.security.GlobalMatrixAuthorizationStrategy) j.jenkins.getAuthorizationStrategy()).getGrantedPermissionEntries()),
                "every global entry must be kept");
        FreeStyleProject reloaded = j.jenkins.getItemByFullName("job", FreeStyleProject.class);
        assertFalse(has(reloaded, "carol", Item.CONFIGURE),
                "S-04: the stale per-item property must stay ineffective until the administrator converts");
        assertTrue(has(j.jenkins, "admin", Jenkins.ADMINISTER));

        StrategyFixtures.changeControlOn();
        assertTrue(StrategyFixtures.strategyMonitor().isActivated(),
                "with change control on, the monitor must offer the conversion for the unwrapped global matrix");
        String manage = j.createWebClient().login("admin").goTo("manage/").getWebResponse().getContentAsString();
        assertTrue(manage.contains("administrativeMonitor/" + StrategyFixtures.MONITOR_ID + "/migrate"),
                "the monitor must offer the migration action");
        assertTrue(manage.toLowerCase(java.util.Locale.ROOT).contains("per-item"),
                "the monitor must warn that per-item properties become effective on conversion");
        assertSavedWithoutLegacyClass(hudson.security.GlobalMatrixAuthorizationStrategy.class);
    }

    /**
     * T-02-29 (successor of T-SEC-13 / S-11): a wrapper nested inside a wrapper around
     * matrix-auth loads as a single BatchControlMatrixAuthorizationStrategy with every entry
     * kept — no nesting survives the load.
     */
    @Test
    public void t_02_29_nestedLegacyWrapperLoadsAsSingleSubclass() throws Exception {
        ProjectMatrixAuthorizationStrategy plain = StrategyFixtures.matrix(new ProjectMatrixAuthorizationStrategy());
        Set<String> before = StrategyFixtures.describeMatrix(plain.getGrantedPermissionEntries());

        loadAsLegacyWrapper(plain, 2);

        assertSame(BatchControlMatrixAuthorizationStrategy.class, j.jenkins.getAuthorizationStrategy().getClass(),
                "a nested legacy wrapper must load as one Batch Control matrix strategy");
        assertEquals(before, StrategyFixtures.describeMatrix(
                ((BatchControlMatrixAuthorizationStrategy) j.jenkins.getAuthorizationStrategy()).getGrantedPermissionEntries()),
                "every global entry must be kept through the nesting");
        assertTrue(has(j.jenkins, "admin", Jenkins.ADMINISTER));
        assertSavedWithoutLegacyClass(BatchControlMatrixAuthorizationStrategy.class);
    }
}
