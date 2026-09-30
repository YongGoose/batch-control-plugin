package io.jenkins.plugins.batchcontrol;

import io.jenkins.plugins.batchcontrol.store.BatchClock;
import hudson.model.FreeStyleProject;
import hudson.plugins.jobConfigHistory.PluginUtils;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.store.FileStore;
import java.time.YearMonth;
import java.util.List;
import java.util.stream.Collectors;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.html.HtmlForm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC item 9, acceptance line "with jobConfigHistory installed, each configuration save still
 * produces exactly one CONFIGURE record" (#36). jobConfigHistory listens to the same save events
 * and writes its own history copy; neither a duplicate nor a missing CONFIGURE record is allowed.
 * Rows T-09-16 .. T-09-18.
 *
 * <p>Premise for every "one record" row: jobConfigHistory itself recorded the save (its revision
 * count for the job grew by one), so the row really runs with the plugin active.
 */
@WithJenkins
public class PluginInteractionJobConfigHistoryTest {

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin"));
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setChangeControlEnabled(true);
        cfg.save();
    }

    /** T-09-16: one REST config.xml save with jobConfigHistory installed -> exactly one CONFIGURE record. */
    @Test
    public void t_09_16_restSaveWritesExactlyOneRecord() throws Exception {
        FreeStyleProject job = j.createFreeStyleProject("jch-rest");
        job.setDescription("jch-before");
        int records = configureRecords("jch-rest").size();
        int revisions = revisions(job);

        String xml = job.getConfigFile().asString()
                .replace("<description>jch-before</description>", "<description>jch-after</description>");
        assertEquals(200, postConfigXml(job, xml));
        assertEquals("jch-after", j.jenkins.getItemByFullName("jch-rest", FreeStyleProject.class).getDescription());

        assertEquals(revisions + 1, revisions(j.jenkins.getItemByFullName("jch-rest", FreeStyleProject.class)), "fixture: jobConfigHistory must have recorded the save");
        assertEquals(records + 1, configureRecords("jch-rest").size(), "one save must produce exactly one CONFIGURE record with jobConfigHistory installed");
    }

    /** T-09-17: one UI configure-form save with jobConfigHistory installed -> exactly one CONFIGURE record. */
    @Test
    public void t_09_17_uiSaveWritesExactlyOneRecord() throws Exception {
        FreeStyleProject job = j.createFreeStyleProject("jch-ui");
        job.setDescription("jch-ui-before");
        int records = configureRecords("jch-ui").size();
        int revisions = revisions(job);

        JenkinsRule.WebClient wc = j.createWebClient().login("admin");
        HtmlForm form = wc.getPage(job, "configure").getFormByName("config");
        form.getTextAreaByName("description").setText("jch-ui-after");
        j.submit(form);
        assertEquals("jch-ui-after", job.getDescription());

        assertEquals(revisions + 1, revisions(job), "fixture: jobConfigHistory must have recorded the save");
        List<ChangeRecord> after = configureRecords("jch-ui");
        assertEquals(records + 1, after.size(), "one UI save must produce exactly one CONFIGURE record with jobConfigHistory installed");
        assertTrue(String.valueOf(after.get(after.size() - 1).getDiff()).contains("jch-ui-after"), "the record's diff must carry the edit");
    }

    /**
     * T-09-18 (negative twin of T-09-16/17): an unchanged save with jobConfigHistory installed
     * writes no CONFIGURE record — jobConfigHistory's own bookkeeping is not a configuration
     * change of the job.
     */
    @Test
    public void t_09_18_unchangedSaveWritesNoRecord() throws Exception {
        FreeStyleProject job = j.createFreeStyleProject("jch-noop");
        job.setDescription("jch-stable");
        int records = configureRecords("jch-noop").size();

        job.save();
        assertEquals(200, postConfigXml(job, job.getConfigFile().asString()));

        assertEquals(records, configureRecords("jch-noop").size(), "an unchanged save must not write a CONFIGURE record with jobConfigHistory installed");
    }

    // ---------------------------------------------------------------- helpers

    private static int revisions(FreeStyleProject job) {
        return PluginUtils.getHistoryDao().getRevisions(job.getConfigFile()).size();
    }

    private int postConfigXml(FreeStyleProject target, String xml) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login("admin");
        WebRequest request = new WebRequest(wc.createCrumbedUrl(target.getUrl() + "config.xml"), HttpMethod.POST);
        request.setAdditionalHeader("Content-Type", "application/xml; charset=UTF-8");
        request.setRequestBody(xml);
        return wc.getPage(request).getWebResponse().getStatusCode();
    }

    private static List<ChangeRecord> configureRecords(String target) {
        return FileStore.get().listChangeRecords(YearMonth.now(BatchClock.clock())).stream()
                .filter(rec -> rec.getType() == ChangeType.CONFIGURE)
                .filter(rec -> target.equals(rec.getTarget()))
                .collect(Collectors.toList());
    }
}
