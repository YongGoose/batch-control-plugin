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
