package io.jenkins.plugins.batchcontrol;

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.ExtensionList;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.ops.ExpiryPeriodicWork;
import io.jenkins.plugins.batchcontrol.security.BatchControlMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.security.GrantService;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import io.jenkins.plugins.batchcontrol.store.FileStore;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.htmlunit.WebResponse;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.HtmlPage;
import org.htmlunit.util.NameValuePair;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.LogRecorder;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.assertClientError;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.assertSuccess;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.decideGrant;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.submitGrant;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.submitGrantOk;
import static io.jenkins.plugins.batchcontrol.UsabilityFixtures.excerpt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Change control (SPEC 8) as its users meet it, under the SPEC 6 usability line. Matrix rows
 * T-08-49 (e2e-03 DEF-18, note 137), T-08-50 (DEF-19, note 138), T-08-51 (DEF-26, note 139) and
 * T-08-52 (DEF-27, note 139).
 *
 * <p>Strategy: the Batch Control matrix strategy (D-35a). Users: {@code u1} (Read, Item/Read,
 * RequestGrant); {@code a1} approver; {@code u2} standing Item/Configure + Item/Delete without
 * RequestGrant; {@code u3} standing Item/Delete with RequestGrant. Time is moved with
 * {@code BatchClock} (matrix note 1), never waited for.
 *
 * <p>Written from docs/SPEC.md, docs/DECISIONS.md D-33, docs/reports/e2e-03.md and
 * docs/TEST-MATRIX.md only (no src/main knowledge).
 */
@WithJenkins
public class GrantUsabilityTest {

    private static final Instant T0 = Instant.parse("2026-09-20T00:00:00Z");
    private static final String PATTERN = "/app-[0-9]+/";

