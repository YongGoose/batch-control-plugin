package io.jenkins.plugins.batchcontrol;

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.cli.CLICommandInvoker;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.Job;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.triggers.TimerTrigger;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.model.GrantAction;
import io.jenkins.plugins.batchcontrol.model.GrantRequest;
import io.jenkins.plugins.batchcontrol.model.GrantScope;
import io.jenkins.plugins.batchcontrol.policy.ActivationService;
import io.jenkins.plugins.batchcontrol.policy.GrantRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.casc.ConfigurationAsCode;
import io.jenkins.plugins.casc.yaml.YamlSource;
import java.io.ByteArrayInputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import javaposse.jobdsl.plugin.GlobalJobDslSecurityConfiguration;
import jenkins.model.GlobalConfiguration;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.HtmlCheckBoxInput;
import org.htmlunit.html.HtmlForm;
import org.htmlunit.html.HtmlInput;
import org.htmlunit.html.HtmlPage;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.ActivationFixtures.CRON_FREESTYLE_XML;
import static io.jenkins.plugins.batchcontrol.ActivationFixtures.assertBlocked;
import static io.jenkins.plugins.batchcontrol.ActivationFixtures.isActivated;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.token;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC item 6a (#15, D-39): no configuration write path can activate a job. Each row creates a
 * job under run control (so it starts locked, D-34), clears {@code blockTimer} and
 * {@code blockUpstream} through one write path, reads the switches back to prove the write took
 * effect (the premise), and then asserts the job is still not activated, its timer is refused
 * with the blocking baseline, and no ACTIVATED record or activation request exists. Matrix rows
 * T-06a-10 (web form), T-06a-11 (REST config.xml), T-06a-12 (CLI update-job), T-06a-13 (script
 * / programmatic setter), T-06a-14 (JCasC via Job DSL) and T-06a-15 (a CREATE+CONFIGURE
 * permission window holder — the scenario #15 was opened for).
 *
 * <p>The REST and CLI rows edit the job's own config.xml and rely on the property's switches
 * being serialised as {@code <blockTimer>}/{@code <blockUpstream>} elements (the field names
 * SPEC uses); the fixture asserts that before editing, so a different serialisation is reported
 * as a fixture failure rather than a silent pass (note 93).
 *
 * <p>Written from docs/SPEC.md item 6a, docs/DESIGN-ACTIVATION-APPROVAL.md section 3 and
 * docs/DECISIONS.md D-39 only (no src/main knowledge).
 */
@WithJenkins
public class ActivationConfigPathTest {

    private JenkinsRule j;

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

        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setChangeControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
        GlobalConfiguration.all().get(GlobalJobDslSecurityConfiguration.class).setUseScriptSecurity(false);
    }

    /** T-06a-10 (P0): the administrator unticks both switches on the job's configuration page. */
    @Test
    public void t_06a_10_webConfigFormDoesNotActivate() throws Exception {
        FreeStyleProject job = createAsAdmin("", "path-form");
        JenkinsRule.WebClient admin = j.createWebClient().login("admin");
        HtmlPage page = admin.getPage(job, "configure");
        HtmlForm form = page.getFormByName("config");
        untick(form, "blockTimer");
        untick(form, "blockUpstream");
        j.submit(form);

        assertNotActivatedAfterClearing(job, "the web configuration form");
    }

    /** T-06a-11 (P0): the administrator POSTs an edited config.xml. */
    @Test
    @Tag("core")
    public void t_06a_11_restConfigXmlDoesNotActivate() throws Exception {
        FreeStyleProject job = createAsAdmin("", "path-rest");
        JenkinsRule.WebClient admin = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login("admin");
        WebRequest request = new WebRequest(admin.createCrumbedUrl(job.getUrl() + "config.xml"), HttpMethod.POST);
        request.setAdditionalHeader("Content-Type", "application/xml; charset=UTF-8");
        request.setRequestBody(clearedConfigXml(job));
        int code = admin.getPage(request).getWebResponse().getStatusCode();
        assertEquals(200, code, "the administrator's config.xml POST must succeed");

        assertNotActivatedAfterClearing(reload(job), "a REST config.xml POST");
    }

    /** T-06a-12 (P0): the administrator runs CLI update-job with an edited config.xml. */
    @Test
    public void t_06a_12_cliUpdateJobDoesNotActivate() throws Exception {
        FreeStyleProject job = createAsAdmin("", "path-cli");
        CLICommandInvoker.Result result = new CLICommandInvoker(j, "update-job")
                .asUser("admin")
                .withStdin(new ByteArrayInputStream(clearedConfigXml(job).getBytes(StandardCharsets.UTF_8)))
                .invokeWithArgs("path-cli");
        assertEquals(0, result.returnCode(), "update-job must succeed: " + result.stderr());

        assertNotActivatedAfterClearing(reload(job), "CLI update-job");
    }

    /**
     * T-06a-13 (P0): a script (the script console, a Groovy init script or any plugin) calls
     * {@code setBlockTimer(false)} / {@code setBlockUpstream(false)} and saves the job — the
     * path the configuration form does not see at all.
     */
    @Test
    public void t_06a_13_scriptSetterDoesNotActivate() throws Exception {
        FreeStyleProject job = createAsAdmin("", "path-script");
        try (ACLContext ignored = ACL.as2(token("admin"))) {
            BatchControlJobProperty property = job.getProperty(BatchControlJobProperty.class);
            assertNotNull(property, "premise: the job created under run control carries the property");
            property.setBlockTimer(false);
            property.setBlockUpstream(false);
            job.save();
        }
        assertNotActivatedAfterClearing(job, "a script calling setBlockTimer(false)");

        // the same through the real script console endpoint
        FreeStyleProject viaConsole = createAsAdmin("", "path-console");
        String groovy = "def job = jenkins.model.Jenkins.get().getItemByFullName('path-console')\n"
                + "def p = job.getProperty(io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty)\n"
                + "p.setBlockTimer(false); p.setBlockUpstream(false)\n"
                + "job.save()\n"
                + "println 'cleared'";
        org.htmlunit.WebResponse console = ApproverFormFixtures.post(j, "admin", "scriptText",
                List.of(new org.htmlunit.util.NameValuePair("script", groovy)));
        assertEquals(200, console.getStatusCode(), "the script console must accept the administrator's script");
        assertTrue(console.getContentAsString().contains("cleared"),
                "the script must have run: " + console.getContentAsString());
        assertNotActivatedAfterClearing(viaConsole, "the script console");
    }

    /**
     * T-06a-14 (P1): JCasC. A Job DSL job defined through the {@code jobs} root is created
     * (locked, D-34), then a second apply — an update, which keeps pinned values (P-14) — sets
     * both switches off through a {@code configure} block. The job is still not activated:
     * activation state is not job configuration, so a configuration-as-code apply cannot carry it.
     */
    @Test
    public void t_06a_14_jcascJobsApplyDoesNotActivate() throws Exception {
        applyCasc("jobs:\n"
                + "  - script: >\n"
                + "      job('path-casc') { triggers { cron('0 3 * * *') } }\n");
        FreeStyleProject job = j.jenkins.getItemByFullName("path-casc", FreeStyleProject.class);
        assertNotNull(job, "the JCasC apply must have created the job");

        applyCasc("jobs:\n"
                + "  - script: >\n"
                + "      job('path-casc') {\n"
                + "        triggers { cron('0 3 * * *') }\n"
                + "        configure { project ->\n"
                + "          def p = project / 'properties' / 'io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty'\n"
                + "          (p / 'approvalRequired').setValue('true')\n"
                + "          (p / 'blockTimer').setValue('false')\n"
                + "          (p / 'blockUpstream').setValue('false')\n"
                + "        }\n"
                + "      }\n");

        assertNotActivatedAfterClearing(reload(job), "a JCasC jobs apply");
    }

    /**
     * T-06a-15 (P0): #15's first acceptance line. u1 holds a CREATE+CONFIGURE permission window
     * on a folder, creates a cron job in it and, inside the same window, clears both switches
     * through config.xml. No approver consented to the job entering service, so it does not run.
     */
    @Test
    @Tag("core")
    public void t_06a_15_permissionWindowHolderCannotActivateTheJobItCreated() throws Exception {
        Folder folder = j.jenkins.createProject(Folder.class, "team");
        GrantRequest request;
        try (ACLContext ignored = ACL.as2(token("u1"))) {
            request = GrantRequestService.get().create(new GrantScope(GrantScope.Type.ITEM, "team"),
                    Arrays.asList(GrantAction.CREATE, GrantAction.CONFIGURE), 30, "new nightly job", "a1");
        }
        try (ACLContext ignored = ACL.as2(token("a1"))) {
            GrantRequestService.get().approve(request.getId(), "ok");
        }

        JenkinsRule.WebClient u1 = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login("u1");
        assertTrue(createFromXml(u1, folder.getUrl(), "nightly") < 400, "u1 must be able to create inside the window");
        FreeStyleProject job = j.jenkins.getItemByFullName("team/nightly", FreeStyleProject.class);
        assertNotNull(job);

        WebRequest edit = new WebRequest(u1.createCrumbedUrl(job.getUrl() + "config.xml"), HttpMethod.POST);
        edit.setAdditionalHeader("Content-Type", "application/xml; charset=UTF-8");
        edit.setRequestBody(clearedConfigXml(job));
        assertEquals(200, u1.getPage(edit).getWebResponse().getStatusCode(),
                "u1's Configure from the window must let the config.xml POST succeed (premise)");

        assertNotActivatedAfterClearing(reload(job), "a permission-window holder's config.xml POST");
    }

    // ---------------------------------------------------------------- helpers

    private FreeStyleProject createAsAdmin(String containerUrl, String name) throws Exception {
        JenkinsRule.WebClient admin = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login("admin");
        assertTrue(createFromXml(admin, containerUrl, name) < 400, "creating " + name + " must succeed");
        FreeStyleProject job = j.jenkins.getItemByFullName(name, FreeStyleProject.class);
        assertNotNull(job, name + " must exist");
        BatchControlJobProperty property = job.getProperty(BatchControlJobProperty.class);
        assertNotNull(property, "premise (D-34): a job created under run control carries the property");
        assertTrue(property.isBlockTimer() && property.isBlockUpstream(), "premise (D-34): it starts locked");
        return job;
    }

    private int createFromXml(JenkinsRule.WebClient wc, String containerUrl, String name) throws Exception {
        URL url = new URL(wc.createCrumbedUrl(containerUrl + "createItem").toExternalForm() + "&name=" + name);
        WebRequest request = new WebRequest(url, HttpMethod.POST);
        request.setAdditionalHeader("Content-Type", "application/xml; charset=UTF-8");
        request.setRequestBody(CRON_FREESTYLE_XML);
        return wc.getPage(request).getWebResponse().getStatusCode();
    }

    /** The job's stored config.xml with both switches turned off (the elements must exist). */
    private static String clearedConfigXml(Job<?, ?> job) throws Exception {
        String xml = job.getConfigFile().asString();
        assertTrue(xml.contains("<blockTimer>true</blockTimer>"),
                "fixture (note 93): config.xml must carry <blockTimer>true</blockTimer>:\n" + xml);
        assertTrue(xml.contains("<blockUpstream>true</blockUpstream>"),
                "fixture (note 93): config.xml must carry <blockUpstream>true</blockUpstream>:\n" + xml);
        return xml.replace("<blockTimer>true</blockTimer>", "<blockTimer>false</blockTimer>")
                .replace("<blockUpstream>true</blockUpstream>", "<blockUpstream>false</blockUpstream>");
    }

    private static void untick(HtmlForm form, String field) {
        HtmlCheckBoxInput box = null;
        for (DomElement element : form.getElementsByTagName("input")) {
            if (element instanceof HtmlCheckBoxInput candidate) {
                String name = ((HtmlInput) element).getNameAttribute();
                if (name != null && (name.equals("_." + field) || name.endsWith(field))) {
                    box = candidate;
                    break;
                }
            }
        }
        assertNotNull(box, "the job configuration form must offer the " + field + " checkbox");
        box.setChecked(false);
    }

    private FreeStyleProject reload(FreeStyleProject job) {
        FreeStyleProject current = j.jenkins.getItemByFullName(job.getFullName(), FreeStyleProject.class);
        assertNotNull(current);
        return current;
    }

    private void applyCasc(String yaml) throws Exception {
        try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) {
            ConfigurationAsCode.get().configureWith(
                    YamlSource.of(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8))));
        }
        j.waitUntilNoActivity();
    }

    /**
     * The shared outcome: the write took effect (premise), yet the job is not activated, its
     * timer is refused with the blocking baseline, and nothing in the activation store or the
     * audit trail says otherwise.
     */
    private void assertNotActivatedAfterClearing(FreeStyleProject job, String path) throws Exception {
        BatchControlJobProperty property = job.getProperty(BatchControlJobProperty.class);
        assertNotNull(property, "premise: " + path + " left the job controlled");
        assertTrue(property.isApprovalRequired(), "premise: " + path + " left approvalRequired on");
        assertFalse(property.isBlockTimer(), "premise: " + path + " must have cleared blockTimer");
        assertFalse(property.isBlockUpstream(), "premise: " + path + " must have cleared blockUpstream");

        assertFalse(isActivated(job), "item 6a: " + path + " must not activate " + job.getFullName());
        int next = job.getNextBuildNumber();
        int builds = job.getBuilds().size();
        assertNull(job.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause()),
                "item 6a: after " + path + " the job's timer must still be refused");
        assertBlocked(j, job, next, builds);

        assertTrue(ActivationFixtures.recordsFor(ChangeType.ACTIVATED, job.getFullName()).isEmpty(),
                "no ACTIVATED record may exist after " + path);
        List<?> requests = ActivationService.get().list();
        assertTrue(requests.isEmpty(), "no activation request may have been created by " + path + ": " + requests);
    }
}
