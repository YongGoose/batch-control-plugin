package io.jenkins.plugins.batchcontrol;

import com.chikli.hudson.plugin.naginator.NaginatorRetryAction;
import com.sonyericsson.rebuild.RebuildAction;
import hudson.cli.CLICommandInvoker;
import hudson.model.FileParameterDefinition;
import hudson.model.FileParameterValue;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.ParameterValue;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.Queue;
import hudson.model.Result;
import hudson.model.StringParameterDefinition;
import hudson.model.StringParameterValue;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.file_parameters.StashedFileParameterDefinition;
import java.io.ByteArrayInputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import javax.xml.transform.stream.StreamSource;
import jenkins.model.Jenkins;
import org.htmlunit.FormEncodingType;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.html.HtmlForm;
import org.htmlunit.html.HtmlPage;
import org.htmlunit.util.KeyDataPair;
import org.htmlunit.util.NameValuePair;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.FailureBuilder;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.uncontrolled;
import static io.jenkins.plugins.batchcontrol.PluginInteractionFixtures.assertBlocked;
import static io.jenkins.plugins.batchcontrol.PluginInteractionFixtures.post;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.CORE_TMP_DIR;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.STASH_TMP_DIR;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.added;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.payload;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.tempFiles;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.under;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.uploadFile;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC item 5 (D-72) and item 6 (D-60): file content stays where its parameter type stores it, and
 * Batch Control does not leave the temporary files of a submission that never becomes a run. When
 * a person's build submission with file parameters (the build form, {@code buildWithParameters},
 * the CLI) or a build-token submission is refused at the queue on an approval-required job, nothing
 * reaches the queue, so no queue listener would ever delete the uploads: nothing new may remain
 * under {@code $JENKINS_HOME/fileParameterValueFiles} or {@code stashedFileParameterValueFiles}
 * (D-60: "nothing is queued and nothing is stored until the requester submits the form"). A refused
 * re-run (Retry, Rebuild) presents values that belong to another build; that build's files are
 * kept. Matrix rows T-05-65 .. T-05-69 (note 263).
 *
 * <p>Every refusal row first proves its detector on an uncontrolled twin job tied to a label no
 * node has (so its item waits in the queue): the same submission there leaves new files under the
 * documented directories while it is queued. The twin's item is then cancelled, and the refused
 * submission is measured as "files that appeared with it".
 *
 * <p>Written from docs/SPEC.md items 5 and 6, docs/DECISIONS.md D-60, D-72 and D-72a and the frozen
 * D-72 contract only (no src/main knowledge).
 */
@WithJenkins
public class RefusedBuildDisposalTest {

