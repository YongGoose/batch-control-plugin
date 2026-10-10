package io.jenkins.plugins.batchcontrol;

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.matrix.AxisList;
import hudson.matrix.MatrixConfiguration;
import hudson.matrix.MatrixProject;
import hudson.matrix.TextAxis;
import hudson.model.FreeStyleProject;
import hudson.model.Job;
import hudson.model.Result;
import hudson.model.Run;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.Incident;
import io.jenkins.plugins.batchcontrol.ops.IncidentService;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import java.net.URI;
import java.net.URL;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import jenkins.model.Jenkins;
import org.htmlunit.Page;
import org.htmlunit.html.HtmlAnchor;
import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.FailureBuilder;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Issue #42 (Wave C-UI contract, matrix rows T-UI-143..145, note 326): every run link on History,
 * the Run Dashboard and the Incidents pages resolves (HTTP 200) and uses the resolved job's own URL,
 * for a freestyle job, a job in a folder and a matrix configuration. SPEC 6 usability: "no link
 * leads to a 404 or 403 page"; SPEC 10 (every job's runs on one screen) and SPEC 11 (an incident for
 * every run whose result is listed, whatever its cause).
 *
 * <p>A Maven module is not covered: {@code org.jenkins-ci.main:maven-plugin} is not on the test class
 * path (pom.xml belongs to release-manager), so the contract's optional fourth kind is reported, not
 * tested.
 *
 * <p>Each row first runs its guard (the freestyle job, the folder job and the matrix parent, whose
 * links work today) and then the matrix configurations. "A run link" is an anchor whose path, below
 * the Jenkins root, starts with {@code job/} and ends with a build number segment; every one of them
 * on the page must answer 200 to the viewer (the administrator, who may read every job, so SPEC 6's
 * plain-text rule for unreadable jobs does not apply).
 *
 * <p>Written from docs/SPEC.md, docs/TEST-MATRIX.md, issue #42 and the Wave C-UI contract only (no
 * src/main knowledge).
 */
@WithJenkins
public class RunLinkResolutionTest {

