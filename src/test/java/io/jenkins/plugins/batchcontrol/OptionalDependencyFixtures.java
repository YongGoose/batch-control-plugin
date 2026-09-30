package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shared steps of the absence boots T-02-84..86 (dependency-01 M-3). This class runs inside a
 * {@code RealJenkinsExtension} JVM that lacks one or more optional plugins, so it references
 * core, test-harness, JDK and batch-control types only: no matrix-auth, role-strategy,
 * configuration-as-code, mailer or rebuild type, and no fixture class that links one.
 *
 * <p>Written from docs/SPEC.md, docs/TEST-MATRIX.md and docs/reports/dependency-01.md only
 * (no src/main knowledge).
 */
final class OptionalDependencyFixtures {

    /** The compile-time optional plugins named by dependency-01 section 2a. */
    static final String[] COMPILE_TIME_OPTIONAL = {
        "matrix-auth", "role-strategy", "configuration-as-code", "mailer", "rebuild"
    };

    /** The plugins batch-control requires (README Requirements, dependency-01 section 1). */
    static final String[] REQUIRED = {"cloudbees-folder", "ionicons-api"};

    private OptionalDependencyFixtures() {
        // utility class
    }

    /**
     * Every plugin of the test classpath that is not batch-control's required dependency set:
     * the test-dependencies directory minus the closure of {@link #REQUIRED} under non-optional
     * {@code Plugin-Dependencies}. Returns an empty array where the directory is not on the
     * class path (the child JVM, which never needs it).
     */
    static String[] everythingButRequired() {
        Map<String, List<String>> required = requiredDependenciesOfTestPlugins();
        if (required.isEmpty()) {
            return new String[0];
        }
        Set<String> keep = requiredClosure(required);
        Set<String> omit = new TreeSet<>(required.keySet());
        omit.removeAll(keep);
        return omit.toArray(new String[0]);
    }

    /** The closure of {@link #REQUIRED} under required plugin dependencies. */
    static Set<String> requiredClosure(Map<String, List<String>> required) {
        Set<String> keep = new TreeSet<>();
        Deque<String> todo = new ArrayDeque<>(Arrays.asList(REQUIRED));
        while (!todo.isEmpty()) {
            String name = todo.pop();
            if (keep.add(name)) {
                todo.addAll(required.getOrDefault(name, List.of()));
            }
        }
        return keep;
    }

    /** Short name to its non-optional dependencies, for every hpi in test-dependencies. */
    static Map<String, List<String>> requiredDependenciesOfTestPlugins() {
        Map<String, List<String>> result = new HashMap<>();
        URL index = OptionalDependencyFixtures.class.getResource("/test-dependencies/index");
        if (index == null || !"file".equals(index.getProtocol())) {
            return result;
        }
        File dir;
        try {
            dir = new File(index.toURI()).getParentFile();
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
        File[] hpis = dir.listFiles((d, n) -> n.endsWith(".hpi") || n.endsWith(".jpi"));
        assertNotNull(hpis, "fixture: test-dependencies must be listable");
        for (File hpi : hpis) {
            try (JarFile jar = new JarFile(hpi)) {
                Manifest mf = jar.getManifest();
                String shortName = mf.getMainAttributes().getValue("Short-Name");
                if (shortName == null) {
                    shortName = hpi.getName().replaceAll("\\.[hj]pi$", "");
                }
                List<String> deps = new ArrayList<>();
                String raw = mf.getMainAttributes().getValue("Plugin-Dependencies");
                if (raw != null && !raw.isBlank()) {
                    for (String dep : raw.split(",")) {
                        if (!dep.contains("resolution:=optional")) {
                            deps.add(dep.trim().split(":")[0]);
                        }
                    }
                }
                result.put(shortName, deps);
            } catch (IOException e) {
                throw new IllegalStateException("fixture: cannot read " + hpi, e);
            }
        }
        return result;
    }

    /**
     * The common check of every absence boot: the root page, {@code /manage} and the Batch
     * Control screen render, a Build Now on an approval-required job is gated, and a run
     * request is submitted, approved and executed exactly once.
     */
    static void rootPageAndApprovedRunWork(JenkinsRule r, String... absent) throws Exception {
        for (String name : absent) {
            assertTrue(r.jenkins.getPlugin(name) == null, "premise: " + name + " must not be installed");
        }
        assertTrue(r.jenkins.getPluginManager().getFailedPlugins().stream().noneMatch(f -> "batch-control".equals(f.name)),
                "Batch Control must not fail to load without " + Arrays.toString(absent) + ": "
                        + r.jenkins.getPluginManager().getFailedPlugins());
        assertNotNull(r.jenkins.getPluginManager().getPlugin("batch-control"),
                "Batch Control must be installed without " + Arrays.toString(absent));
        assertTrue(r.jenkins.getPluginManager().getPlugin("batch-control").isActive(),
                "Batch Control must be active without " + Arrays.toString(absent));

        r.jenkins.setSecurityRealm(r.createDummySecurityRealm());
        r.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, Item.BUILD, BatchControlPermissions.REQUEST).everywhere().to("u1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1"));
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();

