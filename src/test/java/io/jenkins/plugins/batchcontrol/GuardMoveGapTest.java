package io.jenkins.plugins.batchcontrol;

import com.cloudbees.hudson.plugins.folder.Folder;
import com.cloudbees.hudson.plugins.folder.relocate.RelocationAction;
import hudson.model.Failure;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.ItemGroup;
import hudson.security.Permission;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.security.BatchControlMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.store.BlockedAttemptAudit;
import io.jenkins.plugins.batchcontrol.store.FileStore;
import io.jenkins.plugins.batchcontrol.store.Store;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import jenkins.model.Jenkins;
import jenkins.model.ProjectNamingStrategy;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.WebResponse;
import org.htmlunit.util.NameValuePair;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.assertClientError;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.assertSuccess;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.decideGrant;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.excerpt;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.records;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.submitGrantOk;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage lane 2, scenario L2-09 (matrix rows T-GAP-231 .. T-GAP-235, note 277): the D-59 move guard.
 *
 * <p>Basis: SPEC item 8, the D-59 line: "moving an item (folders plugin Item/Move) by a user without
 * Overall/Administer is allowed only if the user holds Item/Delete on the item and Item/Create on the
 * destination, each native or from an active grant; a CREATE grant's name restriction is matched
 * against the moved item's name, and the installed project naming strategy must accept the moved name
 * in the destination (D-59b). A refused move changes nothing, answers with a plain message naming what
 * is missing and is recorded as GRANT_VIOLATION"; LIMITATIONS 44 (the refusal suggests a window, unless
 * no window can help); DECISIONS D-59b; ARCHITECTURE section 1 (recording is independent of control:
 * a record that cannot be written does not change a refusal).
 *
 * <p>Moves go through the folders plugin's endpoint {@code POST <item>/move/move} with
 * {@code destination=/<folder>} (as in MoveChangeControlTest). Store failures are injected only through
 * {@code BlockedAttemptAudit.swapStoreForTesting} with a store whose {@code appendChangeRecord} throws.
 *
 * <p>Written from docs/SPEC.md, docs/DECISIONS.md D-59/D-59b/D-73, docs/LIMITATIONS.md and
 * docs/TEST-MATRIX.md only (no src/main knowledge).
 */
@WithJenkins
public class GuardMoveGapTest {

