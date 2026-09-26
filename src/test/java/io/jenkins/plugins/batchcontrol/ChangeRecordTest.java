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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.jvnet.hudson.test.MockAuthorizationStrategy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC item 9 (automatic change recording). Matrix rows T-09-01 .. T-09-11.
 *
 * Records are asserted through the FileStore read API only (listChangeRecords + getDiff),
 * never through implementation internals.
 *
 * Written from docs/SPEC.md, docs/ARCHITECTURE.md section 5 and docs/TEST-MATRIX.md only.
 */
@WithJenkins
public class ChangeRecordTest {

    private JenkinsRule j;

    private BatchControlGlobalConfiguration cfg;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
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
        assertNotNull(record, "a UI configure must leave a CONFIGURE record");
        assertEquals("admin", record.getUser());
        assertNotNull(record.getAt());
        String diff = record.getDiff();
        assertNotNull(diff, "a CONFIGURE record must carry a unified diff");
        assertTrue(diff.contains("@@"), "the diff must be in unified format (hunk markers)");
        assertTrue(diff.contains("after-ui-change"), "the diff must contain the new value as an addition");
        assertTrue(diff.contains("before-ui-change"), "the diff must contain the old value as a removal");
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
        assertNotNull(record, "a REST config.xml POST must leave a CONFIGURE record");
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
        assertEquals(grant.getId(), record.getGrantId(), "the change must be linked to the active grant it was made under");
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
        assertFalse(newXml.equals(xml), "test precondition: the swap must have changed the XML");
        assertEquals(200, postConfigXml("admin", job, newXml));

        List<ChangeRecord> records = records(ChangeType.CONFIGURE, "secret-job");
        assertFalse(records.isEmpty(), "the secret change must be recorded");
        for (ChangeRecord record : records) {
            String diff = record.getDiff();
            if (diff == null) {
                continue;
            }
            assertFalse(diff.contains("OLD-PLAINTEXT-SECRET"), "no diff may ever contain the old secret in plaintext");
            assertFalse(diff.contains("NEW-PLAINTEXT-SECRET"), "no diff may ever contain the new secret in plaintext");
        }
        ChangeRecord last = records.get(records.size() - 1);
        assertNotNull(last.getDiff(), "the CONFIGURE record must carry a diff");
        assertTrue(last.getDiff().contains("********"), "the secret value must be masked as ******** in the diff");
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
        assertEquals(0, result.returnCode(), "update-job must succeed: " + result.stderr());
        assertEquals("cli-after", job.getDescription());

        ChangeRecord record = lastRecord(ChangeType.CONFIGURE, "cli-job");
        assertNotNull(record, "a CLI update-job must leave a CONFIGURE record");
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
        assertNotNull(record, "a Job DSL update must leave a CONFIGURE record");
        assertNotNull(record.getAt());
    }

    /** T-09-07: create and delete each leave their record with accurate user and time. */
    @Test
    public void t_09_07_createAndDeleteAreRecordedWithUser() throws Exception {
        try (ACLContext ignored = as("admin")) {
            FreeStyleProject born = j.jenkins.createProject(FreeStyleProject.class, "born-job");

            ChangeRecord created = lastRecord(ChangeType.CREATE, "born-job");
            assertNotNull(created, "creating a job must leave a CREATE record");
            assertEquals("admin", created.getUser());
            assertNotNull(created.getAt());

            born.delete();
        }
        assertNull(j.jenkins.getItemByFullName("born-job"));

        ChangeRecord deleted = lastRecord(ChangeType.DELETE, "born-job");
        assertNotNull(deleted, "deleting a job must leave a DELETE record");
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
        assertNotNull(renamed, "renaming a job must leave a RENAME record");
        assertEquals("admin", renamed.getUser());
        String renameText = renamed.getTarget() + " " + renamed.getDetail();
        assertTrue(renameText.contains("old-name-job"), "the RENAME record must reference the old name");
        assertTrue(renameText.contains("new-name-job"), "the RENAME record must reference the new name");

        try (ACLContext ignored = as("admin")) {
            Items.move(job, folder);
        }
        assertNotNull(j.jenkins.getItemByFullName("dest-folder/new-name-job"));
        ChangeRecord moved = lastRecord(ChangeType.MOVE, null);
        assertNotNull(moved, "moving a job must leave a MOVE record");
        assertEquals("admin", moved.getUser());
        assertTrue((moved.getTarget() + " " + moved.getDetail()).contains("dest-folder/new-name-job"), "the MOVE record must reference the new location");
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
        assertNull(record.getGrantId(), "without an active grant the record must carry grantId=null so it can be "
                + "queried as an ungranted change");
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

        assertNotNull(lastRecord(ChangeType.CONFIGURE, "runonly-job"), "recording must stay active while any switch is on");
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

        assertTrue(records(ChangeType.CONFIGURE, "off-job").isEmpty(), "with both switches off no CONFIGURE record may be written");
        assertTrue(records(ChangeType.CREATE, "off-job").isEmpty(), "with both switches off no CREATE record may be written either");
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