        for (String[] visit : new String[][] {{"admin", ""}, {"u1", ""}, {"admin", "manage/"}, {"admin", "batch-control/"}}) {
            JenkinsRule.WebClient wc = r.createWebClient().withThrowExceptionOnFailingStatusCode(false).login(visit[0]);
            Page page = wc.goTo(visit[1]);
            String where = "/" + visit[1] + " as " + visit[0] + " without " + Arrays.toString(absent);
            assertEquals(200, page.getWebResponse().getStatusCode(), where + " must answer 200");
            String body = page.getWebResponse().getContentAsString();
            assertFalse(body.contains("NoClassDefFoundError") || body.contains("ClassNotFoundException"),
                    where + " must not show a class-loading error");
        }

        FreeStyleProject job = r.createFreeStyleProject("batch-x");
        BatchControlFixtures.setBatchControl(job, new BatchControlJobProperty(true));
        int next = job.getNextBuildNumber();
        // The crumb a WebClient fetches is not accepted by the child JVM of a RealJenkinsExtension
        // (the first run answered "No valid crumb"), which would make the refusal below vacuous.
        // Crumbs are orthogonal to run control, so this boot turns them off for the POST.
        r.jenkins.setCrumbIssuer(null);
        JenkinsRule.WebClient u1 = r.createWebClient().withThrowExceptionOnFailingStatusCode(false).login("u1");
        Page buildNow = u1.getPage(new WebRequest(new URL(r.getURL(), "job/batch-x/build"), HttpMethod.POST));
        r.waitUntilNoActivity();
        assertTrue(buildNow.getWebResponse().getStatusCode() >= 400, "a direct Build Now must be refused");
        String refusal = buildNow.getWebResponse().getContentAsString();
        assertFalse(refusal.contains("No valid crumb"), "premise: the refusal must come from run control, not from the crumb filter");
        assertTrue(refusal.toLowerCase(java.util.Locale.ROOT).contains("approval"),
                "the refusal must name approval (T-UI-23)");
        assertTrue(r.jenkins.getQueue().isEmpty(), "a direct Build Now must leave the queue empty");
        assertEquals(next, job.getNextBuildNumber(), "a direct Build Now must not allocate a build number");
        assertEquals(0, job.getBuilds().size(), "a direct Build Now must not run");

        RunRequest request;
        try (ACLContext ignored = ACL.as2(User.getById("u1", true).impersonate2())) {
            request = RunRequestService.get().create(job, new LinkedHashMap<>(), "month-end batch", "a1");
        }
        assertNotNull(request, "the run request must be submitted");
        try (ACLContext ignored = ACL.as2(User.getById("a1", true).impersonate2())) {
            RunRequestService.get().approve(request.getId(), "ok");
        }
        r.waitUntilNoActivity();
        assertEquals(RequestStatus.EXECUTED, RunRequestService.get().load(request.getId()).getStatus(),
                "the approved request must be executed");
        assertEquals(1, job.getBuilds().size(), "the approved request must run exactly once");
    }
}
