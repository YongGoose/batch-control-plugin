package io.jenkins.plugins.batchcontrol;

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.model.FreeStyleProject;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.model.GrantAction;
import io.jenkins.plugins.batchcontrol.model.GrantScope;
import io.jenkins.plugins.batchcontrol.security.BatchControlMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.model.Grant;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.html.HtmlForm;
import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.client;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.records;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC item 9 ("변경 시각에 변경자의 활성 Grant가 있으면 grantId가 연결되고, 없으면 grantId=null"),
 * read with SPEC item 2 / D-35c (a Create grant confers Configure on the item it created during
 * the window), e2e-03 DEF-05: a CONFIGURE made by the holder of an active CREATE grant on an
 * item that grant created is a change under that grant and carries its id. Matrix rows T-09-19
 * and T-09-20 (note 120).
 *
 * <p>bob holds only a FOLDER {@code team} CREATE grant; c1 holds native Item/Configure
 * (StrategyFixtures). Written from docs/SPEC.md, docs/reports/e2e-03.md and
 * docs/TEST-MATRIX.md only (no src/main knowledge).
 */
@WithJenkins
public class ChangeRecordCreateGrantLinkTest {

    private static final String MINIMAL_JOB_XML = "<?xml version='1.1' encoding='UTF-8'?>"
            + "<project><description>first</description><builders/><publishers/><buildWrappers/></project>";

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
     * T-09-19 (DEF-05, A-20): bob creates {@code team/app-2} under his CREATE grant, then saves
     * it twice (REST config.xml and the configure form). The CREATE record and both CONFIGURE
     * records carry the grant's id.
     */
    @Test
    public void t_09_19_configureOfItemCreatedUnderCreateGrantCarriesThatGrantId() throws Exception {
        Grant grant = StrategyFixtures.grant("bob", GrantScope.Type.FOLDER, "team", Arrays.asList(GrantAction.CREATE));

        assertTrue(createItem("bob", "app-2") < 400, "fixture: bob must create team/app-2 under the grant");
        FreeStyleProject created = (FreeStyleProject) team.getItem("app-2");
        assertNotNull(created, "fixture: team/app-2 must exist");

        List<ChangeRecord> creates = forTarget(ChangeType.CREATE, "team/app-2");
        assertEquals(1, creates.size(), "fixture: one CREATE record for team/app-2: " + creates);
        assertEquals(grant.getId(), creates.get(0).getGrantId(), "control: the CREATE record carries the grant id");

        String xml = created.getConfigFile().asString().replace("<description>first</description>",
                "<description>second</description>");
        int code = postConfigXml("bob", created, xml);
        assertTrue(code < 400, "fixture: bob may configure the item his grant created (D-35c), got " + code);
        assertEquals("second", created.getDescription(), "fixture: the REST save must apply");

        List<ChangeRecord> configures = forTarget(ChangeType.CONFIGURE, "team/app-2");
        assertEquals(1, configures.size(), "the REST save must write one CONFIGURE record: " + describe(configures));
        assertEquals("bob", configures.get(0).getUser());
        assertEquals(grant.getId(), configures.get(0).getGrantId(), "the CONFIGURE made under the CREATE grant must"
                + " carry that grant's id, not \"no grant\"");

        JenkinsRule.WebClient wc = j.createWebClient().login("bob");
        HtmlPage configure = wc.getPage(created, "configure");
        HtmlForm form = configure.getFormByName("config");
        form.getTextAreaByName("description").setText("third");
        j.submit(form);
        assertEquals("third", created.getDescription(), "fixture: the form save must apply");

        configures = forTarget(ChangeType.CONFIGURE, "team/app-2");
        assertEquals(2, configures.size(), "the form save must write a second CONFIGURE record: " + describe(configures));
        for (ChangeRecord record : configures) {
            assertEquals(grant.getId(), record.getGrantId(), "every CONFIGURE by bob under the CREATE grant carries its id: "
                    + describe(configures));
        }
    }

    /**
     * T-09-20 (DEF-05, negative twin): a CONFIGURE of the same item by c1, who holds native
     * Configure and no grant, carries no grant id — bob's grant is not attached to someone
     * else's change.
     */
    @Test
    public void t_09_20_configureByUserWithoutGrantCarriesNoGrantId() throws Exception {
        StrategyFixtures.grant("bob", GrantScope.Type.FOLDER, "team", Arrays.asList(GrantAction.CREATE));
        assertTrue(createItem("bob", "app-3") < 400, "fixture: bob must create team/app-3 under the grant");
        FreeStyleProject created = (FreeStyleProject) team.getItem("app-3");
        assertNotNull(created);

        String xml = created.getConfigFile().asString().replace("<description>first</description>",
                "<description>by c1</description>");
        assertTrue(postConfigXml("c1", created, xml) < 400, "fixture: c1 may configure with native Configure");
        assertEquals("by c1", created.getDescription());

        List<ChangeRecord> configures = forTarget(ChangeType.CONFIGURE, "team/app-3");
        assertEquals(1, configures.size(), "one CONFIGURE record: " + describe(configures));
        assertEquals("c1", configures.get(0).getUser());
        assertNull(configures.get(0).getGrantId(), "a change by a user without a grant must carry grantId=null");
    }

    // ---------------------------------------------------------------- helpers

    private int createItem(String userId, String name) throws Exception {
        JenkinsRule.WebClient wc = client(j, userId);
        URL url = new URL(wc.createCrumbedUrl(team.getUrl() + "createItem").toExternalForm()
                + "&name=" + URLEncoder.encode(name, StandardCharsets.UTF_8));
        WebRequest request = new WebRequest(url, HttpMethod.POST);
        request.setAdditionalHeader("Content-Type", "application/xml; charset=UTF-8");
        request.setRequestBody(MINIMAL_JOB_XML);
        return wc.getPage(request).getWebResponse().getStatusCode();
    }

    private int postConfigXml(String userId, FreeStyleProject job, String xml) throws Exception {
        JenkinsRule.WebClient wc = client(j, userId);
        WebRequest request = new WebRequest(wc.createCrumbedUrl(job.getUrl() + "config.xml"), HttpMethod.POST);
        request.setAdditionalHeader("Content-Type", "application/xml; charset=UTF-8");
        request.setRequestBody(xml);
        return wc.getPage(request).getWebResponse().getStatusCode();
    }

    private static List<ChangeRecord> forTarget(ChangeType type, String target) {
        return records(type).stream().filter(r -> target.equals(r.getTarget())).collect(Collectors.toList());
    }

    private static String describe(List<ChangeRecord> records) {
        return records.stream().map(r -> r.getType() + " user=" + r.getUser() + " grantId=" + r.getGrantId())
                .collect(Collectors.joining("; ", "[", "]"));
    }
}
