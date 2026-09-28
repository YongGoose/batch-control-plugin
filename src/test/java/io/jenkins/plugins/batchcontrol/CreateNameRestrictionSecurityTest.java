package io.jenkins.plugins.batchcontrol;

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.cli.CLICommandInvoker;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.security.BatchControlMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.security.GrantService;
import java.io.ByteArrayInputStream;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.WebResponse;
import org.htmlunit.util.NameValuePair;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.assertSuccess;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.client;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.decideGrant;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.excerpt;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.grantRequestIds;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.records;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.submitGrant;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.submitGrantOk;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Regression rows for security-08 S-01, S-02, S-03, S-05 and S-06 against the D-40 CREATE name
 * restriction, as extended by D-40a (SPEC item 8): a restriction also governs renames, the new
 * name is read only from the endpoint that performs the creation or rename, names over 255
 * characters are refused, a user-supplied pattern is matched under a time bound and never while
 * a global lock is held, a typing-time validation (GET) is refused without a record, and the
 * submitted name is matched as submitted (untrimmed). Matrix rows T-SEC-35 .. T-SEC-40.
 *
 * <p>Same set-up as {@link CreateNamePatternTest}: folder {@code team}, a FOLDER-scoped grant
 * held by u1 (no Create/Configure/Delete of their own) under the Batch Control matrix strategy
 * (D-35a), change control on, run control off.
 *
 * <p>Written from docs/SPEC.md, docs/DECISIONS.md D-40/D-40a, docs/reports/security-08.md and
 * docs/TEST-MATRIX.md only (no src/main knowledge).
 */
@WithJenkins
public class CreateNameRestrictionSecurityTest {

    private static final String MINIMAL_JOB_XML = "<?xml version='1.1' encoding='UTF-8'?>"
            + "<project><builders/><publishers/><buildWrappers/></project>";

    private JenkinsRule j;
    private Folder team;

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
        strategy.add(BatchControlPermissions.APPROVE, PermissionEntry.user("a1"));
        j.jenkins.setAuthorizationStrategy(strategy);

        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setChangeControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();

