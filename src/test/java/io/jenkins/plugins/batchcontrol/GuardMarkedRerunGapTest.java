package io.jenkins.plugins.batchcontrol;

import hudson.model.CauseAction;
import hudson.model.Item;
import hudson.model.Queue;
import hudson.model.Run;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.model.GrantAction;
import io.jenkins.plugins.batchcontrol.security.BatchControlMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import java.lang.reflect.Constructor;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.jenkinsci.plugins.pipeline.modeldefinition.actions.RestartFlowFactoryAction;
import org.jenkinsci.plugins.pipeline.modeldefinition.causes.RestartDeclarativePipelineCause;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.cps.replay.ReplayAction;
import org.jenkinsci.plugins.workflow.cps.replay.ReplayCause;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.StrategyFixtures.T0;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage lane 2, scenario L2-13 (matrix rows T-GAP-254 .. T-GAP-258, note 277): re-runs of a run marked
 * under a grant, submitted outside the web form.
 *
 * <p>Basis: SPEC item 2, the D-58c line: "re-running a marked run (Replay, Pipeline Rebuild, Restart from
 * Stage, rebuild-plugin Rebuild) is refused with a plain message for everyone but an Overall/Administer
 * holder, before and after the review; a re-run of a marked run (an administrator's included) is marked
 * too, and a re-run whose source cannot be resolved is refused"; DECISIONS D-58c ("the refusal is
 * recorded like other refused re-runs"); SPEC item 6 ("a refused timer, upstream or Replay submission
 * writes a TRIGGER_BLOCKED change record (job, cause kind, the switch that blocked it)") and LIMITATIONS
 * 13 (cause kinds such as {@code REPLAY}); SPEC 6 usability ("every refusal, on the web, the CLI or a
 * Replay, tells the user in plain words why").
 *
 * <p>The marked run M is produced as AuthorizationEntryGuardTest does: bob, whose Run/Replay comes only
 * from his CONFIGURE window on {@code replay-me}, replays #1 as #2. Change control is on and run control
 * is off, so only the D-58c rule can refuse. alice holds Run/Replay natively (not an administrator); c1
 * holds Item/Configure natively and Job/Build. The CLI is the real client over HTTP
 * ({@link WindowCreateCliGapTest#cli}).
 *
 * <p>Written from docs/SPEC.md, docs/DECISIONS.md D-58b/D-58c, docs/LIMITATIONS.md and docs/TEST-MATRIX.md
 * only (no src/main knowledge).
 */
@WithJenkins
public class GuardMarkedRerunGapTest {

    private static final Pattern PLAIN_REASON = Pattern.compile("(?i)temporary|permission window|replayed under|marked|cannot be resolved|source");

    private JenkinsRule j;
    private WorkflowJob job;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        BatchControlMatrixAuthorizationStrategy strategy = StrategyFixtures.matrix(new BatchControlMatrixAuthorizationStrategy());
        strategy.add(Item.BUILD, PermissionEntry.user("c1"));
        strategy.add(ReplayAction.REPLAY, PermissionEntry.user("alice"));
        strategy.add(Item.BUILD, PermissionEntry.user("alice"));
        strategy.add(ReplayAction.REPLAY, PermissionEntry.user("anonymous"));
        strategy.add(Jenkins.READ, PermissionEntry.user("anonymous"));
        strategy.add(Item.READ, PermissionEntry.user("anonymous"));
        j.jenkins.setAuthorizationStrategy(strategy);
        StrategyFixtures.changeControlOn();
        BatchClock.setForTest(Clock.fixed(T0, ZoneOffset.UTC));

        job = j.jenkins.createProject(WorkflowJob.class, "replay-me");
        job.setDefinition(new CpsFlowDefinition("echo 'hello'", true));
        j.buildAndAssertSuccess(job);
        StrategyFixtures.grant("bob", "replay-me", Arrays.asList(GrantAction.CONFIGURE));
        replayAsBob(1, "echo 'planted'");
        j.waitUntilNoActivity();
        assertNotNull(job.getBuildByNumber(2), "fixture: bob's replay must have run as the marked #2");
    }

    @AfterEach
    public void resetClock() {
        BatchClock.reset();
    }

    /**
     * T-GAP-254 (L2-13, D-58c, SPEC 6 TRIGGER_BLOCKED): alice re-runs the marked #2 through the API
     * ({@code ACL.as2(alice)}, {@code ReplayAction#run2}, no HTTP request): nothing is queued, no build
     * follows, and a TRIGGER_BLOCKED record names alice and {@code replay-me} with the cause kind Replay.
     * Guard: alice's API replay of the unmarked #1 builds.
     */
    @Test
    public void t_gap_254_apiReplayOfAMarkedRunIsRefusedAndRecorded() throws Exception {
        int before = triggerBlocked().size();
        apiReplay(User.getById("alice", true).impersonate2(), 2);
        assertNothingQueued(3);
        List<ChangeRecord> after = newer(triggerBlocked(), before);
        assertTrue(after.stream().anyMatch(r -> "alice".equals(r.getUser()) && "replay-me".equals(r.getTarget())
                        && String.valueOf(r.getDetail()).toLowerCase(Locale.ROOT).contains("replay")),
                "a TRIGGER_BLOCKED record must name alice, replay-me and the Replay cause: " + describe(after));

        apiReplay(User.getById("alice", true).impersonate2(), 1);
        j.waitUntilNoActivity();
        assertNotNull(job.getBuildByNumber(3), "guard: alice's API replay of the unmarked #1 builds");
    }

    /**
     * T-GAP-255 (L2-13, D-58c, SPEC 6): anonymous holds Run/Replay and re-runs the marked #2 through the API:
     * nothing is queued and a TRIGGER_BLOCKED record on {@code replay-me} is written.
     */
    @Test
    public void t_gap_255_anonymousReplayOfAMarkedRunIsRefusedAndRecorded() throws Exception {
        int before = triggerBlocked().size();
        apiReplay(Jenkins.ANONYMOUS2, 2);
        assertNothingQueued(3);
        List<ChangeRecord> after = newer(triggerBlocked(), before);
        assertTrue(after.stream().anyMatch(r -> "replay-me".equals(r.getTarget())),
                "a TRIGGER_BLOCKED record on replay-me must be written: " + describe(after));
    }

    /**
     * T-GAP-256 (L2-13, D-58c, SPEC 6 "every refusal, on ... the CLI ... tells the user in plain words"):
     * alice runs {@code replay-pipeline replay-me -n 2} through the real CLI over HTTP: non-zero exit, a
     * plain refusal on stderr (no stack trace), and no build. Guard: the same command for the unmarked #1
     * exits 0 and builds.
     */
    @Test
    public void t_gap_256_cliReplayOfAMarkedRunIsRefusedPlainly() throws Exception {
        WindowCreateCliGapTest.CliResult refused = WindowCreateCliGapTest.cli(j, "alice", "echo 'again'", "replay-pipeline",
                "replay-me", "-n", "2");
        assertNotEquals(0, refused.code, "the CLI replay of the marked run must exit non-zero: " + refused);
        assertTrue(PLAIN_REASON.matcher(refused.err + refused.out).find(), "the CLI must say why in plain words: " + refused);
        assertFalse((refused.err + refused.out).matches("(?s).*\\n\\s*at [\\w$.]+\\(.*"), "no stack trace: " + refused);
        assertNothingQueued(3);

        WindowCreateCliGapTest.CliResult ok = WindowCreateCliGapTest.cli(j, "alice", "echo 'again'", "replay-pipeline",
                "replay-me", "-n", "1");
        assertEquals(0, ok.code, "guard: the CLI replay of the unmarked #1 succeeds: " + ok);
        j.waitUntilNoActivity();
        assertNotNull(job.getBuildByNumber(3), "guard: it builds");
    }

    /**
     * T-GAP-257 (L2-13, D-58c "a re-run whose source cannot be resolved is refused"): a Replay cause built
     * from the unmarked build #3, and a Restart-from-Stage cause with its flow action built from the
     * unmarked build #4, are submitted by alice after #3 and #4 are deleted: nothing is queued. Guard: the
     * same Replay cause built from the existing unmarked #1 is queued and builds.
     */
    @Test
    public void t_gap_257_reRunWhoseSourceIsGoneIsRefused() throws Exception {
        WorkflowRun third = j.buildAndAssertSuccess(job);
        WorkflowRun fourth = j.buildAndAssertSuccess(job);
        assertEquals(3, third.getNumber(), "fixture");
        assertEquals(4, fourth.getNumber(), "fixture");
        ReplayCause replayOfThird = replayCause(third);
        RestartDeclarativePipelineCause restartOfFourth = new RestartDeclarativePipelineCause(fourth, "only");
        RestartFlowFactoryAction restartFlow = new RestartFlowFactoryAction(fourth.getExternalizableId());
        third.delete();
        fourth.delete();
        assertTrue(job.getBuildByNumber(3) == null && job.getBuildByNumber(4) == null, "premise: the source builds are deleted");

        try (ACLContext ignored = ACL.as2(User.getById("alice", true).impersonate2())) {
            schedule(new CauseAction(replayOfThird));
            schedule(new CauseAction(restartOfFourth), restartFlow);
        }
        assertNothingQueued(5);

        try (ACLContext ignored = ACL.as2(User.getById("alice", true).impersonate2())) {
            schedule(new CauseAction(replayCause(job.getBuildByNumber(1))));
        }
        j.waitUntilNoActivity();
        assertNotNull(job.getBuildByNumber(5), "guard: a Replay cause whose source exists and is unmarked builds");
    }

    /**
     * T-GAP-258 (L2-13, D-58c: only re-runs of marked runs are marked): SYSTEM replays the unmarked #1 as
     * #3; c1 (not an administrator) can then re-run #3 by Pipeline Rebuild, so #3 is not marked. Guard:
     * c1's Pipeline Rebuild of the marked #2 is refused (no build).
     */
    @Test
    public void t_gap_258_systemReplayOfAnUnmarkedRunIsNotMarked() throws Exception {
        apiReplay(ACL.SYSTEM2, 1);
        j.waitUntilNoActivity();
        assertNotNull(job.getBuildByNumber(3), "SYSTEM's replay of the unmarked #1 must run");

        postAs("c1", job.getUrl() + "2/replay/rebuild");
        assertNothingQueued(4);

        postAs("c1", job.getUrl() + "3/replay/rebuild");
        j.waitUntilNoActivity();
        assertNotNull(job.getBuildByNumber(4), "c1's re-run of #3 builds, so SYSTEM's replay of an unmarked run is not marked");
    }

    // ---------------------------------------------------------------- helpers

    private void apiReplay(org.springframework.security.core.Authentication auth, int number) {
        ReplayAction action = job.getBuildByNumber(number).getAction(ReplayAction.class);
        assertNotNull(action, "fixture: #" + number + " offers Replay");
        try (ACLContext ignored = ACL.as2(auth)) {
            action.run2("echo 'replayed'", Collections.emptyMap());
        } catch (RuntimeException refused) {
            // a refusal may surface as an exception on this path; the queue is asserted either way
        }
    }

    private void schedule(hudson.model.Action... actions) {
        try {
            Queue.getInstance().schedule2(job, 0, Arrays.asList(actions));
        } catch (RuntimeException refused) {
            // a refusal may surface as an exception; the queue is asserted either way
        }
    }

    private static ReplayCause replayCause(Run<?, ?> original) throws Exception {
        Constructor<ReplayCause> c = ReplayCause.class.getDeclaredConstructor(Run.class, boolean.class);
        c.setAccessible(true);
        return c.newInstance(original, false);
    }

    private void assertNothingQueued(int nextBuildNumber) throws Exception {
        assertEquals(0, j.jenkins.getQueue().getItems().length, "nothing may be queued");
        j.waitUntilNoActivity();
        assertEquals(nextBuildNumber, job.getNextBuildNumber(), "no build number may be consumed");
        assertEquals(0, j.jenkins.getQueue().getItems().length, "the queue must still be empty");
    }

    private void replayAsBob(int number, String script) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login("bob");
        List<org.htmlunit.util.NameValuePair> params = new ArrayList<>();
        params.add(new org.htmlunit.util.NameValuePair("mainScript", script));
        params.add(new org.htmlunit.util.NameValuePair("json", "{\"mainScript\":\"" + script.replace("\"", "\\\"") + "\"}"));
        WebRequest req = new WebRequest(wc.createCrumbedUrl(job.getUrl() + number + "/replay/run"), HttpMethod.POST);
        req.setRequestParameters(params);
        wc.getPage(req);
    }

    private void postAs(String user, String path) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login(user);
        wc.getPage(new WebRequest(wc.createCrumbedUrl(path), HttpMethod.POST));
    }

    private static List<ChangeRecord> triggerBlocked() {
        return StrategyFixtures.records(ChangeType.TRIGGER_BLOCKED);
    }

    private static List<ChangeRecord> newer(List<ChangeRecord> all, int before) {
        return new ArrayList<>(all.subList(Math.min(before, all.size()), all.size()));
    }

    private static String describe(List<ChangeRecord> records) {
        List<String> out = new ArrayList<>();
        records.forEach(r -> out.add(r.getType() + " user=" + r.getUser() + " target=" + r.getTarget() + " detail=" + r.getDetail()));
        return out.toString();
    }
}
