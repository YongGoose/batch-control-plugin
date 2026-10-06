package io.jenkins.plugins.batchcontrol;

import hudson.ExtensionList;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.ParameterValue;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.PasswordParameterDefinition;
import hudson.model.PasswordParameterValue;
import hudson.model.StringParameterDefinition;
import hudson.model.StringParameterValue;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.util.Secret;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.ops.ExpiryPeriodicWork;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.file_parameters.Base64FileParameterDefinition;
import io.jenkins.plugins.file_parameters.Base64FileParameterValue;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import jenkins.model.Jenkins;
import org.htmlunit.WebResponse;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.HtmlElement;
import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.MASK;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.fileDisplay;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.fileItem;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.payload;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.readable;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.storeDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC item 5 (D-74 (1), replacing the D-72b layout): "Typed values live in a separate values file
 * per request, which is deleted when the approved run starts or the request ends; listings never load
 * it. A request with parameters whose values file is missing or unreadable cannot be approved or
 * run." ARCHITECTURE 5: {@code requests/run/<id>.xml} holds the request without typed values (the
 * masked {@code parameters} map stays there); the typed values are in
 * {@code requests/run/<id>.values.xml}; listings, badges, the index and periodic work read only
 * {@code <id>.xml}. Matrix rows T-05-132 and T-05-133 (note 270, coverage inventory G-H4); the
 * restart rows T-05-130 (G-H2) and T-05-131 (recovery fails closed) are in
 * {@link ValuesFileRestartTest}.
 *
 * <p>The other clauses of G-H4 are already rows of note 268 (the D-72b rows retargeted to the values
 * file by a parallel lane): the layout T-05-101, the deletion at run start and at every end
 * T-05-83 .. T-05-85, "never loaded" by listings T-05-86, a missing values file at approval T-05-81
 * and a repeated name T-05-78/82. These rows add what those do not: listings, the badge and the
 * periodic work keep working when the values file is unreadable or gone, and an unreadable (not
 * merely missing) values file fails closed at approval. None of the note 268 tests or their
 * fixtures' file helpers are used or changed; this class locates and edits the values file itself.
 *
 * <p>Written from docs/SPEC.md item 5, docs/DECISIONS.md D-72, D-72b and D-74, and docs/ARCHITECTURE.md
 * section 5 only (no src/main knowledge).
 */
@WithJenkins
public class ValuesFileTest {

