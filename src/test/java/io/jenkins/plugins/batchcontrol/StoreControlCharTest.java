package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleProject;
import hudson.model.Item;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.GrantAction;
import io.jenkins.plugins.batchcontrol.model.GrantRequest;
import io.jenkins.plugins.batchcontrol.model.GrantScope;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import io.jenkins.plugins.batchcontrol.policy.ActivationService;
import io.jenkins.plugins.batchcontrol.policy.GrantRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.security.GrantService;
import io.jenkins.plugins.batchcontrol.store.FileStore;
import io.jenkins.plugins.batchcontrol.store.StoreWriteException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import jenkins.model.Jenkins;
import org.htmlunit.WebResponse;
import org.htmlunit.util.NameValuePair;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.ActivationFixtures.activationIds;
import static io.jenkins.plugins.batchcontrol.ActivationFixtures.decideActivation;
import static io.jenkins.plugins.batchcontrol.ActivationFixtures.submitActivation;
import static io.jenkins.plugins.batchcontrol.ActivationFixtures.submitActivationOk;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.assertSuccess;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.decideGrant;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.decideRun;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.excerpt;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.grantRequestIds;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.post;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.submitGrant;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.submitGrantOk;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.submitRunOk;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bug hunt A R1-01 (TEST-MATRIX note 300), the user-text half and the store's last line of defence: every
 * free-text field that reaches a store entity (grant request reason, activation request reason, decision
 * comments) refuses a value holding a character XML 1.0 cannot store with a 4xx and a plain message;
 * nothing is stored and the existing entity stays readable. The revoke control takes no user text on main
 * (its reason is the plugin's, D-63); T-08-199 pins that it stores none. The store itself refuses
 * ({@link StoreWriteException}) to save an entity holding such a character, so no file is ever written that
 * cannot be read back. Matrix rows T-04-30, T-05-144, T-06a-57, T-08-197, T-08-198 and T-08-199; the
 * incident rows are {@link IncidentControlCharTest}.
 *
 * <p>Basis: SPEC 4 (the store), SPEC 5 (every stored value contains only characters XML can store; a
 * failed save leaves nothing behind), SPEC 3/5/6a/8 (the request, decision and revoke forms),
 * ARCHITECTURE 5 ({@code requests/run}, {@code requests/grant}, {@code grants}, {@code activation-requests},
 * XStream). The character used on the forms is U+000B (vertical tab), the one of the reproduction.
 *
 * <p>Written from docs/SPEC.md and docs/ARCHITECTURE.md section 5 only (no src/main knowledge).
 */
@WithJenkins
public class StoreControlCharTest {

    static final String VT = "\u000B";
    private static final Pattern WHY = Pattern.compile("(?i)character|control|not allowed|invalid|unsupported");

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
        strategy.add(BatchControlPermissions.REQUEST, PermissionEntry.user("u1"));
        strategy.add(BatchControlPermissions.REQUEST_GRANT, PermissionEntry.user("u1"));
        strategy.add(BatchControlPermissions.APPROVE, PermissionEntry.user("a1"));
        j.jenkins.setAuthorizationStrategy(strategy);
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setChangeControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
        job = j.createFreeStyleProject("batch-x");
        setBatchControl(job, new BatchControlJobProperty(true));
    }

    /**
     * T-04-30 (P0): a grant request and a run request whose reason holds U+000B, U+001B, U+FFFE or an
     * unpaired surrogate, saved straight through the store, are refused with {@link StoreWriteException} and
     * leave no file. Guard: a reason with tab, line feed and a paired surrogate (an emoji) is saved and loads
     * back unchanged.
     */
    @Test
    @Tag("core")
    public void t_04_30_storeRefusesToWriteAnEntityItCannotReadBack() throws Exception {
        for (String bad : new String[] {VT, "\u001B", "￾", "\uD800"}) {
            GrantRequest grant = GrantRequest.create(new GrantScope(GrantScope.Type.ITEM, "batch-x"),
                    Arrays.asList(GrantAction.CONFIGURE), 30, "maintenance " + bad + " window", "u1", "a1");
            assertThrows(StoreWriteException.class, () -> FileStore.get().saveGrantRequest(grant),
                    "R1-01: the store must refuse a grant request whose reason holds U+" + hex(bad));
            assertFalse(Files.exists(store().resolve("requests/grant/" + grant.getId() + ".xml")),
                    "R1-01: the refused grant request leaves no file (U+" + hex(bad) + ")");
        }
        RunRequest run = RunRequest.create("batch-x", new LinkedHashMap<>(), "nightly " + VT + " rerun", "u1", "a1");
        assertThrows(StoreWriteException.class, () -> FileStore.get().saveRunRequest(run),
                "R1-01: the store must refuse a run request whose reason holds U+000B");
        assertFalse(Files.exists(store().resolve("requests/run/" + run.getId() + ".xml")), "R1-01: the refused run request leaves no file");

        String fine = "tab\tline\nemoji 😀 end";
        GrantRequest clean = GrantRequest.create(new GrantScope(GrantScope.Type.ITEM, "batch-x"),
                Arrays.asList(GrantAction.CONFIGURE), 30, fine, "u1", "a1");
        FileStore.get().saveGrantRequest(clean);
        GrantRequest loaded = GrantRequestService.get().load(clean.getId());
        assertNotNull(loaded, "guard: a reason with tab, line feed and an emoji is stored and loads");
        assertEquals(fine, loaded.getReason(), "guard: the allowed characters round-trip unchanged");
    }

    /**
     * T-08-197 (P0): u1 submits a grant request whose reason holds U+000B on the grants form: refused with a
     * 4xx and a plain message, no grant request stored. Guard: the same request with a clean reason is stored.
     */
    @Test
    public void t_08_197_grantRequestReasonWithControlCharacterIsRefused() throws Exception {
        Set<String> before = grantRequestIds();
        WebResponse response = submitGrant(j, "u1", "batch-x", List.of("CONFIGURE"), 15, "r101-grant fix" + VT + "cron", null, "a1");
        assertRefusedPlainly(response, "the grant request reason holding U+000B");
        assertEquals(before, grantRequestIds(), "R1-01: the refused grant request stores nothing");
        assertTrue(filesContaining(store().resolve("requests/grant"), "r101-grant").isEmpty(), "R1-01: no grant request file names it");
        assertEquals(200, ApproverFormFixtures.get(j, "a1", "batch-control/grants/").getStatusCode(), "the grants page still opens");

        String id = submitGrantOk(j, "u1", "batch-x", List.of("CONFIGURE"), 15, "r101-grant fix cron", null, "a1");
        assertNotNull(GrantRequestService.get().load(id), "guard: the clean request is stored");
    }

    /**
     * T-08-198 (P0): a1 approves u1's PENDING grant request with a comment holding U+000B: refused with a 4xx
     * and a plain message; the request file is unchanged, it loads as PENDING, no window is active, and its
     * page opens. Guard: the approval with a clean comment opens the window.
     */
    @Test
    public void t_08_198_grantDecisionCommentWithControlCharacterIsRefused() throws Exception {
        String id = submitGrantOk(j, "u1", "batch-x", List.of("CONFIGURE"), 15, "maintenance", null, "a1");
        Path file = store().resolve("requests/grant/" + id + ".xml");
        String before = Files.readString(file, StandardCharsets.UTF_8);
        assertRefusedPlainly(decideGrant(j, "a1", id, "approve", "ok" + VT + "go"), "the grant approval comment holding U+000B");
        assertEquals(before, Files.readString(file, StandardCharsets.UTF_8), "R1-01: the refused decision stores nothing");
        assertEquals(RequestStatus.PENDING, GrantRequestService.get().load(id).getStatus(), "R1-01: the request stays readable and PENDING");
        assertFalse(GrantService.get().hasActiveGrant("u1", "batch-x", Item.CONFIGURE), "R1-01: no window opened");
        assertEquals(200, ApproverFormFixtures.get(j, "a1", "batch-control/grants/" + id + "/").getStatusCode(), "the request page still opens");

        assertSuccess(decideGrant(j, "a1", id, "approve", "ok go"), "guard: the approval with a clean comment");
        assertTrue(GrantService.get().hasActiveGrant("u1", "batch-x", Item.CONFIGURE), "guard: the window is open");
    }

    /**
     * T-08-199 (P1): the revoke control takes no reason from the user (a revocation's reason is set by the
     * plugin, D-63), so a {@code reason} parameter holding U+000B on the administrator's revoke POST is not
     * user text that reaches the store: the window is revoked, its stored file holds neither the parameter's
     * text nor a character reference XML 1.0 cannot read, and the ended window's page opens. Should a revoke
     * reason field ever be added, it falls under the user-text rule of T-08-197 and this row changes with it
     * (note 300).
     */
    @Test
    public void t_08_199_revokeStoresNoUserTextAndStaysReadable() throws Exception {
        String request = submitGrantOk(j, "u1", "batch-x", List.of("CONFIGURE"), 60, "maintenance", null, "a1");
        assertSuccess(decideGrant(j, "a1", request, "approve", "ok"), "fixture: approval");
        String window = WindowStateFixtures.windowId("u1", "batch-x");
        List<NameValuePair> params = new ArrayList<>();
        params.add(new NameValuePair("reason", "r101-revoke end" + VT + "now"));
        assertSuccess(post(j, "admin", "batch-control/grants/active/" + window + "/revoke", params), "the administrator's revoke");
        assertNull(WindowStateFixtures.active(window), "the window is revoked");
        String stored = WindowStateFixtures.storedGrant(j, window);
        assertFalse(stored.contains("r101-revoke"), "R1-01: a parameter that is not a field of the revoke is never stored: " + stored);
        assertFalse(Pattern.compile("&#(x0*([0-8bBcCeEfF]|1[0-9a-fA-F])|0*([0-8]|1[124-9]|2[0-9]|3[01]));").matcher(stored).find(),
                "R1-01: the stored window holds no character reference XML 1.0 cannot read: " + stored);
        assertEquals(200, ApproverFormFixtures.get(j, "admin", "batch-control/grants/" + window + "/").getStatusCode(),
                "R1-01: the ended window's page opens");
    }

    /**
     * T-06a-57 (P0): u1's ACTIVATE request whose reason holds U+000B is refused with a 4xx and a plain
     * message, nothing stored; a1's rejection of u1's clean request with a comment holding U+000B is refused
     * the same way, the request file is unchanged and it loads as PENDING; the activations page opens and
     * lists it. Guard: the clean request is stored, and its rejection with a clean comment is accepted.
     */
    @Test
    public void t_06a_57_activationTextWithControlCharacterIsRefused() throws Exception {
        Set<String> before = activationIds();
        assertRefusedPlainly(submitActivation(j, "u1", job, "ACTIVATE", "r101-activation go" + VT + "live", "a1"),
                "the activation request reason holding U+000B");
        assertEquals(before, activationIds(), "R1-01: the refused activation request stores nothing");
        assertTrue(filesContaining(store().resolve("activation-requests"), "r101-activation").isEmpty(),
                "R1-01: no activation request file names it");

        String id = submitActivationOk(j, "u1", job, "ACTIVATE", "go live", "a1");
        Path file = store().resolve("activation-requests/" + id + ".xml");
        String stored = Files.readString(file, StandardCharsets.UTF_8);
        assertRefusedPlainly(decideActivation(j, "a1", id, "reject", "not" + VT + "yet"), "the activation rejection comment holding U+000B");
        assertEquals(stored, Files.readString(file, StandardCharsets.UTF_8), "R1-01: the refused decision stores nothing");
        assertEquals(RequestStatus.PENDING, ActivationService.get().load(id).getStatus(), "R1-01: the request stays readable and PENDING");
        WebResponse inbox = ApproverFormFixtures.get(j, "a1", "batch-control/activations/");
        assertEquals(200, inbox.getStatusCode(), "the activations page opens");
        assertTrue(inbox.getContentAsString().contains(id), "R1-01: the request is still listed");

        assertSuccess(decideActivation(j, "a1", id, "reject", "not yet"), "guard: the rejection with a clean comment");
        assertEquals(RequestStatus.REJECTED, ActivationService.get().load(id).getStatus(), "guard: the request is REJECTED");
    }

    /**
     * T-05-144 (P0): a1 rejects u1's PENDING run request with a comment holding U+000B: refused with a 4xx and
     * a plain message; the request file is unchanged and it loads as PENDING; its page opens. Guard: the
     * rejection with a clean comment is accepted.
     */
    @Test
    public void t_05_144_runDecisionCommentWithControlCharacterIsRefused() throws Exception {
        String id = submitRunOk(j, "u1", job, "nightly rerun", "a1");
        Path file = store().resolve("requests/run/" + id + ".xml");
        String before = Files.readString(file, StandardCharsets.UTF_8);
        assertRefusedPlainly(decideRun(j, "a1", id, "reject", "wrong" + VT + "date"), "the run rejection comment holding U+000B");
        assertEquals(before, Files.readString(file, StandardCharsets.UTF_8), "R1-01: the refused decision stores nothing");
        assertEquals(RequestStatus.PENDING, FileStore.get().loadRunRequest(id).getStatus(), "R1-01: the request stays readable and PENDING");
        assertEquals(200, ApproverFormFixtures.get(j, "a1", "batch-control/requests/" + id + "/").getStatusCode(), "the request page still opens");

        assertSuccess(decideRun(j, "a1", id, "reject", "wrong date"), "guard: the rejection with a clean comment");
        assertEquals(RequestStatus.REJECTED, FileStore.get().loadRunRequest(id).getStatus(), "guard: the request is REJECTED");
    }

    // ---------------------------------------------------------------- helpers

    private static void assertRefusedPlainly(WebResponse response, String what) {
        int code = response.getStatusCode();
        assertTrue(code >= 400 && code < 500, "R1-01: " + what + " must be refused with a 4xx, got HTTP " + code + ": "
                + excerpt(response.getContentAsString()));
        UsabilityFixtures.assertPlainRefusal(what, response.getContentAsString(), WHY);
    }

    private Path store() {
        return j.jenkins.getRootDir().toPath().resolve("batch-control");
    }

    private static List<Path> filesContaining(Path dir, String needle) throws Exception {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<Path> out = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.filter(Files::isRegularFile).collect(Collectors.toList())) {
                if (Files.readString(f, StandardCharsets.UTF_8).contains(needle)) {
                    out.add(f);
                }
            }
        }
        return out;
    }

    private static String hex(String c) {
        return String.format("%04X", (int) c.charAt(0));
    }
}