    private JenkinsRule j;
    private Folder team;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        BatchControlMatrixAuthorizationStrategy strategy = new BatchControlMatrixAuthorizationStrategy();
        strategy.add(Jenkins.ADMINISTER, PermissionEntry.user("admin"));
        for (String userId : new String[] {"u1", "a1", "u2", "u3"}) {
            strategy.add(Jenkins.READ, PermissionEntry.user(userId));
            strategy.add(Item.READ, PermissionEntry.user(userId));
        }
        strategy.add(BatchControlPermissions.REQUEST_GRANT, PermissionEntry.user("u1"));
        strategy.add(BatchControlPermissions.REQUEST_GRANT, PermissionEntry.user("u3"));
        strategy.add(BatchControlPermissions.APPROVE, PermissionEntry.user("a1"));
        strategy.add(Item.CONFIGURE, PermissionEntry.user("u2"));
        strategy.add(Item.DELETE, PermissionEntry.user("u2"));
        strategy.add(Item.DELETE, PermissionEntry.user("u3"));
        j.jenkins.setAuthorizationStrategy(strategy);

        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setChangeControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
        team = j.jenkins.createProject(Folder.class, "team");
    }

    @AfterEach
    public void resetClock() {
        BatchClock.reset();
    }

    /**
     * T-08-49 (DEF-18, D-33): the permission screen gives the expiry guidance D-33 moved there.
     * Before expiry the window is not shown as expired; after it, the same screen shows the
     * window as expired (history of expired windows) and offers a re-request link that opens the
     * grant request form.
     */
    @Test
    public void t_08_49_grantsScreenShowsExpiredWindowAndReRequestLink() throws Exception {
        j.createFreeStyleProject("win-job");
        BatchClock.setForTest(Clock.fixed(T0, ZoneOffset.UTC));
        String id = submitGrantOk(j, "u1", "win-job", Arrays.asList("CONFIGURE"), 30, "fix the schedule", null, "a1");
        assertSuccess(decideGrant(j, "a1", id, "approve", "ok"), "fixture: approval");
        assertTrue(GrantService.get().hasActiveGrant("u1", "win-job", Item.CONFIGURE), "fixture: the window is active");

        HtmlPage active = UsabilityFixtures.htmlPage(j, "u1", "batch-control/grants/");
        assertNull(expiredEntry(active, "win-job"), "an active window must not be shown as expired: " + excerpt(active.asNormalizedText()));

        BatchClock.setForTest(Clock.fixed(T0.plus(Duration.ofMinutes(31)), ZoneOffset.UTC));
        ExtensionList.lookupSingleton(ExpiryPeriodicWork.class).doRun();
        assertFalse(GrantService.get().hasActiveGrant("u1", "win-job", Item.CONFIGURE), "fixture: the window has expired");

        HtmlPage expired = UsabilityFixtures.htmlPage(j, "u1", "batch-control/grants/");
        DomElement entry = expiredEntry(expired, "win-job");
        assertNotNull(entry, "the permission screen must show the expired window (D-33: history of expired windows): "
                + excerpt(expired.asNormalizedText()));
        // D-66 (note 248): the re-request entry may be a link or a dialog opener; its target is read
        // from href or a data-* URL attribute, and must render the grant request form.
        String target = null;
        for (DomElement e : expired.getElementById("main-panel").getHtmlElementDescendants()) {
            if (!("a".equals(e.getTagName()) || "button".equals(e.getTagName()))
                    || !Pattern.compile("(?i)(again|re-?request|renew)").matcher(e.asNormalizedText()).find()) {
                continue;
            }
            for (org.htmlunit.html.DomAttr attr : e.getAttributesMap().values()) {
                if (("href".equals(attr.getName()) || attr.getName().startsWith("data-")) && attr.getValue().contains("/")) {
                    target = expired.getFullyQualifiedUrl(attr.getValue()).toExternalForm();
                }
            }
            if (target != null) {
                break;
            }
        }
        assertNotNull(target, "the permission screen must offer a re-request entry (D-33): " + excerpt(expired.asNormalizedText()));
        Page form = UsabilityFixtures.get(j, UsabilityFixtures.clientNoJs(j, "u1"), target.substring(j.getURL().toExternalForm().length()));
        assertEquals(200, form.getWebResponse().getStatusCode(), "the re-request entry must open: " + target);
        assertTrue(form instanceof HtmlPage && !UsabilityFixtures.formsEndingWith((HtmlPage) form, "batch-control/grants/create").isEmpty(),
                "the re-request entry must lead to the grant request form");
    }

    /**
     * T-08-50 (DEF-19, D-40, D-71c): under a name-restricted CREATE grant, the New Item name check
     * answers with a message naming the restriction (not a 403), a matching name is accepted, the
     * refused creation names the restriction instead of "missing the Job/Create permission", and
     * typing logs no AccessDeniedException. Since D-71 the window on the folder carries [CREATE,
     * CONFIGURE] (DELETE applies only to a job and can no longer be requested on a folder); the
     * rename of {@code team/app-7} rests on the D-35c Configure of the item u1 created (note 260).
     * Since D-71c no window allows a rename, so the rename check and the refused rename explain that
     * instead of the restriction (note 266): the check answers 200 with an error reading "Renaming
     * 'team/app-7' (Freestyle project) is not allowed: while change control is on, a permission
     * window does not allow renaming a job or folder" (still not "the same as the current name"),
     * and the refused rename answers 400 with the same text and "Nothing was renamed.", as a plain
     * refusal.
     */
    @Test
    public void t_08_50_nameRestrictionIsExplainedWhileTypingAndOnRefusal() throws Exception {
        String id = submitGrantOk(j, "u1", "team", Arrays.asList("CREATE", "CONFIGURE"), 30,
                "create the app job", PATTERN, "a1");
        assertSuccess(decideGrant(j, "a1", id, "approve", "ok"), "fixture: approval");
        List<NameValuePair> create = new ArrayList<>();
        create.add(new NameValuePair("name", "app-7"));
        create.add(new NameValuePair("mode", "hudson.model.FreeStyleProject"));
        assertSuccess(ApproverFormFixtures.post(j, "u1", team.getUrl() + "createItem", create), "fixture: a matching name is created");
        assertNotNull(team.getItem("app-7"));

        try (LogRecorder log = new LogRecorder().record("", Level.INFO).capture(2000)) {
            for (String typed : new String[] {"a", "ap", "app", "app-x"}) {
                WebResponse check = ApproverFormFixtures.get(j, "u1", team.getUrl() + "checkJobName?value="
                        + URLEncoder.encode(typed, StandardCharsets.UTF_8));
                assertEquals(200, check.getStatusCode(), "the New Item name check must answer a message, not HTTP " + check.getStatusCode()
                        + " for '" + typed + "'");
            }
            WebResponse nonMatching = ApproverFormFixtures.get(j, "u1", team.getUrl() + "checkJobName?value=app-x");
            assertTrue(nonMatching.getContentAsString().contains("app-[0-9]+"), "the name check must name the restriction for a"
                    + " non-matching name: " + excerpt(nonMatching.getContentAsString()));
            WebResponse matching = ApproverFormFixtures.get(j, "u1", team.getUrl() + "checkJobName?value=app-9");
            assertEquals(200, matching.getStatusCode());
            assertFalse(matching.getContentAsString().contains("app-[0-9]+"), "negative twin: a matching name is not refused by the restriction: "
                    + excerpt(matching.getContentAsString()));

            WebResponse renameCheck = ApproverFormFixtures.get(j, "u1", team.getUrl() + "job/app-7/checkNewName?newName=evil");
            String renameText = renameCheck.getContentAsString();
            String windowRename = RenameRefusalFixtures.refusal("team/app-7", "Freestyle project");
            assertEquals(200, renameCheck.getStatusCode(), "the rename check must answer a message, not HTTP " + renameCheck.getStatusCode());
            assertFalse(renameText.contains("same as the current name"), "the rename check must not answer a misleading message: " + excerpt(renameText));
            assertEquals("error", RenameRefusalFixtures.validationKind(renameCheck), "D-71c: the rename check is an error: " + excerpt(renameText));
            assertTrue(RenameRefusalFixtures.visible(renameText).contains(windowRename), "D-71c: the rename check must say '" + windowRename
                    + "': " + excerpt(renameText));

            List<String> offenders = log.getRecords().stream()
                    .filter(r -> mentionsAccessDenied(r))
                    .map(r -> r.getLevel() + " " + r.getMessage())
                    .collect(Collectors.toList());
            assertTrue(offenders.isEmpty(), "typing a name must not log an AccessDeniedException per keystroke: " + offenders);
        }

        List<NameValuePair> refusedCreate = new ArrayList<>();
        refusedCreate.add(new NameValuePair("name", "app-x"));
        refusedCreate.add(new NameValuePair("mode", "hudson.model.FreeStyleProject"));
        WebResponse refused = ApproverFormFixtures.post(j, "u1", team.getUrl() + "createItem", refusedCreate);
        assertClientError(refused, "creating a non-matching name");
        assertNull(team.getItem("app-x"));
        UsabilityFixtures.assertPlainRefusal("refused creation", refused.getContentAsString(), Pattern.compile(Pattern.quote("app-[0-9]+")));
        assertFalse(refused.getContentAsString().contains("missing the Job/Create permission"),
                "the refusal must name the restriction, not a missing permission");

        List<NameValuePair> rename = new ArrayList<>();
        rename.add(new NameValuePair("newName", "evil"));
        WebResponse refusedRename = ApproverFormFixtures.post(j, "u1", team.getUrl() + "job/app-7/confirmRename", rename);
        RenameRefusalFixtures.assertWindowRenameRefused(refusedRename, "team/app-7", "Freestyle project", "renaming to a non-matching name");
        assertNotNull(team.getItem("app-7"), "the item keeps its name");
        assertNull(team.getItem("evil"), "nothing carries the new name");
        UsabilityFixtures.assertPlainRefusal("refused rename", RenameRefusalFixtures.visible(refusedRename.getContentAsString()),
                Pattern.compile(Pattern.quote(RenameRefusalFixtures.REASON)));
    }

    /**
     * T-08-51 (DEF-26): the delete veto's advice fits the user it addresses. u2 (standing Delete,
     * no RequestGrant) is not sent to the Grants screen, which answers 403 to them; the refusal
     * says whom to ask. Control: u3, who holds RequestGrant, is pointed to the Grants screen and
     * can open it.
     */
    @Test
    public void t_08_51_deleteVetoAdviceFitsTheUser() throws Exception {
        FreeStyleProject forU2 = j.createFreeStyleProject("veto-u2");
        FreeStyleProject forU3 = j.createFreeStyleProject("veto-u3");

        JenkinsRule.WebClient u2Client = UsabilityFixtures.client(j, "u2");
        Page u2 = u2Client.getPage(new WebRequest(u2Client.createCrumbedUrl(forU2.getUrl() + "doDelete"), HttpMethod.POST));
        assertTrue(u2.getWebResponse().getStatusCode() >= 400, "fixture: the delete is vetoed without a DELETE grant (SPEC 8)");
        assertNotNull(j.jenkins.getItemByFullName("veto-u2"), "fixture: the job survives");
        String u2Text = UsabilityFixtures.text(u2);
        UsabilityFixtures.assertPlainRefusal("delete veto for u2", u2Text, Pattern.compile("(?i)grant"));
        assertFalse(u2Text.contains("/batch-control"), "u2 cannot open the Grants screen, so it must not be offered: " + excerpt(u2Text));
        if (u2 instanceof HtmlPage) {
            for (String href : UsabilityFixtures.resolvedHrefs((HtmlPage) u2)) {
                assertFalse(href.contains("/batch-control/grants"), "no link to the Grants screen may be offered to u2: " + href);
            }
        }
        assertFalse(Pattern.compile("(?i)request one under").matcher(u2Text).find(),
                "u2 may not request a grant, so the refusal must not tell them to: " + excerpt(u2Text));
        assertTrue(Pattern.compile("(?i)(administrator|ask)").matcher(u2Text).find(), "the refusal must say whom to ask: " + excerpt(u2Text));

        JenkinsRule.WebClient u3Client = UsabilityFixtures.client(j, "u3");
        Page u3 = u3Client.getPage(new WebRequest(u3Client.createCrumbedUrl(forU3.getUrl() + "doDelete"), HttpMethod.POST));
        assertTrue(u3.getWebResponse().getStatusCode() >= 400, "fixture: the delete is vetoed for u3 too");
        String u3Text = UsabilityFixtures.text(u3);
        assertTrue(u3Text.contains("Grants") || u3Text.contains("batch-control/grants"), "control: u3 (RequestGrant) is pointed to the Grants screen: "
                + excerpt(u3Text));
        assertEquals(200, ApproverFormFixtures.get(j, "u3", "batch-control/grants/").getStatusCode(), "control: u3 can open the Grants screen");
    }

    /**
     * T-08-52 (DEF-27): a grant request refused because change control is off is explained and
     * recorded: one new record names the requester and the switch. Negative twin: the explanation
     * (HTTP 400 naming change control) is unchanged.
     */
    @Test
    public void t_08_52_grantRequestRefusedWhileChangeControlIsOffIsRecorded() throws Exception {
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setChangeControlEnabled(false);
        cfg.setRunControlEnabled(true); // keep recording active (SPEC 9: records while either switch is on)
        cfg.save();
        j.createFreeStyleProject("off-job");
        java.util.Set<Object> before = byUser("u1").stream().map(r -> (Object) r.getId()).collect(Collectors.toSet());

        WebResponse refused = submitGrant(j, "u1", "off-job", Arrays.asList("CONFIGURE"), 30, "needs a change", null, "a1");
        assertClientError(refused, "a grant request while change control is off");
        assertTrue(refused.getContentAsString().toLowerCase(Locale.ROOT).contains("change control"),
                "the refusal names the switch: " + excerpt(refused.getContentAsString()));

        List<ChangeRecord> after = byUser("u1").stream().filter(r -> !before.contains(r.getId())).collect(Collectors.toList());
        assertEquals(1, after.size(), "the refused grant request must be recorded once, naming the requester: " + after);
        ChangeRecord rec = after.get(0);
        String detail = (rec.getDetail() == null ? "" : rec.getDetail()) + " " + (rec.getTarget() == null ? "" : rec.getTarget());
        assertTrue(detail.toLowerCase(Locale.ROOT).contains("change control") || detail.contains("changeControl"),
                "the record must say why (change control off): " + detail);
    }

    // ---------------------------------------------------------------- helpers

    /** The smallest element naming the scope and the word "expired". */
    private static DomElement expiredEntry(HtmlPage page, String scope) {
        DomElement main = page.getElementById("main-panel");
        if (main == null) {
            return null;
        }
        Pattern expired = Pattern.compile("(?i)\\bexpired\\b");
        DomElement best = null;
        for (DomElement element : main.getHtmlElementDescendants()) {
            // normalized text separates table cells; raw text content glued "Expired" to its neighbours (note 248)
            String text = element.asNormalizedText();
            if (text == null || !text.contains(scope) || !expired.matcher(text).find()) {
                continue;
            }
            if (best == null || text.length() < best.asNormalizedText().length()) {
                best = element;
            }
        }
        return best;
    }

    private static boolean mentionsAccessDenied(LogRecord record) {
        Throwable t = record.getThrown();
        while (t != null) {
            if (t instanceof org.springframework.security.access.AccessDeniedException) {
                return true;
            }
            t = t.getCause();
        }
        String message = record.getMessage();
        return message != null && message.contains("AccessDeniedException");
    }

    private static List<ChangeRecord> byUser(String user) {
        return new ArrayList<>(FileStore.get().listChangeRecords(YearMonth.now(BatchClock.clock())).stream()
                .filter(r -> user.equals(r.getUser()))
                .collect(Collectors.toList()));
    }
}
