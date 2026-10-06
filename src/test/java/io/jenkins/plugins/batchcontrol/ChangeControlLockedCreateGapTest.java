package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleProject;
import hudson.model.Item;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import io.jenkins.plugins.batchcontrol.store.FileStore;
import java.net.URL;
import java.time.YearMonth;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage lane 2, scenario L2-17 (matrix rows T-GAP-264 and T-GAP-265, note 277): creating a job that
 * already carries the lock, and a {@code config.xml} with a comment and a CDATA section.
 *
 * <p>Basis: SPEC item 8 D-34 "실행 통제가 켜져 있으면, 새로 생성되는 잡은 {@code blockTimer=true}·
 * {@code blockUpstream=true} 로도 시작한다" and D-31 ({@code approvalRequired=true}), LIMITATIONS 13 (and an
 * empty {@code allowedUpstreamJobs}); SPEC item 9 #20 "a save that changes no user-editable configuration
 * writes no CONFIGURE record"; SPEC item 9 (a configuration change writes a CONFIGURE record).
 *
 * <p>The job property's XML is taken from a job the test configures through the public API and saves, so
 * the creation payload uses the plugin's own stored form.
 *
 * <p>Written from docs/SPEC.md, docs/DECISIONS.md D-31/D-34/P-14, docs/LIMITATIONS.md and
 * docs/TEST-MATRIX.md only (no src/main knowledge).
 */
@WithJenkins
public class ChangeControlLockedCreateGapTest {

    private static final String PROPERTY = "io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty";

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, Item.BUILD, BatchControlPermissions.REQUEST).everywhere().to("u1"));
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("admin"));
        cfg.save();
    }

    /**
     * T-GAP-264 (L2-17, SPEC 8 D-31/D-34, SPEC 9 #20): with run control on, the administrator creates
     * {@code locked-in} through {@code createItem} with a config.xml whose job property already has
     * approvalRequired, blockTimer and blockUpstream on and no allowed upstream jobs: the job keeps exactly
     * that and no CONFIGURE record is written for it. Guard: a real change of the job afterwards writes one
     * CONFIGURE record, and u1's manual build is refused.
     */
    @Test
    public void t_gap_264_creatingAnAlreadyLockedJobWritesNoConfigureRecord() throws Exception {
        String property = lockedPropertyXml();
        String payload = "<?xml version='1.1' encoding='UTF-8'?><project><description>born locked</description><properties>"
                + property + "</properties><builders/><publishers/><buildWrappers/></project>";
        int code = createItem("locked-in", payload);
        assertTrue(code < 400, "the creation must succeed, got " + code);
        FreeStyleProject job = j.jenkins.getItemByFullName("locked-in", FreeStyleProject.class);
        assertNotNull(job, "the job must exist");
        List<BatchControlJobProperty> props = job.getAllProperties().stream().filter(p -> p instanceof BatchControlJobProperty)
                .map(p -> (BatchControlJobProperty) p).collect(Collectors.toList());
        assertEquals(1, props.size(), "the job must carry exactly one Batch Control property");
        BatchControlJobProperty p = props.get(0);
        assertTrue(p.isApprovalRequired() && p.isBlockTimer() && p.isBlockUpstream(), "the lock must be kept");
        assertTrue(p.getAllowedUpstreamJobs() == null || p.getAllowedUpstreamJobs().isEmpty(), "no allowed upstream jobs");
        assertEquals(0, configureRecords("locked-in").size(), "creating an already locked job writes no CONFIGURE record: "
                + configureRecords("locked-in"));

        int next = job.getNextBuildNumber();
        PluginInteractionFixtures.post(j, "u1", job.getUrl() + "build?delay=0sec");
        PluginInteractionFixtures.assertBlocked(j, job, next, 0);

        String xml = job.getConfigFile().asString().replace("<description>born locked</description>",
                "<description>changed later</description>");
        assertEquals(200, postConfigXml(job, xml), "fixture: the later change is saved");
        assertEquals(1, configureRecords("locked-in").size(), "guard: a real change writes one CONFIGURE record");
    }

    /**
     * T-GAP-265 (L2-17 (u), SPEC 9 #20): the administrator POSTs a config.xml of {@code cdata-job} whose
     * description is a CDATA section and which carries an XML comment: one CONFIGURE record. The same
     * content POSTed again writes none.
     */
    @Test
    public void t_gap_265_commentAndCdataConfigIsRecordedOnceThenNotAgain() throws Exception {
        FreeStyleProject job = j.createFreeStyleProject("cdata-job");
        int before = configureRecords("cdata-job").size();
        String xml = "<?xml version='1.1' encoding='UTF-8'?>\n<!-- edited by a script -->\n<project>"
                + "<description><![CDATA[a <b>bold</b> & plain description]]></description>"
                + "<!-- the builders follow --><builders/><publishers/><buildWrappers/></project>";
        assertEquals(200, postConfigXml(job, xml), "the first POST is saved");
        assertEquals("a <b>bold</b> & plain description", j.jenkins.getItemByFullName("cdata-job", FreeStyleProject.class)
                .getDescription(), "fixture: the CDATA description is stored");
        assertEquals(before + 1, configureRecords("cdata-job").size(), "the first POST writes one CONFIGURE record");
        assertEquals(200, postConfigXml(job, xml), "the second POST is answered normally");
        assertEquals(before + 1, configureRecords("cdata-job").size(), "the same content again writes no CONFIGURE record");
    }

    // ---------------------------------------------------------------- helpers

    /** The stored XML of a job property with approvalRequired, blockTimer and blockUpstream on (and no upstream list). */
    private String lockedPropertyXml() throws Exception {
        FreeStyleProject template = j.createFreeStyleProject("template-lock");
        BatchControlJobProperty p = BatchControlFixtures.setBatchControl(template, new BatchControlJobProperty(true));
        p.setBlockTimer(true);
        p.setBlockUpstream(true);
        template.save();
        String xml = template.getConfigFile().asString();
        int start = xml.indexOf("<" + PROPERTY);
        int end = xml.indexOf("</" + PROPERTY + ">");
        assertTrue(start >= 0 && end > start, "fixture: the saved job must carry the property: " + xml);
        return xml.substring(start, end + PROPERTY.length() + 3);
    }

    private int createItem(String name, String xml) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login("admin");
        WebRequest req = new WebRequest(new URL(wc.createCrumbedUrl("createItem").toExternalForm() + "&name=" + name), HttpMethod.POST);
        req.setAdditionalHeader("Content-Type", "application/xml; charset=UTF-8");
        req.setRequestBody(xml);
        return wc.getPage(req).getWebResponse().getStatusCode();
    }

    private int postConfigXml(FreeStyleProject job, String xml) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login("admin");
        WebRequest req = new WebRequest(wc.createCrumbedUrl(job.getUrl() + "config.xml"), HttpMethod.POST);
        req.setAdditionalHeader("Content-Type", "application/xml; charset=UTF-8");
        req.setRequestBody(xml);
        return wc.getPage(req).getWebResponse().getStatusCode();
    }

    private static List<ChangeRecord> configureRecords(String target) {
        return FileStore.get().listChangeRecords(YearMonth.now(BatchClock.clock())).stream()
                .filter(r -> r.getType() == ChangeType.CONFIGURE && target.equals(r.getTarget()))
                .collect(Collectors.toList());
    }
}
