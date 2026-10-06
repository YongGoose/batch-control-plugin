package io.jenkins.plugins.batchcontrol;

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.cli.CLICommandInvoker;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.model.GrantRequest;
import io.jenkins.plugins.batchcontrol.policy.GrantRequestService;
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

import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.assertClientError;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.assertSuccess;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.client;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.decideGrant;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.get;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.grantRequestIds;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.records;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.submitGrant;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.submitGrantOk;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC item 8, D-40: a CREATE change request may restrict the name of the item to be created
 * (form field {@code createNamePattern}: an exact name, or a Java regular expression written as
 * {@code /regex/}). Matrix rows T-08-37 .. T-08-46, and T-08-192/193 (D-74 (4), issue #107: the
 * restriction is read only with Create; note 273).
 *
 * <p>Every row works in the folder {@code team} with a window on that folder (D-71: scope type
 * ITEM; CREATE creates directly inside it) held by u1, who has no Create/Configure/Delete of
 * their own, under the Batch Control matrix strategy (D-35a) so
 * the grant actually confers. Run control is off, so D-31/D-34 do not add anything to the jobs.
 * The creation paths are the ones core offers: {@code createItem} with a config.xml body, the
 * New Item form ({@code mode}), a copy ({@code mode=copy}) and CLI {@code create-job}.
 *
 * <p>Written from docs/SPEC.md, docs/DECISIONS.md D-40 and D-74, issue #107 and
 * docs/TEST-MATRIX.md only (no src/main knowledge).
 */
@WithJenkins
public class CreateNamePatternTest {

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
     * T-08-37: an exact-name restriction lets the holder create exactly that item. The name
     * contains a regex metacharacter, which an exact name treats literally. No violation is
     * recorded for the permitted creation.
     */
    @Test
    public void t_08_37_exactNameRestrictionAllowsThatName() throws Exception {
        grant("nightly.report", "CREATE");

        assertTrue(createByConfigXml("u1", "nightly.report") < 400, "creating the named item must succeed");
        assertNotNull(j.jenkins.getItemByFullName("team/nightly.report"));
        assertTrue(records(ChangeType.GRANT_VIOLATION).isEmpty(), "a permitted creation records no GRANT_VIOLATION");
    }

    /**
     * T-08-38: any other name is refused with HTTP 4xx, leaves no item and is recorded as one
     * GRANT_VIOLATION per attempt naming the user and the attempted item. "Matches in full"
     * refuses a longer name; "exact" refuses a name the dot would match as a regex.
     */
    @Test
    public void t_08_38_exactNameRestrictionRefusesOtherNames() throws Exception {
        grant("nightly.report", "CREATE");

        String[] refused = {"other-report", "nightlyXreport", "nightly.report2", "my-nightly.report"};
        for (String name : refused) {
            int code = createByConfigXml("u1", name);
            assertTrue(code >= 400 && code < 500, "creating '" + name + "' must be refused with 4xx, got " + code);
            assertNull(j.jenkins.getItemByFullName("team/" + name), "no item '" + name + "' may be left behind");
        }
        List<ChangeRecord> violations = records(ChangeType.GRANT_VIOLATION);
        assertEquals(refused.length, violations.size(), "one GRANT_VIOLATION per refused attempt, got " + violations.size());
        for (String name : refused) {
            assertTrue(violations.stream().anyMatch(rec -> "u1".equals(rec.getUser()) && mentions(rec, name)),
                    "a GRANT_VIOLATION must name u1 and the attempted item '" + name + "'");
        }
    }

    /** T-08-39: a /regex/ restriction admits names that match in full and refuses the rest. */
    @Test
    public void t_08_39_regexRestrictionMatchesInFull() throws Exception {
        grant("/nightly-[a-z]+/", "CREATE");

        assertTrue(createByConfigXml("u1", "nightly-sales") < 400, "a fully matching name must be created");
        assertNotNull(j.jenkins.getItemByFullName("team/nightly-sales"));

        for (String name : new String[] {"x-nightly-sales", "nightly-sales-2", "nightly-", "daily-sales"}) {
            int code = createByConfigXml("u1", name);
            assertTrue(code >= 400 && code < 500, "'" + name + "' does not match in full and must be refused, got " + code);
            assertNull(j.jenkins.getItemByFullName("team/" + name));
        }
        assertEquals(4, records(ChangeType.GRANT_VIOLATION).size());
    }

