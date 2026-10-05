package io.jenkins.plugins.batchcontrol;

import hudson.ExtensionList;
import hudson.model.FreeStyleBuild;
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
import io.jenkins.plugins.batchcontrol.CustomParameterFixtures.ProbeParameterDefinition;
import io.jenkins.plugins.batchcontrol.CustomParameterFixtures.ProbeParameterValue;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.ops.ExpiryPeriodicWork;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import io.jenkins.plugins.file_parameters.Base64FileParameterDefinition;
import io.jenkins.plugins.file_parameters.Base64FileParameterValue;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import jenkins.model.Jenkins;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.HtmlElement;
import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.MASK;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.assertAbsent;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.assertTypedValuesGone;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.assertTypedValuesKept;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.fileDisplay;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.fileItem;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.payload;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.readable;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.requestFile;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC item 5, D-72b (5) (security-35 S-35-02): typed values are removed from the request file
 * when the approved run starts or the request ends (rejected, cancelled, expired, invalidated);
 * the masked display map stays as the record; listings and periodic work never load typed values.
 * Matrix rows T-05-83 .. T-05-86 (note 265).
 *
 * <p>"No longer holds the typed values" is measured on the file: none of the Base64 text of a
 * {@code base64File} value and no Jenkins-encrypted token that decrypts to the password, while the
 * display map ({@code ********}, {@code [file] <name>}, the plain value) is unchanged and the pages
 * still render it. Element names are not pinned. "When the approved run starts" is observed from
 * inside the build ({@link TypedParameterFixtures.RequestFileSnapshot}). "Never loaded" is observed
 * with a value type that counts its own deserialisation ({@link ProbeParameterValue}); no timing is
 * asserted.
 *
 * <p>Written from docs/SPEC.md item 5, docs/DECISIONS.md D-72 and D-72b, ARCHITECTURE section 5
 * and the Given/When/Then of docs/reports/security-35.md S-35-02 only (no src/main knowledge).
 */
@WithJenkins
public class TypedValueRemovalTest {

    private static final Instant T0 = Instant.parse("2026-10-05T09:00:00Z");
    private static final String SECRET = "str1p-s3cr3t-d72b-Hn4";
    private static final String PLAIN = "plain-kept-d72b-Wq8";

    private JenkinsRule j;

    /** Refuses armed jobs before Batch Control's queue gate (QueueRefusalFixtures). */
    @TestExtension
    public static final class RefuseBeforeGate extends QueueRefusalFixtures.RefusingHandler {
    }

