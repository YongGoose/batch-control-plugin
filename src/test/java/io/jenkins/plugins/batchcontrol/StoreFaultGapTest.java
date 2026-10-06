package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.ParameterDefinition;
import hudson.model.ParameterValue;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.SimpleParameterDefinition;
import hudson.model.StringParameterDefinition;
import hudson.model.StringParameterValue;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.model.GrantAction;
import io.jenkins.plugins.batchcontrol.model.GrantRequest;
import io.jenkins.plugins.batchcontrol.model.GrantScope;
import io.jenkins.plugins.batchcontrol.model.Incident;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import io.jenkins.plugins.batchcontrol.ops.IncidentService;
import io.jenkins.plugins.batchcontrol.policy.GrantRequestService;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.io.IOException;
import java.io.ObjectStreamException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.WebResponse;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.kohsuke.stapler.StaplerRequest2;

import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.excerpt;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static io.jenkins.plugins.batchcontrol.RerunFallbackFixtures.failedFreestyle;
import static io.jenkins.plugins.batchcontrol.RerunFallbackFixtures.incidentFor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage lane 3, scenarios L3-11 (service lookups), L3-12 (the rows that need no restart), L3-25 and
 * L3-26: identifiers that are not store identifiers, unreadable stored records, a configuration
 * snapshot that cannot be written and a values file that cannot be written. Matrix rows T-GAP-339,
 * T-GAP-341, T-GAP-342, T-GAP-385 and T-GAP-386 (note 279).
 *
 * <p>Basis: docs/reports/security-39.md S-39-01 (fix direction: "validate the request id shape ...
 * before building any path"; an id that is not a store identifier names no record) and ARCHITECTURE 5
 * (ids; "디코딩 시 경로 탈출(..) 검증"); D-68 (identifiers are UUIDs or the earlier
 * {@code yyyyMMdd-HHmmss-xxxxxx} form); SPEC 5 (D-74) "A request with parameters whose values file is
 * missing or unreadable cannot be approved or run" and "a failed save leaves nothing behind", ARCHITECTURE
 * 5 "if the values file cannot be written, {@code <id>.xml} is deleted"; SPEC 2 line 51 and SPEC 6
 * usability (no error page from our own code); SPEC 9 "생성·수정·삭제 ... 경로와 무관하게 ... 자동으로 기록" and
 * ARCHITECTURE 1 "기록은 이벤트 리스너에서 항상 남긴다".
 *
 * <p>Fault injection: chmod (skipped where this process can still read), a non-empty directory where a
 * file is expected, a hand-planted file, and a parameter value of a test type that XStream cannot write.
 *
 * <p>Run control on; u1 holds Overall/Read, Item/Read, BatchControl/Request, RequestGrant and
 * ViewHistory; a1 approves.
 *
 * <p>Written from docs/SPEC.md, docs/DECISIONS.md D-68, D-72b and D-74, docs/ARCHITECTURE.md and the
 * fix direction of docs/reports/security-39.md S-39-01 only (no src/main knowledge).
 */
@WithJenkins
public class StoreFaultGapTest {

    private JenkinsRule j;
    private FreeStyleProject job;

    /** The descriptor of {@link UnwritableDefinition}, loaded for this class only. */
    @TestExtension
    public static final class UnwritableDescriptor extends ParameterDefinition.ParameterDescriptor {
        public UnwritableDescriptor() {
            super(UnwritableDefinition.class);
        }

        @Override
        public String getDisplayName() {
            return "L3-26 unwritable parameter";
        }
    }

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.REQUEST, BatchControlPermissions.REQUEST_GRANT,
                        BatchControlPermissions.VIEW_HISTORY).everywhere().to("u1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1"));
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
        job = j.createFreeStyleProject("l3-store");
        job.addProperty(new ParametersDefinitionProperty(new StringParameterDefinition("P", "default")));
        setBatchControl(job, new BatchControlJobProperty(true));
    }

    /**
     * T-GAP-339 (L3-11; S-39-01, D-68, ARCHITECTURE 5): for an existing run request with a parameter
     * (so its values file exists), {@code RunRequestService.get().load("<id>.values")} is null;
     * {@code IncidentService.get().load("a.b")} is null even though a copy of a real incident was planted
     * at {@code incidents/a.b.xml} under that id, and u1's {@code IncidentService.get().rerun("a.b", "a1")}
     * is refused and creates no request; {@code GrantRequestService.get().load("")} and {@code load} of a
     * 101-character id are null (no file is planted for these: no document limits an identifier's length,
     * note 279). Guards: the real ids load.
     */
    @Test
    public void t_gap_339_idsThatAreNotStoreIdentifiersNameNoRecord() throws Exception {
        RunRequest run = as("u1", () -> RunRequestService.get().create(job, List.of(new StringParameterValue("P", "v")), "values here", "a1"));
        Path values = store().resolve("requests/run/" + run.getId() + ".values.xml");
        assertTrue(Files.isRegularFile(values), "premise (ARCHITECTURE 5): the values file is stored at " + values);
        assertNotNull(RunRequestService.get().load(run.getId()), "guard: the request loads by its id");
        assertNull(RunRequestService.get().load(run.getId() + ".values"), "S-39-01: '<id>.values' names no request");

        failedFreestyle(j, "l3-ids");
        Incident incident = incidentFor("l3-ids#1");
        assertNotNull(IncidentService.get().load(incident.getId()), "guard: the incident loads by its id");
        Path incidentFile = store().resolve("incidents/" + incident.getId() + ".xml");
        Path planted = store().resolve("incidents/a.b.xml");
        Files.writeString(planted, Files.readString(incidentFile, StandardCharsets.UTF_8).replace(incident.getId(), "a.b"), StandardCharsets.UTF_8);
        assertNull(IncidentService.get().load("a.b"), "S-39-01: 'a.b' is not an incident identifier, a planted file is not read");
        Set<String> runsBefore = ApproverFormFixtures.runRequestIds();
        Throwable refused = null;
        try {
            as("u1", () -> IncidentService.get().rerun("a.b", "a1"));
        } catch (Exception e) {
            refused = e;
        }
        assertNotNull(refused, "a rerun of 'a.b' must be refused");
        assertEquals(runsBefore, ApproverFormFixtures.runRequestIds(), "the refused rerun creates no request");

        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setChangeControlEnabled(true); // permission windows can be requested only while change control is on (LIMITATIONS 29)
        cfg.save();
        GrantRequest grant = as("u1", () -> GrantRequestService.get().create(new GrantScope(GrantScope.Type.ITEM, "l3-store"),
                List.of(GrantAction.CONFIGURE), 30, "a window", "a1"));
        assertNotNull(GrantRequestService.get().load(grant.getId()), "guard: the grant request loads by its id");
        assertNull(GrantRequestService.get().load(""), "an empty id names no grant request");
        assertNull(GrantRequestService.get().load("g".repeat(101)), "a 101-character id names no grant request");
    }

    /**
     * T-GAP-341 (L3-12; SPEC 2 line 51, SPEC 6 usability): u1 opens {@code batch-control/requests/}
     * once; then the file {@code requests/run/<id>.xml} of u1's pending request R is made unreadable.
     * R's page answers without a server error or crash page (404, or the request from memory; observed
     * and printed), the list still answers 200, and another request's page renders. Guard: after the
     * permissions are restored R's page renders. Skipped where this process can still read the file.
     */
    @Test
    public void t_gap_341_unreadableRequestFileDoesNotBreakTheRequestPages() throws Exception {
        String r = ApproverFormFixtures.submitRunOk(j, "u1", job, "request R", "a1");
        String other = ApproverFormFixtures.submitRunOk(j, "u1", job, "request R2", "a1");
        assertEquals(200, ApproverFormFixtures.get(j, "u1", "batch-control/requests/").getStatusCode(), "premise: the list opens");
        Path file = store().resolve("requests/run/" + r + ".xml");
        try {
            assertTrue(file.toFile().setReadable(false, false), "fixture: R's file made unreadable");
            Assumptions.assumeFalse(Files.isReadable(file), "the file system does not refuse reads for this process");
            WebResponse page = ApproverFormFixtures.get(j, "u1", "batch-control/requests/" + r + "/");
            System.out.println("T-GAP-341 observation: the page of the unreadable request answers HTTP " + page.getStatusCode());
            assertTrue(page.getStatusCode() < 500, "SPEC 6: R's page must not be a server error: " + excerpt(page.getContentAsString()));
            UsabilityFixtures.assertPlainRefusal("R's page", page.getContentAsString(), null);
            if (page.getStatusCode() == 200) {
                assertTrue(page.getContentAsString().contains("request R"), "a page that renders shows R: " + excerpt(page.getContentAsString()));
            } else {
                assertEquals(404, page.getStatusCode(), "a page that does not render R answers 404 (no such record)");
            }
            WebResponse list = ApproverFormFixtures.get(j, "u1", "batch-control/requests/");
            assertEquals(200, list.getStatusCode(), "SPEC 6: the list still renders: " + excerpt(list.getContentAsString()));
            WebResponse otherPage = ApproverFormFixtures.get(j, "u1", "batch-control/requests/" + other + "/");
            assertEquals(200, otherPage.getStatusCode(), "another request's page renders");
            assertTrue(otherPage.getContentAsString().contains("request R2"), "and shows that request");
        } finally {
            file.toFile().setReadable(true, false);
        }
        assertEquals(200, ApproverFormFixtures.get(j, "u1", "batch-control/requests/" + r + "/").getStatusCode(),
                "guard: once readable again R's page renders");
    }

    /**
     * T-GAP-342 (L3-12; SPEC 5 D-74 "A request with parameters whose values file is missing or unreadable
     * cannot be approved or run"): the values file of u1's pending request on {@code l3-store} (string
     * parameter P) is made unreadable; a1's {@code RunRequestService.get().approve} is refused and the
     * request stays PENDING with no build. Guard: after the permissions are restored a1's approval runs
     * the build with P's value. Skipped where this process can still read the file.
     */
    @Test
    public void t_gap_342_unreadableValuesFileBlocksApproval() throws Exception {
        RunRequest request = as("u1", () -> RunRequestService.get().create(job, List.of(new StringParameterValue("P", "l3-value")),
                "needs its values", "a1"));
        Path values = store().resolve("requests/run/" + request.getId() + ".values.xml");
        assertTrue(Files.isRegularFile(values), "premise (ARCHITECTURE 5): the values file is stored at " + values);
        try {
            assertTrue(values.toFile().setReadable(false, false), "fixture: the values file made unreadable");
            Assumptions.assumeFalse(Files.isReadable(values), "the file system does not refuse reads for this process");
            Throwable refused = null;
            try {
                as("a1", () -> {
                    RunRequestService.get().approve(request.getId(), "ok");
                    return null;
                });
            } catch (Exception e) {
                refused = e;
            }
            assertNotNull(refused, "SPEC 5 D-74: approving a request whose values file cannot be read must be refused");
            assertEquals(RequestStatus.PENDING, RunRequestService.get().load(request.getId()).getStatus(), "the request stays PENDING");
            j.waitUntilNoActivity();
            assertTrue(job.getBuilds().isEmpty(), "nothing runs");
        } finally {
            values.toFile().setReadable(true, false);
        }
        as("a1", () -> {
            RunRequestService.get().approve(request.getId(), "ok now");
            return null;
        });
        j.waitUntilNoActivity();
        assertNotNull(job.getBuildByNumber(1), "guard: once readable the approval runs the build");
        assertEquals("l3-value", ((StringParameterValue) job.getBuildByNumber(1).getAction(hudson.model.ParametersAction.class)
                .getParameter("P")).getValue(), "guard: with the stored value");
    }

    /**
     * T-GAP-385 (L3-25; SPEC 9, ARCHITECTURE 1 "기록은 이벤트 리스너에서 항상 남긴다"): the snapshot file of job
     * {@code l3-snap} ({@code snapshots/l3-snap.xml}, ARCHITECTURE 5; a top-level name is not encoded) is
     * replaced by a non-empty directory; the administrator saves the job's configuration with a changed
     * description through {@code config.xml}: the save succeeds (HTTP below 400, the description is
     * stored) and a CONFIGURE record for {@code l3-snap} is written. Guard: the same edit of
     * {@code l3-snap-control}, whose snapshot can be written, writes one CONFIGURE record.
     */
    @Test
    public void t_gap_385_configureIsRecordedWhenTheSnapshotCannotBeWritten() throws Exception {
        FreeStyleProject control = j.createFreeStyleProject("l3-snap-control");
        int controlBefore = configureRecords("l3-snap-control");
        JenkinsRule.WebClient admin = ApproverFormFixtures.client(j, "admin");
        WebRequest controlPost = new WebRequest(admin.createCrumbedUrl(control.getUrl() + "config.xml"), HttpMethod.POST);
        controlPost.setAdditionalHeader("Content-Type", "application/xml");
        controlPost.setRequestBody(withDescription(control.getConfigFile().asString(), "l3 snapshot edit"));
        assertTrue(admin.getPage(controlPost).getWebResponse().getStatusCode() < 400, "guard: the control save succeeds");
        assertEquals(controlBefore + 1, configureRecords("l3-snap-control"), "guard: the same edit of a job whose snapshot can be written"
                + " writes one CONFIGURE record");

        FreeStyleProject snap = j.createFreeStyleProject("l3-snap");
        Path snapshot = store().resolve("snapshots/l3-snap.xml");
        try {
            if (Files.exists(snapshot)) {
                Files.delete(snapshot);
            }
            Files.createDirectories(snapshot);
            Files.writeString(snapshot.resolve("blocker.txt"), "a non-empty directory where the snapshot was", StandardCharsets.UTF_8);
            int before = configureRecords("l3-snap");
            String xml = snap.getConfigFile().asString();
            String edited = withDescription(xml, "l3 snapshot edit");
            assertFalse(edited.equals(xml), "fixture: the description changes");
            JenkinsRule.WebClient wc = ApproverFormFixtures.client(j, "admin");
            WebRequest post = new WebRequest(wc.createCrumbedUrl(snap.getUrl() + "config.xml"), HttpMethod.POST);
            post.setAdditionalHeader("Content-Type", "application/xml");
            post.setRequestBody(edited);
            WebResponse answer = wc.getPage(post).getWebResponse();
            assertTrue(answer.getStatusCode() < 400, "SPEC 9: the save succeeds although the snapshot cannot be written, got "
                    + answer.getStatusCode() + ": " + excerpt(answer.getContentAsString()));
            assertEquals("l3 snapshot edit", ((FreeStyleProject) j.jenkins.getItemByFullName("l3-snap")).getDescription(), "the change is saved");
            assertEquals(before + 1, configureRecords("l3-snap"), "ARCHITECTURE 1: the CONFIGURE record is written anyway");
        } finally {
            if (Files.isDirectory(snapshot)) {
                try (Stream<Path> files = Files.list(snapshot)) {
                    for (Path p : files.toList()) {
                        Files.delete(p);
                    }
                }
                Files.delete(snapshot);
            }
        }
    }

    /**
     * T-GAP-386 (L3-26; SPEC 5 D-72b "a failed save leaves nothing behind", ARCHITECTURE 5 "if the values
     * file cannot be written, {@code <id>.xml} is deleted"): a job whose parameter is of a test type whose
     * value cannot be written as XML (its {@code writeReplace} throws). u1's run request submission
     * through the service is refused, and nothing is left under {@code requests/run/} (no {@code <id>.xml}
     * and no values file). Whether the refusal came before or after the first file was written cannot be
     * told from outside (note 279). Guard: a request with a string value on another job is stored.
     */
    @Test
    public void t_gap_386_valueThatCannotBeWrittenLeavesNoRequestFile() throws Exception {
        FreeStyleProject unwritable = j.createFreeStyleProject("l3-unwritable");
        unwritable.addProperty(new ParametersDefinitionProperty(new UnwritableDefinition("U")));
        setBatchControl(unwritable, new BatchControlJobProperty(true));
        Path dir = store().resolve("requests/run");
        Set<String> before = files(dir);
        Set<String> idsBefore = ApproverFormFixtures.runRequestIds();
        Throwable refused = null;
        try {
            as("u1", () -> RunRequestService.get().create(unwritable, List.of(new UnwritableValue("U", "cannot be stored")),
                    "a value XStream cannot write", "a1"));
        } catch (Exception e) {
            refused = e;
        }
        assertNotNull(refused, "D-72b: a submission whose value cannot be stored is refused");
        assertEquals(before, files(dir), "D-72b, ARCHITECTURE 5: nothing is left under requests/run/");
        assertEquals(idsBefore, ApproverFormFixtures.runRequestIds(), "no request is listed");

        RunRequest ok = as("u1", () -> RunRequestService.get().create(job, List.of(new StringParameterValue("P", "fine")), "a plain value", "a1"));
        assertTrue(Files.isRegularFile(dir.resolve(ok.getId() + ".xml")), "guard: a storable request is written");
    }

    // ------------------------------------------------------------------ helpers

    /** Sets the description in a job's config.xml text, whatever form the element has. */
    static String withDescription(String xml, String text) {
        if (xml.contains("<description/>")) {
            return xml.replace("<description/>", "<description>" + text + "</description>");
        }
        if (xml.matches("(?s).*<description>.*?</description>.*")) {
            return xml.replaceFirst("(?s)<description>.*?</description>", "<description>" + text + "</description>");
        }
        return xml.replaceFirst("(<(project|flow-definition)[^>]*>)", "$1<description>" + text + "</description>");
    }

    private Path store() {
        return j.jenkins.getRootDir().toPath().resolve("batch-control");
    }

    private static int configureRecords(String target) {
        return (int) ApproverFormFixtures.records(ChangeType.CONFIGURE).stream().filter(r -> target.equals(r.getTarget())).count();
    }

    private static Set<String> files(Path dir) throws IOException {
        Set<String> out = new TreeSet<>();
        if (Files.isDirectory(dir)) {
            try (Stream<Path> list = Files.list(dir)) {
                list.forEach(p -> out.add(p.getFileName().toString()));
            }
        }
        return out;
    }

    private interface Body<T> {
        T run() throws Exception;
    }

    private static <T> T as(String userId, Body<T> body) throws Exception {
        try (ACLContext ignored = ACL.as2(User.getById(userId, true).impersonate2())) {
            return body.run();
        }
    }

    /** A parameter whose values cannot be written by XStream. */
    public static final class UnwritableDefinition extends SimpleParameterDefinition {
        private static final long serialVersionUID = 1L;

        public UnwritableDefinition(String name) {
            super(name);
        }

        @Override
        public ParameterValue createValue(String value) {
            return new UnwritableValue(getName(), value);
        }

        @Override
        public ParameterValue createValue(StaplerRequest2 req, JSONObject jo) {
            return createValue(jo.optString("value", ""));
        }

        @Override
        public ParameterValue getDefaultParameterValue() {
            return new UnwritableValue(getName(), "");
        }
    }

    /** A plain value whose {@code writeReplace} throws, so XStream cannot write it. */
    public static final class UnwritableValue extends ParameterValue {
        private static final long serialVersionUID = 1L;

        private final String text;

        public UnwritableValue(String name, String text) {
            super(name);
            this.text = text;
        }

        @Override
        public Object getValue() {
            return text;
        }

        private Object writeReplace() throws ObjectStreamException {
            throw new IllegalStateException("L3-26: this value cannot be written");
        }

        @Override
        public String toString() {
            return "(UnwritableValue) " + name;
        }
    }
}
