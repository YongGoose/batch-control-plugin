package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.Queue;
import hudson.scm.NullSCM;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import javaposse.jobdsl.plugin.ExecuteDslScripts;
import javaposse.jobdsl.plugin.GlobalJobDslSecurityConfiguration;
import jenkins.branch.BranchSource;
import jenkins.branch.OrganizationFolder;
import jenkins.model.GlobalConfiguration;
import jenkins.model.Jenkins;
import jenkins.scm.impl.SingleSCMSource;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.WebResponse;
import org.htmlunit.util.NameValuePair;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.excerpt;
import static io.jenkins.plugins.batchcontrol.SnapshotBaselineGapTest.describe;
import static io.jenkins.plugins.batchcontrol.SnapshotBaselineGapTest.postConfigXml;
import static io.jenkins.plugins.batchcontrol.SnapshotBaselineGapTest.records;
import static io.jenkins.plugins.batchcontrol.SnapshotBaselineGapTest.withDescription;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Creating an item writes its CREATE record and nothing else: the saves Jenkins and Batch Control make while an
 * item is being created are part of the creation, not configuration changes. Matrix rows T-GAP-409 ..
 * T-GAP-415 (note 281).
 *
 * <p>Basis: SPEC 9 ("UI, REST({@code config.xml} POST), CLI, Job DSL 경로의 변경이 모두 ChangeRecord로 남는다", "a save
 * that changes no user-editable configuration writes no CONFIGURE record", "a computed folder ... saving
 * itself during indexing writes no CONFIGURE record unless its user-editable configuration changed"); SPEC 8
 * (D-31, D-34: a job created while run control is on starts locked, whatever the creation path); DECISIONS D-76
 * (2): "Saves made while an item is being created (including an organization folder or multibranch project
 * created from config.xml) are part of its CREATE, not CONFIGURE records."
 *
 * <p>Both switches are on, so the D-31/D-34 lock of a new job is applied during each job creation. Every row
 * ends with a guard: a real change of the new item afterwards writes a CONFIGURE record whose diff adds the new
 * description, so "no CONFIGURE record" cannot come from recording that does not work for the item.
 *
 * <p>The CLI is the real client ({@code hudson.cli.CLI}) in a child JVM over {@code -http}, as in
 * {@link WindowCreateCliGapTest}. Job DSL is on the test classpath, so its variant is included. The computed
 * folder rows T-GAP-414 and T-GAP-415 were relayed as "being fixed on another branch": they stay red until that
 * fix lands (note 281).
 *
 * <p>Written from docs/SPEC.md items 8 and 9 and docs/DECISIONS.md D-31, D-34 and D-76 only (no src/main
 * knowledge).
 */
@WithJenkins
public class CreationRecordGapTest {