    /** A link to a build page: {@code job/.../<number>} with an optional trailing slash. */
    private static final Pattern RUN_LINK = Pattern.compile("^job/.+/\\d+/?$");

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setNumExecutors(4);
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin"));
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("admin"));
        cfg.save();
    }

    /**
     * T-UI-143 (#42): on History ({@code history/?kind=runs}) the administrator gets a link to each
     * of the five failed runs at the run's own URL, and every run link on History (kinds runs,
     * requests, changes) answers 200. Guard: the freestyle run, the folder job's run and the matrix
     * parent's run.
     */
    @Test
    public void t_ui_143_historyRunLinksResolveForEveryJobKind() throws Exception {
        Fixture f = buildAll();
        check(f, List.of("batch-control/history/?kind=runs"),
                List.of("batch-control/history/?kind=requests", "batch-control/history/?kind=changes"));
    }

    /** T-UI-144 (#42): as T-UI-143 on the Run Dashboard ({@code batch-control/dashboard/}). */
    @Test
    public void t_ui_144_dashboardRunLinksResolveForEveryJobKind() throws Exception {
        Fixture f = buildAll();
        check(f, List.of("batch-control/dashboard/"), List.of());
    }

    /**
     * T-UI-145 (#42): every failed run has an incident (SPEC 11); each incident's detail page links
     * its run at the run's own URL, and every run link on the detail pages and on the Incidents list
     * answers 200. Guard as T-UI-143.
     */
    @Test
    public void t_ui_145_incidentRunLinksResolveForEveryJobKind() throws Exception {
        Fixture f = buildAll();
        List<Incident> incidents = IncidentService.get().list(YearMonth.now(BatchClock.clock()));
        Map<Run<?, ?>, String> detail = new LinkedHashMap<>();
        for (Run<?, ?> run : f.all()) {
            Incident incident = incidents.stream().filter(i -> run.getExternalizableId().equals(i.getRunId()))
                    .findFirst().orElse(null);
            assertNotNull(incident, "premise (SPEC 11): the FAILURE of " + run.getExternalizableId() + " must open an incident; incidents: "
                    + incidents.stream().map(Incident::getRunId).toList());
            detail.put(run, "batch-control/incidents/" + incident.getId() + "/");
        }
        // Guard: each guard run's incident page links that run, and its run links resolve.
        List<String> guardProblems = new ArrayList<>();
        for (Run<?, ?> run : f.guard) {
            guardProblems.addAll(problems(detail.get(run), List.of(run), true));
        }
        guardProblems.addAll(problems("batch-control/incidents/", f.guard, false));
        assertTrue(guardProblems.isEmpty(), "guard: links of the freestyle job, the folder job and the matrix parent must resolve: "
                + guardProblems);
        List<String> problems = new ArrayList<>();
        for (Run<?, ?> run : f.subItems) {
            problems.addAll(problems(detail.get(run), List.of(run), true));
        }
        problems.addAll(problems("batch-control/incidents/", f.all(), false));
        assertTrue(problems.isEmpty(), "#42: every run link on the Incidents pages must use the run's own URL and answer 200: " + problems);
    }

    // ---------------------------------------------------------------- fixture

    /** Runs whose links work today (guard) and the matrix configurations' runs (#42). */
    private record Fixture(List<Run<?, ?>> guard, List<Run<?, ?>> subItems) {
        List<Run<?, ?>> all() {
            List<Run<?, ?>> out = new ArrayList<>(guard);
            out.addAll(subItems);
            return out;
        }
    }

    /**
     * Failed run #1 of {@code fs-x}, {@code ops/in-x} and the matrix project {@code mx} (axis X = a,
     * b), each activated (unattended submission, D-46) and not approval-required.
     */
    private Fixture buildAll() throws Exception {
        FreeStyleProject fs = j.createFreeStyleProject("fs-x");
        Folder ops = j.jenkins.createProject(Folder.class, "ops");
        FreeStyleProject inFolder = ops.createProject(FreeStyleProject.class, "in-x");
        MatrixProject mx = j.jenkins.createProject(MatrixProject.class, "mx");
        mx.setAxes(new AxisList(new TextAxis("X", "a", "b")));
        for (Job<?, ?> job : List.<Job<?, ?>>of(fs, inFolder, mx)) {
            BatchControlJobProperty property = new BatchControlJobProperty(false);
            property.setBlockTimer(false);
            property.setBlockUpstream(false);
            BatchControlFixtures.setBatchControl(job, property);
            BatchControlFixtures.activateAsAdmin(job);
        }
        fs.getBuildersList().add(new FailureBuilder());
        inFolder.getBuildersList().add(new FailureBuilder());
        mx.getBuildersList().add(new FailureBuilder());

        List<Run<?, ?>> guard = new ArrayList<>();
        guard.add(j.buildAndAssertStatus(Result.FAILURE, fs));
        guard.add(j.buildAndAssertStatus(Result.FAILURE, inFolder));
        guard.add(j.buildAndAssertStatus(Result.FAILURE, mx));
        j.waitUntilNoActivity();
        List<Run<?, ?>> subItems = new ArrayList<>();
        for (String name : new String[] {"X=a", "X=b"}) {
            MatrixConfiguration c = mx.getItem(name);
            assertNotNull(c, "fixture: mx has the configuration " + name);
            Run<?, ?> run = c.getBuildByNumber(1);
            assertNotNull(run, "fixture: the configuration " + name + " ran once");
            assertEquals(Result.FAILURE, run.getResult(), "fixture: the configuration " + name + " failed");
            subItems.add(run);
        }
        assertEquals("job/mx/X=a/1/", subItems.get(0).getUrl(), "premise: core's URL of the configuration run");
        for (Run<?, ?> run : guard) {
            assertEquals(200, status(run.getUrl()), "premise: " + run.getUrl() + " opens");
        }
        for (Run<?, ?> run : subItems) {
            assertEquals(200, status(run.getUrl()), "premise: " + run.getUrl() + " opens");
        }
        return new Fixture(guard, subItems);
    }

    // ---------------------------------------------------------------- checks

    /**
     * Guard first (guard runs present at their own URL on the presence pages, every run link of
     * theirs resolves), then the configurations: present at their own URL, and every run link on
     * every listed page answers 200.
     */
    private void check(Fixture f, List<String> presencePages, List<String> otherPages) throws Exception {
        List<String> guardProblems = new ArrayList<>();
        for (String path : presencePages) {
            guardProblems.addAll(problems(path, f.guard, true));
        }
        assertTrue(guardProblems.isEmpty(), "guard: links of the freestyle job, the folder job and the matrix parent must be present"
                + " at the run's own URL and resolve: " + guardProblems);
        List<String> problems = new ArrayList<>();
        for (String path : presencePages) {
            problems.addAll(problems(path, f.all(), true));
        }
        for (String path : otherPages) {
            problems.addAll(problems(path, f.all(), false));
        }
        assertTrue(problems.isEmpty(), "#42: every run link must use the run's own URL and answer 200: " + problems);
    }

    /**
     * Problems on one page: a run of {@code expected} without a link at its own URL (when
     * {@code requirePresence}), and every run link that does not answer 200 (only links to the
     * expected runs' jobs when presence is not required for the guard).
     */
    private List<String> problems(String path, List<Run<?, ?>> expected, boolean requirePresence) throws Exception {
        HtmlPage page = page(path);
        String root = decodedPath(j.getURL().toString());
        Set<String> runLinks = new LinkedHashSet<>();
        for (HtmlAnchor a : page.getAnchors()) {
            String href = a.getHrefAttribute();
            if (href == null || href.isEmpty() || href.startsWith("#") || href.startsWith("javascript:")) {
                continue;
            }
            String abs = page.getFullyQualifiedUrl(href).toString();
            String decoded = decodedPath(abs);
            if (!decoded.startsWith(root)) {
                continue;
            }
            String relative = decoded.substring(root.length());
            if (RUN_LINK.matcher(relative).matches() && belongsToAny(relative, expected)) {
                runLinks.add(abs);
            }
        }
        List<String> out = new ArrayList<>();
        if (requirePresence) {
            for (Run<?, ?> run : expected) {
                String own = decodedPath(j.getURL() + run.getUrl()).replaceAll("/+$", "");
                boolean present = runLinks.stream().anyMatch(l -> decodedPath(l).replaceAll("/+$", "").equals(own));
                if (!present) {
                    out.add(path + ": no link to " + run.getExternalizableId() + " at its own URL " + run.getUrl()
                            + " (run links on the page: " + runLinks + ")");
                }
            }
        }
        for (String link : runLinks) {
            int status = status(link);
            if (status != 200) {
                out.add(path + ": " + link + " answers " + status);
            }
        }
        return out;
    }

    /** Whether a run link names one of the expected runs' top-level job (so the guard ignores the configurations' links). */
    private static boolean belongsToAny(String relative, List<Run<?, ?>> runs) {
        for (Run<?, ?> run : runs) {
            String top = run.getParent().getFullName().split("/")[0];
            if (relative.startsWith("job/" + top + "/")) {
                if (run.getParent() instanceof MatrixConfiguration || !(run.getParent() instanceof MatrixProject)) {
                    return true;
                }
                // The matrix parent alone: only job/mx/<n>.
                if (relative.matches("^job/" + Pattern.quote(top) + "/\\d+/?$")) {
                    return true;
                }
            }
        }
        return false;
    }

    private HtmlPage page(String path) throws Exception {
        JenkinsRule.WebClient wc = client();
        Page p = wc.getPage(new URL(j.getURL(), path));
        assertEquals(200, p.getWebResponse().getStatusCode(), "admin GET " + path + " must answer 200");
        if (!(p instanceof HtmlPage)) {
            fail("admin GET " + path + " must render HTML, got " + p.getWebResponse().getContentType());
        }
        return (HtmlPage) p;
    }

    private int status(String urlOrPath) throws Exception {
        URL url = urlOrPath.startsWith("http") ? new URL(urlOrPath) : new URL(j.getURL(), urlOrPath);
        return client().getPage(url).getWebResponse().getStatusCode();
    }

    private JenkinsRule.WebClient client() throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false);
        wc.getOptions().setJavaScriptEnabled(false);
        wc.getOptions().setCssEnabled(false);
        return wc.login("admin");
    }

    /** The percent-decoded path of an absolute URL. */
    private static String decodedPath(String absolute) {
        try {
            return new URI(absolute).getPath();
        } catch (Exception e) {
            throw new AssertionError("not a URI: " + absolute, e);
        }
    }
}
