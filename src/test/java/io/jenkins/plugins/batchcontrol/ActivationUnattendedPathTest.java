package io.jenkins.plugins.batchcontrol;

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.model.Cause;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.Job;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.triggers.SCMTrigger;
import hudson.triggers.TimerTrigger;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.model.GrantAction;
import io.jenkins.plugins.batchcontrol.model.GrantRequest;
import io.jenkins.plugins.batchcontrol.model.GrantScope;
import io.jenkins.plugins.batchcontrol.policy.ActivationService;
import io.jenkins.plugins.batchcontrol.policy.GrantRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import java.net.URL;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.ActivationFixtures.CRON_FREESTYLE_XML;
import static io.jenkins.plugins.batchcontrol.ActivationFixtures.assertBlocked;
import static io.jenkins.plugins.batchcontrol.ActivationFixtures.isActivated;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.activate;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.token;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.uncontrolled;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC item 6a as amended by D-46 (security-13 S-13-01, S-13-02): while run control is on, every
 * unattended cause (timer, upstream, SCM, unclassified) of every non-computed job needs an
 * approved activation, regardless of {@code approvalRequired}; clearing
 * {@code approvalRequired}/{@code blockTimer}/{@code blockUpstream} or removing the job property
 * never lets a non-activated job run unattended. Matrix rows T-06a-43..45.
 *
 * <p>TRIGGER_BLOCKED contract for a missing activation (note 101, supersedes note 92 (b)): the
 * record's detail names the cause kind and, as the blocking switch, {@code activation}
 * (matched case-insensitively).
 *
 * <p>Written from docs/SPEC.md item 6a, docs/DECISIONS.md D-46 and docs/reports/security-13.md
 * only (no src/main knowledge).
 */
@WithJenkins
public class ActivationUnattendedPathTest {

    private static final Instant T0 = Instant.now();

