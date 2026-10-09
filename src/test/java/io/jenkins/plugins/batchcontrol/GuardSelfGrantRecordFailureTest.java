package io.jenkins.plugins.batchcontrol;

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.security.AuthorizationMatrixProperty;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.model.GrantAction;
import io.jenkins.plugins.batchcontrol.security.BatchControlMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import java.net.URL;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.stream.Collectors;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.jenkinsci.plugins.matrixauth.inheritance.InheritParentStrategy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.LogRecorder;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.StrategyFixtures.T0;
import static io.jenkins.plugins.batchcontrol.StrategyFixtures.has;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A failed change-record append never breaks the self-grant guard's report to the saving user (TEST-MATRIX
 * note 305, rows T-02-127 and T-02-128). A user whose Configure on an item comes only from a grant (here
 * the D-35c Configure on the item bob creates through his CREATE window) cannot give the item an
 * authorization property: the property is removed and recorded as {@code GRANT_VIOLATION}, and the saving
 * user is told (D-48: HTTP 403 with a plain message). With {@code batch-control/changes/} unwritable the
 * {@code GRANT_VIOLATION} record cannot be appended; the property must still be removed, the user still
 * told, and the failure logged at WARNING or SEVERE.
 *
 * <p>Basis: SPEC 2 ("A Create grant leaves no permanent authorization entry on the item it created,
 * whether ... the creation payload (a submitted config.xml or a copied item) would have added it; a
 * payload's authorization property is removed and recorded as GRANT_VIOLATION" (D-35b, D-35c); "when the
 * guard reverts part of a save, the saving user is told: a save made through an HTTP request (the
 * configuration form, a {@code config.xml} POST, {@code createItem}, a copy) answers 403 with a plain
 * message naming the item ..." (D-48)), the contract of bug hunt A R3-01 and D-42 (a failure to append the
 * change record never stops, reverses or half-applies the operation it records; it is logged at WARNING or
 * above; the request does not answer 500), ARCHITECTURE 5. The positive rows with a writable store are
 * T-02-34 and T-02-35 ({@link GrantSelfGrantGuardTest}); each row here repeats its flow with a writable
 * store first (the guard).
 *
 * <p>Written from docs/SPEC.md item 2, docs/DECISIONS.md D-35c/D-42/D-48 and docs/ARCHITECTURE.md section 5
 * only (no src/main knowledge).
 */
@WithJenkins
public class GuardSelfGrantRecordFailureTest {

    /** An authorization property giving bob and carol Item/Configure (the T-02-34 payload). */
    private static final String PAYLOAD_PROPERTY = "<hudson.security.AuthorizationMatrixProperty>"
            + "<inheritanceStrategy class=\"org.jenkinsci.plugins.matrixauth.inheritance.InheritParentStrategy\"/>"
            + "<permission>USER:hudson.model.Item.Configure:bob</permission>"
            + "<permission>USER:hudson.model.Item.Configure:carol</permission>"
            + "</hudson.security.AuthorizationMatrixProperty>";