    /**
     * T-08-40: an invalid regular expression is refused when the request is submitted; no grant
     * request is stored. The same request with a valid expression is accepted (fixture control).
     */
    @Test
    public void t_08_40_invalidRegexIsRefusedAtSubmission() throws Exception {
        Set<String> before = grantRequestIds();
        assertClientError(submitGrant(j, "u1", "team", Arrays.asList("CREATE"), 30,
                "new nightly job", "/nightly-[a-z+/", "a1"), "a request with the invalid regex /nightly-[a-z+/");
        assertClientError(submitGrant(j, "u1", "team", Arrays.asList("CREATE"), 30,
                "new nightly job", "/(unclosed/", "a1"), "a request with the invalid regex /(unclosed/");
        assertEquals(before, grantRequestIds(), "no grant request may be stored after a refused submission");

        String id = submitGrantOk(j, "u1", "team", Arrays.asList("CREATE"), 30,
                "new nightly job", "/nightly-[a-z]+/", "a1");
        assertEquals("/nightly-[a-z]+/", GrantRequestService.get().load(id).getCreateNamePattern());
    }

    /**
     * T-08-41: the restriction is stored as submitted and shown to the approver on the request
     * screen; a request without one stores none.
     */
    @Test
    public void t_08_41_restrictionIsStoredAndShownToTheApprover() throws Exception {
        String restricted = submitGrantOk(j, "u1", "team", Arrays.asList("CREATE"), 30,
                "new nightly job", "/nightly-[a-z]+/", "a1");
        GrantRequest stored = GrantRequestService.get().load(restricted);
        assertEquals("/nightly-[a-z]+/", stored.getCreateNamePattern());

        WebResponse screen = get(j, "a1", "batch-control/grants/" + restricted + "/");
        assertEquals(200, screen.getStatusCode());
        assertTrue(screen.getContentAsString().contains("/nightly-[a-z]+/"), "the approver must see the name restriction on the request screen");

        String open = submitGrantOk(j, "u1", "team", Arrays.asList("CREATE"), 30,
                "any job", "", "a1");
        String stored2 = GrantRequestService.get().load(open).getCreateNamePattern();
        assertTrue(stored2 == null || stored2.isEmpty(), "an empty field means no restriction, was '" + stored2 + "'");
        assertFalse(get(j, "a1", "batch-control/grants/" + open + "/").getContentAsString()
                .contains("/nightly-[a-z]+/"), "falsifiability: an unrestricted request's screen does not show the other request's pattern");
    }

    /**
     * T-08-42, rewritten for D-71: the restriction does not affect CONFIGURE or DELETE. With a
     * [CREATE, CONFIGURE] window on {@code team} restricted to an exact name, the holder still
     * configures the folder {@code team} itself (whose name does not match the restriction). A
     * [CREATE, CONFIGURE, DELETE] window on a folder can no longer be requested (DELETE applies
     * only to a job, D-71), and the folder's window no longer reaches {@code team/legacy-job}: the
     * row asserts that refusal, then gives u1 a second, unrestricted [CONFIGURE, DELETE] window on
     * {@code team/legacy-job}, under which u1 configures and deletes it while still holding the
     * restricted CREATE window. No violation is recorded (note 260).
     */
    @Test
    public void t_08_42_restrictionDoesNotAffectConfigureOrDelete() throws Exception {
        FreeStyleProject existing = team.createProject(FreeStyleProject.class, "legacy-job");
        existing.setDescription("base");
        team.setDescription("folder-base");
        int requestsBefore = grantRequestIds().size();
        assertClientError(submitGrant(j, "u1", "team", Arrays.asList("CREATE", "CONFIGURE", "DELETE"), 30,
                "create the nightly report job", "nightly.report", "a1"), "D-71: DELETE in a request on the folder team");
        assertEquals(requestsBefore, grantRequestIds().size(), "D-71: the refused request must not be stored");
        grant("nightly.report", "CREATE", "CONFIGURE");

        JenkinsRule.WebClient wc = client(j, "u1");
        String folderXml = team.getConfigFile().asString()
                .replace("<description>folder-base</description>", "<description>folder-changed</description>");
        assertTrue(folderXml.contains("folder-changed"), "fixture: the folder's config.xml must carry its description");
        WebRequest folderSave = new WebRequest(wc.createCrumbedUrl(team.getUrl() + "config.xml"), HttpMethod.POST);
        folderSave.setAdditionalHeader("Content-Type", "application/xml; charset=UTF-8");
        folderSave.setRequestBody(folderXml);
        assertEquals(200, wc.getPage(folderSave).getWebResponse().getStatusCode(),
                "CONFIGURE of the window's folder, whose name does not match the restriction, must still work");
        assertEquals("folder-changed", j.jenkins.getItemByFullName("team", Folder.class).getDescription());

        String xml = existing.getConfigFile().asString()
                .replace("<description>base</description>", "<description>changed</description>");
        assertEquals(403, saveConfigXml(wc, existing, xml), "D-71: the folder's window must not reach team/legacy-job");
        assertEquals("base", existing.getDescription());

        String jobWindow = submitGrantOk(j, "u1", "team/legacy-job", Arrays.asList("CONFIGURE", "DELETE"), 30,
                "retire the legacy job", null, "a1");
        assertSuccess(decideGrant(j, "a1", jobWindow, "approve", "ok"), "fixture: approval by a1");
        assertEquals(200, saveConfigXml(wc, existing, xml), "CONFIGURE of another name must still work");
        assertEquals("changed", existing.getDescription());

        wc.getPage(new WebRequest(wc.createCrumbedUrl(existing.getUrl() + "doDelete"), HttpMethod.POST));
        assertNull(j.jenkins.getItemByFullName("team/legacy-job"), "DELETE of another name must still work");
        assertTrue(records(ChangeType.GRANT_VIOLATION).isEmpty(), "configure and delete are not violations of a name restriction");
    }

