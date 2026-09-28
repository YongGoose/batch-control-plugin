package io.jenkins.plugins.batchcontrol.security;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.Extension;
import java.util.List;
import jenkins.cli.listeners.CLIContext;
import jenkins.cli.listeners.CLIListener;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * D-40: remembers, on the thread that runs it, the target name of a {@code create-job} or
 * {@code copy-job} CLI command, for {@link NewItemName}. {@code CLICommand.getCurrent()} is only
 * set by the remote CLI transports, while {@code CLICommand.main} always notifies
 * {@link CLIListener}s on the executing thread just before {@code run()}.
 */
@Extension
@Restricted(NoExternalUse.class)
public class CliCreateContext implements CLIListener {

    private static final ThreadLocal<String> TARGET = new ThreadLocal<>();

    @Override
    public void onExecution(CLIContext context) {
        String command = context.getCommand();
        List<String> args = context.getArgs();
        if (("create-job".equals(command) || "copy-job".equals(command)) && args != null && !args.isEmpty()) {
            // create-job NAME, copy-job SRC DST: the new item's name is the last argument.
            TARGET.set(args.get(args.size() - 1));
        } else {
            TARGET.remove();
        }
    }

    @Override
    public void onCompleted(CLIContext context, int exitCode) {
        TARGET.remove();
    }

    @Override
    public void onThrowable(CLIContext context, Throwable t) {
        TARGET.remove();
    }

    /** The target name of the create-job/copy-job command running on this thread, or {@code null}. */
    @CheckForNull
    static String currentTarget() {
        return TARGET.get();
    }
}
