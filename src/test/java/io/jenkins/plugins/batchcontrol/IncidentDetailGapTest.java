package io.jenkins.plugins.batchcontrol;

import hudson.EnvVars;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.Cause;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.ParameterDefinition;
import hudson.model.ParameterValue;
import hudson.model.ParametersAction;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.SimpleParameterDefinition;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.Incident;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import io.jenkins.plugins.batchcontrol.ops.IncidentService;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import io.jenkins.plugins.batchcontrol.store.FileStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;
import java.util.Arrays;
import java.util.Map;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.htmlunit.Page;
import org.htmlunit.html.HtmlForm;
import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.TestBuilder;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.kohsuke.stapler.StaplerRequest2;

import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.excerpt;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.uncontrolled;
import static io.jenkins.plugins.batchcontrol.RerunFallbackFixtures.DATE;
import static io.jenkins.plugins.batchcontrol.RerunFallbackFixtures.failedFreestyle;
import static io.jenkins.plugins.batchcontrol.RerunFallbackFixtures.forceFromRerun;
import static io.jenkins.plugins.batchcontrol.RerunFallbackFixtures.formUrl;
import static io.jenkins.plugins.batchcontrol.RerunFallbackFixtures.incidentFor;
import static io.jenkins.plugins.batchcontrol.RerunFallbackFixtures.openForm;
import static io.jenkins.plugins.batchcontrol.RerunFallbackFixtures.submitOne;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage lane 3, scenario L3-18: incident service details. Matrix rows T-GAP-363 .. T-GAP-368
 * (note 279).
 *
 * <p>Basis: SPEC 11 ("각 전이에 사용자·시각·코멘트가 남는다", RESOLVED에서 코멘트 추가는 가능; "Request rerun"
 * and D-72a "A request submitted from that prefilled form is linked to the incident only after the
 * server re-validates the incident reference it carries (the incident exists ...)"; "연결된 재실행이
 * SUCCESS면 Incident에 resolvedByRunId가 자동 기록된다"; "logTail 저장 시 해당 빌드의 비밀 파라미터 값 ... 마스킹"
 * (D-19)); SPEC 5 (D-72) "a sensitive value ({@code ParameterValue#isSensitive()} ...) appears as
 * {@code ********}"; SPEC 10 (every build is recorded); SPEC 12 (month filter) and SPEC 6 usability (no
 * error page). ARCHITECTURE 5: {@code incidents/<id>.xml}.
 *
 * <p>Run control on; u1 holds Overall/Read, Item/Read, BatchControl/Request and ViewHistory; a1 approves.
 *
 * <p>Written from docs/SPEC.md, docs/DECISIONS.md D-19, D-72 and D-72a and docs/ARCHITECTURE.md only
 * (no src/main knowledge).
 */
@WithJenkins
public class IncidentDetailGapTest {

    private static final String PLAIN_SECRET = "plain-but-sensitive-l3-Wx7";

    private JenkinsRule j;

    /** The descriptor of {@link SensitiveTextDefinition}, loaded for this class only. */
    @TestExtension
    public static final class SensitiveTextDescriptor extends ParameterDefinition.ParameterDescriptor {
        public SensitiveTextDescriptor() {
            super(SensitiveTextDefinition.class);
        }