    private static final String JOB_XML = "<?xml version='1.1' encoding='UTF-8'?><project>"
            + "<description>created from xml</description>"
            + "<triggers><hudson.triggers.TimerTrigger><spec>0 3 * * *</spec></hudson.triggers.TimerTrigger></triggers>"
            + "<builders/><publishers/><buildWrappers/></project>";

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin"));
    }

    /**
     * T-GAP-409 (SPEC 9 UI path; D-76 (2); D-31/D-34): both switches on; the administrator creates
     * {@code form-new} through the New Item form ({@code createItem} with {@code name} and {@code mode}). One
     * CREATE record and no CONFIGURE record for {@code form-new}. Guard: a real change then writes one.
     */
    @Test
    public void t_gap_409_newItemFormWritesOnlyCreate() throws Exception {
        switchesOn();
        WebResponse created = ApproverFormFixtures.post(j, "admin", "createItem", Arrays.asList(
                new NameValuePair("name", "form-new"), new NameValuePair("mode", FreeStyleProject.class.getName())));
        assertTrue(created.getStatusCode() < 400, "fixture: the New Item form creates the job, got " + created.getStatusCode()
                + ": " + excerpt(created.getContentAsString()));
        assertCreatedOnly("form-new", "the New Item form");
    }

    /**
     * T-GAP-410 (SPEC 9 REST path; D-76 (2); D-31/D-34): both switches on; the administrator creates
     * {@code xml-new} through {@code createItem} with a {@code config.xml} body (description and a cron trigger).
     * One CREATE record and no CONFIGURE record. Guard: a real change then writes one.
     */
    @Test
    public void t_gap_410_createItemWithXmlWritesOnlyCreate() throws Exception {
        switchesOn();
        int code = createFromXml("", "xml-new", JOB_XML);
        assertTrue(code < 400, "fixture: createItem with XML creates the job, got " + code);
        assertCreatedOnly("xml-new", "createItem with config.xml");
    }

    /**
     * T-GAP-411 (SPEC 9; D-76 (2); D-31/D-34): both switches on; job {@code copy-src} exists; the administrator
     * copies it to {@code copy-new} ({@code createItem} with {@code mode=copy}). One CREATE record and no
     * CONFIGURE record for {@code copy-new}, and the copy writes no CONFIGURE record for {@code copy-src}. Guard:
     * a real change of the copy then writes one.
     */
    @Test
    public void t_gap_411_copyWritesOnlyCreate() throws Exception {
        switchesOn();
        FreeStyleProject source = j.createFreeStyleProject("copy-src");
        source.setDescription("the copied job");
        int sourceConfigure = records(ChangeType.CONFIGURE, "copy-src").size();
        WebResponse copied = ApproverFormFixtures.post(j, "admin", "createItem", Arrays.asList(
                new NameValuePair("name", "copy-new"), new NameValuePair("mode", "copy"), new NameValuePair("from", "copy-src")));
        assertTrue(copied.getStatusCode() < 400, "fixture: the copy is created, got " + copied.getStatusCode() + ": "
                + excerpt(copied.getContentAsString()));
        assertEquals(sourceConfigure, records(ChangeType.CONFIGURE, "copy-src").size(),
                "SPEC 9: copying writes no CONFIGURE record for the source: " + describe(records(ChangeType.CONFIGURE, "copy-src")));
        assertCreatedOnly("copy-new", "a copy");
    }

    /**
     * T-GAP-412 (SPEC 9 CLI path; D-76 (2); D-31/D-34): both switches on; the administrator runs
     * {@code create-job cli-new} on the real CLI over {@code -http} with a {@code config.xml} on stdin. One
     * CREATE record and no CONFIGURE record. Guard: a real change then writes one.
     */
    @Test
    public void t_gap_412_cliCreateJobWritesOnlyCreate() throws Exception {
        switchesOn();
        WindowCreateCliGapTest.CliResult result = WindowCreateCliGapTest.cli(j, "admin", JOB_XML, "create-job", "cli-new");
        assertEquals(0, result.code, "fixture: create-job succeeds: " + result);
        assertCreatedOnly("cli-new", "CLI create-job");
    }

    /**
     * T-GAP-413 (SPEC 9 Job DSL path; D-76 (2); D-31/D-34): a seed job (created while both switches were off, so
     * that its own build is not what the row measures) generates {@code dsl-new}; both switches on; the seed
     * runs. One CREATE record and no CONFIGURE record for {@code dsl-new}. Guard: a real change then writes one.
     */
    @Test
    public void t_gap_413_jobDslSeedWritesOnlyCreate() throws Exception {
        GlobalConfiguration.all().get(GlobalJobDslSecurityConfiguration.class).setUseScriptSecurity(false);
        FreeStyleProject seed = j.createFreeStyleProject("dsl-seed");
        ExecuteDslScripts dsl = new ExecuteDslScripts();
        dsl.setScriptText("job('dsl-new') {\n  description('generated by the seed')\n  triggers { cron('0 3 * * *') }\n}");
        seed.getBuildersList().add(dsl);
        switchesOn();
        j.buildAndAssertSuccess(seed);
        assertCreatedOnly("dsl-new", "a Job DSL seed run");
    }

    /**
     * T-GAP-414 (D-76 (2) "including ... a multibranch project created from config.xml"; SPEC 9 indexing line):
     * the {@code config.xml} of a multibranch project with one branch source ({@code main}, NullSCM) is taken
     * from a template made while both switches were off; both switches on; the administrator creates
     * {@code xml-mb} through {@code createItem} with that XML. One CREATE record and no CONFIGURE record for
     * {@code xml-mb}, also after an indexing run. Guard: a real change then writes a CONFIGURE record with a diff.
     * Red until the D-76 (2) computed-folder fix (note 281).
     */
    @Test
    public void t_gap_414_multibranchProjectCreatedFromXmlWritesOnlyCreate() throws Exception {
        WorkflowMultiBranchProject template = j.jenkins.createProject(WorkflowMultiBranchProject.class, "tmpl-mb");
        template.getSourcesList().add(new BranchSource(new SingleSCMSource("main", new NullSCM())));
        template.save();
        j.waitUntilNoActivity();
        String xml = template.getConfigFile().asString();
        switchesOn();

        int code = createFromXml("", "xml-mb", xml);
        assertTrue(code < 400, "fixture: createItem with the multibranch XML creates the project, got " + code);
        j.waitUntilNoActivity();
        WorkflowMultiBranchProject created = j.jenkins.getItemByFullName("xml-mb", WorkflowMultiBranchProject.class);
        assertNotNull(created, "premise: xml-mb is a multibranch project");
        assertCreateOneConfigureNone("xml-mb", "a multibranch project created from config.xml");

        Queue.Item indexing = created.scheduleBuild2(0);
        assertNotNull(indexing, "fixture: indexing can be scheduled");
        indexing.getFuture().get();
        j.waitUntilNoActivity();
        assertNotNull(created.getItem("main"), "premise: indexing created the branch job");
        assertEquals(0, records(ChangeType.CONFIGURE, "xml-mb").size(), "SPEC 9: indexing the new project writes no CONFIGURE record: "
                + describe(records(ChangeType.CONFIGURE, "xml-mb")));

        assertRealChangeRecorded("xml-mb", false);
    }

    /**
     * T-GAP-415 (D-76 (2) "including an organization folder ... created from config.xml"): the
     * {@code config.xml} of an organization folder is taken from a template made while both switches were off;
     * both switches on; the administrator creates {@code xml-org} through {@code createItem} with that XML. One
     * CREATE record and no CONFIGURE record for {@code xml-org}. Guard: a real change then writes a CONFIGURE
     * record with a diff. Red until the D-76 (2) computed-folder fix (note 281).
     */
    @Test
    public void t_gap_415_organizationFolderCreatedFromXmlWritesOnlyCreate() throws Exception {
        OrganizationFolder template = j.jenkins.createProject(OrganizationFolder.class, "tmpl-org");
        template.save();
        j.waitUntilNoActivity();
        String xml = template.getConfigFile().asString();
        switchesOn();

        int code = createFromXml("", "xml-org", xml);
        assertTrue(code < 400, "fixture: createItem with the organization folder XML creates it, got " + code);
        j.waitUntilNoActivity();
        assertNotNull(j.jenkins.getItemByFullName("xml-org", OrganizationFolder.class), "premise: xml-org is an organization folder");
        assertCreateOneConfigureNone("xml-org", "an organization folder created from config.xml");

        assertRealChangeRecorded("xml-org", false);
    }

    // ------------------------------------------------------------------ helpers

    private void switchesOn() throws Exception {
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setChangeControlEnabled(true);
        cfg.save();
        assertTrue(BatchControlGlobalConfiguration.get().isRunControlEnabled() && BatchControlGlobalConfiguration.get().isChangeControlEnabled(),
                "fixture: both switches are on");
    }

    /** One CREATE, no CONFIGURE, then the guard. */
    private void assertCreatedOnly(String name, String path) throws Exception {
        j.waitUntilNoActivity();
        assertNotNull(j.jenkins.getItemByFullName(name), "premise: " + path + " created " + name);
        assertCreateOneConfigureNone(name, path);
        assertRealChangeRecorded(name, true);
    }

    private void assertCreateOneConfigureNone(String name, String path) {
        List<ChangeRecord> create = records(ChangeType.CREATE, name);
        List<ChangeRecord> configure = records(ChangeType.CONFIGURE, name);
        System.out.println("T-GAP creation observation (" + path + "): CREATE " + create.size() + ", CONFIGURE " + configure.size()
                + " " + describe(configure));
        assertEquals(1, create.size(), "SPEC 9: " + path + " writes exactly one CREATE record: " + describe(create));
        assertEquals(0, configure.size(), "D-76 (2): the saves made while " + path + " creates the item are part of its CREATE, not"
                + " CONFIGURE records: " + describe(configure));
    }

    /**
     * Guard: a real change of the new item writes a CONFIGURE record whose diff adds the new description; for a job
     * exactly one (SPEC 9, #36). For a computed folder the count is printed, not asserted: a config.xml POST may
     * start an indexing of its own, and the rows of the computed folders are about their creation (note 281).
     */
    private void assertRealChangeRecorded(String name, boolean exactlyOne) throws Exception {
        Item item = j.jenkins.getItemByFullName(name);
        assertNotNull(item, "fixture: " + name + " exists");
        String xml = ((hudson.model.AbstractItem) item).getConfigFile().asString();
        String edited = withDescription(xml, "guard edit of " + name);
        assertTrue(!edited.equals(xml), "fixture: the guard edit changes the XML of " + name);
        assertEquals(200, postConfigXml(j, "admin", item, edited), "fixture: the guard edit of " + name + " is saved");
        List<ChangeRecord> configure = records(ChangeType.CONFIGURE, name);
        System.out.println("T-GAP creation guard (" + name + "): CONFIGURE " + configure.size() + " after one real change");
        List<ChangeRecord> withDiff = configure.stream().filter(r -> r.getDiff() != null && r.getDiff().lines()
                .anyMatch(l -> l.startsWith("+") && !l.startsWith("+++") && l.contains("guard edit of " + name)))
                .collect(Collectors.toList());
        assertTrue(!withDiff.isEmpty(), "guard (SPEC 9): a real change of " + name + " writes a CONFIGURE record whose diff adds the"
                + " description: " + describe(configure));
        if (exactlyOne) {
            assertEquals(1, configure.size(), "guard (SPEC 9): one real change of the job writes exactly one CONFIGURE record: "
                    + describe(configure));
        }
        // a config.xml POST to a multibranch project schedules an indexing (branch-api MultiBranchProject#onLoad); let it finish so teardown does not delete indexing.log while it is open (Windows refuses)
        j.waitUntilNoActivity();
    }

    private int createFromXml(String parentUrl, String name, String xml) throws Exception {
        JenkinsRule.WebClient wc = ApproverFormFixtures.client(j, "admin");
        URL url = new URL(wc.createCrumbedUrl(parentUrl + "createItem").toExternalForm()
                + "&name=" + URLEncoder.encode(name, StandardCharsets.UTF_8));
        WebRequest request = new WebRequest(url, HttpMethod.POST);
        request.setAdditionalHeader("Content-Type", "application/xml; charset=UTF-8");
        request.setRequestBody(xml);
        WebResponse response = wc.getPage(request).getWebResponse();
        if (response.getStatusCode() >= 400) {
            System.out.println("createItem " + name + " answered " + response.getStatusCode() + ": " + excerpt(response.getContentAsString()));
        }
        return response.getStatusCode();
    }
}