    private static int saveConfigXml(JenkinsRule.WebClient wc, FreeStyleProject job, String xml) throws Exception {
        WebRequest save = new WebRequest(wc.createCrumbedUrl(job.getUrl() + "config.xml"), HttpMethod.POST);
        save.setAdditionalHeader("Content-Type", "application/xml; charset=UTF-8");
        save.setRequestBody(xml);
        return wc.getPage(save).getWebResponse().getStatusCode();
    }

    /** T-08-43: the New Item form path (name + mode) obeys the restriction the same way. */
    @Test
    public void t_08_43_newItemFormPathObeysTheRestriction() throws Exception {
        grant("/nightly-[a-z]+/", "CREATE");

        int refused = createByMode("u1", "adhoc-job");
        assertTrue(refused >= 400 && refused < 500, "the New Item form with another name must be refused with 4xx, got " + refused);
        assertNull(j.jenkins.getItemByFullName("team/adhoc-job"));
        assertEquals(1, records(ChangeType.GRANT_VIOLATION).size());

        assertTrue(createByMode("u1", "nightly-form") < 400, "the New Item form with a matching name must succeed");
        assertNotNull(j.jenkins.getItemByFullName("team/nightly-form"));
        assertEquals(1, records(ChangeType.GRANT_VIOLATION).size(), "the permitted creation adds no violation");
    }

    /**
     * T-08-44: the copy path (mode=copy) obeys the restriction the same way. A copy reads the
     * source's configuration (Item/Extended Read); before D-71 the folder window's CONFIGURE
     * covered {@code team/template-job}, since D-71 u1 gets it from a CONFIGURE window on that job
     * next to the restricted CREATE window on {@code team} (note 260).
     */
    @Test
    public void t_08_44_copyPathObeysTheRestriction() throws Exception {
        team.createProject(FreeStyleProject.class, "template-job");
        grant("/nightly-[a-z]+/", "CREATE");
        String readSource = submitGrantOk(j, "u1", "team/template-job", Arrays.asList("CONFIGURE"), 30,
                "read the template", null, "a1");
        assertSuccess(decideGrant(j, "a1", readSource, "approve", "ok"), "fixture: approval by a1");

        int refused = copy("u1", "template-job", "copied-job");
        assertTrue(refused >= 400 && refused < 500, "a copy with another name must be refused with 4xx, got " + refused);
        assertNull(j.jenkins.getItemByFullName("team/copied-job"));
        assertEquals(1, records(ChangeType.GRANT_VIOLATION).size());

        assertTrue(copy("u1", "template-job", "nightly-copy") < 400, "a copy with a matching name must succeed");
        assertNotNull(j.jenkins.getItemByFullName("team/nightly-copy"));
    }