        @Override
        public String getDisplayName() {
            return "L3-18 sensitive text parameter";
        }
    }

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.REQUEST, BatchControlPermissions.VIEW_HISTORY)
                        .everywhere().to("u1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1"));
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
    }

    /**
     * T-GAP-363 (L3-18; SPEC 11 "각 전이에 사용자·시각·코멘트가 남는다", SPEC 6 usability): on an open incident,
     * {@code IncidentService.get().addComment(id, "  ")} by u1 adds nothing: the incident's transitions
     * are unchanged (whether the call throws is not documented; observed and printed). Guard: a real
     * comment adds one transition naming u1 and the comment.
     */
    @Test
    public void t_gap_363_blankCommentAddsNothing() throws Exception {
        failedFreestyle(j, "l3-comment");
        Incident incident = incidentFor("l3-comment#1");
        int before = IncidentService.get().load(incident.getId()).getTransitions().size();
        Throwable thrown = null;
        try (ACLContext ignored = as("u1")) {
            IncidentService.get().addComment(incident.getId(), "  ");
        } catch (RuntimeException e) {
            thrown = e;
        }
        System.out.println("T-GAP-363 observation: addComment(\"  \") threw " + (thrown == null ? "nothing" : thrown.getClass().getName()));
        assertEquals(before, IncidentService.get().load(incident.getId()).getTransitions().size(), "a blank comment adds no transition");

        try (ACLContext ignored = as("u1")) {
            IncidentService.get().addComment(incident.getId(), "looked at the log");
        }
        Incident commented = IncidentService.get().load(incident.getId());
        assertEquals(before + 1, commented.getTransitions().size(), "guard: a real comment adds one transition");
        assertEquals("looked at the log", commented.getTransitions().get(commented.getTransitions().size() - 1).getComment(),
                "guard: the comment is recorded");
    }

    /**
     * T-GAP-364 (L3-18; D-72a "an invalid reference is ignored, never trusted"; "linked ... only after the
     * server re-validates the incident reference (the incident exists ...)"): the incident of
     * {@code l3-broken#1}'s file {@code incidents/<id>.xml} is overwritten with text that is not XML; u1
     * submits {@code l3-broken}'s Request Run form with that incident as its {@code fromRerun} reference.
     * The request is created and is not linked to any incident. Guard: the same on the intact incident of
     * {@code l3-intact#1} links the request.
     */
    @Test
    public void t_gap_364_referenceToAnUnreadableIncidentIsIgnored() throws Exception {
        FreeStyleProject broken = failedFreestyle(j, "l3-broken");
        Incident brokenIncident = incidentFor("l3-broken#1");
        FreeStyleProject intact = failedFreestyle(j, "l3-intact");
        Incident intactIncident = incidentFor("l3-intact#1");
        Path file = incidentFile(brokenIncident.getId());
        Files.writeString(file, "this is not an incident <<<", StandardCharsets.UTF_8);

        JenkinsRule.WebClient wc = TypedParameterFixtures.browser(j, "u1");
        HtmlForm form = openForm(wc, broken, formUrl(j, broken, Map.of("DATE", DATE), brokenIncident.getId()));
        forceFromRerun(form, brokenIncident.getId());
        String id = submitOne(wc, form, "a reference to an unreadable incident");
        RunRequest request = RunRequestService.get().load(id);
        assertTrue(request.getIncidentId() == null || request.getIncidentId().isEmpty(),
                "D-72a: a reference to an incident that cannot be read must leave the request unlinked, got " + request.getIncidentId());

        HtmlForm good = openForm(wc, intact, formUrl(j, intact, Map.of("DATE", DATE), intactIncident.getId()));
        forceFromRerun(good, intactIncident.getId());
        String linked = submitOne(wc, good, "a reference to an intact incident");
        assertEquals(intactIncident.getId(), RunRequestService.get().load(linked).getIncidentId(), "guard: an intact incident is linked");
    }

    /**
     * T-GAP-365 (L3-18; SPEC 11 "Request rerun on an incident creates a RunRequest linked by incidentId",
     * "연결된 재실행이 SUCCESS면 Incident에 resolvedByRunId가 자동 기록된다"): the {@code incidents/} directory refuses
     * writes while u1's {@code IncidentService.get().rerun} creates the rerun request: the request is
     * created, linked by its {@code incidentId}. After the directory is writable again, a1 approves, the
     * rerun succeeds and the incident records {@code resolvedByRunId = l3-ro#2}. Skipped where this
     * process can write despite the read-only bits.
     */
    @Test
    public void t_gap_365_rerunCreatedWhileIncidentsAreReadOnlyStillResolvesTheIncident() throws Exception {
        failedFreestyle(j, "l3-ro");
        Incident incident = incidentFor("l3-ro#1");
        Path dir = incidentFile(incident.getId()).getParent();
        RunRequest rerun;
        try {
            assertTrue(dir.toFile().setWritable(false, false), "fixture: incidents/ made read-only");
            incidentFile(incident.getId()).toFile().setWritable(false, false);
            Assumptions.assumeTrue(writesRefused(dir), "the file system does not refuse writes to a read-only directory for this process");
            try (ACLContext ignored = as("u1")) {
                rerun = IncidentService.get().rerun(incident.getId(), "a1");
            }
        } finally {
            dir.toFile().setWritable(true, false);
            incidentFile(incident.getId()).toFile().setWritable(true, false);
        }
        assertNotNull(rerun, "SPEC 11: the rerun request is created");
        assertEquals(incident.getId(), RunRequestService.get().load(rerun.getId()).getIncidentId(), "SPEC 11: it is linked by incidentId");
        try (ACLContext ignored = as("a1")) {
            RunRequestService.get().approve(rerun.getId(), "go");
        }
        j.waitUntilNoActivity();
        FreeStyleBuild second = ((FreeStyleProject) j.jenkins.getItemByFullName("l3-ro")).getBuildByNumber(2);
        assertNotNull(second, "the approved rerun ran");
        j.assertBuildStatusSuccess(second);
        assertEquals("l3-ro#2", IncidentService.get().load(incident.getId()).getResolvedByRunId(),
                "SPEC 11: the successful linked rerun is recorded as resolvedByRunId");
    }

    /**
     * T-GAP-366 (L3-18; SPEC 10 "Freestyle과 Pipeline 빌드가 모두 기록된다", SPEC 6 usability): u1's rerun request
     * of the incident of {@code l3-gone#1} is created; the incident's file is deleted; a1 approves; the
     * rerun build ends SUCCESS and its run record {@code l3-gone#2} is written.
     */
    @Test
    public void t_gap_366_rerunWhoseIncidentVanishedStillCompletesAndIsRecorded() throws Exception {
        failedFreestyle(j, "l3-gone");
        Incident incident = incidentFor("l3-gone#1");
        RunRequest rerun;
        try (ACLContext ignored = as("u1")) {
            rerun = IncidentService.get().rerun(incident.getId(), "a1");
        }
        Files.delete(incidentFile(incident.getId()));
        try (ACLContext ignored = as("a1")) {
            RunRequestService.get().approve(rerun.getId(), "go");
        }
        j.waitUntilNoActivity();
        FreeStyleBuild second = ((FreeStyleProject) j.jenkins.getItemByFullName("l3-gone")).getBuildByNumber(2);
        assertNotNull(second, "the approved rerun ran");
        j.assertBuildStatusSuccess(second);
        assertTrue(FileStore.get().listRunRecords(YearMonth.now(BatchClock.clock())).stream()
                        .anyMatch(rec -> "l3-gone#2".equals(rec.getRunId())),
                "SPEC 10: the rerun's run record is written although its incident vanished");
    }

    /**
     * T-GAP-367 (L3-18; SPEC 11 D-19 "logTail 저장 시 해당 빌드의 비밀 파라미터 값 ... 마스킹", SPEC 5 (D-72) a value
     * whose {@code isSensitive()} is true is {@code ********}): a job with a parameter of a test type
     * whose value is plain text but flagged sensitive; its build prints the value and fails. The build's
     * own console shows the value (premise), and the incident's log tail shows the mask
     * {@code ********} and not the value.
     */
    @Test
    public void t_gap_367_logTailMasksAPlainTextValueFlaggedSensitive() throws Exception {
        FreeStyleProject job = uncontrolled(j.createFreeStyleProject("l3-mask"));
        job.addProperty(new ParametersDefinitionProperty(new SensitiveTextDefinition("TOKEN")));
        job.getBuildersList().add(new PrintAndFail());
        BatchControlFixtures.activateAsAdmin(job);
        FreeStyleBuild build = j.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0, (Cause) null,
                new ParametersAction(new SensitiveTextValue("TOKEN", PLAIN_SECRET))));
        j.waitUntilNoActivity();
        assertTrue(JenkinsRule.getLog(build).contains(PLAIN_SECRET), "premise: the build printed the value");
        setBatchControl(job, new BatchControlJobProperty(true));
        Incident incident = IncidentService.get().load(incidentFor("l3-mask#1").getId());
        String tail = String.valueOf(incident.getLogTail());
        assertTrue(tail.contains("printed:"), "premise: the log tail holds the printed line: " + excerpt(tail));
        assertFalse(tail.contains(PLAIN_SECRET), "D-19: the log tail must not carry the sensitive value: " + excerpt(tail));
        assertTrue(tail.contains("********"), "D-19, D-72: the log tail shows the mask instead: " + excerpt(tail));
    }

    /**
     * T-GAP-368 (L3-18; SPEC 12 month filter, SPEC 6 usability "no ... stack trace, 'Oops!' page"):
     * u1's {@code GET batch-control/incidents/?month=garbage} answers without a server error or crash
     * page, and either refuses the month in plain words (4xx naming the month form) or shows the
     * current month, listing the incident of {@code l3-month#1}. Which of the two is not documented
     * (note 279; observed: printed). Guard: {@code ?month=<current month>} lists the incident.
     */
    @Test
    public void t_gap_368_garbageMonthIsHandledWithoutAnErrorPage() throws Exception {
        failedFreestyle(j, "l3-month");
        String month = StoreDataFixtures.monthName(YearMonth.now(BatchClock.clock()));
        HtmlPage current = UsabilityFixtures.htmlPage(j, "u1", "batch-control/incidents/?month=" + month);
        assertTrue(current.asNormalizedText().contains("l3-month"), "guard: the current month lists the incident");

        Page garbage = UsabilityFixtures.get(j, UsabilityFixtures.clientNoJs(j, "u1"), "batch-control/incidents/?month=garbage");
        int code = garbage.getWebResponse().getStatusCode();
        String text = UsabilityFixtures.text(garbage);
        System.out.println("T-GAP-368 observation: ?month=garbage answered HTTP " + code);
        assertTrue(code < 500, "?month=garbage must not end in a server error: " + excerpt(text));
        UsabilityFixtures.assertPlainRefusal("?month=garbage", text, null);
        if (code >= 400) {
            assertTrue(text.contains("YYYY-MM") || text.toLowerCase().contains("month"), "a refused month is explained: " + excerpt(text));
        } else {
            assertTrue(text.contains("l3-month") && text.contains(month), "a page shown for an unreadable month shows the current month "
                    + month + " with its incident: " + excerpt(text));
        }
    }

    // ------------------------------------------------------------------ helpers

    private Path incidentFile(String id) {
        Path file = j.jenkins.getRootDir().toPath().resolve("batch-control/incidents/" + id + ".xml");
        assertTrue(Files.isRegularFile(file), "premise (ARCHITECTURE 5): the incident is stored at " + file);
        return file;
    }

    private static boolean writesRefused(Path dir) {
        Path probe = dir.resolve("probe-" + System.nanoTime() + ".tmp");
        try {
            Files.createFile(probe);
            Files.delete(probe);
            return false;
        } catch (IOException expected) {
            return true;
        }
    }

    private static ACLContext as(String userId) {
        return ACL.as2(User.getById(userId, true).impersonate2());
    }

    /** A parameter whose values are plain text flagged sensitive. */
    public static final class SensitiveTextDefinition extends SimpleParameterDefinition {
        private static final long serialVersionUID = 1L;

        public SensitiveTextDefinition(String name) {
            super(name);
        }

        @Override
        public ParameterValue createValue(String value) {
            return new SensitiveTextValue(getName(), value);
        }

        @Override
        public ParameterValue createValue(StaplerRequest2 req, JSONObject jo) {
            return createValue(jo.optString("value", ""));
        }

        @Override
        public ParameterValue getDefaultParameterValue() {
            return new SensitiveTextValue(getName(), "");
        }
    }

    /** A plain-text value whose {@code isSensitive()} is true; it puts the text into the build environment. */
    public static final class SensitiveTextValue extends ParameterValue {
        private static final long serialVersionUID = 1L;

        private final String text;

        public SensitiveTextValue(String name, String text) {
            super(name);
            this.text = text;
        }

        @Override
        public Object getValue() {
            return text;
        }

        @Override
        public boolean isSensitive() {
            return true;
        }

        @Override
        public void buildEnvironment(Run<?, ?> build, EnvVars env) {
            env.put(name, text);
        }

        @Override
        public String toString() {
            return "(SensitiveTextValue) " + name;
        }
    }

    /** Prints the TOKEN variable and fails. */
    public static final class PrintAndFail extends TestBuilder {
        @Override
        public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener)
                throws InterruptedException, IOException {
            listener.getLogger().println("printed: " + build.getEnvironment(listener).get("TOKEN"));
            return false;
        }
    }
}