    private static final String PROPERTY_TAG = "io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty";

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        BatchClock.setForTest(Clock.fixed(T0, ZoneOffset.UTC));
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        BatchControlMatrixAuthorizationStrategy strategy = new BatchControlMatrixAuthorizationStrategy();
        strategy.add(Jenkins.ADMINISTER, PermissionEntry.user("admin"));
        for (String userId : new String[] {"u1", "a1"}) {
            strategy.add(Jenkins.READ, PermissionEntry.user(userId));
            strategy.add(Item.READ, PermissionEntry.user(userId));
        }
        strategy.add(BatchControlPermissions.REQUEST_GRANT, PermissionEntry.user("u1"));
        strategy.add(BatchControlPermissions.REQUEST, PermissionEntry.user("u1"));
        strategy.add(BatchControlPermissions.APPROVE, PermissionEntry.user("a1"));
        j.jenkins.setAuthorizationStrategy(strategy);

        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setChangeControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
    }

    @AfterEach
    public void tearDown() {
        BatchClock.reset();
    }

    /**
     * T-06a-43 (P0, S-13-01): a CREATE+CONFIGURE window holder creates a cron job and, in the
     * same window, POSTs its config.xml with {@code approvalRequired}, {@code blockTimer} and
     * {@code blockUpstream} all false. The job is still not activated: its timer and an upstream
     * cause are refused inside the window and after it expired, each refusal is recorded as
     * TRIGGER_BLOCKED naming {@code activation}, and no ACTIVATED record exists.
     */
    @Test
    public void t_06a_43_windowHolderClearingApprovalRequiredCannotPutTheJobIntoService() throws Exception {
        JenkinsRule.WebClient u1 = openWindowAndCreate("nightly");
        FreeStyleProject job = job("team/nightly");

        String xml = job.getConfigFile().asString();
        xml = flip(xml, "approvalRequired");
        xml = flip(xml, "blockTimer");
        xml = flip(xml, "blockUpstream");
        assertEquals(200, postConfig(u1, job, xml), "u1's Configure from the window must let the config.xml POST succeed (premise)");

        job = job("team/nightly");
        BatchControlJobProperty property = job.getProperty(BatchControlJobProperty.class);
        assertNotNull(property, "premise: the edit kept the property");
        assertFalse(property.isApprovalRequired(), "premise: approvalRequired read back off");
        assertFalse(property.isBlockTimer(), "premise: blockTimer read back off");
        assertFalse(property.isBlockUpstream(), "premise: blockUpstream read back off");

        assertUnattendedRefusedAndRecorded(job);

        BatchClock.setForTest(Clock.fixed(T0.plus(Duration.ofMinutes(31)), ZoneOffset.UTC));
        assertNull(job.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause()),
                "D-46: after the window expired the planted cron must still be refused");
        assertBlocked(j, job, 1, 0);
        assertFalse(isActivated(job), "nothing may have activated the job");
        assertTrue(ActivationFixtures.recordsFor(ChangeType.ACTIVATED, "team/nightly").isEmpty(),
                "no ACTIVATED record may exist for a job nobody activated");
    }

    /**
     * T-06a-44 (P0, S-13-01 twin): the same window holder removes the job property from the
     * config.xml altogether. A job without the property is still refused on its timer and on
     * an upstream cause, with the same TRIGGER_BLOCKED records and no ACTIVATED record.
     */
    @Test
    public void t_06a_44_windowHolderRemovingThePropertyCannotPutTheJobIntoService() throws Exception {
        JenkinsRule.WebClient u1 = openWindowAndCreate("nightly");
        FreeStyleProject job = job("team/nightly");

        String xml = job.getConfigFile().asString();
        Matcher element = Pattern.compile("<" + Pattern.quote(PROPERTY_TAG) + "(\\s[^>]*)?(/>|>.*?</"
                + Pattern.quote(PROPERTY_TAG) + ">)", Pattern.DOTALL).matcher(xml);
        assertTrue(element.find(), "fixture (note 93): config.xml must carry the " + PROPERTY_TAG + " element:\n" + xml);
        String stripped = element.replaceAll("");
        assertFalse(stripped.contains(PROPERTY_TAG), "fixture: the property element must be gone:\n" + stripped);
        assertEquals(200, postConfig(u1, job, stripped), "u1's Configure from the window must let the config.xml POST succeed (premise)");

        job = job("team/nightly");
        assertNull(job.getProperty(BatchControlJobProperty.class), "premise: the property was removed");

        assertUnattendedRefusedAndRecorded(job);
        assertTrue(ActivationFixtures.recordsFor(ChangeType.ACTIVATED, "team/nightly").isEmpty(),
                "no ACTIVATED record may exist for a job nobody activated");
    }

    /**
     * T-06a-45 (P0, S-13-02): an SCM cause and a cause outside every known family (a custom
     * {@link Cause} subclass) are unattended causes. On a job created under run control — once
     * as created (D-34 switches on) and once with the property removed — both are refused until
     * the job is activated, and both build afterwards. {@code blockTimer}/{@code blockUpstream}
     * do not govern these causes, so the switches left on do not refuse them once activated.
     */
    @Test
    public void t_06a_45_scmAndUnclassifiedCausesNeedActivation() throws Exception {
        FreeStyleProject asCreated = createAsAdmin("scm-locked");
        FreeStyleProject stripped = uncontrolled(createAsAdmin("scm-free"));

        for (FreeStyleProject job : Arrays.asList(asCreated, stripped)) {
            assertFalse(isActivated(job), "premise: " + job.getName() + " created under run control is not activated");
            assertNull(job.scheduleBuild2(0, new SCMTrigger.SCMTriggerCause("simulated polling detected changes")),
                    "D-46: an SCM cause must not start the non-activated " + job.getName());
            assertBlocked(j, job, 1, 0);
            assertNull(job.scheduleBuild2(0, new UnclassifiedCause()),
                    "D-46: an unclassified cause must not start the non-activated " + job.getName());
            assertBlocked(j, job, 1, 0);
        }

        for (FreeStyleProject job : Arrays.asList(asCreated, stripped)) {
            activate(job);
            Future<FreeStyleBuild> scm = job.scheduleBuild2(0,
                    new SCMTrigger.SCMTriggerCause("simulated polling detected changes"));
            assertNotNull(scm, "an activated " + job.getName() + " must pass an SCM cause");
            j.assertBuildStatusSuccess(scm);
            Future<FreeStyleBuild> other = job.scheduleBuild2(0, new UnclassifiedCause());
            assertNotNull(other, "an activated " + job.getName() + " must pass an unclassified cause");
            j.assertBuildStatusSuccess(other);
            j.waitUntilNoActivity();
            assertEquals(2, job.getBuilds().size(), "both unattended runs of " + job.getName() + " must have built");
        }
    }

    // ---------------------------------------------------------------- helpers

    /** A cause outside every classified family (not user, timer, upstream, SCM or remote). */
    public static final class UnclassifiedCause extends Cause {
        @Override
        public String getShortDescription() {
            return "a plugin-defined trigger (test)";
        }
    }

    /**
     * Timer and upstream refused with the blocking baseline; one TRIGGER_BLOCKED record per cause
     * kind naming the kind and {@code activation} (note 101); the job is not activated.
     */
    private void assertUnattendedRefusedAndRecorded(FreeStyleProject job) throws Exception {
        assertFalse(isActivated(job), "D-46: no configuration write can activate " + job.getFullName());
        assertNull(job.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause()),
                "D-46: the timer of a non-activated job must be refused whatever approvalRequired says");
        assertBlocked(j, job, 1, 0);
        assertNull(job.scheduleBuild2(0, new Cause.UpstreamCause(upstreamBuild())),
                "D-46: an upstream cause of a non-activated job must be refused whatever approvalRequired says");
        assertBlocked(j, job, 1, 0);

        List<ChangeRecord> records = ActivationFixtures.recordsFor(ChangeType.TRIGGER_BLOCKED, job.getFullName());
        List<String> details = records.stream().map(ChangeRecord::getDetail).collect(Collectors.toList());
        assertEquals(2, records.size(), "one TRIGGER_BLOCKED record per refused cause kind: " + details);
        assertTrue(details.stream().anyMatch(d -> d != null && d.contains("TIMER")
                && d.toLowerCase(Locale.ROOT).contains("activation")), "a TIMER record naming activation: " + details);
        assertTrue(details.stream().anyMatch(d -> d != null && d.contains("UPSTREAM")
                && d.toLowerCase(Locale.ROOT).contains("activation")), "an UPSTREAM record naming activation: " + details);
    }

    /** u1 gets an approved CREATE+CONFIGURE window on folder {@code team} and creates a cron job in it. */
    private JenkinsRule.WebClient openWindowAndCreate(String name) throws Exception {
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
        assertTrue(createFromXml(u1, folder.getUrl(), name) < 400, "u1 must be able to create inside the window");
        return u1;
    }

    private FreeStyleProject createAsAdmin(String name) throws Exception {
        JenkinsRule.WebClient admin = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login("admin");
        assertTrue(createFromXml(admin, "", name) < 400, "creating " + name + " must succeed");
        return job(name);
    }

    private int createFromXml(JenkinsRule.WebClient wc, String containerUrl, String name) throws Exception {
        URL url = new URL(wc.createCrumbedUrl(containerUrl + "createItem").toExternalForm() + "&name=" + name);
        WebRequest request = new WebRequest(url, HttpMethod.POST);
        request.setAdditionalHeader("Content-Type", "application/xml; charset=UTF-8");
        request.setRequestBody(CRON_FREESTYLE_XML);
        return wc.getPage(request).getWebResponse().getStatusCode();
    }

    private int postConfig(JenkinsRule.WebClient wc, Job<?, ?> job, String xml) throws Exception {
        WebRequest edit = new WebRequest(wc.createCrumbedUrl(job.getUrl() + "config.xml"), HttpMethod.POST);
        edit.setAdditionalHeader("Content-Type", "application/xml; charset=UTF-8");
        edit.setRequestBody(xml);
        return wc.getPage(edit).getWebResponse().getStatusCode();
    }

    /** Turns {@code <field>true</field>} into false; the element must exist (note 93). */
    private static String flip(String xml, String field) {
        String on = "<" + field + ">true</" + field + ">";
        assertTrue(xml.contains(on), "fixture (note 93): config.xml must carry " + on + ":\n" + xml);
        return xml.replace(on, "<" + field + ">false</" + field + ">");
    }

    private FreeStyleProject job(String fullName) {
        FreeStyleProject job = j.jenkins.getItemByFullName(fullName, FreeStyleProject.class);
        assertNotNull(job, fullName + " must exist");
        return job;
    }

    /**
     * A finished build of an uncontrolled job started by a human cause (note 100). security-15
     * S-15-01: the submission, not only the {@code Cause}, must run while impersonating the
     * user, or the gate now (correctly) classifies it as unattended.
     */
    private FreeStyleBuild upstreamBuild() throws Exception {
        FreeStyleProject upstream = uncontrolled(j.createFreeStyleProject("up-" + System.nanoTime()));
        try (ACLContext ignored = ACL.as2(token("admin"))) {
            return j.assertBuildStatusSuccess(upstream.scheduleBuild2(0, new Cause.UserIdCause()));
        }
    }
}
