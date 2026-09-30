package io.jenkins.plugins.batchcontrol;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * dependency-01 M-2, matrix row T-02-87: a canary for every upstream class and member that
 * batch-control matches by name only or calls reflectively. The names are copied verbatim from
 * docs/reports/dependency-01.md sections 2a and 2b; each is loaded through the Jenkins uber class
 * loader of a Jenkins started with the test classpath's plugins (the BOM versions in pom.xml),
 * which is the class loader a name match resolves against at run time.
 *
 * <p>A name match fails silently when upstream renames its target: for the "replayed under a
 * grant" marking it fails open (M-2). This row turns such a rename into a named failure on the
 * dependency bump that brings it. It lists every missing name in one message, so a single run
 * shows the whole damage.
 *
 * <p>Written from docs/SPEC.md, docs/TEST-MATRIX.md and docs/reports/dependency-01.md only
 * (no src/main knowledge).
 */
@WithJenkins
public class UpstreamNameCanaryTest {

    private JenkinsRule j;
    private final List<String> missing = new ArrayList<>();

    @BeforeEach
    public void setUp(JenkinsRule rule) {
        this.j = rule;
    }

    /** T-02-87: every name-matched class and every reflectively used member still resolves. */
    @Test
    public void t_02_87_upstreamNamesBatchControlMatchesStillResolve() {
        // Pipeline Replay and Pipeline Rebuild (workflow-cps)
        String replayCause = "org.jenkinsci.plugins.workflow.cps.replay.ReplayCause";
        method(replayCause, "getOriginalNumber", false);
        method(replayCause, "isRebuilt", false);
        type("org.jenkinsci.plugins.workflow.cps.replay.ReplayFlowFactoryAction");

        // Restart from Stage (pipeline-model-definition)
        method("org.jenkinsci.plugins.pipeline.modeldefinition.causes.RestartDeclarativePipelineCause",
                "getOriginRunNumber", false);
        String restartAction = "org.jenkinsci.plugins.pipeline.modeldefinition.actions.RestartFlowFactoryAction";
        method(restartAction, "getOriginRunId", false);
        declaredField(restartAction, "originRunId");

        // rebuild
        type("com.sonyericsson.rebuild.RebuildCause");

        // the build-log notice (workflow-cps CpsThread, workflow-api FlowExecutionOwner)
        method("org.jenkinsci.plugins.workflow.cps.CpsThread", "current", true);
        method("org.jenkinsci.plugins.workflow.cps.CpsFlowExecution", "getOwner", false);
        String owner = "org.jenkinsci.plugins.workflow.flow.FlowExecutionOwner";
        method(owner, "getExecutable", false);
        method(owner, "getListener", false);

        // naginator, build-token-root, core CLI
        method("com.chikli.hudson.plugin.naginator.NaginatorCause", "getSourceBuildNumber", false);
        type("org.jenkinsci.plugins.build_token_root.BuildRootAction");
        type("hudson.cli.BuildCommand$CLICause");

        // the standing-permission monitor (matrix-auth)
        method("org.jenkinsci.plugins.matrixauth.AuthorizationContainer", "getAllPermissionEntries", false);
        String entry = "org.jenkinsci.plugins.matrixauth.PermissionEntry";
        method(entry, "getType", false);
        method(entry, "getSid", false);

        assertTrue(missing.isEmpty(), "upstream renamed or removed names that batch-control matches by"
                + " string or reflection (dependency-01 M-2); update the plugin's name constants and this"
                + " canary together: " + missing);
    }

    private Class<?> type(String name) {
        try {
            return Class.forName(name, false, j.jenkins.getPluginManager().uberClassLoader);
        } catch (ClassNotFoundException | LinkageError e) {
            missing.add("class " + name + " (" + e + ")");
            return null;
        }
    }

    /** A public no-argument method; {@code isStatic} pins how it is called (no receiver). */
    private void method(String className, String name, boolean isStatic) {
        Class<?> c = type(className);
        if (c == null) {
            missing.add("method " + className + "#" + name + " (class missing)");
            return;
        }
        try {
            Method m = c.getMethod(name);
            if (Modifier.isStatic(m.getModifiers()) != isStatic) {
                missing.add("method " + className + "#" + name + " is " + (isStatic ? "no longer" : "now") + " static");
            }
        } catch (NoSuchMethodException | LinkageError e) {
            missing.add("method " + className + "#" + name + "() (" + e + ")");
        }
    }

    private void declaredField(String className, String name) {
        Class<?> c = type(className);
        if (c == null) {
            missing.add("field " + className + "." + name + " (class missing)");
            return;
        }
        try {
            Field f = c.getDeclaredField(name);
            if (Modifier.isStatic(f.getModifiers())) {
                missing.add("field " + className + "." + name + " is now static");
            }
        } catch (NoSuchFieldException | LinkageError e) {
            missing.add("field " + className + "." + name + " (" + e + ")");
        }
    }
}
