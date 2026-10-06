package io.jenkins.plugins.batchcontrol;

import hudson.model.Cause;
import hudson.model.CauseAction;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.Result;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import io.jenkins.plugins.batchcontrol.store.BlockedAttemptAudit;
import io.jenkins.plugins.batchcontrol.store.FileStore;
import io.jenkins.plugins.batchcontrol.store.Store;
import java.io.ByteArrayInputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.YearMonth;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import javax.xml.transform.stream.StreamSource;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.WebResponse;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.cps.replay.ReplayAction;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.FailureBuilder;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.token;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.uncontrolled;
import static io.jenkins.plugins.batchcontrol.PluginInteractionFixtures.assertBlocked;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage lane 2, scenario L2-18 (matrix rows T-GAP-266 .. T-GAP-269, note 277): queue gate refusals
 * outside the web form.
 *
 * <p>Basis: SPEC item 6 "UI, REST API, CLI 등 승인 없는 수동 실행은 모두 큐 진입 단계에서 차단" and the
 * acceptance line listing CLI {@code build} and Pipeline Replay; e2e-03 DEF-14 (a CLI caller gets a
 * one-line error, ARCHITECTURE 6: user-originated refusals throw a plain Failure); SPEC 6 ("every refused
 * retry ... is recorded", the remote-run record {@code REMOTE_RUN_BLOCKED}); SPEC 6a (an unattended
 * cause needs activation, so the jobs are activated to isolate the approval rule); ARCHITECTURE section 1
 * (a record that cannot be written does not change a refusal).
 *
 * <p>Users (PluginInteractionFixtures): u1 holds Overall/Read, Job/Read, Job/Build, BatchControl/Request
 * and Run/Replay; a1 approves. The CLI is the real client over HTTP ({@link WindowCreateCliGapTest#cli}).
 *
 * <p>Written from docs/SPEC.md, docs/DECISIONS.md, docs/ARCHITECTURE.md and docs/TEST-MATRIX.md only (no
 * src/main knowledge).
 */
@WithJenkins
public class GuardQueueGateGapTest {

    private static final String TOKEN = "gate-token-7";

    private JenkinsRule j;
    private Store original;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, Item.BUILD, BatchControlPermissions.REQUEST, ReplayAction.REPLAY)
                        .everywhere().to("u1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1"));
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
    }

    @AfterEach
    public void tearDown() {
        if (original != null) {
            BlockedAttemptAudit.swapStoreForTesting(original);
        }
    }

    /**
     * T-GAP-266 (L2-18, SPEC 6 Pipeline Replay and CLI, DEF-14): u1 runs {@code replay-pipeline ar-pipe -n 1}
     * through the real CLI over HTTP for a run of the approval-required Pipeline job {@code ar-pipe}: the
     * command exits non-zero, its error output is one line naming the approval, and nothing is queued.
     * Guard: the same command for the uncontrolled, activated {@code free-pipe} exits 0 and builds.
     */
    @Test
    public void t_gap_266_cliReplayOfAnApprovalRequiredJobIsRefusedInOneLine() throws Exception {
        WorkflowJob job = pipelineBuiltOnce("ar-pipe");
        setBatchControl(job, new BatchControlJobProperty(true));
        WindowCreateCliGapTest.CliResult refused = WindowCreateCliGapTest.cli(j, "u1", "echo 'again'", "replay-pipeline", "ar-pipe",
                "-n", "1");
        assertNotEquals(0, refused.code, "the CLI replay must exit non-zero: " + refused);
        String[] lines = refused.err.trim().split("\\R");
        assertEquals(1, lines.length, "the CLI refusal must be one line: " + refused);
        assertTrue(lines[0].toLowerCase(Locale.ROOT).contains("approv"), "the line must name the approval: " + refused);
        assertBlocked(j, job, 2, 1);

        WorkflowJob free = pipelineBuiltOnce("free-pipe");
        WindowCreateCliGapTest.CliResult ok = WindowCreateCliGapTest.cli(j, "u1", "echo 'again'", "replay-pipeline", "free-pipe",
                "-n", "1");
        assertEquals(0, ok.code, "guard: the CLI replay of an uncontrolled job succeeds: " + ok);
        j.waitUntilNoActivity();
        assertNotNull(free.getBuildByNumber(2), "guard: the uncontrolled job builds");
    }

    /**
     * T-GAP-267 (L2-18, SPEC 6 REST/remote run, REMOTE_RUN_BLOCKED): a submission carrying a remote (token)
     * cause, scheduled from test code without an HTTP request, for the activated approval-required
     * {@code remote-x}: nothing is queued and a REMOTE_RUN_BLOCKED record names {@code remote-x}. Guard: the
     * same submission for the activated {@code remote-free} (no approval required) builds.
     */
    @Test
    public void t_gap_267_remoteCauseFromCodeIsRefusedAndRecorded() throws Exception {
        FreeStyleProject job = activated("remote-x");
        setBatchControl(job, new BatchControlJobProperty(true));
        int before = remoteBlocked().size();
        job.scheduleBuild2(0, new CauseAction(new Cause.RemoteCause("127.0.0.1", "token call")));
        assertBlocked(j, job, 1, 0);
        List<ChangeRecord> after = remoteBlocked();
        assertTrue(after.size() > before && after.stream().skip(before).anyMatch(r -> "remote-x".equals(r.getTarget())),
                "a REMOTE_RUN_BLOCKED record must name remote-x: " + after.stream().map(r -> r.getTarget() + " " + r.getDetail())
                        .collect(Collectors.toList()));

        FreeStyleProject free = activated("remote-free");
        free.scheduleBuild2(0, new CauseAction(new Cause.RemoteCause("127.0.0.1", "token call")));
        j.waitUntilNoActivity();
        assertEquals(1, free.getBuilds().size(), "guard: the remote submission of a job without approval builds");
    }

    /**
     * T-GAP-268 (L2-18, SPEC 6 REST, e2e-run3 DEF-34): u1's {@code POST job/token-x/build/?token=...} (trailing
     * slash) on the approval-required {@code token-x} answers exactly as {@code job/token-x/build?token=...}:
     * the same status (not 2xx/3xx), the same content type and the same refusal text naming the approval;
     * nothing is queued either way.
     */
    @Test
    public void t_gap_268_trailingSlashTokenBuildGetsTheSameRefusal() throws Exception {
        FreeStyleProject job = withToken(activated("token-x"));
        setBatchControl(job, new BatchControlJobProperty(true));
        WebResponse plain = tokenBuild(job.getUrl() + "build?token=" + TOKEN);
        WebResponse slash = tokenBuild(job.getUrl() + "build/?token=" + TOKEN);
        assertTrue(plain.getStatusCode() >= 400, "the token build must be refused, got " + plain.getStatusCode());
        assertEquals(plain.getStatusCode(), slash.getStatusCode(), "the trailing-slash form must answer the same status");
        assertEquals(contentType(plain), contentType(slash), "the trailing-slash form must answer the same content type");
        String plainText = RenameRefusalFixtures.visible(plain.getContentAsString());
        assertEquals(plainText, RenameRefusalFixtures.visible(slash.getContentAsString()), "the same refusal text");
        assertTrue(plainText.toLowerCase(Locale.ROOT).contains("approv"), "the refusal must name the approval: " + plainText);
        assertBlocked(j, job, 1, 0);
    }

    /**
     * T-GAP-269 (L2-18 (F), ARCHITECTURE 1, SPEC 6): the audit store's {@code appendChangeRecord} throws.
     * A token build of the approval-required {@code token-y} is still refused, and u1's Retry of the failed
     * build #1 of the approval-required {@code retry-y} (naginator) is still refused; nothing is queued.
     * Guard: the store was asked to append at least once.
     */
    @Test
    public void t_gap_269_failingAuditStoreDoesNotLetARefusedRunThrough() throws Exception {
        FreeStyleProject tokenJob = withToken(activated("token-y"));
        setBatchControl(tokenJob, new BatchControlJobProperty(true));
        FreeStyleProject retryJob = activated("retry-y");
        retryJob.getBuildersList().add(new FailureBuilder());
        try (ACLContext ignored = ACL.as2(token("u1"))) {
            j.assertBuildStatus(Result.FAILURE, retryJob.scheduleBuild2(0, new Cause.UserIdCause()));
        }
        setBatchControl(retryJob, new BatchControlJobProperty(true));
        j.waitUntilNoActivity();

        AtomicInteger failures = new AtomicInteger();
        original = BlockedAttemptAudit.swapStoreForTesting(GuardMoveGapTest.failingAppends(FileStore.get(), failures));
        WebResponse refused = tokenBuild(tokenJob.getUrl() + "build?token=" + TOKEN);
        assertTrue(refused.getStatusCode() >= 400, "the token build must stay refused while the store fails, got "
                + refused.getStatusCode());
        assertBlocked(j, tokenJob, 1, 0);
        PluginInteractionFixtures.post(j, "u1", retryJob.getBuildByNumber(1).getUrl() + "retry/");
        assertBlocked(j, retryJob, 2, 1);
        assertTrue(failures.get() > 0, "premise: the failing store was asked to append");
    }

    // ---------------------------------------------------------------- helpers

    private WorkflowJob pipelineBuiltOnce(String name) throws Exception {
        WorkflowJob job = uncontrolled(j.jenkins.createProject(WorkflowJob.class, name));
        job.setDefinition(new CpsFlowDefinition("echo 'hello'", true));
        BatchControlFixtures.activate(job);
        j.buildAndAssertSuccess(job);
        return job;
    }

    private FreeStyleProject activated(String name) throws Exception {
        FreeStyleProject job = uncontrolled(j.createFreeStyleProject(name));
        BatchControlFixtures.activate(job);
        return job;
    }

    private FreeStyleProject withToken(FreeStyleProject job) throws Exception {
        String xml = job.getConfigFile().asString();
        String withToken = xml.replace("</project>", "  <authToken>" + TOKEN + "</authToken>\n</project>");
        job.updateByXml(new StreamSource(new ByteArrayInputStream(withToken.getBytes(StandardCharsets.UTF_8))));
        FreeStyleProject reloaded = j.jenkins.getItemByFullName(job.getFullName(), FreeStyleProject.class);
        assertNotNull(reloaded.getAuthToken(), "fixture: the job must carry an authentication token");
        return reloaded;
    }

    /** POST (crumb in the query) of {@code path}, whose query is kept as written; redirects not followed. */
    private WebResponse tokenBuild(String path) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).withRedirectEnabled(false).login("u1");
        int q = path.indexOf('?');
        URL url = new URL(wc.createCrumbedUrl(path.substring(0, q)).toExternalForm() + "&" + path.substring(q + 1));
        return wc.getPage(new WebRequest(url, HttpMethod.POST)).getWebResponse();
    }

    private static String contentType(WebResponse r) {
        return String.valueOf(r.getContentType()).toLowerCase(Locale.ROOT);
    }

    private static List<ChangeRecord> remoteBlocked() {
        return FileStore.get().listChangeRecords(YearMonth.now(BatchClock.clock())).stream()
                .filter(r -> r.getType() == ChangeType.REMOTE_RUN_BLOCKED).collect(Collectors.toList());
    }
}