        team = j.jenkins.createProject(Folder.class, "team");
    }

    /**
     * T-SEC-35 (S-01): the holder's Configure on an item they created through a restricted Create
     * grant (D-35c) does not let them rename it to a name outside the restriction. The rename is
     * refused before anything changes and recorded as GRANT_VIOLATION; a rename to a matching
     * name still works (falsifiability twin).
     */
    @Test
    public void t_sec_35_renameOfCreatedItemMustMatchTheRestriction() throws Exception {
        grant("/nightly-[a-z]+/", "CREATE");
        assertTrue(createByConfigXml("u1", "nightly-a") < 400, "fixture: creating the matching item must succeed");
        assertNotNull(team.getItem("nightly-a"), "fixture: team/nightly-a must exist");
        assertTrue(records(ChangeType.GRANT_VIOLATION).isEmpty(), "fixture: the permitted creation records no violation");

        WebResponse refused = rename("u1", "nightly-a", "evil-name", null);
        assertClientError(refused, "renaming the created item to a non-matching name");
        assertNull(team.getItem("evil-name"), "no item may carry the non-matching name");
        assertNotNull(team.getItem("nightly-a"), "the refused rename must leave the item under its old name");
        List<ChangeRecord> violations = records(ChangeType.GRANT_VIOLATION);
        assertEquals(1, violations.size(), "the refused rename is recorded once as GRANT_VIOLATION, got " + violations);
        assertTrue("u1".equals(violations.get(0).getUser()) && mentions(violations.get(0), "evil-name"),
                "the GRANT_VIOLATION must name u1 and the attempted name, was " + describe(violations.get(0)));

        WebResponse allowed = rename("u1", "nightly-a", "nightly-b", null);
        assertSuccess(allowed, "renaming the created item to a matching name");
        assertNotNull(team.getItem("nightly-b"), "the matching rename must go through");
        assertNull(team.getItem("nightly-a"));
        assertEquals(1, records(ChangeType.GRANT_VIOLATION).size(), "the matching rename adds no violation");
    }

    /**
     * T-SEC-36 (S-02): a rename authorised by the grant's Create on the parent (core's Create +
     * Delete rename branch) must match the restriction, and the new name is read from the rename
     * endpoint's own parameter ({@code newName}); a spoofed {@code name} parameter carrying a
     * matching name does not help. A matching rename still works.
     */
    @Test
    public void t_sec_36_createAndDeleteRenameIgnoresASpoofedNameParameter() throws Exception {
        team.createProject(FreeStyleProject.class, "legacy");
        grant("/nightly-[a-z]+/", "CREATE", "DELETE");

        WebResponse refused = rename("u1", "legacy", "evil-name", "nightly-ok");
        assertClientError(refused, "a Create+Delete rename to a non-matching newName with a matching name parameter");
        assertNull(team.getItem("evil-name"), "no item may carry the non-matching name");
        assertNull(team.getItem("nightly-ok"), "the spoofed name parameter must not create or rename anything");
        assertNotNull(team.getItem("legacy"), "the refused rename must leave the item under its old name");
        List<ChangeRecord> violations = records(ChangeType.GRANT_VIOLATION);
        assertEquals(1, violations.size(), "the refused rename is recorded once as GRANT_VIOLATION, got " + violations);
        assertTrue(mentions(violations.get(0), "evil-name"),
                "the GRANT_VIOLATION must name the real new name, was " + describe(violations.get(0)));

        WebResponse allowed = rename("u1", "legacy", "nightly-ren", null);
        assertSuccess(allowed, "a Create+Delete rename to a matching name");
        assertNotNull(team.getItem("nightly-ren"), "the matching rename must go through");
        assertNull(team.getItem("legacy"));
    }

    /**
     * T-SEC-37 (S-03): a catastrophic-backtracking pattern cannot stall the instance. Either the
     * pattern is refused at submission (nothing stored), or with the grant active a REST
     * {@code createItem} and a CLI {@code create-job} with a hostile 30-character name both
     * finish in under a second and are refused, and an Item/Read check by another user made
     * while the REST call is in flight is not delayed.
     */
    @Test
    public void t_sec_37_backtrackingPatternCannotStallTheInstance() throws Exception {
        team.createProject(FreeStyleProject.class, "existing");
        String pattern = "/(a|a)*\\1b/";
        Set<String> before = grantRequestIds();
        WebResponse submission = submitGrant(j, "u1", "FOLDER", "team", Arrays.asList("CREATE"), 30,
                "create the report job", pattern, "a1");
        if (submission.getStatusCode() >= 400) {
            // Refusing the construct at submission also satisfies "no pattern can stall the instance".
            assertTrue(submission.getStatusCode() < 500, "a refused pattern is user input: 4xx, got " + submission.getStatusCode());
            assertEquals(before, grantRequestIds(), "a refused submission stores nothing");
            return;
        }
        Set<String> after = grantRequestIds();
        after.removeAll(before);
        assertEquals(1, after.size(), "fixture: one grant request stored");
        assertSuccess(decideGrant(j, "a1", after.iterator().next(), "approve", "ok"), "fixture: approval by a1");

        String hostile = "a".repeat(30);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            long start = System.nanoTime();
            Future<Integer> rest = executor.submit(() -> createByConfigXml("u1", hostile));
            Thread.sleep(100); // let the hostile request reach the matcher; coordination, not an expiry wait

            long readStart = System.nanoTime();
            boolean visible;
            try (ACLContext ignored = ACL.as2(User.getById("a1", true).impersonate2())) {
                visible = team.getItem("existing") != null && team.getItem("existing").hasPermission(Item.READ);
            }
            long readMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - readStart);
            assertTrue(visible, "fixture: a1 can read team/existing");
            assertTrue(readMs < 1000, "an Item/Read check by another user must not wait for the hostile match, took " + readMs + " ms");

            int code;
            try {
                code = rest.get(60, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                rest.cancel(true);
                fail("the REST createItem with a hostile name did not return within 60 s: the match is unbounded");
                return;
            }
            long restMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertTrue(code >= 400 && code < 500, "the hostile name does not match and must be refused with 4xx, got " + code);
            assertTrue(restMs < 1000, "the REST createItem must finish in under 1 s, took " + restMs + " ms");
            assertNull(team.getItem(hostile), "no item may be left behind");
        } finally {
            executor.shutdownNow();
        }

        long cliStart = System.nanoTime();
        CLICommandInvoker.Result cli = new CLICommandInvoker(j, "create-job").asUser("u1")
                .withStdin(new ByteArrayInputStream(MINIMAL_JOB_XML.getBytes(StandardCharsets.UTF_8)))
                .invokeWithArgs("team/" + hostile);
        long cliMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - cliStart);
        assertNotEquals(0, cli.returnCode(), "the CLI create-job with a hostile name must be refused");
        assertTrue(cliMs < 1000, "the CLI create-job must finish in under 1 s, took " + cliMs + " ms");
        assertNull(team.getItem(hostile), "no item may be left behind by the CLI");
    }

    /**
     * T-SEC-38 (S-03, D-40a): names longer than 255 characters are refused (4xx, no item) even
     * when the pattern would match them; a 255-character matching name is still created
     * (boundary twin).
     */
    @Test
    public void t_sec_38_namesOver255CharactersAreRefused() throws Exception {
        grant("/nightly-[a-z]+/", "CREATE");
        String tooLong = "nightly-" + "a".repeat(248);
        String longest = "nightly-" + "b".repeat(247);
        assertEquals(256, tooLong.length(), "fixture");
        assertEquals(255, longest.length(), "fixture");

        int code = createByConfigXml("u1", tooLong);
        assertTrue(code >= 400 && code < 500, "a 256-character name must be refused with 4xx, got " + code);
        assertNull(team.getItem(tooLong), "no item may be left behind");

        int ok = createByConfigXml("u1", longest);
        assertTrue(ok < 400, "a 255-character matching name is within the bound and must be created, got " + ok);
        assertNotNull(team.getItem(longest));
    }

    /**
     * T-SEC-39 (S-05): typing-time validation GETs ({@code checkJobName} on the New Item page,
     * {@code checkNewName} on the rename page) with non-matching prefixes are refused without a
     * GRANT_VIOLATION record. Falsifiability: a real POST creation with a non-matching name still
     * records exactly one.
     */
    @Test
    public void t_sec_39_typingTimeValidationIsRefusedWithoutARecord() throws Exception {
        team.createProject(FreeStyleProject.class, "legacy");
        grant("/nightly-[a-z]+/", "CREATE", "DELETE");

        for (String prefix : new String[] {"a", "ad", "adh", "adho", "adhoc"}) {
            WebResponse check = ApproverFormFixtures.get(j, "u1",
                    team.getUrl() + "checkJobName?value=" + URLEncoder.encode(prefix, StandardCharsets.UTF_8));
            assertNotEquals(404, check.getStatusCode(), "premise: checkJobName is served on the folder");
            assertRefusedValidation(check, "checkJobName?value=" + prefix);
        }
        for (String prefix : new String[] {"e", "ev", "evil"}) {
            WebResponse check = ApproverFormFixtures.get(j, "u1",
                    team.getUrl() + "job/legacy/checkNewName?newName=" + URLEncoder.encode(prefix, StandardCharsets.UTF_8));
            assertNotEquals(404, check.getStatusCode(), "premise: checkNewName is served on the item");
            assertRefusedValidation(check, "checkNewName?newName=" + prefix);
        }
        assertTrue(records(ChangeType.GRANT_VIOLATION).isEmpty(),
                "a validation request while typing (GET) must not be recorded, got " + records(ChangeType.GRANT_VIOLATION));
        assertNotNull(team.getItem("legacy"));

        int code = createByConfigXml("u1", "adhoc");
        assertTrue(code >= 400 && code < 500, "falsifiability: a real non-matching creation is refused, got " + code);
        assertEquals(1, records(ChangeType.GRANT_VIOLATION).size(), "falsifiability: the real attempt is recorded once");
    }

    /**
     * T-SEC-40 (S-06): the name is matched as the command submits it. CLI {@code create-job} and
     * {@code copy-job} with {@code "app-1 "} (trailing space) against the exact restriction
     * {@code app-1} are refused, leave no item and are recorded; {@code app-1} itself is created.
     */
    @Test
    public void t_sec_40_trailingSpaceNameIsNotTrimmedIntoAMatch() throws Exception {
        team.createProject(FreeStyleProject.class, "template-job");
        grant("app-1", "CREATE", "CONFIGURE");

        CLICommandInvoker.Result created = new CLICommandInvoker(j, "create-job").asUser("u1")
                .withStdin(new ByteArrayInputStream(MINIMAL_JOB_XML.getBytes(StandardCharsets.UTF_8)))
                .invokeWithArgs("team/app-1 ");
        assertNotEquals(0, created.returnCode(), "create-job 'app-1 ' must be refused: " + created.stdout());
        assertNull(team.getItem("app-1 "), "no item 'app-1 ' may be left behind");
        assertNull(team.getItem("app-1"), "the refused name must not be trimmed into the permitted one either");
        assertEquals(1, records(ChangeType.GRANT_VIOLATION).size(), "the refused create-job is recorded");

        CLICommandInvoker.Result copied = new CLICommandInvoker(j, "copy-job").asUser("u1")
                .invokeWithArgs("team/template-job", "team/app-1 ");
        assertNotEquals(0, copied.returnCode(), "copy-job to 'app-1 ' must be refused: " + copied.stdout());
        assertNull(team.getItem("app-1 "), "no copy 'app-1 ' may be left behind");
        assertNull(team.getItem("app-1"));
        assertEquals(2, records(ChangeType.GRANT_VIOLATION).size(), "the refused copy-job is recorded");

        CLICommandInvoker.Result exact = new CLICommandInvoker(j, "create-job").asUser("u1")
                .withStdin(new ByteArrayInputStream(MINIMAL_JOB_XML.getBytes(StandardCharsets.UTF_8)))
                .invokeWithArgs("team/app-1");
        assertEquals(0, exact.returnCode(), "create-job with the exact name must succeed: " + exact.stderr());
        assertNotNull(team.getItem("app-1"));
    }

    // ---------------------------------------------------------------- helpers

    private String grant(String createNamePattern, String... actions) throws Exception {
        String id = submitGrantOk(j, "u1", "FOLDER", "team", Arrays.asList(actions), 30,
                "create the nightly report job", createNamePattern, "a1");
        assertSuccess(decideGrant(j, "a1", id, "approve", "ok"), "fixture: approval by a1");
        assertTrue(GrantService.get().listActive().stream().anyMatch(g -> "u1".equals(g.getUser())),
                "fixture: u1 must hold an active grant");
        return id;
    }

    private int createByConfigXml(String userId, String name) throws Exception {
        JenkinsRule.WebClient wc = client(j, userId);
        URL url = new URL(wc.createCrumbedUrl(team.getUrl() + "createItem").toExternalForm()
                + "&name=" + URLEncoder.encode(name, StandardCharsets.UTF_8));
        WebRequest request = new WebRequest(url, HttpMethod.POST);
        request.setAdditionalHeader("Content-Type", "application/xml; charset=UTF-8");
        request.setRequestBody(MINIMAL_JOB_XML);
        return wc.getPage(request).getWebResponse().getStatusCode();
    }

    /** {@code POST job/team/job/<item>/confirmRename} with {@code newName} and an optional extra {@code name}. */
    private WebResponse rename(String userId, String item, String newName, String spoofedName) throws Exception {
        List<NameValuePair> params = new ArrayList<>();
        params.add(new NameValuePair("newName", newName));
        if (spoofedName != null) {
            params.add(new NameValuePair("name", spoofedName));
        }
        return ApproverFormFixtures.post(j, userId, team.getUrl() + "job/" + item + "/confirmRename", params);
    }

    private static void assertClientError(WebResponse response, String what) {
        ApproverFormFixtures.assertClientError(response, what);
    }

    /** A refused validation is a 4xx or a FormValidation error, never an OK. */
    private static void assertRefusedValidation(WebResponse response, String what) {
        int code = response.getStatusCode();
        assertTrue(code < 500, what + " must not fail with a server error, got " + code);
        if (code < 400) {
            String body = response.getContentAsString();
            assertTrue(body.contains("error"), what + " must be refused (4xx or a validation error), got "
                    + code + ": " + excerpt(body));
        }
    }

    private static boolean mentions(ChangeRecord rec, String name) {
        return (rec.getTarget() != null && rec.getTarget().contains(name))
                || (rec.getDetail() != null && rec.getDetail().contains(name));
    }

    private static String describe(ChangeRecord rec) {
        return rec.getType() + " user=" + rec.getUser() + " target=" + rec.getTarget() + " detail=" + rec.getDetail();
    }
}