    /** T-08-45: CLI create-job obeys the restriction too. */
    @Test
    public void t_08_45_cliCreateJobObeysTheRestriction() throws Exception {
        grant("nightly.report", "CREATE");

        CLICommandInvoker.Result refused = new CLICommandInvoker(j, "create-job").asUser("u1")
                .withStdin(new ByteArrayInputStream(MINIMAL_JOB_XML.getBytes(StandardCharsets.UTF_8)))
                .invokeWithArgs("team/cli-job");
        assertNotEquals(0, refused.returnCode(), "create-job with another name must fail");
        assertNull(j.jenkins.getItemByFullName("team/cli-job"));
        assertEquals(1, records(ChangeType.GRANT_VIOLATION).size());

        CLICommandInvoker.Result allowed = new CLICommandInvoker(j, "create-job").asUser("u1")
                .withStdin(new ByteArrayInputStream(MINIMAL_JOB_XML.getBytes(StandardCharsets.UTF_8)))
                .invokeWithArgs("team/nightly.report");
        assertEquals(0, allowed.returnCode(), "create-job with the named item must succeed: " + allowed.stderr());
        assertNotNull(j.jenkins.getItemByFullName("team/nightly.report"));
    }

    /**
     * T-08-57 (e2e-03 DEF-36): under a CREATE window on {@code team} with the exact-name restriction
     * {@code nightly.report}, u1 creates that item and then asks core's rename check
     * ({@code checkNewName}) about a different name. The answer names the restriction (SPEC 8:
     * a rename authorised by the grant must match; SPEC 6 usability line: refusals explained) and
     * is not core's "same as the current name" or a permission refusal. Note 159.
     */
    @Test
    public void t_08_57_renameCheckUnderExactRestrictionNamesTheRestriction() throws Exception {
        grant("nightly.report", "CREATE");
        assertTrue(createByConfigXml("u1", "nightly.report") < 400, "fixture: the named item must be created");
        assertNotNull(j.jenkins.getItemByFullName("team/nightly.report"));

        WebResponse check = get(j, "u1", team.getUrl() + "job/nightly.report/checkNewName?newName=other-report");
        String text = check.getContentAsString();
        assertEquals(200, check.getStatusCode(), "the rename check must answer a message, not HTTP " + check.getStatusCode()
                + ": " + UsabilityFixtures.excerpt(text));
        assertFalse(text.contains("same as the current name"), "the rename check must not answer core's misleading"
                + " message: " + UsabilityFixtures.excerpt(text));
        assertFalse(text.contains("Job/Create") || text.contains("Job/Configure"), "the rename check must name the"
                + " restriction, not a missing permission: " + UsabilityFixtures.excerpt(text));
        assertTrue(text.contains("nightly.report") && RESTRICTION_WORD.matcher(text).find(), "the rename check must name"
                + " the name restriction: " + UsabilityFixtures.excerpt(text));
    }

    /**
     * T-08-58 (e2e-03 DEF-36): CLI {@code create-job} with a non-matching name — a different name
     * and the permitted name with a trailing space — fails, creates nothing, and its message names
     * the restriction, not "missing the Job/Create permission". Note 159.
     */
    @Test
    public void t_08_58_cliCreateJobRefusalNamesTheRestriction() throws Exception {
        grant("nightly.report", "CREATE");

        for (String name : new String[] {"team/cli-job", "team/nightly.report "}) {
            CLICommandInvoker.Result refused = new CLICommandInvoker(j, "create-job").asUser("u1")
                    .withStdin(new ByteArrayInputStream(MINIMAL_JOB_XML.getBytes(StandardCharsets.UTF_8)))
                    .invokeWithArgs(name);
            String out = refused.stderr() + "\n" + refused.stdout();
            assertNotEquals(0, refused.returnCode(), "create-job '" + name + "' must fail");
            assertNull(j.jenkins.getItemByFullName(name.trim()), "no item may be created for '" + name + "'");
            assertFalse(out.contains("Job/Create"), "create-job '" + name + "': the refusal must name the restriction, not"
                    + " a missing Job/Create permission: " + out);
            assertTrue(out.contains("nightly.report") && RESTRICTION_WORD.matcher(out).find(), "create-job '" + name
                    + "': the refusal must name the name restriction: " + out);
        }
        assertNull(team.getItem("cli-job"));
    }

    private static final java.util.regex.Pattern RESTRICTION_WORD =
            java.util.regex.Pattern.compile("(?i)restrict|only|allowed|permitted|match");