    private static final String SECRET = "vf-s3cr3t-d74-Kp2";
    private static final String PLAIN = "plain-d74-Zx5";

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.REQUEST).everywhere().to("u1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1"));
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
        TypedParameterFixtures.CaptureEnv.SEEN.clear();
    }

    /**
     * T-05-132 (D-74 (1) "listings read only {@code <id>.xml}"; SPEC item 5 "listings never load
     * it"; complements T-05-86, which counts deserialisations): two PENDING typed requests; the first
     * one's values file is replaced by bytes that are not XML, the second one's is deleted. The Run
     * Requests list (requester and approver) and the overview still answer 200 and list both
     * requests, the approver's tab badge counts 2, and after the expiry periodic work (nothing due)
     * both requests are still PENDING with their masked display maps: no listing, badge or periodic
     * work depends on a values file.
     */
    @Test
    public void t_05_132_listingsAndPeriodicWorkDoNotDependOnTheValuesFile() throws Exception {
        FreeStyleProject job = typedJob("vf-list");
        String[] ids = {createTyped(job, payload("vf-list-0-marker", 1200)), createTyped(job, payload("vf-list-1-marker", 1200))};
        Files.writeString(valuesFile(ids[0]), "\u0000<not-xml d74", StandardCharsets.UTF_8);
        Files.delete(valuesFile(ids[1]));

        for (String user : new String[] {"u1", "a1"}) {
            String list = readable(j, user, "batch-control/requests/");
            for (String id : ids) {
                assertTrue(list.contains(id), "the Run Requests list seen by " + user + " must list " + id + " without its values file");
            }
            readable(j, user, "batch-control/");
        }
        HtmlPage overview = UsabilityFixtures.htmlPage(j, "a1", "batch-control/");
        assertEquals("2", badge(overview, "requests"), "the designated approver's tab badge must count the 2 pending requests");
        ExtensionList.lookupSingleton(ExpiryPeriodicWork.class).doRun();
        for (String id : ids) {
            assertEquals(RequestStatus.PENDING, RunRequestService.get().load(id).getStatus(),
                    "the periodic work reads only <id>.xml: the request stays PENDING");
            assertEquals(Map.of("TOKEN", MASK, "B64", fileDisplay("payload.bin"), "PLAIN", PLAIN),
                    RunRequestService.get().load(id).getParameters(), "the masked display map comes from <id>.xml");
        }
    }

    /**
     * T-05-133 (SPEC item 5 "a request with parameters whose values file is missing or unreadable
     * cannot be approved or run"; the missing case is T-05-81, a repeated name T-05-78): two PENDING
     * requests for TARGET {@code staging}, each on its own job: the first one's values file is
     * replaced by bytes that are not XML, the second one's cut off in the middle of its XML. Each
     * approval is refused (service and HTTP: below 500 with a plain message), the request is neither
     * APPROVED nor EXECUTED and nothing runs; no build runs with the job's default. Guard: each job's
     * unedited twin is approved afterwards and runs with its own value.
     */
    @Test
    public void t_05_133_unreadableValuesFileFailsClosedAtApproval() throws Exception {
        String[][] cases = {{"vf-garbage", "not XML"}, {"vf-truncated", "cut off in the middle"}};
        for (String[] c : cases) {
            FreeStyleProject job = targetJob(c[0]);
            String id = createAsU1(job, List.of(new StringParameterValue("TARGET", "staging")));
            String twin = createAsU1(job, List.of(new StringParameterValue("TARGET", "guarded-" + c[0])));
            Path values = valuesFile(id);
            String xml = Files.readString(values, StandardCharsets.UTF_8);
            if ("vf-garbage".equals(c[0])) {
                Files.writeString(values, "\u0000<garbage d74", StandardCharsets.UTF_8);
            } else {
                int cut = xml.indexOf("staging");
                assertTrue(cut > 0, "fixture: the values file holds the typed TARGET: " + UsabilityFixtures.excerpt(xml));
                Files.writeString(values, xml.substring(0, cut), StandardCharsets.UTF_8);
            }
            assertEquals(Map.of("TARGET", "staging"), RunRequestService.get().load(id).getParameters(), "premise: the approver sees staging");

            assertApprovalRefused("a request whose values file is " + c[1], job, id);
            try (ACLContext ignored = as("a1")) {
                RunRequestService.get().approve(twin, "the unedited twin");
            }
            j.waitUntilNoActivity();
            assertNotNull(job.getBuildByNumber(1), "guard: the unedited twin on " + c[0] + " must run");
            assertEquals("guarded-" + c[0], TypedParameterFixtures.CaptureEnv.seen(c[0], 1, "TARGET"), "guard: the twin's run receives its own value");
            assertEquals(1, job.getBuilds().size(), "exactly one run on " + c[0] + ": the twin's");
        }
        assertFalse(TypedParameterFixtures.CaptureEnv.SEEN.containsValue("default-target"), "no build may have run with the job's default");
    }

    // ---------------------------------------------------------------- helpers

    /** {@code requests/run/<id>.values.xml} (ARCHITECTURE 5, D-74), whether it exists or not. */
    private Path valuesPath(String id) {
        return storeDir(j).resolve("requests").resolve("run").resolve(id + ".values.xml");
    }

    /** {@code requests/run/<id>.values.xml}, which must exist. */
    private Path valuesFile(String id) {
        Path file = valuesPath(id);
        assertTrue(Files.isRegularFile(file), "D-74: the typed values must be stored at " + file);
        return file;
    }

    /** An approval-required Freestyle job with Password TOKEN, base64File B64 and string PLAIN. */
    private FreeStyleProject typedJob(String name) throws Exception {
        FreeStyleProject job = j.createFreeStyleProject(name);
        job.addProperty(new ParametersDefinitionProperty(
                new PasswordParameterDefinition("TOKEN", Secret.fromString("vf-d3fault"), "token"),
                new Base64FileParameterDefinition("B64"),
                new StringParameterDefinition("PLAIN", "plain-default")));
        setBatchControl(job, new BatchControlJobProperty(true));
        return job;
    }

    /** An approval-required Freestyle job with TARGET (default {@code default-target}) whose build records TARGET. */
    private FreeStyleProject targetJob(String name) throws Exception {
        FreeStyleProject job = j.createFreeStyleProject(name);
        job.addProperty(new ParametersDefinitionProperty(new StringParameterDefinition("TARGET", "default-target")));
        job.getBuildersList().add(new TypedParameterFixtures.CaptureEnv(false, "TARGET"));
        setBatchControl(job, new BatchControlJobProperty(true));
        return job;
    }

    /** u1's typed request on {@code job}: TOKEN={@link #SECRET}, B64=payload.bin holding {@code content}, PLAIN. */
    private String createTyped(FreeStyleProject job, byte[] content) throws Exception {
        Base64FileParameterValue file = new Base64FileParameterValue("B64");
        file.setFile(fileItem("payload.bin", content));
        List<ParameterValue> values = new ArrayList<>();
        values.add(new PasswordParameterValue("TOKEN", SECRET));
        values.add(file);
        values.add(new StringParameterValue("PLAIN", PLAIN));
        return createAsU1(job, values);
    }

    private String createAsU1(FreeStyleProject job, List<ParameterValue> values) {
        try (ACLContext ignored = as("u1")) {
            return RunRequestService.get().create(job, values, "values file rows (D-74)", "a1").getId();
        }
    }

    /** Approval is refused by the service and over HTTP (below 500, plain message); never APPROVED or EXECUTED; nothing ran. */
    private void assertApprovalRefused(String what, FreeStyleProject job, String id) throws Exception {
        try (ACLContext ignored = as("a1")) {
            RunRequestService.get().approve(id, "looks fine");
        } catch (RuntimeException refused) {
            // a refusal; the state below is what counts
        }
        assertNotApproved(what + " (service approve)", id);
        WebResponse http = ApproverFormFixtures.decideRun(j, "a1", id, "approve", "looks fine");
        assertTrue(http.getStatusCode() < 500, what + ": the HTTP approval must be refused without a server error, got HTTP "
                + http.getStatusCode() + ": " + UsabilityFixtures.excerpt(http.getContentAsString()));
        UsabilityFixtures.assertPlainRefusal(what + " (HTTP approve)", http.getContentAsString(), null);
        assertNotApproved(what + " (HTTP approve)", id);
        j.waitUntilNoActivity();
        assertTrue(j.jenkins.getQueue().isEmpty(), what + ": the queue must be empty");
        assertEquals(1, job.getNextBuildNumber(), what + ": no build number may have been consumed");
    }

    private static void assertNotApproved(String what, String id) {
        RequestStatus status = RunRequestService.get().load(id).getStatus();
        assertNotEquals(RequestStatus.APPROVED, status, what + ": the approval must be refused");
        assertNotEquals(RequestStatus.EXECUTED, status, what + ": the request must never run");
    }

    /** The badge text of the tab bar's {@code section} tab (as SectionTabsTest reads it), or null. */
    private static String badge(HtmlPage page, String section) {
        DomElement tab = null;
        for (Object o : page.querySelectorAll("nav[data-batch-control-tabs] a[data-batch-control-tab]")) {
            if (section.equals(((DomElement) o).getAttribute("data-batch-control-tab"))) {
                tab = (DomElement) o;
            }
        }
        if (tab == null) {
            return null;
        }
        for (HtmlElement e : tab.getHtmlElementDescendants()) {
            if (e.getAttribute("class").contains("badge")) {
                String text = e.asNormalizedText().trim();
                return text.isEmpty() || "0".equals(text) ? null : text;
            }
        }
        return null;
    }

    private static ACLContext as(String userId) {
        return ACL.as2(User.getById(userId, true).impersonate2());
    }
}