    /** The descriptor of the probe parameter type used by T-05-86. */
    @TestExtension
    public static final class ProbeDescriptor extends CustomParameterFixtures.ProbeDescriptorBase {
    }

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
        TypedParameterFixtures.RequestFileSnapshot.SNAPSHOTS.clear();
    }

    @AfterEach
    public void resetClock() {
        BatchClock.reset();
    }

    /**
     * T-05-83: a typed request (password, {@code base64File}, string) is approved; read from inside
     * the running build, the request file no longer holds the Base64 text or the encrypted
     * password, and after the run neither does it; the display map and the detail page still show
     * {@code ********}, {@code [file] payload.bin} and the plain value. Guard: the file held both
     * before the approval, and the build received the original password.
     */
    @Test
    public void t_05_83_typedValuesLeaveTheFileWhenTheApprovedRunStarts() throws Exception {
        FreeStyleProject job = typedJob("strip-run");
        job.getBuildersList().add(new TypedParameterFixtures.CaptureEnv(false, "TOKEN", "PLAIN"));
        job.getBuildersList().add(new TypedParameterFixtures.RequestFileSnapshot());
        byte[] content = payload("strip-run-marker-Xc31", 2000);
        String base64 = Base64.getEncoder().encodeToString(content);
        String id = createTyped(job, content);
        assertTypedValuesKept(j, "premise (pending)", id, List.of(base64), SECRET);

        try (ACLContext ignored = as("a1")) {
            RunRequestService.get().approve(id, "checked");
        }
        j.waitUntilNoActivity();
        FreeStyleBuild build = job.getBuildByNumber(1);
        assertNotNull(build, "the approved request must run");
        j.assertBuildStatusSuccess(build);
        assertEquals(SECRET, TypedParameterFixtures.CaptureEnv.seen("strip-run", 1, "TOKEN"), "guard: the run received the original secret");

        String during = TypedParameterFixtures.RequestFileSnapshot.SNAPSHOTS.get("strip-run#1");
        assertNotNull(during, "fixture: the build must have read the request file");
        assertAbsent("the request file while the approved run runs", during, List.of(base64.substring(0, 32)));
        assertFalse(decryptsTo(during, SECRET), "the request file while the approved run runs must not hold the encrypted secret");
        assertTrue(during.contains(fileDisplay("payload.bin")) && during.contains(PLAIN) && during.contains(MASK),
                "the masked display map stays in the file: " + UsabilityFixtures.excerpt(during));

        assertTypedValuesGone(j, "after the run", id, List.of(base64.substring(0, 32)), SECRET);
        assertDisplayStays(id, "after the run");
    }

    /**
     * T-05-84: typed requests that end without a run (rejected, cancelled, expired while pending,
     * invalidated by a rename) no longer hold the Base64 text or the encrypted password; each
     * display map and detail page still shows the masked values. Guard: each file held them before.
     */
    @Test
    public void t_05_84_typedValuesLeaveTheFileWhenAPendingRequestEnds() throws Exception {
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setPendingTimeoutHours(1);
        cfg.save();
        BatchClock.setForTest(Clock.fixed(T0, ZoneOffset.UTC));
        FreeStyleProject job = typedJob("strip-end");
        FreeStyleProject renamed = typedJob("strip-rename");
        byte[][] contents = new byte[4][];
        String[] ids = new String[4];
        for (int i = 0; i < 4; i++) {
            contents[i] = payload("strip-end-" + i + "-marker", 1500);
            ids[i] = createTyped(i == 3 ? renamed : job, contents[i]);
            assertTypedValuesKept(j, "premise (request " + i + ")", ids[i], List.of(b64(contents[i])), SECRET);
        }

        try (ACLContext ignored = as("a1")) {
            RunRequestService.get().reject(ids[0], "not this month");
        }
        try (ACLContext ignored = as("u1")) {
            RunRequestService.get().cancel(ids[1]);
        }
        renamed.renameTo("strip-renamed");
        BatchClock.setForTest(Clock.fixed(T0.plus(Duration.ofHours(2)), ZoneOffset.UTC));
        ExtensionList.lookupSingleton(ExpiryPeriodicWork.class).doRun();

        RequestStatus[] expected = {RequestStatus.REJECTED, RequestStatus.CANCELLED, RequestStatus.EXPIRED, RequestStatus.INVALIDATED};
        for (int i = 0; i < 4; i++) {
            String what = expected[i] + " request";
            assertEquals(expected[i], RunRequestService.get().load(ids[i]).getStatus(), "premise: " + what);
            assertTypedValuesGone(j, what, ids[i], List.of(b64(contents[i]).substring(0, 32)), SECRET);
            assertDisplayStays(ids[i], what);
        }
        String list = readable(j, "u1", "batch-control/requests/");
        for (String id : ids) {
            assertTrue(list.contains(id), "the request list still lists " + id);
        }
    }

    /**
     * T-05-85: an approved request whose run was never queued keeps its typed values while it can
     * still run (guard), and loses them when it ends: EXPIRED by the approved-run timeout, and
     * INVALIDATED by a rename; the display stays. SPEC 7: the expiry work ends every approved,
     * unqueued request past the timeout, so the request ended by the rename is approved after the
     * clock move and is not yet due when the expiry work runs (note 265).
     */
    @Test
    public void t_05_85_typedValuesLeaveTheFileWhenAnApprovedUnqueuedRequestEnds() throws Exception {
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setApprovedRunTimeoutMinutes(60);
        cfg.save();
        BatchClock.setForTest(Clock.fixed(T0, ZoneOffset.UTC));
        FreeStyleProject expiring = typedJob("strip-appr");
        FreeStyleProject renamed = typedJob("strip-appr-rename");
        byte[] first = payload("strip-appr-0-marker", 1500);
        byte[] second = payload("strip-appr-1-marker", 1500);
        String expiredId = createTyped(expiring, first);
        String invalidatedId = createTyped(renamed, second);
        QueueRefusalFixtures.refusedBeforeTheGate(expiring, () -> approve(expiredId));
        assertEquals(RequestStatus.APPROVED, RunRequestService.get().load(expiredId).getStatus(), "premise: approved, not queued");
        assertTypedValuesKept(j, "approved, not yet run (it can still be submitted)", expiredId, List.of(b64(first)), SECRET);

        BatchClock.setForTest(Clock.fixed(T0.plus(Duration.ofMinutes(61)), ZoneOffset.UTC));
        // SPEC 7 expires every approved, unqueued request past the timeout, so the second request is
        // approved only now: the expiry work below leaves it APPROVED and only the rename ends it
        QueueRefusalFixtures.refusedBeforeTheGate(renamed, () -> approve(invalidatedId));
        assertEquals(RequestStatus.APPROVED, RunRequestService.get().load(invalidatedId).getStatus(), "premise: approved, not queued");
        ExtensionList.lookupSingleton(ExpiryPeriodicWork.class).doRun();
        assertEquals(RequestStatus.EXPIRED, RunRequestService.get().load(expiredId).getStatus(), "premise: expired by the approved-run timeout");
        assertTypedValuesGone(j, "the expired approval", expiredId, List.of(b64(first).substring(0, 32)), SECRET);
        assertDisplayStays(expiredId, "the expired approval");
        assertEquals(RequestStatus.APPROVED, RunRequestService.get().load(invalidatedId).getStatus(), "premise: not yet due, still approved");
        assertTypedValuesKept(j, "approved within the timeout, not yet run", invalidatedId, List.of(b64(second)), SECRET);

        renamed.renameTo("strip-appr-renamed");
        assertEquals(RequestStatus.INVALIDATED, RunRequestService.get().load(invalidatedId).getStatus(), "premise: invalidated");
        assertTypedValuesGone(j, "the invalidated approval", invalidatedId, List.of(b64(second).substring(0, 32)), SECRET);
        assertDisplayStays(invalidatedId, "the invalidated approval");
        j.waitUntilNoActivity();
        assertTrue(expiring.getBuilds().isEmpty() && renamed.getBuilds().isEmpty(), "nothing ran");
    }

    /**
     * T-05-86: with two pending typed requests holding a value that counts its own deserialisation,
     * the Run Requests list (requester and approver), the overview with its tab badge and the
     * minute's expiry work (nothing due) load none of the typed values. The pages render: the list
     * shows both requests and the approver's badge counts 2. Premise: reading the request file with
     * Jenkins' XStream does load the value (the counter works).
     */
    @Test
    public void t_05_86_listingsBadgeAndPeriodicWorkNeverLoadTypedValues() throws Exception {
        FreeStyleProject job = j.createFreeStyleProject("probe-x");
        job.addProperty(new ParametersDefinitionProperty(new ProbeParameterDefinition("PROBE"),
                new StringParameterDefinition("PLAIN", "plain-default")));
        setBatchControl(job, new BatchControlJobProperty(true));
        String[] ids = new String[2];
        for (int i = 0; i < 2; i++) {
            List<ParameterValue> values = new ArrayList<>();
            values.add(new ProbeParameterValue("PROBE", "probe-" + i));
            values.add(new StringParameterValue("PLAIN", PLAIN));
            try (ACLContext ignored = as("u1")) {
                ids[i] = RunRequestService.get().create(job, values, "listing probe", "a1").getId();
            }
        }
        ProbeParameterValue.LOADS.set(0);
        Jenkins.XSTREAM2.fromXML(requestFile(j, ids[0]).toFile());
        assertTrue(ProbeParameterValue.LOADS.get() > 0, "premise: reading the request file with Jenkins' XStream materialises the probe");

        ProbeParameterValue.LOADS.set(0);
        for (String user : new String[] {"u1", "a1"}) {
            String list = readable(j, user, "batch-control/requests/");
            for (String id : ids) {
                assertTrue(list.contains(id), "the Run Requests list seen by " + user + " must list " + id);
            }
            readable(j, user, "batch-control/");
        }
        HtmlPage overview = UsabilityFixtures.htmlPage(j, "a1", "batch-control/");
        assertEquals("2", badge(overview, "requests"), "the designated approver's tab badge must count the 2 pending requests");
        ExtensionList.lookupSingleton(ExpiryPeriodicWork.class).doRun();
        assertEquals(0, ProbeParameterValue.LOADS.get(), "listings, the badge and the periodic work must not load typed values (D-72b (5))");
        assertEquals(RequestStatus.PENDING, RunRequestService.get().load(ids[0]).getStatus());
    }

    // ---------------------------------------------------------------- helpers

    /** An approval-required Freestyle job with Password TOKEN, base64File B64 and string PLAIN. */
    private FreeStyleProject typedJob(String name) throws Exception {
        FreeStyleProject job = j.createFreeStyleProject(name);
        job.addProperty(new ParametersDefinitionProperty(
                new PasswordParameterDefinition("TOKEN", Secret.fromString("strip-d3fault"), "token"),
                new Base64FileParameterDefinition("B64"),
                new StringParameterDefinition("PLAIN", "plain-default")));
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
        try (ACLContext ignored = as("u1")) {
            return RunRequestService.get().create(job, values, "typed values that must not outlive their use", "a1").getId();
        }
    }

    private void approve(String id) {
        try (ACLContext ignored = as("a1")) {
            RunRequestService.get().approve(id, "checked");
        }
    }

    /** The display map and the detail page still show the masked values (the record stays). */
    private void assertDisplayStays(String id, String what) throws Exception {
        assertEquals(Map.of("TOKEN", MASK, "B64", fileDisplay("payload.bin"), "PLAIN", PLAIN),
                RunRequestService.get().load(id).getParameters(), what + ": the masked display map must stay");
        String detail = readable(j, "a1", "batch-control/requests/" + id + "/");
        assertTrue(detail.contains(MASK) && detail.contains(fileDisplay("payload.bin")) && detail.contains(PLAIN),
                what + ": the detail page must still show the masked values");
        assertFalse(detail.contains(SECRET), what + ": the detail page must not show the secret");
    }

    private static String b64(byte[] content) {
        return Base64.getEncoder().encodeToString(content);
    }

    private static boolean decryptsTo(String xml, String plaintext) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\{[A-Za-z0-9+/=]{16,}\\}").matcher(xml);
        while (m.find()) {
            Secret secret = Secret.decrypt(m.group());
            if (secret != null && plaintext.equals(secret.getPlainText())) {
                return true;
            }
        }
        return false;
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