    /** T-08-46: without a restriction the grant behaves as before: any name in the scope, no violation. */
    @Test
    public void t_08_46_noRestrictionKeepsTodaysBehaviour() throws Exception {
        String id = grant(null, "CREATE");
        assertNull(GrantRequestService.get().load(id).getCreateNamePattern());

        assertTrue(createByConfigXml("u1", "first-job") < 400);
        assertTrue(createByMode("u1", "second-job") < 400);
        assertNotNull(j.jenkins.getItemByFullName("team/first-job"));
        assertNotNull(j.jenkins.getItemByFullName("team/second-job"));
        assertTrue(records(ChangeType.GRANT_VIOLATION).isEmpty());

        // the scope still bounds the grant (fixture control, T-08-11)
        int outside = createAtRoot("u1", "root-job");
        assertTrue(outside >= 400, "outside the scope creation stays refused, got " + outside);
        assertNull(j.jenkins.getItemByFullName("root-job"));
    }

    /**
     * T-08-192 (SPEC 8 D-40 line: "A CREATE request may carry an optional name restriction (form
     * field {@code createNamePattern})"; D-74 (4) and issue #107: the field belongs to the Create
     * action and is shown only while Create is ticked; note 273). (a) u1 submits a [CONFIGURE]
     * request on the folder {@code team} through the form contract with {@code createNamePattern} =
     * {@code nightly.report}, as a browser does when the hidden field still holds text: it is
     * accepted, one request is stored with no restriction, a1's request screen does not show the
     * pattern, and once approved the window configures {@code team} (config.xml 200) and confers no
     * Create; no GRANT_VIOLATION. (b) The same pattern on a [CREATE] request is stored as submitted,
     * shown to a1, and enforced: {@code other-report} is refused with 4xx, leaves no item and adds one
     * GRANT_VIOLATION naming u1; {@code nightly.report} is created.
     */
    @Test
    public void t_08_192_nameRestrictionWithoutCreateIsIgnoredAndWithCreateIsStoredAndEnforced() throws Exception {
        team.setDescription("folder-base");
        Set<String> before = grantRequestIds();
        String configure = submitGrantOk(j, "u1", "team", Arrays.asList("CONFIGURE"), 30,
                "tidy the folder settings", "nightly.report", "a1");
        assertEquals(before.size() + 1, grantRequestIds().size(), "exactly one request is stored for the submission without Create");
        String ignored = GrantRequestService.get().load(configure).getCreateNamePattern();
        assertTrue(ignored == null || ignored.isEmpty(), "#107: a name restriction submitted without Create must not be stored, was '"
                + ignored + "'");
        WebResponse configureScreen = get(j, "a1", "batch-control/grants/" + configure + "/");
        assertEquals(200, configureScreen.getStatusCode(), "fixture: a1 opens the request screen");
        assertFalse(configureScreen.getContentAsString().contains("nightly.report"),
                "#107: the request screen of a request without Create must not show the ignored restriction");
        assertSuccess(decideGrant(j, "a1", configure, "approve", "ok"), "approval of the CONFIGURE request by a1");

        JenkinsRule.WebClient wc = client(j, "u1");
        String folderXml = team.getConfigFile().asString()
                .replace("<description>folder-base</description>", "<description>folder-changed</description>");
        assertTrue(folderXml.contains("folder-changed"), "fixture: the folder's config.xml must carry its description");
        WebRequest folderSave = new WebRequest(wc.createCrumbedUrl(team.getUrl() + "config.xml"), HttpMethod.POST);
        folderSave.setAdditionalHeader("Content-Type", "application/xml; charset=UTF-8");
        folderSave.setRequestBody(folderXml);
        assertEquals(200, wc.getPage(folderSave).getWebResponse().getStatusCode(), "the CONFIGURE window configures team");
        assertEquals("folder-changed", j.jenkins.getItemByFullName("team", Folder.class).getDescription());
        assertFalse(team.getACL().hasPermission2(hudson.model.User.getById("u1", true).impersonate2(), Item.CREATE),
                "the ignored restriction must not have turned the CONFIGURE request into a Create window");
        assertTrue(records(ChangeType.GRANT_VIOLATION).isEmpty(), "nothing so far is a violation");

        String create = submitGrantOk(j, "u1", "team", Arrays.asList("CREATE"), 30,
                "create the nightly report job", "nightly.report", "a1");
        assertEquals("nightly.report", GrantRequestService.get().load(create).getCreateNamePattern(),
                "guard: with Create the restriction is stored as submitted");
        assertTrue(get(j, "a1", "batch-control/grants/" + create + "/").getContentAsString().contains("nightly.report"),
                "guard: with Create the approver sees the restriction");
        assertSuccess(decideGrant(j, "a1", create, "approve", "ok"), "approval of the CREATE request by a1");

        int refused = createByConfigXml("u1", "other-report");
        assertTrue(refused >= 400 && refused < 500, "guard: with Create the restriction is enforced, 'other-report' must be refused with 4xx, got "
                + refused);
        assertNull(j.jenkins.getItemByFullName("team/other-report"), "no item 'other-report' may be left behind");
        List<ChangeRecord> violations = records(ChangeType.GRANT_VIOLATION);
        assertEquals(1, violations.size(), "the refused creation is one GRANT_VIOLATION, got " + violations.size());
        assertTrue("u1".equals(violations.get(0).getUser()) && mentions(violations.get(0), "other-report"),
                "the GRANT_VIOLATION names u1 and other-report");
        assertTrue(createByConfigXml("u1", "nightly.report") < 400, "guard: the restricted name is created");
        assertNotNull(j.jenkins.getItemByFullName("team/nightly.report"));
    }

