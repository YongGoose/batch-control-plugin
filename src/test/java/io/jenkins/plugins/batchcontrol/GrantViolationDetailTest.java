package io.jenkins.plugins.batchcontrol;

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.security.AuthorizationMatrixProperty;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.model.GrantAction;
import io.jenkins.plugins.batchcontrol.security.BatchControlMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.security.GrantService;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.regex.Pattern;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.WebResponse;
import org.htmlunit.util.NameValuePair;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.jenkinsci.plugins.matrixauth.inheritance.InheritParentStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.assertSuccess;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.client;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.decideGrant;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.records;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.submitGrantOk;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC items 2 and 8 (D-35b, D-35c, D-40), e2e-03 DEF-04: a GRANT_VIOLATION record is read by
 * operators on Change Records and in {@code changes.csv}; its wording carries no internal design
 * reference ("(D-40)", "SPEC item 12"). Matrix row T-08-47 (note 119).
 *
 * <p>The four violation paths SPEC names are produced once each by bob (who has no native
 * Create/Configure) under the Batch Control matrix strategy: a creation outside a name
 * restriction, a rename of an item he created to a non-matching name, a creation payload
 * carrying an authorization property, and a grant-only Configure that writes an authorization
 * entry. Each record is checked, and then every row of {@code changes.csv}.
 *
 * <p>Written from docs/SPEC.md, docs/reports/e2e-03.md and docs/TEST-MATRIX.md only
 * (no src/main knowledge).
 */
@WithJenkins
public class GrantViolationDetailTest {

    private static final String MINIMAL_JOB_XML = "<?xml version='1.1' encoding='UTF-8'?>"
            + "<project><builders/><publishers/><buildWrappers/></project>";

    private static final String PAYLOAD_JOB_XML = "<?xml version='1.1' encoding='UTF-8'?><project>"
            + "<properties><hudson.security.AuthorizationMatrixProperty>"
            + "<inheritanceStrategy class=\"org.jenkinsci.plugins.matrixauth.inheritance.InheritParentStrategy\"/>"
            + "<permission>USER:hudson.model.Item.Configure:bob</permission>"
            + "</hudson.security.AuthorizationMatrixProperty></properties>"
            + "<builders/><publishers/><buildWrappers/></project>";

    /** Internal design references that must not reach an operator. */
    private static final Pattern INTERNAL_REFERENCE = Pattern.compile("\\(D-|SPEC|\\bD-\\d+");

