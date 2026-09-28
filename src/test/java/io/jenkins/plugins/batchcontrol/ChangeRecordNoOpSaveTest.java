package io.jenkins.plugins.batchcontrol;

import hudson.XmlFile;
import hudson.cli.CLICommandInvoker;
import hudson.model.AbstractItem;
import hudson.model.Action;
import hudson.model.FreeStyleProject;
import hudson.model.InvisibleAction;
import hudson.model.Saveable;
import hudson.model.TaskListener;
import hudson.scm.SCM;
import java.nio.file.Files;
import java.util.Collections;
import jenkins.scm.api.SCMHead;
import jenkins.scm.api.SCMHeadEvent;
import jenkins.scm.api.SCMHeadObserver;
import jenkins.scm.api.SCMRevision;
import jenkins.scm.api.SCMSource;
import jenkins.scm.api.SCMSourceCriteria;
import jenkins.scm.api.SCMSourceDescriptor;
import jenkins.scm.api.SCMSourceEvent;
import hudson.model.listeners.SaveableListener;
import hudson.scm.NullSCM;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.store.FileStore;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import jenkins.branch.BranchSource;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC item 9, acceptance lines citing #20:
 * <ul>
 *   <li>"a save that changes no user-editable configuration writes no CONFIGURE record. The
 *       comparison ignores the {@code plugin="name@version"} attributes, so saving an unchanged
 *       job after a plugin upgrade records nothing";</li>
 *   <li>"a computed folder (multibranch project, organization folder) saving itself during
 *       indexing writes no CONFIGURE record unless its user-editable configuration changed".</li>
 * </ul>
 * Rows T-09-12 .. T-09-15. T-09-15 is the false-positive guard: a real edit, alone and mixed with
 * plugin-version noise, still writes exactly one record — so a listener that simply stopped
 * recording would fail here.
 *
 * <p>Every "no record" row also asserts its premise: that Jenkins really did fire a save of the
 * item (counted by a test {@link SaveableListener}), so a save that silently never happened cannot
 * make the row green.
 */
@WithJenkins
public class ChangeRecordNoOpSaveTest {