    private static final String TOKEN = "dispose-token-263";

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, Item.BUILD, BatchControlPermissions.REQUEST).everywhere().to("u1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1"));
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
    }

    /**
     * T-05-65: u1 submits core's parameters form (multipart) of an approval-required job with a core
     * file, a stashed file and a string: the build is refused (D-60 leads to the Request Run form),
     * nothing is queued, and no file that appeared with the submission remains under either
     * temporary directory. Premise: the same form on the uncontrolled twin leaves new files under
     * both directories while its item is queued.
     */
    @Test
    public void t_05_65_refusedBuildFormSubmissionLeavesNoTemporaryFiles() throws Exception {
        FreeStyleProject twin = twin(fileJob("form-twin"));
        Set<Path> before = tempFiles(j);
        submitBuildForm(twin, "twin");
        assertQueuedWithFiles(twin, before, true);

        FreeStyleProject job = controlled(fileJob("form-x"));
        before = tempFiles(j);
        submitBuildForm(job, "refused");
        assertBlocked(j, job, 1, 0);
        assertEquals(Set.of(), added(before, tempFiles(j)), "a refused build form submission must leave none of its uploads behind");
    }

    /**
     * T-05-66: u1 POSTs {@code job/<j>/buildWithParameters} as multipart (crumb in the header) with
     * a core file, a stashed file and a string: refused, nothing queued, nothing new under either
     * temporary directory. Premise: the same POST on the twin queues an item holding new files.
     */
    @Test
    public void t_05_66_refusedBuildWithParametersLeavesNoTemporaryFiles() throws Exception {
        FreeStyleProject twin = twin(fileJob("bwp-twin"));
        Set<Path> before = tempFiles(j);
        postMultipart("u1", twin.getUrl() + "buildWithParameters", true, "twin");
        assertQueuedWithFiles(twin, before, true);

        FreeStyleProject job = controlled(fileJob("bwp-x"));
        before = tempFiles(j);
        postMultipart("u1", job.getUrl() + "buildWithParameters", true, "refused");
        assertBlocked(j, job, 1, 0);
        assertEquals(Set.of(), added(before, tempFiles(j)), "a refused buildWithParameters submission must leave none of its uploads behind");
    }

    /**
     * T-05-67: build-token submissions with file parameters: anonymous
     * {@code buildByToken/buildWithParameters?job=&token=} (build-token-root) and u1's
     * {@code job/<j>/buildWithParameters?token=} (core's token trigger), both multipart: refused,
     * nothing queued, nothing new under either temporary directory. Premise: the same two
     * submissions on an activated twin queue items holding new files.
     */
    @Test
    public void t_05_67_refusedTokenSubmissionsLeaveNoTemporaryFiles() throws Exception {
        FreeStyleProject twin = twin(withToken(fileJob("token-twin")));
        BatchControlFixtures.activate(twin); // SPEC 6a / D-46: a token submission is unattended
        Set<Path> before = tempFiles(j);
        postMultipart(null, "buildByToken/buildWithParameters?job=token-twin&token=" + TOKEN, false, "twin (build-token-root)");
        assertQueuedWithFiles(twin, before, true);
        before = tempFiles(j);
        postMultipart("u1", twin.getUrl() + "buildWithParameters?token=" + TOKEN, true, "twin (core token)");
        assertQueuedWithFiles(twin, before, true);

        FreeStyleProject job = controlled(withToken(fileJob("token-x")));
        before = tempFiles(j);
        postMultipart(null, "buildByToken/buildWithParameters?job=token-x&token=" + TOKEN, false, "refused (build-token-root)");
        assertBlocked(j, job, 1, 0);
        assertEquals(Set.of(), added(before, tempFiles(j)), "a refused build-token-root submission must leave none of its uploads behind");
        before = tempFiles(j);
        postMultipart("u1", job.getUrl() + "buildWithParameters?token=" + TOKEN, true, "refused (core token)");
        assertBlocked(j, job, 1, 0);
        assertEquals(Set.of(), added(before, tempFiles(j)), "a refused core token submission must leave none of its uploads behind");
    }

    /**
     * T-05-68: u1 runs CLI {@code build <job> -p UPLOAD= -p DATE=...} with the file on stdin: the
     * command is refused (non-zero exit), nothing is queued, nothing new remains under the core
     * temporary directory. Premise: the same command on the twin queues an item holding a new file
     * under that directory.
     */
    @Test
    public void t_05_68_refusedCliBuildLeavesNoTemporaryFiles() throws Exception {
        FreeStyleProject twin = twin(coreFileJob("cli-twin"));
        Set<Path> before = tempFiles(j);
        CLICommandInvoker.Result premise = cliBuild("cli-twin");
        assertEquals(0, premise.returnCode(), "fixture: the twin's CLI build must be accepted: " + premise.stderr());
        assertQueuedWithFiles(twin, before, false);

        FreeStyleProject job = controlled(coreFileJob("cli-x"));
        before = tempFiles(j);
        CLICommandInvoker.Result refused = cliBuild("cli-x");
        assertNotEquals(0, refused.returnCode(), "the CLI build of an approval-required job must be refused");
        assertBlocked(j, job, 1, 0);
        assertEquals(Set.of(), added(before, tempFiles(j)), "a refused CLI build must leave none of its uploads behind");
    }

    /**
     * T-05-69 (guard of T-05-65..68): a refused re-run presents another build's values and must
     * not dispose of that build's files. An approved run with a core file fails; u1 presses
     * naginator's Retry and then the rebuild plugin's Rebuild (with its parameters, autorebuild):
     * both are refused (blocking triple, recorded) and build #1's copy of the file
     * ({@code <build>/fileParameters/UPLOAD}) still holds exactly the uploaded bytes.
     */
    @Test
    public void t_05_69_refusedRerunKeepsTheSourceBuildsFiles() throws Exception {
        byte[] content = payload("rerun-keep-marker-Vz28", 2400);
        FreeStyleProject job = controlled(coreFileJob("rerun-keep"));
        job.getBuildersList().add(new FailureBuilder());
        RunRequest request;
        try (ACLContext ignored = as("u1")) {
            List<ParameterValue> values = new ArrayList<>();
            values.add(new FileParameterValue("UPLOAD", uploadFile("data.csv", content), "data.csv"));
            values.add(new StringParameterValue("DATE", "2026-10-01"));
            request = RunRequestService.get().create(job, values, "month-end with a file", "a1");
        }
        try (ACLContext ignored = as("a1")) {
            RunRequestService.get().approve(request.getId(), "ok");
        }
        j.waitUntilNoActivity();
        FreeStyleBuild first = job.getBuildByNumber(1);
        assertNotNull(first, "fixture: the approved request must run");
        j.assertBuildStatus(Result.FAILURE, first);
        Path copy = first.getRootDir().toPath().resolve("fileParameters").resolve("UPLOAD");
        assertArrayEquals(content, Files.readAllBytes(copy), "premise: build #1 keeps its copy of the file");
        assertNotNull(first.getAction(NaginatorRetryAction.class), "fixture: naginator must offer Retry");
        assertNotNull(first.getAction(RebuildAction.class), "fixture: the rebuild plugin must offer Rebuild");
        int recordsBefore = refusalRecords(job);

        post(j, "u1", first.getUrl() + "retry/");
        assertBlocked(j, job, 2, 1);
        assertArrayEquals(content, Files.readAllBytes(copy), "a refused Retry must keep the source build's file");

        post(j, "u1", first.getUrl() + "rebuild/?autorebuild=true");
        assertBlocked(j, job, 2, 1);
        assertArrayEquals(content, Files.readAllBytes(copy), "a refused Rebuild must keep the source build's file");
        assertTrue(refusalRecords(job) > recordsBefore, "the refused re-runs must have reached the gate and been recorded");
    }

    // ---------------------------------------------------------------- helpers

    /** The crumb as the {@code (field name, value)} pair a script sends in a request header. */
    private NameValuePair crumbHeader() {
        hudson.security.csrf.CrumbIssuer issuer = j.jenkins.getCrumbIssuer();
        assertNotNull(issuer, "fixture: CSRF protection must be on");
        return new NameValuePair(issuer.getDescriptor().getCrumbRequestField(), issuer.getCrumb((jakarta.servlet.ServletRequest) null));
    }

    /** A Freestyle job with a core file UPLOAD, a stashed file DATA and a string DATE. */
    private FreeStyleProject fileJob(String name) throws Exception {
        FreeStyleProject job = j.createFreeStyleProject(name);
        job.addProperty(new ParametersDefinitionProperty(
                new FileParameterDefinition("UPLOAD", "core file"),
                new StashedFileParameterDefinition("DATA"),
                new StringParameterDefinition("DATE", "2000-01-01")));
        return job;
    }

    /** A Freestyle job with a core file UPLOAD and a string DATE. */
    private FreeStyleProject coreFileJob(String name) throws Exception {
        FreeStyleProject job = j.createFreeStyleProject(name);
        job.addProperty(new ParametersDefinitionProperty(
                new FileParameterDefinition("UPLOAD", "core file"),
                new StringParameterDefinition("DATE", "2000-01-01")));
        return job;
    }

    /** Approval-required. */
    private FreeStyleProject controlled(FreeStyleProject job) throws Exception {
        setBatchControl(job, new BatchControlJobProperty(true));
        return job;
    }

    /** Out of run control and tied to a label no node has, so an accepted item waits in the queue. */
    private FreeStyleProject twin(FreeStyleProject job) throws Exception {
        uncontrolled(job);
        job.setAssignedLabel(j.jenkins.getLabel("nowhere-263"));
        return job;
    }

    /**
     * Premise of every refusal row: the twin's submission is queued and new files appeared under
     * the core directory (and the stash directory when {@code stashToo}); the item is then cancelled.
     */
    private void assertQueuedWithFiles(FreeStyleProject twin, Set<Path> before, boolean stashToo) {
        Queue.Item item = j.jenkins.getQueue().getItem(twin);
        assertNotNull(item, "premise: the twin's submission must be queued (the detector needs an accepted twin)");
        Set<Path> held = added(before, tempFilesUnchecked());
        assertFalse(under(j, held, CORE_TMP_DIR).isEmpty(), "premise: the accepted submission keeps its core file under "
                + CORE_TMP_DIR + " while queued: " + held);
        if (stashToo) {
            assertFalse(under(j, held, STASH_TMP_DIR).isEmpty(), "premise: the accepted submission keeps its stashed file under "
                    + STASH_TMP_DIR + " while queued: " + held);
        }
        // cancel by task: the item may have moved from the waiting list to the buildable list since
        // it was looked up, and cancelling the stale item object would do nothing
        j.jenkins.getQueue().cancel(twin);
        assertNull(j.jenkins.getQueue().getItem(twin), "fixture: the twin's item must be cancelled");
    }

    private Set<Path> tempFilesUnchecked() {
        try {
            return tempFiles(j);
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }

    /** u1 fills core's parameters form of {@code job} (both files and DATE) and submits it. */
    private void submitBuildForm(FreeStyleProject job, String what) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login("u1");
        wc.getOptions().setJavaScriptEnabled(true);
        HtmlPage page = (HtmlPage) wc.getPage(new WebRequest(new URL(j.getURL(), job.getUrl() + "build?delay=0sec"), HttpMethod.GET));
        HtmlForm form = page.getFormByName("parameters");
        byte[] content = payload("build-form-marker-" + what.replace(' ', '-'), 1800);
        TypedParameterFixtures.setFile(form, "UPLOAD", uploadFile("data.csv", content));
        TypedParameterFixtures.setFile(form, "DATA", uploadFile("report.bin", content));
        TypedParameterFixtures.setValue(form, "DATE", "2026-10-01");
        wc.getOptions().setRedirectEnabled(false);
        j.submit(form);
    }

    /**
     * POSTs a multipart body (DATE, file UPLOAD and, when the job has it, file DATA) to
     * {@code relative} as {@code userId} (null = anonymous), with the crumb in the header when asked.
     */
    private void postMultipart(String userId, String relative, boolean crumb, String what) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false);
        if (userId != null) {
            wc.login(userId);
        }
        wc.getOptions().setRedirectEnabled(false);
        WebRequest request = new WebRequest(new URL(j.getURL(), relative), HttpMethod.POST);
        if (crumb) {
            NameValuePair header = crumbHeader();
            request.setAdditionalHeader(header.getName(), header.getValue());
        }
        request.setEncodingType(FormEncodingType.MULTIPART);
        byte[] content = payload("multipart-marker-" + what.replaceAll("[^A-Za-z]", ""), 1700);
        List<NameValuePair> fields = new ArrayList<>();
        fields.add(new NameValuePair("DATE", "2026-10-01"));
        fields.add(new KeyDataPair("UPLOAD", uploadFile("data.csv", content), "data.csv", "application/octet-stream",
                StandardCharsets.UTF_8));
        fields.add(new KeyDataPair("DATA", uploadFile("report.bin", content), "report.bin", "application/octet-stream",
                StandardCharsets.UTF_8));
        request.setRequestParameters(fields);
        wc.getPage(request);
    }

    /** CLI {@code build <job> -p UPLOAD= -p DATE=...} as u1 with the file on stdin. */
    private CLICommandInvoker.Result cliBuild(String jobName) {
        return new CLICommandInvoker(j, "build").asUser("u1")
                .withStdin(new ByteArrayInputStream(payload("cli-marker-" + jobName, 1600)))
                .invokeWithArgs(jobName, "-p", "UPLOAD=", "-p", "DATE=2026-10-01");
    }

    /** Installs the job's authentication token (core's {@code authToken} config element). */
    private FreeStyleProject withToken(FreeStyleProject job) throws Exception {
        String xml = job.getConfigFile().asString();
        assertTrue(xml.contains("</project>"), "fixture: unexpected config.xml shape");
        job.updateByXml(new StreamSource(new ByteArrayInputStream(
                xml.replace("</project>", "  <authToken>" + TOKEN + "</authToken>\n</project>").getBytes(StandardCharsets.UTF_8))));
        FreeStyleProject reloaded = j.jenkins.getItemByFullName(job.getFullName(), FreeStyleProject.class);
        assertNotNull(reloaded.getAuthToken(), "fixture: the job must carry an authentication token");
        return reloaded;
    }

    private static int refusalRecords(FreeStyleProject job) {
        return ActivationFixtures.recordsFor(ChangeType.MARKER_REUSE_BLOCKED, job.getFullName()).size()
                + ActivationFixtures.recordsFor(ChangeType.TRIGGER_BLOCKED, job.getFullName()).size();
    }

    private static ACLContext as(String userId) {
        return ACL.as2(User.getById(userId, true).impersonate2());
    }
}