    private static final String PAYLOAD = "<?xml version='1.1' encoding='UTF-8'?><project><properties>" + PAYLOAD_PROPERTY
            + "</properties><builders/><publishers/><buildWrappers/></project>";

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(StrategyFixtures.matrix(new BatchControlMatrixAuthorizationStrategy()));
        StrategyFixtures.changeControlOn();
        BatchClock.setForTest(Clock.fixed(T0, ZoneOffset.UTC));
        j.jenkins.createProject(Folder.class, "team");
    }

    @AfterEach
    public void resetClock() {
        BatchClock.reset();
    }

    /**
     * T-02-127 (P0): bob holds only a CREATE window on the folder {@code team}. Guard: with a writable store
     * bob POSTs {@code job/team/createItem?name=guard-new} with a config.xml carrying an authorization property
     * (bob and carol Item/Configure): 403 with the D-48 message naming the item, the item exists without the
     * property, one GRANT_VIOLATION. Then the same POST for {@code team/new} with
     * {@code batch-control/changes/} unwritable: 403 (not 200, not 500) with the D-48 message naming
     * {@code team/new}; the item exists; its property is removed (no entry for bob or carol, in memory and in
     * the stored {@code config.xml}); carol holds no Configure on it; a WARNING or SEVERE log record.
     */
    @Test
    public void t_02_127_createItemPayloadPropertyIsReportedWhenTheRecordCannotBeWritten() throws Exception {
        StrategyFixtures.grant("bob", "team", Arrays.asList(GrantAction.CREATE));

        int violations = StrategyFixtures.records(ChangeType.GRANT_VIOLATION).size();
        Page guard = createItem("bob", "name=guard-new", PAYLOAD);
        assertReported("guard: createItem with a writable store", guard, "team/guard-new");
        assertPropertyRemoved("team/guard-new", "guard");
        assertEquals(violations + 1, StrategyFixtures.records(ChangeType.GRANT_VIOLATION).size(),
                "guard: one GRANT_VIOLATION record is written with a writable store");

        Fallout fallout = underFault(() -> createItem("bob", "name=new", PAYLOAD));
        assertReported("R3-01, D-48: createItem while the GRANT_VIOLATION record cannot be written", fallout.answer, "team/new");
        assertPropertyRemoved("team/new", "R3-01, D-35c");
        assertFalse(fallout.problems.isEmpty(), "R3-01: the failed GRANT_VIOLATION record must be logged at WARNING or SEVERE");
    }

    /**
     * T-02-128 (P0): as T-02-127; {@code team/src} carries an administrator-set authorization property (bob
     * and carol Item/Configure). Guard: with a writable store bob copies it to {@code team/guard-copy}
     * ({@code createItem?mode=copy}): 403 with the D-48 message, the copy exists without the source's
     * property, one GRANT_VIOLATION. Then bob copies it to {@code team/copy} with
     * {@code batch-control/changes/} unwritable: 403 (not 200, not 500) with the D-48 message naming
     * {@code team/copy}; the copy exists without the property (memory and disk); the source keeps its
     * property; a WARNING or SEVERE log record.
     */
    @Test
    public void t_02_128_copiedPropertyIsReportedWhenTheRecordCannotBeWritten() throws Exception {
        Folder team = j.jenkins.getItemByFullName("team", Folder.class);
        FreeStyleProject src = team.createProject(FreeStyleProject.class, "src");
        AuthorizationMatrixProperty amp = new AuthorizationMatrixProperty(new HashMap<>(), new InheritParentStrategy());
        amp.add(Item.CONFIGURE, PermissionEntry.user("bob"));
        amp.add(Item.CONFIGURE, PermissionEntry.user("carol"));
        src.addProperty(amp);
        assertTrue(has(src, "bob", Item.EXTENDED_READ), "premise: bob may read the source's configuration");
        StrategyFixtures.grant("bob", "team", Arrays.asList(GrantAction.CREATE));

        int violations = StrategyFixtures.records(ChangeType.GRANT_VIOLATION).size();
        Page guard = createItem("bob", "name=guard-copy&mode=copy&from=src", null);
        assertReported("guard: copy with a writable store", guard, "team/guard-copy");
        assertPropertyRemoved("team/guard-copy", "guard");
        assertEquals(violations + 1, StrategyFixtures.records(ChangeType.GRANT_VIOLATION).size(),
                "guard: one GRANT_VIOLATION record is written with a writable store");

        Fallout fallout = underFault(() -> createItem("bob", "name=copy&mode=copy&from=src", null));
        assertReported("R3-01, D-48: copy while the GRANT_VIOLATION record cannot be written", fallout.answer, "team/copy");
        assertPropertyRemoved("team/copy", "R3-01, D-35c");
        assertTrue(has(src, "carol", Item.CONFIGURE), "the source keeps its own property");
        assertFalse(fallout.problems.isEmpty(), "R3-01: the failed GRANT_VIOLATION record must be logged at WARNING or SEVERE");
    }

    // ---------------------------------------------------------------- helpers

    private interface Request {
        Page send() throws Exception;
    }

    private static final class Fallout {
        final Page answer;
        final List<String> problems;

        Fallout(Page answer, List<String> problems) {
            this.answer = answer;
            this.problems = problems;
        }
    }

    private Fallout underFault(Request request) throws Exception {
        try (RecordFaultFixtures.Fault ignored = RecordFaultFixtures.makeUnwritable(
                RecordFaultFixtures.changesDir(j.jenkins.getRootDir().toPath()));
             LogRecorder log = new LogRecorder().record("io.jenkins.plugins.batchcontrol", Level.WARNING).capture(200)) {
            Page answer = request.send();
            List<String> problems = log.getRecords().stream()
                    .filter(r -> r.getLevel().intValue() >= Level.WARNING.intValue())
                    .map(r -> r.getLevel() + " " + r.getMessage())
                    .collect(Collectors.toList());
            return new Fallout(answer, problems);
        }
    }

    /** D-48: 403 with the plain message naming the item (full name or full display name). */
    private void assertReported(String what, Page answer, String fullName) {
        int code = answer.getWebResponse().getStatusCode();
        String text = UsabilityFixtures.text(answer);
        assertEquals(403, code, what + ": the creation whose authorization property the guard removed must answer 403 with the"
                + " D-48 message (the user is told), got HTTP " + code + ": " + UsabilityFixtures.excerpt(text));
        GrantSelfGrantFeedbackTest.assertGuardFeedback(what, text, fullName, fullName.replace("/", " » "));
    }

    /** The item exists and carries no authorization entry for bob or carol, in memory and in its stored config.xml. */
    private void assertPropertyRemoved(String fullName, String what) throws Exception {
        FreeStyleProject item = j.jenkins.getItemByFullName(fullName, FreeStyleProject.class);
        assertNotNull(item, what + ": the item " + fullName + " must be created (only its property is removed)");
        AuthorizationMatrixProperty amp = item.getProperty(AuthorizationMatrixProperty.class);
        if (amp != null) {
            for (Map.Entry<?, Set<PermissionEntry>> e : amp.getGrantedPermissionEntries().entrySet()) {
                assertFalse(e.getValue().contains(PermissionEntry.user("bob")) || e.getValue().contains(PermissionEntry.user("carol")),
                        what + ": the payload's entries must be removed from " + fullName + ": " + e);
            }
        }
        String stored = item.getConfigFile().asString();
        assertFalse(stored.contains(":bob</permission>") || stored.contains(":carol</permission>"),
                what + ": the stored config.xml of " + fullName + " must carry no entry of the payload: " + stored);
        assertFalse(has(item, "carol", Item.CONFIGURE), what + ": carol must hold no Configure on " + fullName);
    }

    private Page createItem(String user, String query, String body) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login(user);
        URL url = new URL(wc.createCrumbedUrl("job/team/createItem").toExternalForm() + "&" + query);
        WebRequest req = new WebRequest(url, HttpMethod.POST);
        if (body != null) {
            req.setAdditionalHeader("Content-Type", "application/xml; charset=UTF-8");
            req.setRequestBody(body);
        }
        return wc.getPage(req);
    }
}
