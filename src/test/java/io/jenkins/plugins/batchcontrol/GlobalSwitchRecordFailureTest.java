package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.Grant;
import io.jenkins.plugins.batchcontrol.model.GrantAction;
import io.jenkins.plugins.batchcontrol.model.GrantRequest;
import io.jenkins.plugins.batchcontrol.model.GrantScope;
import io.jenkins.plugins.batchcontrol.policy.GrantRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.security.GrantService;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;
import java.util.stream.Collectors;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.htmlunit.html.HtmlCheckBoxInput;
import org.htmlunit.html.HtmlForm;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.LogRecorder;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bug hunt A R3-01 (TEST-MATRIX note 301): a failure to append the change record never stops or
 * reverses the switch change. With {@code batch-control/changes/} unwritable, turning change control
 * off completes: the switch is off in memory and in the saved configuration, every active window is
 * revoked ({@code revokedAt} written), the form answers without a server error, the direct setter
 * does not throw, and the failure is logged at WARNING or SEVERE. Matrix rows T-01-17 and T-01-18;
 * the JCasC-at-boot row T-01-19 is {@link GlobalSwitchRecordFailureRestartTest}, the review row
 * T-02-126 {@link GuardReviewRecordFailureTest}.
 *
 * <p>Basis: SPEC 1 (a switch change takes effect once the new configuration is saved; side effects
 * such as revoking the active grants when change control is turned off run after the new state is
 * durable; a direct setter never throws, D-42), P-15 (change control off revokes the open windows),
 * D-63 (such a revocation says so), ARCHITECTURE 5 ({@code changes/YYYY-MM.jsonl}). The guard of the
 * same submission without the fault is T-01-08 ({@link SwitchSaveFailureTest}); each row here also
 * asserts its premise (the window active and conferring before the toggle).
 *
 * <p>Written from docs/SPEC.md, docs/DECISIONS.md and docs/ARCHITECTURE.md only (no src/main knowledge).
 */
@WithJenkins
public class GlobalSwitchRecordFailureTest {

    private JenkinsRule j;
    private FreeStyleProject job;
    private BatchControlGlobalConfiguration cfg;

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

