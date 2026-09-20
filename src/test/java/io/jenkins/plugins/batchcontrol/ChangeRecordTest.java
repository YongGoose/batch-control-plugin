package io.jenkins.plugins.batchcontrol;

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.cli.CLICommandInvoker;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.Items;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.PasswordParameterDefinition;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.util.Secret;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.model.Grant;
import io.jenkins.plugins.batchcontrol.model.GrantAction;
import io.jenkins.plugins.batchcontrol.model.GrantRequest;
import io.jenkins.plugins.batchcontrol.model.GrantScope;
import io.jenkins.plugins.batchcontrol.policy.GrantRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.store.FileStore;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.YearMonth;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import javaposse.jobdsl.plugin.ExecuteDslScripts;
import javaposse.jobdsl.plugin.GlobalJobDslSecurityConfiguration;
import jenkins.model.GlobalConfiguration;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.html.HtmlForm;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * SPEC item 9 (automatic change recording). Matrix rows T-09-01 .. T-09-11.
 *
 * Records are asserted through the FileStore read API only (listChangeRecords + getDiff),
 * never through implementation internals.
 *
 * Written from docs/SPEC.md, docs/ARCHITECTURE.md section 5 and docs/TEST-MATRIX.md only.
 */
public class ChangeRecordTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    private BatchControlGlobalConfiguration cfg;

    @Before
    public void setUp() throws Exception {
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        MockAuthorizationStrategy delegate = new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.REQUEST_GRANT)
                        .everywhere().to("u1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1");
        j.jenkins.setAuthorizationStrategy(new BatchControlAuthorizationStrategy(delegate));

        cfg = BatchControlGlobalConfiguration.get();
        cfg.setChangeControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
    }

    /** T-09-01: a configure through the UI form leaves a CONFIGURE record with a unified diff. */
    @Test
    public void t_09_01_uiConfigureLeavesRecordWithDiff() throws Exception {
        FreeStyleProject job = j.createFreeStyleProject("ui-job");
        job.setDescription("before-ui-change");

        JenkinsRule.WebClient wc = j.createWebClient().login("admin");
        HtmlForm form = wc.getPage(job, "configure").getFormByName("config");
        form.getTextAreaByName("description").setText("after-ui-change");
        j.submit(form);
        assertEquals("after-ui-change", job.getDescription());

        ChangeRecord record = lastRecord(ChangeType.CONFIGURE, "ui-job");
        assertNotNull("a UI configure must leave a CONFIGURE record", record);
        assertEquals("admin", record.getUser());
        assertNotNull(record.getAt());
        String diff = record.getDiff();
        assertNotNull("a CONFIGURE record must carry a unified diff", diff);
        assertTrue("the diff must be in unified format (hunk markers)", diff.contains("@@"));
        assertTrue("the diff must contain the new value as an addition",
                diff.contains("after-ui-change"));
        assertTrue("the diff must contain the old value as a removal",
                diff.contains("before-ui-change"));
    }

    /** T-09-02: a REST config.xml POST leaves a CONFIGURE record. */
    @Test
    public void t_09_02_restConfigXmlPostLeavesRecord() throws Exception {
        FreeStyleProject job = j.createFreeStyleProject("rest-job");
        job.setDescription("rest-before");

        assertEquals(200, postConfigXml("admin", job,
                describedXml(job, "rest-before", "rest-after")));
        assertEquals("rest-after", job.getDescription());

        ChangeRecord record = lastRecord(ChangeType.CONFIGURE, "rest-job");
        assertNotNull("a REST config.xml POST must leave a CONFIGURE record", record);
        assertEquals("admin", record.getUser());
        assertNotNull(record.getAt());
    }

    /** T-09-03: a change made inside an active grant window carries the grant id. */
    @Test
    public void t_09_03_changeInsideGrantWindowLinksGrantId() throws Exception {
        FreeStyleProject job = j.createFreeStyleProject("grant-job");
        job.setDescription("grant-before");
        Grant grant = grantTo("u1", new GrantScope(GrantScope.Type.JOB, "grant-job"),
                Arrays.asList(GrantAction.CONFIGURE), 30);

        assertEquals(200, postConfigXml("u1", job,
                describedXml(job, "grant-before", "grant-after")));
        assertEquals("grant-after", job.getDescription());

        ChangeRecord record = lastRecord(ChangeType.CONFIGURE, "grant-job");
        assertNotNull(record);
        assertEquals("u1", record.getUser());
        assertEquals("the change must be linked to the active grant it was made under",
                grant.getId(), record.getGrantId());
    }

    /** T-09-04: a password/Secret default change never leaks a plaintext value; the diff shows the mask. */
    @Test
    public void t_09_04_secretValuesAreMaskedInDiff() throws Exception {
        FreeStyleProject job = j.createFreeStyleProject("secret-job");
        job.addProperty(new ParametersDefinitionProperty(
                new PasswordParameterDefinition("PW",
                        Secret.fromString("OLD-PLAINTEXT-SECRET"), "credential")));

        // swap the stored default for a new plaintext value; Jenkins re-encrypts it on save
        String xml = job.getConfigFile().asString();
        String newXml = xml.replaceFirst("<defaultValue>[^<]*</defaultValue>",
                "<defaultValue>NEW-PLAINTEXT-SECRET</defaultValue>");
        assertFalse("test precondition: the swap must have changed the XML", newXml.equals(xml));
        assertEquals(200, postConfigXml("admin", job, newXml));

        List<ChangeRecord> records = records(ChangeType.CONFIGURE, "secret-job");
        assertFalse("the secret change must be recorded", records.isEmpty());
        for (ChangeRecord record : records) {
            String diff = record.getDiff();
            if (diff == null) {
                continue;
            }
            assertFalse("no diff may ever contain the old secret in plaintext",
                    diff.contains("OLD-PLAINTEXT-SECRET"));
            assertFalse("no diff may ever contain the new secret in plaintext",
                    diff.contains("NEW-PLAINTEXT-SECRET"));
        }
        ChangeRecord last = records.get(records.size() - 1);
        assertNotNull("the CONFIGURE record must carry a diff", last.getDiff());
        assertTrue("the secret value must be masked as ******** in the diff",
                last.getDiff().contains("********"));
    }

    /** T-09-05: CLI update-job leaves a CONFIGURE record. */
    @Test
    public void t_09_05_cliUpdateJobLeavesRecord() throws Exception {
        FreeStyleProject job = j.createFreeStyleProject("cli-job");
        job.setDescription("cli-before");
        String newXml = describedXml(job, "cli-before", "cli-after");

        CLICommandInvoker.Result result = new CLICommandInvoker(j, "update-job")
                .asUser("admin")
                .withStdin(new ByteArrayInputStream(newXml.getBytes(StandardCharsets.UTF_8)))
                .invokeWithArgs("cli-job");
        assertEquals("update-job must succeed: " + result.stderr(), 0, result.returnCode());
        assertEquals("cli-after", job.getDescription());

        ChangeRecord record = lastRecord(ChangeType.CONFIGURE, "cli-job");
        assertNotNull("a CLI update-job must leave a CONFIGURE record", record);
        assertEquals("admin", record.getUser());
    }

    /** T-09-06: a Job DSL seed run that modifies a job leaves a CONFIGURE record. */
    @Test
    public void t_09_06_jobDslUpdateLeavesRecord() throws Exception {
        GlobalConfiguration.all().get(GlobalJobDslSecurityConfiguration.class)
                .setUseScriptSecurity(false);

        FreeStyleProject target = j.createFreeStyleProject("dsl-target");
        target.setDescription("dsl v1");

        FreeStyleProject seed = j.createFreeStyleProject("dsl-seed");
        ExecuteDslScripts dsl = new ExecuteDslScripts();
        dsl.setScriptText("job('dsl-target') { description('dsl v2') }");
        seed.getBuildersList().add(dsl);

        j.buildAndAssertSuccess(seed);
        assertEquals("dsl v2",
                j.jenkins.getItemByFullName("dsl-target", FreeStyleProject.class).getDescription());

        ChangeRecord record = lastRecord(ChangeType.CONFIGURE, "dsl-target");
        assertNotNull("a Job DSL update must leave a CONFIGURE record", record);
        assertNotNull(record.getAt());
    }

    /** T-09-07: create and delete each leave their record with accurate user and time. */
    @Test
    public void t_09_07_createAndDeleteAreRecordedWithUser() throws Exception {
        try (ACLContext ignored = as("admin")) {
            FreeStyleProject born = j.jenkins.createProject(FreeStyleProject.class, "born-job");

            ChangeRecord created = lastRecord(ChangeType.CREATE, "born-job");
            assertNotNull("creating a job must leave a CREATE record", created);
            assertEquals("admin", created.getUser());
            assertNotNull(created.getAt());

            born.delete();
        }
        assertNull(j.jenkins.getItemByFullName("born-job"));

        ChangeRecord deleted = lastRecord(ChangeType.DELETE, "born-job");
        assertNotNull("deleting a job must leave a DELETE record", deleted);
        assertEquals("admin", deleted.getUser());
        assertNotNull(deleted.getAt());
    }

    /** T-09-08: rename and move each leave their record. */
    @Test
    public void t_09_08_renameAndMoveAreRecorded() throws Exception {
        FreeStyleProject job = j.createFreeStyleProject("old-name-job");
        Folder folder = j.jenkins.createProject(Folder.class, "dest-folder");

        try (ACLContext ignored = as("admin")) {
            job.renameTo("new-name-job");
        }
        ChangeRecord renamed = lastRecord(ChangeType.RENAME, null);
        assertNotNull("renaming a job must leave a RENAME record", renamed);
        assertEquals("admin", renamed.getUser());
        String renameText = renamed.getTarget() + " " + renamed.getDetail();
        assertTrue("the RENAME record must reference the old name", renameText.contains("old-name-job"));
        assertTrue("the RENAME record must reference the new name", renameText.contains("new-name-job"));

        try (ACLContext ignored = as("admin")) {
            Items.move(job, folder);
        }
        assertNotNull(j.jenkins.getItemByFullName("dest-folder/new-name-job"));
        ChangeRecord moved = lastRecord(ChangeType.MOVE, null);
        assertNotNull("moving a job must leave a MOVE record", moved);
        assertEquals("admin", moved.getUser());
        assertTrue("the MOVE record must reference the new location",
                (moved.getTarget() + " " + moved.getDetail()).contains("dest-folder/new-name-job"));
    }

    /** T-09-09: a change made without any active grant is stored with grantId=null. */
    @Test
    public void t_09_09_changeWithoutGrantHasNullGrantId() throws Exception {
        FreeStyleProject job = j.createFreeStyleProject("plain-job");
        job.setDescription("plain-before");

        assertEquals(200, postConfigXml("admin", job,
                describedXml(job, "plain-before", "plain-after")));

        ChangeRecord record = lastRecord(ChangeType.CONFIGURE, "plain-job");
        assertNotNull(record);
        assertNull("without an active grant the record must carry grantId=null so it can be "
                + "queried as an ungranted change", record.getGrantId());
    }

    /** T-09-10: recording stays active when only run control is on (records follow either switch). */
    @Test
    public void t_09_10_recordingActiveWithRunControlOnly() throws Exception {
        cfg.setChangeControlEnabled(false);
        cfg.setRunControlEnabled(true);
        cfg.save();

        FreeStyleProject job = j.createFreeStyleProject("runonly-job");
        job.setDescription("runonly-before");
        assertEquals(200, postConfigXml("admin", job,
                describedXml(job, "runonly-before", "runonly-after")));

        assertNotNull("recording must stay active while any switch is on",
                lastRecord(ChangeType.CONFIGURE, "runonly-job"));
    }

    /** T-09-11: with both switches off no change record is written. */
    @Test
    public void t_09_11_noRecordWhenBothSwitchesOff() throws Exception {
        cfg.setChangeControlEnabled(false);
        cfg.setRunControlEnabled(false);
        cfg.save();

        FreeStyleProject job = j.createFreeStyleProject("off-job");
        job.setDescription("off-before");
        assertEquals(200, postConfigXml("admin", job,
                describedXml(job, "off-before", "off-after")));
        assertEquals("off-after", job.getDescription());

        assertTrue("with both switches off no CONFIGURE record may be written",
                records(ChangeType.CONFIGURE, "off-job").isEmpty());
        assertTrue("with both switches off no CREATE record may be written either",
                records(ChangeType.CREATE, "off-job").isEmpty());
    }

    // ---------------------------------------------------------------- helpers

    private ACLContext as(String userId) {
        return ACL.as2(User.getById(userId, true).impersonate2());
    }

    private Grant grantTo(String userId, GrantScope scope, List<GrantAction> actions, int minutes) {
        GrantRequest request;
        try (ACLContext ignored = as(userId)) {
            request = GrantRequestService.get().create(scope, actions, minutes, "maintenance", "a1");
        }
        try (ACLContext ignored = as("a1")) {
            return GrantRequestService.get().approve(request.getId(), "ok");
        }
    }

    /** The job's config.xml with the description element swapped. */
    private static String describedXml(FreeStyleProject job, String oldDescription, String newDescription)
            throws Exception {
        return job.getConfigFile().asString()
                .replace("<description>" + oldDescription + "</description>",
                        "<description>" + newDescription + "</description>");
    }

    private int postConfigXml(String userId, FreeStyleProject target, String xml) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient()
                .withThrowExceptionOnFailingStatusCode(false)
                .login(userId);
        WebRequest request = new WebRequest(
                wc.createCrumbedUrl(target.getUrl() + "config.xml"), HttpMethod.POST);
        request.setAdditionalHeader("Content-Type", "application/xml; charset=UTF-8");
        request.setRequestBody(xml);
        return wc.getPage(request).getWebResponse().getStatusCode();
    }

    /** All records of the given type; target=null matches any target. */
    private List<ChangeRecord> records(ChangeType type, String target) {
        return FileStore.get().listChangeRecords(YearMonth.now()).stream()
                .filter(rec -> rec.getType() == type)
                .filter(rec -> target == null || target.equals(rec.getTarget()))
                .collect(Collectors.toList());
    }

    private ChangeRecord lastRecord(ChangeType type, String target) {
        List<ChangeRecord> matching = records(type, target);
        return matching.isEmpty() ? null : matching.get(matching.size() - 1);
    }
}
