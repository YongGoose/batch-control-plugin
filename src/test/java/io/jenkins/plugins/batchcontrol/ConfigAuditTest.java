package io.jenkins.plugins.batchcontrol;

import io.jenkins.plugins.batchcontrol.store.BatchClock;
import hudson.model.Item;
import hudson.security.HudsonPrivateSecurityRealm;
import hudson.security.ProjectMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.security.BatchControlMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.store.FileStore;
import java.net.URL;
import java.time.YearMonth;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.HtmlAnchor;
import org.htmlunit.html.HtmlForm;
import org.htmlunit.html.HtmlInput;
import org.htmlunit.html.HtmlPage;
import org.htmlunit.html.HtmlTextArea;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.UsabilityFixtures.excerpt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * SPEC acceptance lines ending in (D-52) and (D-53), and the SPEC 6 usability line for e2e-04
 * FD-01: every change of the Batch Control configuration is recorded; approver ids are
 * validated; a control is shown only to users who can use it. Matrix rows T-CFG-05 .. T-CFG-08
 * and T-UI-25 (note 167).
 *
 * <p>The security realm is Jenkins' own user database, so an unknown id is really unknown to the
 * realm (the test harness's dummy realm resolves every name). {@code manager} holds Overall/Read,
 * Job/Read and BatchControl/Manage; {@code admin} Administer. The configuration is opened at
 * {@code batch-control-configuration/} (e2e-04 S1-02) and its form is the one carrying the
 * {@code pendingTimeoutHours} field; the approver field is the text field whose name contains
 * "approver".
 *
 * <p>Written from docs/SPEC.md, docs/DECISIONS.md D-52/D-53, docs/reports/e2e-04.md and
 * docs/TEST-MATRIX.md only (no src/main knowledge).
 */
@WithJenkins
public class ConfigAuditTest {

    private static final String CONFIG = "batch-control-configuration/";
    private static final String MONITOR = "manage/administrativeMonitor/batch-control-strategy/";

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        HudsonPrivateSecurityRealm realm = new HudsonPrivateSecurityRealm(false, false, null);
        for (String id : new String[] {"admin", "manager", "a1", "a2"}) {
            realm.createAccount(id, id); // WebClient.login(id) uses the id as password
        }
        j.jenkins.setSecurityRealm(realm);
        BatchControlMatrixAuthorizationStrategy strategy = new BatchControlMatrixAuthorizationStrategy();
        strategy.add(Jenkins.ADMINISTER, PermissionEntry.user("admin"));
        strategy.add(Jenkins.READ, PermissionEntry.user("manager"));
        strategy.add(Item.READ, PermissionEntry.user("manager"));
        strategy.add(BatchControlPermissions.MANAGE, PermissionEntry.user("manager"));
        j.jenkins.setAuthorizationStrategy(strategy);
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
    }

    /**
     * T-CFG-05 (D-52, FD-02): the manager changes the approvers (a1 to a1, a2) and the pending
     * timeout (72 to 48) in one save: exactly one CONFIG_CHANGE record by the manager naming the
     * old and new values of both fields. A second save without a change writes no record.
     */
    @Test
    public void t_cfg_05_configurationChangeIsRecordedOnceAndANoOpSaveIsNot() throws Exception {
        assertEquals(72, BatchControlGlobalConfiguration.get().getPendingTimeoutHours(), "fixture: default timeout");
        int before = records("CONFIG_CHANGE").size();

        HtmlForm form = configForm("manager");
        setApprovers(form, "a1, a2");
        UsabilityFixtures.setField(form, "pendingTimeoutHours", "48");
        Page saved = j.submit(form);
        assertTrue(saved.getWebResponse().getStatusCode() < 400, "fixture: the manager's save must succeed");
        assertEquals(48, BatchControlGlobalConfiguration.get().getPendingTimeoutHours(), "fixture: the timeout is saved");
        assertEquals(Arrays.asList("a1", "a2"), BatchControlGlobalConfiguration.get().getApprovers(), "fixture: approvers saved");

        List<ChangeRecord> changes = records("CONFIG_CHANGE");
        assertEquals(before + 1, changes.size(), "one save that changes the approvers and the timeout must write exactly"
                + " one CONFIG_CHANGE record: " + describe(changes));
        ChangeRecord record = changes.get(changes.size() - 1);
        assertEquals("manager", record.getUser(), "the record must name the user");
        String detail = String.valueOf(record.getDetail());
        for (String expected : new String[] {"72", "48", "a2"}) {
            assertTrue(detail.contains(expected), "the record must list old and new values (" + expected + "): " + detail);
        }

        Page again = j.submit(configForm("manager"));
        assertTrue(again.getWebResponse().getStatusCode() < 400, "fixture: the unchanged save must succeed");
        assertEquals(before + 1, records("CONFIG_CHANGE").size(), "a save that changes nothing must write no record");
    }

    /**
     * T-CFG-06 (D-52): installing the Batch Control strategy (the monitor's migrate) and reverting
     * it each write one STRATEGY_CHANGE record by the administrator.
     */
    @Test
    public void t_cfg_06_strategyInstallAndRevertAreRecorded() throws Exception {
        ProjectMatrixAuthorizationStrategy plain = new ProjectMatrixAuthorizationStrategy();
        plain.add(Jenkins.ADMINISTER, PermissionEntry.user("admin"));
        j.jenkins.setAuthorizationStrategy(plain);
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setChangeControlEnabled(true);
        cfg.save();
        int before = records("STRATEGY_CHANGE").size();

        assertTrue(postMonitor("migrate") < 400, "fixture: the administrator's migrate must succeed");
        assertTrue(j.jenkins.getAuthorizationStrategy() instanceof BatchControlMatrixAuthorizationStrategy,
                "fixture: migrate must install the Batch Control strategy");
        List<ChangeRecord> installed = records("STRATEGY_CHANGE");
        assertEquals(before + 1, installed.size(), "installing the strategy must write one STRATEGY_CHANGE record");
        assertEquals("admin", installed.get(installed.size() - 1).getUser());

        assertTrue(postMonitor("revert") < 400, "fixture: the administrator's revert must succeed");
        assertFalse(j.jenkins.getAuthorizationStrategy() instanceof BatchControlMatrixAuthorizationStrategy,
                "fixture: revert must install the plain strategy");
        List<ChangeRecord> reverted = records("STRATEGY_CHANGE");
        assertEquals(before + 2, reverted.size(), "reverting the strategy must write one STRATEGY_CHANGE record");
        assertEquals("admin", reverted.get(reverted.size() - 1).getUser());
    }

    /**
     * T-CFG-07 (D-53, FD-03): the manager saves the approver id {@code no-such-user}, which names
     * no user and which the realm does not resolve. The save is refused with a message naming the
     * id, the typed value is kept on the page, and the stored approvers stay [a1].
     */
    @Test
    public void t_cfg_07_unknownApproverIdIsRefusedAndNothingIsSaved() throws Exception {
        HtmlForm form = configForm("manager");
        setApprovers(form, "no-such-user");
        UsabilityFixtures.setField(form, "pendingTimeoutHours", "24");
        Page answer = j.submit(form);

        assertEquals(Arrays.asList("a1"), BatchControlGlobalConfiguration.get().getApprovers(),
                "an unknown approver id must not be saved");
        assertEquals(72, BatchControlGlobalConfiguration.get().getPendingTimeoutHours(), "nothing may be saved");
        String text = UsabilityFixtures.text(answer);
        assertTrue(text.contains("no-such-user"), "the refusal must name the unknown id: " + excerpt(text));
        UsabilityFixtures.assertPlainRefusal("unknown approver id", text, null);
        assertTrue(answer instanceof HtmlPage && UsabilityFixtures.pageKeepsValue((HtmlPage) answer, "no-such-user"),
                "the form must keep the typed input: " + excerpt(text));
    }

    /**
     * T-CFG-08 (D-53): with run control on, an empty approver list is refused and the stored
     * list stays [a1] and the answer is a plain refusal.
     */
    @Test
    public void t_cfg_08_emptyApproverListIsRefusedWhileASwitchIsOn() throws Exception {
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.save();

        HtmlForm form = configForm("manager");
        setApprovers(form, "");
        Page answer = j.submit(form);
        assertEquals(Arrays.asList("a1"), BatchControlGlobalConfiguration.get().getApprovers(),
                "an empty approver list must be refused while run control is on");
        UsabilityFixtures.assertPlainRefusal("empty approver list", UsabilityFixtures.text(answer), null);
    }

    /**
     * T-UI-25 (FD-01): a holder of BatchControl/Manage without Administer is offered no "Revert to
     * the plain strategy" control on the Batch Control configuration (the revert needs Administer).
     * Premise: the administrator is offered it on the same page.
     */
    @Test
    public void t_ui_25_manageHolderIsNotOfferedTheStrategyRevert() throws Exception {
        assertTrue(offersRevert(configPage("admin")), "premise: the administrator is offered the revert on "
                + CONFIG + ": " + excerpt(configPage("admin").asNormalizedText()));
        HtmlPage manager = configPage("manager");
        assertFalse(offersRevert(manager), "a BatchControl/Manage holder must not be offered the revert, which needs"
                + " Overall/Administer: " + excerpt(manager.asNormalizedText()));
    }

    /**
     * T-CFG-09 (security-25 S-25-09): the approver field's check. Premise: the manager's POST of
     * {@code checkApproversText?value=no-such-user} answers a check result naming the id. A user
     * without BatchControl/Manage (Overall/Read only) gets 403 and no check result; a GET by the
     * manager is refused (the check requires POST) without a check result. Note 173.
     */
    @Test
    public void t_cfg_09_approverCheckIsForManageHoldersAndPostOnly() throws Exception {
        BatchControlMatrixAuthorizationStrategy strategy = (BatchControlMatrixAuthorizationStrategy) j.jenkins.getAuthorizationStrategy();
        strategy.add(Jenkins.READ, PermissionEntry.user("a2"));
        String path = "descriptorByName/io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration/"
                + "checkApproversText?value=no-such-user";

        org.htmlunit.WebResponse manager = postAs("manager", path);
        assertEquals(200, manager.getStatusCode(), "premise: the manager's check must be served");
        assertTrue(manager.getContentAsString().contains("no-such-user"), "premise: the manager's check names the"
                + " unknown id: " + excerpt(manager.getContentAsString()));

        org.htmlunit.WebResponse other = postAs("a2", path);
        assertEquals(403, other.getStatusCode(), "a user without BatchControl/Manage must be refused with 403, got "
                + other.getStatusCode() + ": " + excerpt(other.getContentAsString()));
        assertFalse(other.getContentAsString().contains("no-such-user"), "no check result for a user without Manage");

        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login("manager");
        org.htmlunit.WebResponse get = wc.getPage(new WebRequest(new URL(j.getURL(), path), HttpMethod.GET)).getWebResponse();
        assertTrue(get.getStatusCode() >= 400, "the check requires POST; a GET must be refused, got " + get.getStatusCode());
        assertFalse(get.getContentAsString().contains("no-such-user"), "a GET must not give a check result");
    }

    /**
     * T-CFG-10 (security-26 S-26-05, the 100-id cap): the manager saves 101 approver ids, all
     * existing users. The save is refused with a message naming the limit of 100, and the stored
     * approvers stay [a1].
     */
    @Test
    public void t_cfg_10_approverListIsCappedAtOneHundred() throws Exception {
        HudsonPrivateSecurityRealm realm = (HudsonPrivateSecurityRealm) j.jenkins.getSecurityRealm();
        StringBuilder ids = new StringBuilder();
        for (int i = 0; i < 101; i++) {
            String id = "appr" + i;
            realm.createAccount(id, id);
            ids.append(i == 0 ? "" : ", ").append(id);
        }
        HtmlForm form = configForm("manager");
        setApprovers(form, ids.toString());
        Page answer = j.submit(form);
        assertEquals(Arrays.asList("a1"), BatchControlGlobalConfiguration.get().getApprovers(),
                "a list of 101 approvers must not be saved");
        String text = UsabilityFixtures.text(answer);
        assertTrue(text.contains("100"), "the refusal must name the limit of 100: " + excerpt(text));
        UsabilityFixtures.assertPlainRefusal("101 approvers", text, null);
    }

    /**
     * T-CFG-11 (S-26-05, the early stop): the manager saves seven unknown ids. The save is
     * refused; the message names the first unknown id and says that the remaining ids were not
     * checked.
     */
    @Test
    public void t_cfg_11_unknownIdCheckStopsAfterFive() throws Exception {
        HtmlForm form = configForm("manager");
        setApprovers(form, "nobody1, nobody2, nobody3, nobody4, nobody5, nobody6, nobody7");
        Page answer = j.submit(form);
        assertEquals(Arrays.asList("a1"), BatchControlGlobalConfiguration.get().getApprovers(), "nothing may be saved");
        String text = UsabilityFixtures.text(answer);
        assertTrue(text.contains("nobody1"), "the refusal must name the unknown ids: " + excerpt(text));
        assertTrue(text.toLowerCase(java.util.Locale.ROOT).contains("not checked"), "after five unknown ids the message"
                + " must say that the remaining ids were not checked: " + excerpt(text));
    }

    /**
     * T-CFG-12 (D-53, security-25 S-25-05): an id the realm cannot tell about (its lookup throws
     * UserMayOrMayNotExistException2) is accepted with a warning: the save goes through with that
     * id, and the field's check answers a warning, not an error.
     */
    @Test
    public void t_cfg_12_idTheRealmCannotTellAboutIsAcceptedWithAWarning() throws Exception {
        MaybeRealm realm = new MaybeRealm();
        for (String id : new String[] {"admin", "manager", "a1", "a2"}) {
            realm.createAccount(id, id);
        }
        j.jenkins.setSecurityRealm(realm);

        HtmlForm form = configForm("manager");
        setApprovers(form, "a1, maybe-user");
        Page saved = j.submit(form);
        assertTrue(saved.getWebResponse().getStatusCode() < 400, "the save must go through, got "
                + saved.getWebResponse().getStatusCode() + ": " + excerpt(UsabilityFixtures.text(saved)));
        assertEquals(Arrays.asList("a1", "maybe-user"), BatchControlGlobalConfiguration.get().getApprovers(),
                "an id the realm cannot tell about must be accepted");

        org.htmlunit.WebResponse check = postAs("manager", "descriptorByName/io.jenkins.plugins.batchcontrol.config."
                + "BatchControlGlobalConfiguration/checkApproversText?value=maybe-user");
        assertEquals(200, check.getStatusCode());
        String body = check.getContentAsString();
        assertTrue(body.contains("warning"), "the check must answer a warning for an id the realm cannot tell about: "
                + excerpt(body));
        assertFalse(body.contains("class=\"error\"") || body.contains("class='error'"), "not an error: " + excerpt(body));
    }

    /** A user database whose lookup of {@code maybe-user} cannot tell whether the user exists. */
    public static class MaybeRealm extends HudsonPrivateSecurityRealm {
        public MaybeRealm() {
            super(false, false, null);
        }

        @Override
        public org.springframework.security.core.userdetails.UserDetails loadUserByUsername2(String username) {
            if ("maybe-user".equals(username)) {
                throw new hudson.security.UserMayOrMayNotExistException2("test: the realm cannot tell");
            }
            return super.loadUserByUsername2(username);
        }

        @Override
        public hudson.model.Descriptor<hudson.security.SecurityRealm> getDescriptor() {
            return Jenkins.get().getDescriptorByType(HudsonPrivateSecurityRealm.DescriptorImpl.class);
        }
    }

    private org.htmlunit.WebResponse postAs(String userId, String path) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login(userId);
        int q = path.indexOf('?');
        URL url = new URL(wc.createCrumbedUrl(path.substring(0, q)).toExternalForm() + "&" + path.substring(q + 1));
        return wc.getPage(new WebRequest(url, HttpMethod.POST)).getWebResponse();
    }

    // ---------------------------------------------------------------- helpers

    private HtmlPage configPage(String userId) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login(userId);
        Page page = wc.getPage(new WebRequest(new URL(j.getURL(), CONFIG), HttpMethod.GET));
        assertEquals(200, page.getWebResponse().getStatusCode(), "fixture: " + userId + " must open " + CONFIG);
        return (HtmlPage) page;
    }

    private HtmlForm configForm(String userId) throws Exception {
        HtmlPage page = configPage(userId);
        for (HtmlForm form : page.getForms()) {
            if (UsabilityFixtures.hasField(form, "pendingTimeoutHours")) {
                return form;
            }
        }
        fail("fixture: " + CONFIG + " must carry the settings form; forms: " + UsabilityFixtures.formActions(page));
        return null;
    }

    private static void setApprovers(HtmlForm form, String value) {
        for (String tag : new String[] {"textarea", "input"}) {
            for (DomElement element : form.getElementsByTagName(tag)) {
                String name = element.getAttribute("name");
                if (name == null || !name.toLowerCase(java.util.Locale.ROOT).contains("approver")) {
                    continue;
                }
                if (element instanceof HtmlTextArea) {
                    ((HtmlTextArea) element).setText(value);
                    return;
                }
                if (element instanceof HtmlInput && !"checkbox".equals(element.getAttribute("type"))
                        && !"hidden".equals(element.getAttribute("type"))) {
                    ((HtmlInput) element).setValue(value);
                    return;
                }
            }
        }
        fail("fixture: the settings form must offer an approver text field; form was: " + excerpt(form.asXml()));
    }

    private static boolean offersRevert(HtmlPage page) throws Exception {
        if (page.asNormalizedText().contains("Revert to the plain strategy")) {
            return true;
        }
        for (String action : UsabilityFixtures.formActions(page)) {
            if (action != null && action.contains("batch-control-strategy/revert")) {
                return true;
            }
        }
        for (HtmlAnchor a : page.getAnchors()) {
            String href = a.getHrefAttribute();
            if (href != null && href.contains("batch-control-strategy/revert")) {
                return true;
            }
        }
        return false;
    }

    private int postMonitor(String action) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login("admin");
        return wc.getPage(new WebRequest(wc.createCrumbedUrl(MONITOR + action), HttpMethod.POST))
                .getWebResponse().getStatusCode();
    }

    /** Records of the type named {@code type} (looked up by name: the constant is new with D-52). */
    private static List<ChangeRecord> records(String type) {
        return FileStore.get().listChangeRecords(YearMonth.now(BatchClock.clock())).stream()
                .filter(r -> r.getType() != null && type.equals(r.getType().name()))
                .collect(Collectors.toList());
    }

    private static String describe(List<ChangeRecord> records) {
        return records.stream().map(r -> r.getType() + " " + r.getUser() + " " + r.getDetail())
                .collect(Collectors.joining("; ", "[", "]"));
    }
}