    private static final Pattern PLUGIN_ATTRIBUTE = Pattern.compile("plugin=\"([^\"@]+)@[^\"]*\"");

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin"));
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setChangeControlEnabled(true);
        cfg.save();
        SaveCounter.reset();
    }

    /**
     * T-09-12: saving an unchanged job — programmatic save, a POST of the identical config.xml and
     * a CLI update-job with the identical XML — writes no CONFIGURE record.
     */
    @Test
    public void t_09_12_unchangedSaveWritesNoRecord() throws Exception {
        FreeStyleProject job = j.createFreeStyleProject("noop-job");
        job.setDescription("stable description");
        int before = configureRecords("noop-job").size();
        int savesBefore = SaveCounter.count(job);

        job.save();
        String xml = job.getConfigFile().asString();
        assertEquals(200, postConfigXml(job, xml));
        CLICommandInvoker.Result cli = new CLICommandInvoker(j, "update-job").asUser("admin")
                .withStdin(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)))
                .invokeWithArgs("noop-job");
        assertEquals(0, cli.returnCode(), "fixture: update-job must succeed: " + cli.stderr());

        assertTrue(SaveCounter.count(j.jenkins.getItemByFullName("noop-job", FreeStyleProject.class)) > savesBefore, "fixture: Jenkins must actually have saved the job");
        assertEquals("stable description",
                j.jenkins.getItemByFullName("noop-job", FreeStyleProject.class).getDescription());
        assertEquals(before, configureRecords("noop-job").size(), "saving an unchanged job must not write a CONFIGURE record");
    }

    /**
     * T-09-13: a config whose only difference is the {@code plugin="x@version"} attributes writes
     * no record. The job's stored config.xml is rewritten on disk with every attribute at
     * {@code @1.0} and Jenkins reloads it (the state an instance is in after a plugin upgrade
     * and restart); a plain save then writes the running versions back — the "saving an unchanged
     * job after a plugin upgrade" case. Then a POST of the config with every attribute at
     * {@code @2.0}.
     */
    @Test
    public void t_09_13_pluginVersionAttributeOnlyChangeWritesNoRecord() throws Exception {
        WorkflowJob job = j.jenkins.createProject(WorkflowJob.class, "attr-job");
        job.setDefinition(new CpsFlowDefinition("echo 'hello'", true));
        String current = job.getConfigFile().asString();
        assertTrue(PLUGIN_ATTRIBUTE.matcher(current).find(), "fixture: a Pipeline job's config.xml must carry plugin=\"name@version\" attributes; it read: " + current);

        String atOne = withPluginVersions(current, "1.0");
        assertFalse(atOne.equals(current), "fixture: the rewrite must change the XML");
        Files.writeString(job.getConfigFile().getFile().toPath(), atOne, StandardCharsets.UTF_8);
        j.jenkins.reload();
        WorkflowJob reloaded = j.jenkins.getItemByFullName("attr-job", WorkflowJob.class);
        assertEquals(atOne, reloaded.getConfigFile().asString(), "fixture: after the reload the stored config must still carry the @1.0 attributes");
        int before = configureRecords("attr-job").size();

        int saves = SaveCounter.count(reloaded);
        reloaded.save();
        assertTrue(SaveCounter.count(reloaded) > saves, "fixture: the plain save must have fired");
        String written = reloaded.getConfigFile().asString();
        assertFalse(written.equals(atOne), "fixture: the save must have written the running plugin versions back");
        assertEquals(withPluginVersions(atOne, "0"), withPluginVersions(written, "0"),
                "fixture: the save must differ from the stored file only in the plugin version attributes");
        assertEquals(before, configureRecords("attr-job").size(), "saving an unchanged job after a plugin upgrade (only the version attributes move) must not write a CONFIGURE record");

        String atTwo = withPluginVersions(written, "2.0");
        assertEquals(200, postConfigXml(reloaded, atTwo));
        assertEquals(before, configureRecords("attr-job").size(), "a config.xml differing only in plugin=\"x@...\" -> plugin=\"x@2.0\" must not write a CONFIGURE record");

        // Guard inside the row: after the reload the job is still recorded, so the silence above is
        // the attribute normalisation and not a listener that lost track of the reloaded job.
        WorkflowJob current2 = j.jenkins.getItemByFullName("attr-job", WorkflowJob.class);
        String edited = current2.getConfigFile().asString()
                .replace("echo &apos;hello&apos;", "echo &apos;edited&apos;")
                .replace("echo 'hello'", "echo 'edited'");
        assertTrue(edited.contains("edited"), "fixture: the script edit must be in the XML");
        assertEquals(200, postConfigXml(current2, edited));
        assertEquals(before + 1, configureRecords("attr-job").size(), "a real edit of the reloaded job must still write exactly one CONFIGURE record");
    }

    /**
     * T-09-14: a multibranch project indexing (and saving itself) writes no CONFIGURE record while
     * its user-editable configuration is unchanged. During indexing the multibranch project saves
     * its own files: its computation ({@code indexing.xml}) always, and its state
     * ({@code state.xml}) whenever the source's folder-level actions change. The test source
     * ({@link MetadataSCMSource}) reports SCM metadata (a fetch counter, as a hosted SCM reports
     * a repository description or avatar) that changes on every fetch, so every indexing run
     * saves the project's own files — asserted as the premise. That metadata is not user-editable
     * configuration. (branch-api 2.1303 calls {@code save()} on the project's config.xml during
     * indexing only when migrating legacy folder-level actions; see matrix note 56.)
     */
    @Test
    public void t_09_14_multibranchIndexingWritesNoRecord() throws Exception {
        WorkflowMultiBranchProject mp = j.jenkins.createProject(WorkflowMultiBranchProject.class, "mb");
        mp.getSourcesList().add(new BranchSource(new MetadataSCMSource()));
        mp.save();
        j.waitUntilNoActivity();
        int before = configureRecords("mb").size();
        int savesBefore = SaveCounter.countOwnFiles(mp);
        String configBefore = mp.getConfigFile().asString();

        for (int i = 0; i < 2; i++) {
            assertNotNull(mp.scheduleBuild2(0), "fixture: indexing must be schedulable");
            j.waitUntilNoActivity();
        }
        assertNotNull(mp.getComputation(), "fixture: the multibranch project must have indexed");
        assertTrue(SaveCounter.countOwnFiles(mp) >= savesBefore + 2, "fixture: each indexing run must have saved the multibranch project's own files, or this row measures"
                + " nothing; indexing log: " + indexingLog(mp));
        assertEquals(withPluginVersions(configBefore, "0"), withPluginVersions(mp.getConfigFile().asString(), "0"),
                "fixture: indexing must not have changed the project's configuration");

        assertEquals(before, configureRecords("mb").size(), "indexing a multibranch project whose configuration did not change must not write a CONFIGURE record");
    }

    /**
     * T-09-15 (false-positive guard of T-09-12..14): a real edit still writes exactly one
     * CONFIGURE record — on a Freestyle job, on a Pipeline job whose edit also moves the plugin
     * version attributes, and on a multibranch project.
     */
    @Test
    public void t_09_15_realEditStillWritesOneRecord() throws Exception {
        FreeStyleProject job = j.createFreeStyleProject("edit-job");
        job.setDescription("edit-before");
        int freestyleBefore = configureRecords("edit-job").size();
        assertEquals(200, postConfigXml(job, job.getConfigFile().asString()
                .replace("<description>edit-before</description>", "<description>edit-after</description>")));
        List<ChangeRecord> freestyle = configureRecords("edit-job");
        assertEquals(freestyleBefore + 1, freestyle.size(), "a real edit must write exactly one CONFIGURE record");
        assertTrue(String.valueOf(freestyle.get(freestyle.size() - 1).getDiff()).contains("edit-after"), "the record's diff must carry the edit");

        WorkflowJob pipeline = j.jenkins.createProject(WorkflowJob.class, "edit-pipe");
        pipeline.setDefinition(new CpsFlowDefinition("echo 'before-edit'", true));
        int pipelineBefore = configureRecords("edit-pipe").size();
        String edited = withPluginVersions(pipeline.getConfigFile().asString(), "2.0")
                .replace("echo &apos;before-edit&apos;", "echo &apos;after-edit&apos;")
                .replace("echo 'before-edit'", "echo 'after-edit'");
        assertTrue(edited.contains("after-edit"), "fixture: the script edit must be in the XML");
        assertEquals(200, postConfigXml(pipeline, edited));
        List<ChangeRecord> pipe = configureRecords("edit-pipe");
        assertEquals(pipelineBefore + 1, pipe.size(), "a real edit mixed with plugin-version noise must write exactly one CONFIGURE record");
        assertTrue(String.valueOf(pipe.get(pipe.size() - 1).getDiff()).contains("after-edit"), "the record's diff must carry the script edit");

        WorkflowMultiBranchProject mp = j.jenkins.createProject(WorkflowMultiBranchProject.class, "edit-mb");
        mp.getSourcesList().add(new BranchSource(new MetadataSCMSource()));
        mp.setDescription("mb-before");
        j.waitUntilNoActivity();
        int mbBefore = configureRecords("edit-mb").size();
        String mbXml = mp.getConfigFile().asString();
        String mbEdited = mbXml.replace("<description>mb-before</description>", "<description>mb-after</description>");
        assertTrue(mbEdited.contains("mb-after"), "fixture: the multibranch description edit must be in the XML: " + mbXml);
        assertEquals(200, postConfigXml(mp, mbEdited));
        j.waitUntilNoActivity();
        assertEquals(mbBefore + 1, configureRecords("edit-mb").size(), "a real edit of a multibranch project must write exactly one CONFIGURE record");
    }

    /**
     * T-SEC-33 (security-07 S-02, SPEC item 9 / #20): the {@code plugin="name@version"}
     * stripper (T-09-13) must anchor to an actual attribute named exactly {@code plugin} — not
     * to any substring elsewhere in the file that merely looks like {@code plugin="..."}. Ordinary
     * user-editable text can embed that exact substring (a description mentioning a command line
     * flag, for example {@code cmd plugin="1.0" tail}), and an edit confined to it (only the
     * digit inside the embedded substring changes) must still be recorded — a non-anchored
     * stripper would erase the differing text from both sides and normalise the two bodies
     * equal, silently dropping the edit. In-row guard: changing only a genuine
     * {@code plugin="x@1"} attribute still records nothing (T-09-13's rule, unaffected).
     *
     * <p>Reproduced through the {@code description} field rather than a synthetic custom XML
     * attribute: {@code AbstractItem#updateByXml} (config.xml POST) re-serialises the job from
     * its object model before it is stored, so any attribute a fixture invents with no backing
     * field (verified experimentally against this branch) never survives to be diffed at all —
     * the row would measure nothing. {@code description} is a real field that round-trips
     * byte-for-byte (T-09-12/15 rely on the same fact), so it can actually carry the vulnerable
     * substring through a real POST, and the stripper's regex cannot distinguish "text" from
     * "attribute value" any more than it could distinguish two different attributes — both are
     * just bytes to a stripper that is not anchored to attribute boundaries.
     */
    @Test
    public void t_sec_33_pluginAttributeStripperIsAnchoredToAttributeBoundaries() throws Exception {
        FreeStyleProject job = j.createFreeStyleProject("attr-boundary-job");
        // Same "name@version" shape T-09-13 uses for a genuine plugin="..." attribute, so the
        // fixture cannot be distinguished by the stripper on shape alone — only by where it sits.
        job.setDescription("cmd plugin=\"custom-thing@1.0\" tail");
        job.save();
        int before = configureRecords("attr-boundary-job").size();

        // The edit is confined to the version digit inside the embedded plugin="..." substring:
        // exactly the text a non-anchored stripper would erase from both sides, normalising the
        // two bodies equal. On disk the literal quotes inside the description's text content are
        // XML-entity-escaped (verified experimentally against this branch: Jenkins's writer emits
        // &quot; even in element text, not only in attribute values), so the edit is expressed in
        // that escaped form here — the escaping is orthogonal to the anchoring bug: a stripper
        // that parses attributes/text and decodes entities sees the very same literal
        // plugin="custom-thing@1.0" substring either way.
        String stored = job.getConfigFile().asString();
        assertTrue(stored.contains("plugin=&quot;custom-thing@1.0&quot; tail"), "fixture: the description embedding plugin=\"custom-thing@1.0\" must be stored (entity-escaped): " + stored);
        String edited = stored.replace("plugin=&quot;custom-thing@1.0&quot; tail", "plugin=&quot;custom-thing@2.0&quot; tail");
        assertFalse(edited.equals(stored), "fixture: the edit must change the XML");
        assertEquals(200, postConfigXml(job, edited));
        FreeStyleProject reloaded = j.jenkins.getItemByFullName("attr-boundary-job", FreeStyleProject.class);
        assertEquals("cmd plugin=\"custom-thing@2.0\" tail", reloaded.getDescription(), "fixture: the POST must really have changed the stored description");

        List<ChangeRecord> afterEdit = configureRecords("attr-boundary-job");
        assertEquals(before + 1, afterEdit.size(), "an edit confined to a plugin=\"...\" substring embedded in ordinary user text must still be recorded (security-07 S-02): the stripper may not match text that is not a real plugin=\"...\" attribute");
        assertTrue(String.valueOf(afterEdit.get(afterEdit.size() - 1).getDiff()).contains("2.0"), "the record's diff must carry the edit");

        // In-row guard: a change to a genuine plugin="x@1" attribute must still record nothing.
        WorkflowJob pipeline = j.jenkins.createProject(WorkflowJob.class, "attr-boundary-pipe");
        pipeline.setDefinition(new CpsFlowDefinition("echo 'hello'", true));
        String pipelineXml = pipeline.getConfigFile().asString();
        assertTrue(PLUGIN_ATTRIBUTE.matcher(pipelineXml).find(), "fixture: a Pipeline job's config.xml must carry plugin=\"name@version\" attributes");
        int guardBefore = configureRecords("attr-boundary-pipe").size();
        String bumpedVersion = withPluginVersions(pipelineXml, "2.0");
        assertEquals(200, postConfigXml(pipeline, bumpedVersion));
        assertEquals(guardBefore, configureRecords("attr-boundary-pipe").size(), "changing only a real plugin=\"x@1\" attribute must still record nothing");
    }

    /**
     * T-SEC-34 (security-07 S-04, SPEC item 9 / #20): a computed folder's child is excluded from
     * CONFIGURE recording only for saves the indexing itself performs — not for a direct,
     * non-indexing edit of that child's configuration, which is exactly the kind of "who changed
     * what" SPEC item 9 exists to capture (SPEC item 9 names the Job DSL path as one of the four
     * that must all be recorded, alongside UI, REST and CLI). In-row guard: re-indexing
     * afterwards writes no CONFIGURE record for the child (the computed-child skip still holds
     * for indexing-driven saves).
     *
     * <p>The edit is applied with a direct, unauthenticated {@code save()} — the shape a Job DSL
     * seed job or an init/groovy script takes, and the one SPEC-required path that is actually
     * reachable here. A REST config.xml POST (Item/Configure over HTTP, "a user with Item/Configure
     * POSTs config.xml") was tried first and is NOT reachable for a multibranch project's branch
     * child: workflow-multibranch's {@code BranchJobProperty} denies {@code Item/CONFIGURE} on a
     * branch job unconditionally, even to an administrator (verified experimentally against this
     * branch: the branch job's ACL is {@code BranchJobProperty$1}, {@code hasPermission(CONFIGURE)}
     * is false for the admin user). The same is true one level up: an organization folder's
     * generated multibranch project child carries the same kind of restriction
     * ({@code MultiBranchProject$1}, also verified experimentally). So for the two computed-folder
     * types SPEC item 9 itself names, an HTTP config.xml POST against Item/Configure can never
     * "take effect" as security-07's illustrative wording describes; the reachable, SPEC-mandated
     * path is Job DSL / script-style direct persistence, which this row exercises instead. See the
     * report for the full reasoning and the request this raises for the security-reviewer.
     */
    @Test
    public void t_sec_34_directSaveOfBranchChildIsRecorded() throws Exception {
        WorkflowMultiBranchProject mp = j.jenkins.createProject(WorkflowMultiBranchProject.class, "mb-branch");
        mp.getSourcesList().add(new BranchSource(new OneBranchSCMSource()));
        assertNotNull(mp.scheduleBuild2(0), "fixture: indexing must be schedulable");
        j.waitUntilNoActivity();

        WorkflowJob branch = j.jenkins.getItemByFullName("mb-branch/master", WorkflowJob.class);
        assertNotNull(branch, "fixture: indexing must have created the branch child job: " + indexingLog(mp));
        int before = configureRecords("mb-branch/master").size();

        // A direct, non-indexing configuration save of the branch child — the Job DSL / script
        // shape SPEC item 9 requires to be recorded, and the only SPEC-mandated path actually
        // reachable for a branch-api computed child (see class-level note above).
        branch.setDescription("edited-by-script");
        branch.save();

        assertEquals("edited-by-script", j.jenkins.getItemByFullName("mb-branch/master", WorkflowJob.class).getDescription(), "fixture: the direct save must really have changed the branch job's configuration");
        assertEquals(before + 1, configureRecords("mb-branch/master").size(), "a direct, non-indexing save of a multibranch project's branch child must write a CONFIGURE record (security-07 S-04): the computed-child skip may cover only saves the indexing itself performs");

        // In-row guard: re-indexing afterwards writes no new CONFIGURE record for the child.
        int beforeReindex = configureRecords("mb-branch/master").size();
        assertNotNull(mp.scheduleBuild2(0), "fixture: re-indexing must be schedulable");
        j.waitUntilNoActivity();
        assertEquals(beforeReindex, configureRecords("mb-branch/master").size(), "re-indexing must not itself write a CONFIGURE record for the branch child");
    }

    // ---------------------------------------------------------------- helpers

    private static String indexingLog(WorkflowMultiBranchProject mp) {
        try {
            java.io.ByteArrayOutputStream log = new java.io.ByteArrayOutputStream();
            mp.getComputation().getLogText().writeLogTo(0, log);
            return log.toString(StandardCharsets.UTF_8) + " saves=" + SaveCounter.COUNTS;
        } catch (Exception e) {
            return "(unavailable: " + e + ")";
        }
    }

    private static String withPluginVersions(String xml, String version) {
        Matcher m = PLUGIN_ATTRIBUTE.matcher(xml);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement("plugin=\"" + m.group(1) + "@" + version + "\""));
        }
        m.appendTail(out);
        return out.toString();
    }

    private int postConfigXml(AbstractItem target, String xml) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login("admin");
        WebRequest request = new WebRequest(wc.createCrumbedUrl(target.getUrl() + "config.xml"), HttpMethod.POST);
        request.setAdditionalHeader("Content-Type", "application/xml; charset=UTF-8");
        request.setRequestBody(xml);
        return wc.getPage(request).getWebResponse().getStatusCode();
    }

    private static List<ChangeRecord> configureRecords(String target) {
        return FileStore.get().listChangeRecords(YearMonth.now()).stream()
                .filter(rec -> rec.getType() == ChangeType.CONFIGURE)
                .filter(rec -> target.equals(rec.getTarget()))
                .collect(Collectors.toList());
    }

    /** An SCM source with no heads that reports one folder-level metadata action per fetch. */
    public static class MetadataSCMSource extends SCMSource {

        public MetadataSCMSource() {
            setId("metadata-source");
        }

        @Override
        protected void retrieve(SCMSourceCriteria criteria, SCMHeadObserver observer,
                SCMHeadEvent<?> event, TaskListener listener) {
            // no branches
        }

        @Override
        protected List<Action> retrieveActions(SCMSourceEvent event, TaskListener listener) {
            return Collections.singletonList(new MetadataAction());
        }

        @Override
        public SCM build(SCMHead head, SCMRevision revision) {
            return new NullSCM();
        }

        @TestExtension
        public static class DescriptorImpl extends SCMSourceDescriptor {
            @Override
            public String getDisplayName() {
                return "Metadata-only test source";
            }
        }
    }

    /** An SCM source that unconditionally reports one head ("master"), for T-SEC-34's branch child. */
    public static class OneBranchSCMSource extends SCMSource {

        public OneBranchSCMSource() {
            setId("one-branch-source");
        }

        @Override
        protected void retrieve(SCMSourceCriteria criteria, SCMHeadObserver observer,
                SCMHeadEvent<?> event, TaskListener listener) throws java.io.IOException, InterruptedException {
            SCMHead head = new SCMHead("master");
            observer.observe(head, new FixedRevision(head, "r1"));
        }

        @Override
        public SCM build(SCMHead head, SCMRevision revision) {
            return new NullSCM();
        }

        @TestExtension
        public static class DescriptorImpl extends SCMSourceDescriptor {
            @Override
            public String getDisplayName() {
                return "One-branch test source";
            }
        }

        private static final class FixedRevision extends SCMRevision {

            private final String hash;

            FixedRevision(SCMHead head, String hash) {
                super(head);
                this.hash = hash;
            }

            @Override
            public boolean equals(Object o) {
                if (!(o instanceof FixedRevision)) {
                    return false;
                }
                FixedRevision other = (FixedRevision) o;
                return getHead().equals(other.getHead()) && hash.equals(other.hash);
            }

            @Override
            public int hashCode() {
                return getHead().hashCode() * 31 + hash.hashCode();
            }
        }
    }

    /** SCM-reported metadata (not user-editable) whose value moves on every fetch. */
    public static class MetadataAction extends InvisibleAction {

        private static final AtomicInteger FETCHES = new AtomicInteger();

        private final int fetch = FETCHES.incrementAndGet();

        public int getFetch() {
            return fetch;
        }
    }

    /** Counts Jenkins save events per config file — the premise that a save really happened. */
    @TestExtension
    public static class SaveCounter extends SaveableListener {

        private static final Map<String, AtomicInteger> COUNTS = new ConcurrentHashMap<>();

        static void reset() {
            COUNTS.clear();
        }

        static int count(AbstractItem item) {
            AtomicInteger n = COUNTS.get(item.getConfigFile().getFile().getAbsolutePath());
            return n == null ? 0 : n.get();
        }

        /** Save events of any file directly in the item's root directory (config, state, indexing). */
        static int countOwnFiles(AbstractItem item) {
            String root = item.getRootDir().getAbsolutePath() + java.io.File.separator;
            return COUNTS.entrySet().stream()
                    .filter(e -> e.getKey().startsWith(root)
                            && e.getKey().indexOf(java.io.File.separatorChar, root.length()) < 0)
                    .mapToInt(e -> e.getValue().get())
                    .sum();
        }

        @Override
        public void onChange(Saveable o, XmlFile file) {
            if (file != null) {
                COUNTS.computeIfAbsent(file.getFile().getAbsolutePath(), k -> new AtomicInteger()).incrementAndGet();
            }
        }
    }
}