    /**
     * T-08-193 (SPEC 8 D-40 line, D-74 (4), issue #107; SPEC 6 usability line: invalid input is
     * refused "with a message next to the field"; note 273): the restriction is read only with
     * Create, so text the hidden field still holds cannot refuse a request without Create. u1's
     * [CONFIGURE] request on {@code team} with the invalid regular expression {@code /(unclosed/} in
     * {@code createNamePattern} is accepted and stores no restriction. Guard (T-08-40): the same text
     * on a [CREATE] request is refused with 4xx and nothing is stored.
     */
    @Test
    public void t_08_193_invalidRestrictionWithoutCreateDoesNotRefuseTheRequest() throws Exception {
        String configure = submitGrantOk(j, "u1", "team", Arrays.asList("CONFIGURE"), 30,
                "tidy the folder settings", "/(unclosed/", "a1");
        String ignored = GrantRequestService.get().load(configure).getCreateNamePattern();
        assertTrue(ignored == null || ignored.isEmpty(), "#107: the restriction is not read without Create, so nothing is stored, was '"
                + ignored + "'");

        Set<String> before = grantRequestIds();
        assertClientError(submitGrant(j, "u1", "team", Arrays.asList("CREATE"), 30,
                "new nightly job", "/(unclosed/", "a1"), "guard: the invalid regex on a CREATE request");
        assertEquals(before, grantRequestIds(), "guard: the refused CREATE request is not stored");
    }

    // ---------------------------------------------------------------- helpers

    /** Files a grant request on the folder {@code team} as u1 with the given restriction, approves it as a1. */
    private String grant(String createNamePattern, String... actions) throws Exception {
        String id = submitGrantOk(j, "u1", "team", Arrays.asList(actions), 30,
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

    private int createAtRoot(String userId, String name) throws Exception {
        JenkinsRule.WebClient wc = client(j, userId);
        URL url = new URL(wc.createCrumbedUrl("createItem").toExternalForm()
                + "&name=" + URLEncoder.encode(name, StandardCharsets.UTF_8));
        WebRequest request = new WebRequest(url, HttpMethod.POST);
        request.setAdditionalHeader("Content-Type", "application/xml; charset=UTF-8");
        request.setRequestBody(MINIMAL_JOB_XML);
        return wc.getPage(request).getWebResponse().getStatusCode();
    }

    private int createByMode(String userId, String name) throws Exception {
        List<NameValuePair> params = new ArrayList<>();
        params.add(new NameValuePair("name", name));
        params.add(new NameValuePair("mode", "hudson.model.FreeStyleProject"));
        return ApproverFormFixtures.post(j, userId, team.getUrl() + "createItem", params).getStatusCode();
    }

    private int copy(String userId, String from, String name) throws Exception {
        List<NameValuePair> params = new ArrayList<>();
        params.add(new NameValuePair("name", name));
        params.add(new NameValuePair("mode", "copy"));
        params.add(new NameValuePair("from", from));
        return ApproverFormFixtures.post(j, userId, team.getUrl() + "createItem", params).getStatusCode();
    }

    private static boolean mentions(ChangeRecord rec, String name) {
        return (rec.getTarget() != null && rec.getTarget().contains(name))
                || (rec.getDetail() != null && rec.getDetail().contains(name));
    }
}
