package io.jenkins.plugins.batchcontrol.listener;

import hudson.model.Job;
import hudson.model.Run;
import hudson.model.TaskListener;
import java.lang.reflect.Method;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * D-58: tells a build that its save of the job's authorization property was partly reverted, with
 * one line in the build log. Pipeline is optional, so its classes are reached reflectively.
 *
 * <p>The line goes only to the build that saved: the Pipeline build whose CPS thread runs the
 * save ({@code CpsThread.current()}) when it is a build of the saved job. When the saving build
 * cannot be identified (a save from another job, a script, a Freestyle build), no line is written
 * (S-27-10); the GRANT_VIOLATION record is written either way. Never throws.
 */
@Restricted(NoExternalUse.class)
final class BuildLogNotice {

    private static final Logger LOGGER = Logger.getLogger(BuildLogNotice.class.getName());

    private BuildLogNotice() {
    }

    static void print(Job<?, ?> job, String line) {
        try {
            TaskListener own = cpsListener(job);
            if (own != null) {
                own.getLogger().println(line);
                return;
            }
            // S-27-10: only the build that saved; when it cannot be identified, no line.
        } catch (RuntimeException | LinkageError e) {
            LOGGER.log(Level.FINE, "Could not write the authorization notice to a build log of '"
                    + job.getFullName() + "'", e);
        }
    }

    /** The listener of the Pipeline build whose CPS thread is saving, if it is a build of {@code job}. */
    private static TaskListener cpsListener(Job<?, ?> job) {
        try {
            Class<?> cpsThread = Class.forName("org.jenkinsci.plugins.workflow.cps.CpsThread", false,
                    Jenkins.get().getPluginManager().uberClassLoader);
            Object thread = cpsThread.getMethod("current").invoke(null);
            if (thread == null) {
                return null;
            }
            Object execution = call(thread, "getExecution");
            Object owner = call(execution, "getOwner");
            Object executable = call(owner, "getExecutable");
            if (!(executable instanceof Run) || ((Run<?, ?>) executable).getParent() != job) {
                return null;
            }
            Object listener = call(owner, "getListener");
            return listener instanceof TaskListener ? (TaskListener) listener : null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return null;
        }
    }

    /**
     * Calls the public no-argument method {@code name} on {@code target}, through a public class
     * or interface that declares it (the runtime class may be a non-public nested class, such as
     * Pipeline's run owner, whose methods cannot be invoked through it).
     */
    private static Object call(Object target, String name) throws ReflectiveOperationException {
        if (target == null) {
            return null;
        }
        Method method = publicMethod(target.getClass(), name);
        if (method == null) {
            throw new NoSuchMethodException(name);
        }
        return method.invoke(target);
    }

    private static Method publicMethod(Class<?> type, String name) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            if (java.lang.reflect.Modifier.isPublic(c.getModifiers())) {
                try {
                    return c.getMethod(name);
                } catch (NoSuchMethodException e) {
                    // look further up
                }
            }
            for (Class<?> i : c.getInterfaces()) {
                if (java.lang.reflect.Modifier.isPublic(i.getModifiers())) {
                    try {
                        return i.getMethod(name);
                    } catch (NoSuchMethodException e) {
                        // next
                    }
                }
            }
        }
        return null;
    }

}