        job = j.createFreeStyleProject("batch-x");
        job.setDescription("base");
        cfg = BatchControlGlobalConfiguration.get();
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
    }

    /**
     * T-01-17 (P0): change control on, u1 holds an active CONFIGURE window on batch-x; the change-record
     * directory refuses writes; the administrator unticks changeControlEnabled on the global configuration
     * form. The form answers without an error (no HTTP 500), the switch is off in memory and in the saved
     * configuration, the window is revoked ({@code revokedAt} in its stored file, not active, no longer
     * conferring), and the failed record is logged at WARNING or SEVERE. After the directory is writable
     * again and change control is turned back on, the window still confers nothing.
     */
    @Test
    public void t_01_17_formTurnsChangeControlOffAndRevokesWhenTheRecordCannotBeWritten() throws Exception {
        Grant grant = changeControlOnWithActiveGrant();
        assertEquals(200, postConfigXml("u1", "inside-window"), "premise: the window confers Configure before the toggle");

        int code;
        List<String> problems;
        try (RecordFaultFixtures.Fault ignored = RecordFaultFixtures.makeUnwritable(changesDir());
             LogRecorder log = new LogRecorder().record("io.jenkins.plugins.batchcontrol", Level.WARNING).capture(200)) {
            code = submitSwitch("admin", "changeControlEnabled", false);
            problems = warnings(log);
        }

        assertTrue(code < 400, "R3-01: turning change control off on the form must complete although the CONFIG_TOGGLE record"
                + " cannot be written (no HTTP 500), got HTTP " + code);
        assertSwitchOffAndWindowRevoked(grant, "the form");
        assertFalse(problems.isEmpty(), "R3-01: the failed change record must be logged at WARNING or SEVERE");

        turnChangeControlBackOn();
        assertNull(WindowStateFixtures.active(grant.getId()), "R3-01: the revoked window must stay revoked when change control is turned back on");
        assertFalse(GrantService.get().hasActiveGrant("u1", "batch-x", Item.CONFIGURE),
                "R3-01: the revoked window must not confer again when change control is turned back on");
        assertEquals(403, postConfigXml("u1", "after-switch-back-on"), "R3-01: the revoked window must not confer Configure again");
        assertEquals("inside-window", job.getDescription(), "the job keeps the description saved inside the window");
    }

    /**
     * T-01-18 (P0, D-42): as T-01-17, but through the direct setter (script console, JCasC):
     * {@code setChangeControlEnabled(false)} does not throw, the switch is off in memory and in the saved
     * configuration, and the window is revoked. After the directory is writable again and change control is
     * turned back on, the window still confers nothing.
     */
    @Test
    public void t_01_18_directSetterTurnsChangeControlOffPersistsAndRevokesWhenTheRecordCannotBeWritten() throws Exception {
        Grant grant = changeControlOnWithActiveGrant();

        List<String> problems;
        try (RecordFaultFixtures.Fault ignored = RecordFaultFixtures.makeUnwritable(changesDir());
             LogRecorder log = new LogRecorder().record("io.jenkins.plugins.batchcontrol", Level.WARNING).capture(200)) {
            assertDoesNotThrow(() -> BatchControlGlobalConfiguration.get().setChangeControlEnabled(false),
                    "R3-01, D-42: the direct setter must not throw when the CONFIG_TOGGLE record cannot be written");
            problems = warnings(log);
        }

        assertSwitchOffAndWindowRevoked(grant, "the direct setter");
        assertFalse(problems.isEmpty(), "R3-01: the failed change record must be logged at WARNING or SEVERE");

        turnChangeControlBackOn();
        assertFalse(GrantService.get().hasActiveGrant("u1", "batch-x", Item.CONFIGURE),
                "R3-01: the revoked window must not confer again when change control is turned back on");
        assertEquals(403, postConfigXml("u1", "after-switch-back-on"), "R3-01: the revoked window must not confer Configure again");
    }

    // ---------------------------------------------------------------- helpers

    private java.nio.file.Path changesDir() {
        return RecordFaultFixtures.changesDir(j.jenkins.getRootDir().toPath());
    }

    private void assertSwitchOffAndWindowRevoked(Grant grant, String path) throws Exception {
        assertFalse(BatchControlGlobalConfiguration.get().isChangeControlEnabled(),
                "R3-01: after " + path + " change control must be off in memory");
        BatchControlGlobalConfiguration.get().load();
        assertFalse(BatchControlGlobalConfiguration.get().isChangeControlEnabled(),
                "R3-01: after " + path + " change control must be off in the saved configuration (reloaded from disk)");
        assertFalse(GrantService.get().hasActiveGrant("u1", "batch-x", Item.CONFIGURE),
                "R3-01, P-15: after " + path + " the active window must be revoked although its record could not be written");
        assertNull(WindowStateFixtures.active(grant.getId()), "R3-01: the window must no longer be listed as active");
        String stored = WindowStateFixtures.storedGrant(j, grant.getId());
        assertTrue(stored.contains("<revokedAt"), "R3-01: the stored window must carry revokedAt: " + stored.replaceAll("\\s+", " "));
    }

    private void turnChangeControlBackOn() {
        BatchControlGlobalConfiguration c = BatchControlGlobalConfiguration.get();
        c.setChangeControlEnabled(true);
        c.save();
        assertTrue(c.isChangeControlEnabled(), "fixture: change control is on again");
    }

    private static List<String> warnings(LogRecorder log) {
        return log.getRecords().stream()
                .filter(r -> r.getLevel().intValue() >= Level.WARNING.intValue())
                .map(r -> r.getLevel() + " " + r.getLoggerName() + ": " + r.getMessage())
                .collect(Collectors.toList());
    }

    /** Turns change control on (saved) and gives u1 an approved 60-minute CONFIGURE window on batch-x. */
    private Grant changeControlOnWithActiveGrant() throws Exception {
        cfg.setChangeControlEnabled(true);
        cfg.save();
        assertTrue(cfg.isChangeControlEnabled(), "fixture: change control must really be on before it is turned off");
        GrantRequest request;
        try (ACLContext ignored = as("u1")) {
            request = GrantRequestService.get().create(new GrantScope(GrantScope.Type.ITEM, "batch-x"),
                    Arrays.asList(GrantAction.CONFIGURE), 60, "scheduled maintenance", "a1");
        }
        Grant grant;
        try (ACLContext ignored = as("a1")) {
            grant = GrantRequestService.get().approve(request.getId(), "ok");
        }
        assertNotNull(grant, "fixture: the grant must have been issued");
        assertTrue(GrantService.get().hasActiveGrant("u1", "batch-x", Item.CONFIGURE), "fixture: the window must be active");
        return grant;
    }

    /** Opens the global configure page as {@code userId}, sets one switch checkbox and submits; returns the HTTP status. */
    private int submitSwitch(String userId, String field, boolean value) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login(userId);
        HtmlForm form = wc.goTo("configure").getFormByName("config");
        List<HtmlCheckBoxInput> boxes = form.getByXPath(".//input[@type='checkbox' and (@name='_." + field
                + "' or @name='" + field + "')]");
        assertEquals(1, boxes.size(), "fixture: the global form must render exactly one " + field + " checkbox");
        boxes.get(0).setChecked(value);
        Page result = j.submit(form);
        return result.getWebResponse().getStatusCode();
    }

    private int postConfigXml(String userId, String newDescription) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login(userId);
        String xml = job.getConfigFile().asString()
                .replace("<description>" + job.getDescription() + "</description>",
                        "<description>" + newDescription + "</description>");
        WebRequest request = new WebRequest(wc.createCrumbedUrl(job.getUrl() + "config.xml"), HttpMethod.POST);
        request.setAdditionalHeader("Content-Type", "application/xml; charset=UTF-8");
        request.setRequestBody(xml);
        return wc.getPage(request).getWebResponse().getStatusCode();
    }

    private static ACLContext as(String userId) {
        return ACL.as2(User.getById(userId, true).impersonate2());
    }
}
