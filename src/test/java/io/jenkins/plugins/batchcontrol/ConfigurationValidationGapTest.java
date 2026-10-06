package io.jenkins.plugins.batchcontrol;

import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.security.HudsonPrivateSecurityRealm;
import hudson.security.SecurityRealm;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import io.jenkins.plugins.batchcontrol.store.FileStore;
import java.io.ByteArrayInputStream;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.htmlunit.WebResponse;
import org.htmlunit.html.HtmlForm;
import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.StrategyFixtures.T0;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Coverage lane 2, scenario L2-12 (matrix rows T-GAP-248 .. T-GAP-253, note 277): validation and setters
 * of the Batch Control global configuration.
 *
 * <p>Basis: SPEC item 2 "{@code Manage}가 없는 사용자는 전역 설정·결재자 목록을 바꿀 수 없다"; SPEC 6
 * usability "invalid input is refused with a message next to the field" and "The BatchControl/Manage
 * permission is enough to open and save the Batch Control configuration"; SPEC 5 table (defaults); SPEC 8
 * ({@code grantDurationOptions}, {@code maxGrantMinutes} is the upper limit); the D-53 line ("an approver
 * id that names no existing user and that the security realm does not resolve is refused ... If the realm
 * cannot be asked the id is accepted with a warning. An empty approver list is refused while either
 * switch is on"); SPEC 1 ("스위치 on/off 시 ChangeRecord(type=CONFIG_TOGGLE, 사용자, 시각, 이전/이후 값)") and
 * the D-42 line ("A direct setter call (JCasC, script console) never throws: it applies the value");
 * SPEC 2 (the configuration round-trips through JCasC); D-52 ({@code CONFIG_CHANGE}); ARCHITECTURE
 * section 1 (recording is independent of control).
 *
 * <p>The field checks are POSTed with a crumb to
 * {@code descriptorByName/io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration/check<Field>},
 * as the configuration page does (ConfigUsabilityTest, ConfigAuditTest).
 *
 * <p>Written from docs/SPEC.md, docs/DECISIONS.md, docs/ARCHITECTURE.md and docs/TEST-MATRIX.md only (no
 * src/main knowledge).
 */
@WithJenkins
public class ConfigurationValidationGapTest {

    private static final String DESCRIPTOR =
            "descriptorByName/io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration/";

    private JenkinsRule j;
    private Path restoreDir;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, BatchControlPermissions.MANAGE).everywhere().to("manager")
                .grant(Jenkins.READ).everywhere().to("reader"));
        BatchClock.setForTest(Clock.fixed(T0, ZoneOffset.UTC));
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setApprovers(Arrays.asList("admin"));
        cfg.save();
    }

    @AfterEach
    public void tearDown() throws Exception {
        if (restoreDir != null && Files.isDirectory(restoreDir)) {
            try (Stream<Path> walk = Files.walk(restoreDir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
        BatchClock.reset();
    }

    /**
     * T-GAP-248 (L2-12, SPEC 2 "Manage가 없는 사용자는 전역 설정 ... 바꿀 수 없다"): reader (Overall/Read,
     * no Manage) POSTs every field check with an invalid value (pending timeout, approved-run timeout,
     * max grant minutes, retention, notify-before-expiry, both duration-option checks, both
     * incident-result checks): no answer carries a validation error or a server error, so nothing about the
     * configuration is checked for reader. Guard: the same calls by the manager answer the error.
     */
    @Test
    public void t_gap_248_fieldChecksGiveNoVerdictWithoutManage() throws Exception {
        String[][] checks = {
            {"checkPendingTimeoutHours", "0"},
            {"checkApprovedRunTimeoutMinutes", "-1"},
            {"checkMaxGrantMinutes", "0"},
            {"checkRetentionMonths", "0"},
            {"checkNotifyBeforeExpiryMinutes", "-1"},
            {"checkGrantDurationOptions", "15,abc"},
            {"checkGrantDurationOptionsText", "15,abc"},
            {"checkIncidentResults", "FAILURE, BOGUS"},
            {"checkIncidentResultsText", "FAILURE, BOGUS"},
        };
        for (String[] check : checks) {
            WebResponse reader = check("reader", check[0], "value=" + enc(check[1]));
            assertTrue(reader.getStatusCode() < 500, check[0] + " must not fail with a server error for reader, got "
                    + reader.getStatusCode());
            assertFalse(isError(reader), check[0] + " must give reader no validation verdict: "
                    + UsabilityFixtures.excerpt(reader.getContentAsString()));
            WebResponse manager = check("manager", check[0], "value=" + enc(check[1]));
            assertEquals(200, manager.getStatusCode(), "guard: " + check[0] + " is served to the manager");
            assertTrue(isError(manager), "guard: " + check[0] + " answers the manager with an error for '" + check[1] + "': "
                    + UsabilityFixtures.excerpt(manager.getContentAsString()));
        }
    }

    /**
     * T-GAP-249 (L2-12, SPEC 6 "invalid input is refused with a message next to the field", SPEC 8, D-53):
     * for the manager, the checks answer an error for an empty value and {@code abc} on a whole-number
     * field, empty duration options, a duration of 0, a duration above {@code maxGrantMinutes}, empty
     * incident results and an approver id of 257 characters; durations checked with an unparsable
     * {@code maxGrantMinutes} answer a validation (no server error); an empty approver list is accepted
     * while both switches are off. Guards: valid values answer no error, and the empty approver list is an
     * error while run control is on.
     */
    @Test
    public void t_gap_249_managerChecksRefuseInvalidValuesWithAMessage() throws Exception {
        assertError("checkPendingTimeoutHours", "value=");
        assertError("checkPendingTimeoutHours", "value=abc");
        assertNoError("checkPendingTimeoutHours", "value=24");
        for (String field : new String[] {"checkGrantDurationOptions", "checkGrantDurationOptionsText"}) {
            assertError(field, "value=");
            assertError(field, "value=" + enc("0, 15"));
            assertError(field, "value=" + enc("15, 300") + "&maxGrantMinutes=240");
            assertNoError(field, "value=" + enc("15, 30") + "&maxGrantMinutes=240");
            WebResponse odd = check("manager", field, "value=" + enc("15, 30") + "&maxGrantMinutes=x");
            assertEquals(200, odd.getStatusCode(), field + " with an unparsable maxGrantMinutes must still answer a validation");
            UsabilityFixtures.assertPlainRefusal(field + " with maxGrantMinutes=x", odd.getContentAsString(), null);
        }
        for (String field : new String[] {"checkIncidentResults", "checkIncidentResultsText"}) {
            assertError(field, "value=");
            assertNoError(field, "value=" + enc("FAILURE, UNSTABLE"));
        }
        assertError("checkApproversText", "value=" + "x".repeat(257));

        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        assertFalse(cfg.isRunControlEnabled() || cfg.isChangeControlEnabled(), "premise: both switches are off");
        assertNoError("checkApproversText", "value=");
        cfg.setRunControlEnabled(true);
        cfg.save();
        assertError("checkApproversText", "value=");
    }

    /**
     * T-GAP-250 (L2-12, D-53 "If the realm cannot be asked the id is accepted with a warning"): with the
     * security realm "none" an unknown approver id is answered with a warning, not an error; with a test
     * realm whose user lookup throws an unexpected exception, the same. Guard: Jenkins' own user database
     * answers an error for an id it does not know (T-CFG-07).
     */
    @Test
    public void t_gap_250_unaskableRealmGivesAWarning() throws Exception {
        j.jenkins.setSecurityRealm(SecurityRealm.NO_AUTHENTICATION);
        j.jenkins.setAuthorizationStrategy(hudson.security.AuthorizationStrategy.UNSECURED);
        WebResponse none = check(null, "checkApproversText", "value=unknown-zz1");
        assertWarningOnly(none, "realm none");

        BrokenRealm broken = new BrokenRealm();
        for (String id : new String[] {"admin", "manager"}) {
            broken.createAccount(id, id);
        }
        j.jenkins.setSecurityRealm(broken);
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, BatchControlPermissions.MANAGE).everywhere().to("manager"));
        WebResponse thrown = check("manager", "checkApproversText", "value=boom-user");
        assertWarningOnly(thrown, "realm lookup throwing");

        HudsonPrivateSecurityRealm own = new HudsonPrivateSecurityRealm(false, false, null);
        for (String id : new String[] {"admin", "manager"}) {
            own.createAccount(id, id);
        }
        j.jenkins.setSecurityRealm(own);
        assertTrue(isError(check("manager", "checkApproversText", "value=unknown-zz2")),
                "guard: Jenkins' own user database refuses an id it does not know");
    }

    /**
     * T-GAP-251 (L2-12, SPEC 1 CONFIG_TOGGLE with before/after values): calling the run-control setter
     * twice with {@code true}, and the change-control setter twice with {@code true}, writes one
     * CONFIG_TOGGLE record per switch, not two.
     */
    @Test
    public void t_gap_251_sameValueSetTwiceWritesOneToggleRecord() throws Exception {
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        int before = toggles().size();
        try (ACLContext ignored = ACL.as2(hudson.model.User.getById("admin", true).impersonate2())) {
            cfg.setRunControlEnabled(true);
            cfg.setRunControlEnabled(true);
        }
        assertEquals(before + 1, toggles().size(), "setting run control on twice writes one CONFIG_TOGGLE record: " + toggles());
        try (ACLContext ignored = ACL.as2(hudson.model.User.getById("admin", true).impersonate2())) {
            cfg.setChangeControlEnabled(true);
            cfg.setChangeControlEnabled(true);
        }
        assertEquals(before + 2, toggles().size(), "setting change control on twice writes one more CONFIG_TOGGLE record: " + toggles());
        assertTrue(cfg.isRunControlEnabled() && cfg.isChangeControlEnabled(), "both switches are on");
    }

    /**
     * T-GAP-252 (L2-12, SPEC 2 JCasC round trip, D-42 "applies the value"): applying
     * {@code unclassified: batchControl: grantDurationOptionsText: "15, abc, 30"} through JCasC does not
     * throw and leaves the options 15 and 30.
     */
    @Test
    public void t_gap_252_cascDurationTextKeepsTheValidOptions() throws Exception {
        String yaml = "unclassified:\n  batchControl:\n    grantDurationOptionsText: \"15, abc, 30\"\n";
        try {
            io.jenkins.plugins.casc.ConfigurationAsCode.get().configureWith(io.jenkins.plugins.casc.yaml.YamlSource.of(
                    new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8))));
        } catch (Exception e) {
            fail("a JCasC apply of the duration options must not throw: " + e);
        }
        assertEquals(Arrays.asList(15, 30), BatchControlGlobalConfiguration.get().getGrantDurationOptions(),
                "the valid options 15 and 30 must be applied");
    }

    /**
     * T-GAP-253 (L2-12 (F), ARCHITECTURE 1, D-52): the month's change file ({@code changes/2026-09.jsonl})
     * is replaced by a non-empty directory, so the CONFIG_CHANGE record cannot be written; the manager's
     * save of the configuration page changing the pending timeout (72 to 48) is still saved.
     */
    @Test
    public void t_gap_253_settingIsSavedWhenItsRecordCannotBeWritten() throws Exception {
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true); // a CONFIG_TOGGLE record, so the month's change file exists
        cfg.save();
        Path month = j.jenkins.getRootDir().toPath().resolve("batch-control/changes/" + YearMonth.from(T0.atZone(ZoneOffset.UTC)) + ".jsonl");
        assertTrue(Files.isRegularFile(month), "premise (ARCHITECTURE 5): the month's change file is " + month);
        assertEquals(72, cfg.getPendingTimeoutHours(), "fixture: the default pending timeout");

        HtmlForm form = configForm("manager");
        Files.delete(month);
        Files.createDirectories(month);
        Files.writeString(month.resolve("keep"), "x", StandardCharsets.UTF_8);
        restoreDir = month;
        UsabilityFixtures.setField(form, "pendingTimeoutHours", "48");
        Page saved = j.submit(form);
        assertTrue(saved.getWebResponse().getStatusCode() < 500, "the save must not fail because its record cannot be written, got "
                + saved.getWebResponse().getStatusCode());
        assertEquals(48, BatchControlGlobalConfiguration.get().getPendingTimeoutHours(), "the changed setting must be saved");
        assertTrue(Files.isDirectory(month), "premise: the change file was not writable during the save");
    }

    // ---------------------------------------------------------------- helpers

    /** A user database whose lookup of {@code boom-user} fails with an unexpected exception. */
    public static class BrokenRealm extends HudsonPrivateSecurityRealm {
        public BrokenRealm() {
            super(false, false, null);
        }

        @Override
        public org.springframework.security.core.userdetails.UserDetails loadUserByUsername2(String username) {
            if ("boom-user".equals(username)) {
                throw new IllegalStateException("test: the realm is broken");
            }
            return super.loadUserByUsername2(username);
        }

        @Override
        public hudson.model.Descriptor<SecurityRealm> getDescriptor() {
            return Jenkins.get().getDescriptorByType(HudsonPrivateSecurityRealm.DescriptorImpl.class);
        }
    }

    private HtmlForm configForm(String userId) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login(userId);
        Page page = wc.getPage(new WebRequest(new URL(j.getURL(), "batch-control-configuration/"), HttpMethod.GET));
        assertEquals(200, page.getWebResponse().getStatusCode(), "fixture: " + userId + " opens the configuration");
        for (HtmlForm form : ((HtmlPage) page).getForms()) {
            if (UsabilityFixtures.hasField(form, "pendingTimeoutHours")) {
                return form;
            }
        }
        fail("fixture: the configuration page must carry the settings form");
        return null;
    }

    private WebResponse check(String user, String field, String query) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false);
        if (user != null) {
            wc.login(user);
        }
        URL url = new URL(wc.createCrumbedUrl(DESCRIPTOR + field).toExternalForm() + "&" + query);
        return wc.getPage(new WebRequest(url, HttpMethod.POST)).getWebResponse();
    }

    private void assertError(String field, String query) throws Exception {
        WebResponse r = check("manager", field, query);
        assertEquals(200, r.getStatusCode(), field + "?" + abbreviate(query) + " must be served");
        assertTrue(isError(r), field + "?" + abbreviate(query) + " must answer an error next to the field: "
                + UsabilityFixtures.excerpt(r.getContentAsString()));
    }

    private void assertNoError(String field, String query) throws Exception {
        WebResponse r = check("manager", field, query);
        assertEquals(200, r.getStatusCode(), field + "?" + abbreviate(query) + " must be served");
        assertFalse(isError(r), field + "?" + abbreviate(query) + " must not answer an error: "
                + UsabilityFixtures.excerpt(r.getContentAsString()));
    }

    private static void assertWarningOnly(WebResponse r, String what) {
        assertEquals(200, r.getStatusCode(), what + ": the check must be served");
        String body = r.getContentAsString();
        assertTrue(body.contains("warning"), what + ": the check must answer a warning: " + UsabilityFixtures.excerpt(body));
        assertFalse(isError(r), what + ": not an error: " + UsabilityFixtures.excerpt(body));
    }

    private static boolean isError(WebResponse r) {
        String body = r.getContentAsString();
        return body.contains("class=\"error\"") || body.contains("class=error") || body.contains("class='error'");
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String abbreviate(String query) {
        return query.length() > 60 ? query.substring(0, 60) + "..." : query;
    }

    private static List<ChangeRecord> toggles() {
        return FileStore.get().listChangeRecords(YearMonth.now(BatchClock.clock())).stream()
                .filter(r -> r.getType() == ChangeType.CONFIG_TOGGLE).collect(Collectors.toList());
    }
}