    private JenkinsRule j;
    private BatchControlMatrixAuthorizationStrategy strategy;
    private Folder a;
    private Folder b;
    private Store original;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        strategy = new BatchControlMatrixAuthorizationStrategy();
        strategy.add(Jenkins.ADMINISTER, PermissionEntry.user("admin"));
        for (String userId : new String[] {"u1", "u2", "a1"}) {
            strategy.add(Jenkins.READ, PermissionEntry.user(userId));
            strategy.add(Item.READ, PermissionEntry.user(userId));
        }
        strategy.add(RelocationAction.RELOCATE, PermissionEntry.user("u1"));
        strategy.add(RelocationAction.RELOCATE, PermissionEntry.user("u2"));
        strategy.add(BatchControlPermissions.REQUEST_GRANT, PermissionEntry.user("u1"));
        strategy.add(BatchControlPermissions.REQUEST_GRANT, PermissionEntry.user("u2"));
        strategy.add(BatchControlPermissions.APPROVE, PermissionEntry.user("a1"));
        j.jenkins.setAuthorizationStrategy(strategy);
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setChangeControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
        a = j.jenkins.createProject(Folder.class, "aa");
        b = j.jenkins.createProject(Folder.class, "bb");
        a.createProject(FreeStyleProject.class, "jj");
    }

    @AfterEach
    public void tearDown() {
        if (original != null) {
            BlockedAttemptAudit.swapStoreForTesting(original);
        }
    }

    /**
     * T-GAP-231 (L2-09, SPEC 8 D-59 "names what is missing", LIMITATIONS 44): u1 holds only Item/Move
     * and Read. u1 moves {@code aa/jj} into {@code bb}: refused (4xx), nothing moves, the message names
     * the missing Delete on {@code aa/jj} and the missing Create in {@code bb} and suggests a permission
     * window, and one GRANT_VIOLATION names u1. Guard: an administrator's move goes through.
     */
    @Test
    public void t_gap_231_moveOnlyUserIsToldBothMissingPermissions() throws Exception {
        WebResponse refused = move("u1", a.getItem("jj"), b);
        assertClientError(refused, "u1's move with Move only");
        String text = RenameRefusalFixtures.visible(refused.getContentAsString());
        assertTrue(text.contains("Delete") && text.contains("aa/jj"), "the message must name the missing Delete on aa/jj: "
                + excerpt(text));
        assertTrue(text.contains("Create") && text.contains("bb"), "the message must name the missing Create in bb: " + excerpt(text));
        assertTrue(text.toLowerCase(Locale.ROOT).contains("window"), "the message must suggest a permission window: " + excerpt(text));
        assertNotNull(a.getItem("jj"), "nothing may move");
        assertNull(b.getItem("jj"), "nothing may arrive in bb");
        List<ChangeRecord> violations = records(ChangeType.GRANT_VIOLATION);
        assertEquals(1, violations.size(), "the refused move is one GRANT_VIOLATION: " + violations);
        assertEquals("u1", violations.get(0).getUser(), "the GRANT_VIOLATION names u1");

        assertSuccess(move("admin", a.getItem("jj"), b), "guard: the administrator's move");
        assertNotNull(b.getItem("jj"), "guard: the administrator's move goes through");
    }

    /**
     * T-GAP-232 (L2-09, SPEC 8 D-59 with D-40): u1 holds native Item/Delete on {@code aa} and two CREATE
     * windows on {@code bb}, one restricted to another name ({@code other-*}) and one unrestricted. The
     * move of {@code aa/jj} into {@code bb} is allowed and records no violation. Guard: with only the
     * restricted window the move is refused.
     */
    @Test
    public void t_gap_232_unrestrictedWindowNextToARestrictedOneAdmitsTheMove() throws Exception {
        folderPermission(a, "u1", Item.DELETE);
        window("u1", "bb", "/other-.*/");
        assertClientError(move("u1", a.getItem("jj"), b), "guard: the restricted window alone does not admit jj");
        assertNotNull(a.getItem("jj"), "guard: nothing moved");
        int before = records(ChangeType.GRANT_VIOLATION).size();

        window("u1", "bb", null);
        assertSuccess(move("u1", a.getItem("jj"), b), "the move with an unrestricted window next to the restricted one");
        assertNotNull(b.getItem("jj"), "jj must arrive in bb");
        assertNull(a.getItem("jj"), "jj must have left aa");
        assertEquals(before, records(ChangeType.GRANT_VIOLATION).size(), "the permitted move records no violation");
    }

    /**
     * T-GAP-233 (L2-09 (F), D-59b): the installed project naming strategy throws an unexpected
     * exception when asked. u2 (native Delete on {@code aa} and Create on {@code bb}) moves {@code aa/jj}
     * into {@code bb}: refused, nothing moves, and the message says the naming strategy could not check
     * the name. Guard: with the default naming strategy the same move goes through.
     */
    @Test
    public void t_gap_233_namingStrategyFailureRefusesTheMove() throws Exception {
        folderPermission(a, "u2", Item.DELETE);
        folderPermission(b, "u2", Item.CREATE);
        j.jenkins.setProjectNamingStrategy(new ThrowingNamingStrategy());

        WebResponse refused = move("u2", a.getItem("jj"), b);
        assertClientError(refused, "u2's move while the naming strategy fails");
        String text = RenameRefusalFixtures.visible(refused.getContentAsString()).toLowerCase(Locale.ROOT);
        assertTrue(text.contains("naming"), "the message must say that the naming strategy could not check the name: " + excerpt(text));
        assertNotNull(a.getItem("jj"), "nothing may move");
        assertNull(b.getItem("jj"), "nothing may arrive in bb");

        j.jenkins.setProjectNamingStrategy(ProjectNamingStrategy.DEFAULT_NAMING_STRATEGY);
        assertSuccess(move("u2", a.getItem("jj"), b), "guard: the move with the default naming strategy");
        assertNotNull(b.getItem("jj"), "guard: jj arrives in bb");
    }

    /**
     * T-GAP-234 (L2-09, SPEC 8 D-59 "With change control off moves behave as in Jenkins" and the guard
     * is about permissions only): u2 holds native Item/Delete and Item/Create everywhere (not an
     * administrator). Crafted moves the folders plugin itself refuses: {@code destination=bb} (no leading
     * slash), folder {@code outer} into its own sub-folder {@code outer/inner}, and {@code aa/jj} into the
     * multibranch project {@code mb}. Nothing moves and no GRANT_VIOLATION is written. Guard: u2's valid
     * move of {@code aa/jj} into {@code bb} goes through, so u2's permissions suffice.
     */
    @Test
    public void t_gap_234_movesTheFoldersPluginRefusesWriteNoViolation() throws Exception {
        strategy.add(Item.DELETE, PermissionEntry.user("u2"));
        strategy.add(Item.CREATE, PermissionEntry.user("u2"));
        Folder outer = j.jenkins.createProject(Folder.class, "outer");
        outer.createProject(Folder.class, "inner");
        j.jenkins.createProject(WorkflowMultiBranchProject.class, "mb");

        moveTo("u2", a.getItem("jj"), "bb");
        moveTo("u2", outer, "/outer/inner");
        moveTo("u2", a.getItem("jj"), "/mb");
        assertNotNull(a.getItem("jj"), "aa/jj must not move");
        assertNull(b.getItem("jj"), "nothing may arrive in bb");
        assertNotNull(j.jenkins.getItem("outer"), "outer must not move into itself");
        assertTrue(records(ChangeType.GRANT_VIOLATION).isEmpty(), "moves the folders plugin refuses are not Batch Control"
                + " violations: " + records(ChangeType.GRANT_VIOLATION));

        assertSuccess(move("u2", a.getItem("jj"), b), "guard: u2's valid move");
        assertNotNull(b.getItem("jj"), "guard: jj arrives in bb");
    }

    /**
     * T-GAP-235 (L2-09 (F), ARCHITECTURE 1): the audit store's {@code appendChangeRecord} throws. A
     * refused move (u1, Move only) is still refused and nothing moves; a creation refused by a restricted
     * CREATE window is still refused and leaves no item; a rename resting on windows (Delete + Create) is
     * still refused and renames nothing. Guard: with the store working the same refusals are recorded.
     */
    @Test
    public void t_gap_235_failingAuditStoreDoesNotTurnRefusalsIntoSuccess() throws Exception {
        // the job's own property first: once the window exists, bb and everything below it is guarded (D-58b)
        FreeStyleProject renameMe = b.createProject(FreeStyleProject.class, "rn");
        itemPermission(renameMe, "u1", Item.DELETE);
        window("u1", "bb", "/ok-.*/");

        AtomicInteger failures = new AtomicInteger();
        original = BlockedAttemptAudit.swapStoreForTesting(failingAppends(FileStore.get(), failures));

        assertClientError(move("u1", a.getItem("jj"), b), "a refused move while the audit store fails");
        assertNotNull(a.getItem("jj"), "nothing may move");
        int created = createItem("u1", b, "bad-name");
        assertTrue(created >= 400 && created < 500, "a refused restricted creation while the audit store fails, got " + created);
        assertNull(b.getItem("bad-name"), "no item may be left behind");
        WebResponse renamed = ApproverFormFixtures.post(j, "u1", renameMe.getUrl() + "confirmRename",
                Collections.singletonList(new NameValuePair("newName", "ok-renamed")));
        assertClientError(renamed, "a refused rename while the audit store fails");
        assertNotNull(b.getItem("rn"), "the item keeps its name");
        assertNull(b.getItem("ok-renamed"), "nothing carries the new name");
        assertTrue(failures.get() > 0, "premise: the failing store was asked to append at least once");

        BlockedAttemptAudit.swapStoreForTesting(original);
        original = null;
        int before = records(ChangeType.GRANT_VIOLATION).size();
        assertClientError(move("u1", a.getItem("jj"), b), "guard: the refused move with a working store");
        assertTrue(records(ChangeType.GRANT_VIOLATION).size() > before, "guard: with a working store the refusal is recorded");
    }

    // ---------------------------------------------------------------- helpers

    /** A project naming strategy whose check fails with an unexpected exception. */
    public static class ThrowingNamingStrategy extends ProjectNamingStrategy {
        @Override
        public void checkName(String name) throws Failure {
            throw new IllegalStateException("test: the naming strategy is broken");
        }

        @Override
        public void checkName(String parentName, String name) throws Failure {
            throw new IllegalStateException("test: the naming strategy is broken");
        }

        @org.jvnet.hudson.test.TestExtension("t_gap_233_namingStrategyFailureRefusesTheMove")
        public static class DescriptorImpl extends ProjectNamingStrategy.ProjectNamingStrategyDescriptor {
        }
    }

    private WebResponse move(String userId, Item item, Folder destination) throws Exception {
        return moveTo(userId, item, "/" + destination.getFullName());
    }

    private WebResponse moveTo(String userId, Item item, String destination) throws Exception {
        assertNotNull(item, "fixture: the item to move must exist");
        return ApproverFormFixtures.post(j, userId, item.getUrl() + "move/move",
                Collections.singletonList(new NameValuePair("destination", destination)));
    }

    private void window(String userId, String scope, String pattern) throws Exception {
        String id = submitGrantOk(j, userId, scope, Arrays.asList("CREATE"), 30, "maintenance in " + scope, pattern, "a1");
        assertSuccess(decideGrant(j, "a1", id, "approve", "ok"), "fixture: approval by a1");
    }

    private void folderPermission(Folder folder, String userId, Permission permission) throws Exception {
        com.cloudbees.hudson.plugins.folder.properties.AuthorizationMatrixProperty property =
                folder.getProperties().get(com.cloudbees.hudson.plugins.folder.properties.AuthorizationMatrixProperty.class);
        if (property == null) {
            property = new com.cloudbees.hudson.plugins.folder.properties.AuthorizationMatrixProperty(new HashMap<Permission, Set<String>>());
            property.add(permission, PermissionEntry.user(userId));
            folder.addProperty(property);
        } else {
            property.add(permission, PermissionEntry.user(userId));
            folder.save();
        }
        assertTrue(StrategyFixtures.has(folder, userId, permission), "premise: " + userId + " holds " + permission.getId()
                + " on " + folder.getFullName());
    }

    private void itemPermission(FreeStyleProject job, String userId, Permission permission) throws Exception {
        hudson.security.AuthorizationMatrixProperty amp = new hudson.security.AuthorizationMatrixProperty(new HashMap<>(),
                new org.jenkinsci.plugins.matrixauth.inheritance.InheritParentStrategy());
        amp.add(permission, PermissionEntry.user(userId));
        job.addProperty(amp);
        assertTrue(StrategyFixtures.has(job, userId, permission), "premise: " + userId + " holds " + permission.getId());
    }

    private int createItem(String userId, ItemGroup<?> container, String name) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login(userId);
        URL url = new URL(wc.createCrumbedUrl(((Item) container).getUrl() + "createItem").toExternalForm()
                + "&name=" + URLEncoder.encode(name, StandardCharsets.UTF_8));
        WebRequest req = new WebRequest(url, HttpMethod.POST);
        req.setAdditionalHeader("Content-Type", "application/xml; charset=UTF-8");
        req.setRequestBody(WindowCreateCliGapTest.MINIMAL_JOB_XML);
        return wc.getPage(req).getWebResponse().getStatusCode();
    }

    /** A {@link Store} forwarding to {@code real}, except that every appendChangeRecord throws. */
    static Store failingAppends(Store real, AtomicInteger failures) {
        return (Store) Proxy.newProxyInstance(Store.class.getClassLoader(), new Class<?>[] {Store.class},
                (proxy, method, args) -> {
                    if ("appendChangeRecord".equals(method.getName())) {
                        failures.incrementAndGet();
                        IOException io = new IOException("test: the audit store cannot append");
                        if (Arrays.asList(method.getExceptionTypes()).contains(IOException.class)) {
                            throw io;
                        }
                        throw new UncheckedIOException(io);
                    }
                    try {
                        return method.invoke(real, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }
}