    private JenkinsRule j;
    private Folder team;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(StrategyFixtures.matrix(new BatchControlMatrixAuthorizationStrategy()));
        StrategyFixtures.changeControlOn();
        team = j.jenkins.createProject(Folder.class, "team");
    }

    /**
     * T-08-47 (DEF-04): no GRANT_VIOLATION detail and no row of {@code changes.csv} contains
     * "(D-", "SPEC" or a D-number; every detail still says something (it is not blanked).
     */
    @Test
    public void t_08_47_grantViolationWordingCarriesNoInternalReference() throws Exception {
        // a restricted CREATE grant on the folder
        String requestId = submitGrantOk(j, "bob", "team", Arrays.asList("CREATE"), 30,
                "create the app jobs", "/app-[0-9]+/", "a1");
        assertSuccess(decideGrant(j, "a1", requestId, "approve", "ok"), "fixture: approval by a1");
        assertTrue(GrantService.get().listActive().stream().anyMatch(g -> "bob".equals(g.getUser())),
                "fixture: bob must hold an active grant");

        // 1. creation outside the name restriction
        int refused = createItem("bob", "other-name", MINIMAL_JOB_XML);
        assertTrue(refused >= 400 && refused < 500, "fixture: the non-matching creation must be refused, got " + refused);
        // 2. a creation payload carrying an authorization property (the item is created, the property removed)
        // D-48 ruling: the item is created, its payload property is stripped, and bob is told with
        // a 403 and the D-48 message (note 153)
        org.htmlunit.Page payload = createItemPage("bob", "app-1", PAYLOAD_JOB_XML);
        assertEquals(403, payload.getWebResponse().getStatusCode(), "a creation whose payload authorization property"
                + " the guard stripped must answer 403 (D-48), got " + payload.getWebResponse().getStatusCode());
        GrantSelfGrantFeedbackTest.assertGuardFeedback("createItem with an authorization payload",
                UsabilityFixtures.text(payload), "team/app-1", "team » app-1");
        assertNotNull(team.getItem("app-1"), "fixture: team/app-1 must exist");
        // 3. renaming the created item to a non-matching name
        List<NameValuePair> rename = new ArrayList<>();
        rename.add(new NameValuePair("newName", "evil-name"));
        WebResponse renamed = ApproverFormFixtures.post(j, "bob", team.getUrl() + "job/app-1/confirmRename", rename);
        assertTrue(renamed.getStatusCode() >= 400, "fixture: the non-matching rename must be refused, got " + renamed.getStatusCode());
        assertNull(team.getItem("evil-name"), "fixture: no item may carry the non-matching name");
        // 4. grant-only Configure writing an authorization entry (D-35b)
        FreeStyleProject solo = j.createFreeStyleProject("solo");
        AuthorizationMatrixProperty amp = new AuthorizationMatrixProperty(new HashMap<>(), new InheritParentStrategy());
        amp.add(Item.CONFIGURE, PermissionEntry.user("alice"));
        solo.addProperty(amp);
        StrategyFixtures.grant("bob", "solo", Arrays.asList(GrantAction.CONFIGURE));
        String close = "</hudson.security.AuthorizationMatrixProperty>";
        String xml = solo.getConfigFile().asString();
        assertTrue(xml.contains(close), "fixture: solo must carry an authorization property");
        postConfigXml("bob", solo, xml.replace(close, "<permission>USER:hudson.model.Item.Configure:bob</permission>" + close));

        List<ChangeRecord> violations = records(ChangeType.GRANT_VIOLATION);
        assertEquals(4, violations.size(), "fixture: each of the four paths records one GRANT_VIOLATION, got " + describe(violations));
        for (ChangeRecord v : violations) {
            assertNotNull(v.getDetail(), "a GRANT_VIOLATION must carry a detail: " + describe(v));
            assertFalse(v.getDetail().isBlank(), "a GRANT_VIOLATION detail must not be blank: " + describe(v));
            assertFalse(INTERNAL_REFERENCE.matcher(v.getDetail()).find(),
                    "a GRANT_VIOLATION detail must not carry an internal design reference: " + describe(v));
        }

        WebResponse csv = ApproverFormFixtures.get(j, "admin", "batch-control/history/changes.csv");
        assertEquals(200, csv.getStatusCode(), "the administrator must download changes.csv");
        String body = csv.getContentAsString();
        assertTrue(body.contains("GRANT_VIOLATION"), "fixture: changes.csv must carry the GRANT_VIOLATION rows:\n" + body);
        for (String line : body.split("\\R")) {
            assertFalse(INTERNAL_REFERENCE.matcher(line).find(), "no row of changes.csv may carry an internal design"
                    + " reference: " + line);
        }
    }

    // ---------------------------------------------------------------- helpers

    private int createItem(String userId, String name, String xml) throws Exception {
        return createItemPage(userId, name, xml).getWebResponse().getStatusCode();
    }

    private org.htmlunit.Page createItemPage(String userId, String name, String xml) throws Exception {
        JenkinsRule.WebClient wc = client(j, userId);
        URL url = new URL(wc.createCrumbedUrl(team.getUrl() + "createItem").toExternalForm()
                + "&name=" + URLEncoder.encode(name, StandardCharsets.UTF_8));
        WebRequest request = new WebRequest(url, HttpMethod.POST);
        request.setAdditionalHeader("Content-Type", "application/xml; charset=UTF-8");
        request.setRequestBody(xml);
        return wc.getPage(request);
    }

    private int postConfigXml(String userId, FreeStyleProject job, String xml) throws Exception {
        JenkinsRule.WebClient wc = client(j, userId);
        WebRequest request = new WebRequest(wc.createCrumbedUrl(job.getUrl() + "config.xml"), HttpMethod.POST);
        request.setAdditionalHeader("Content-Type", "application/xml; charset=UTF-8");
        request.setRequestBody(xml);
        return wc.getPage(request).getWebResponse().getStatusCode();
    }

    private static String describe(ChangeRecord r) {
        return r.getType() + " user=" + r.getUser() + " target=" + r.getTarget() + " detail=" + r.getDetail();
    }

    private static String describe(List<ChangeRecord> records) {
        List<String> out = new ArrayList<>();
        records.forEach(r -> out.add(describe(r)));
        return out.toString();
    }
}
