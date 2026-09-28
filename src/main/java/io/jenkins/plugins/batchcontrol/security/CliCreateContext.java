package io.jenkins.plugins.batchcontrol.security;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.Extension;
import io.jenkins.plugins.batchcontrol.model.Approvers;
import java.util.List;
import jenkins.cli.listeners.CLIContext;
import jenkins.cli.listeners.CLIListener;
import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.springframework.security.core.Authentication;

/**
 * D-40: remembers, on the thread that runs it, the target of a {@code create-job} or
 * {@code copy-job} CLI command, for {@link NewItemName}. {@code CLICommand.getCurrent()} is only
 * set by the remote CLI transports, while {@code CLICommand.main} always notifies
 * {@link CLIListener}s on the executing thread just before {@code run()}.
 *
 * <p>S-13: the captured target is bound to the command's user and target folder, so a value left
 * behind on a pooled thread (if clean-up never ran) cannot name an item for another user or
 * another folder.
 */
@Extension
@Restricted(NoExternalUse.class)
public class CliCreateContext implements CLIListener {

    private static final ThreadLocal<Captured> TARGET = new ThreadLocal<>();

    private static final class Captured {
        final String command;
        final String target;
        @CheckForNull
        final String user;

        Captured(String command, String target, @CheckForNull String user) {
            this.command = command;
            this.target = target;
            this.user = user;
        }
    }

    @Override
    public void onExecution(CLIContext context) {
        String command = context.getCommand();
        List<String> args = context.getArgs();
        if (("create-job".equals(command) || "copy-job".equals(command)) && args != null && !args.isEmpty()) {
            Authentication auth = context.getAuth();
            // create-job NAME, copy-job SRC DST: the new item's full name is the last argument.
            TARGET.set(new Captured(command, args.get(args.size() - 1), auth == null ? null : auth.getName()));
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

    /**
     * The command name and target (full name) of the create-job/copy-job command running on this
     * thread, as {@code {command, target}}, when it was issued by the current user into
     * {@code groupFullName}; otherwise {@code null}.
     */
    @CheckForNull
    static String[] currentTarget(String groupFullName) {
        Captured captured = TARGET.get();
        if (captured == null) {
            return null;
        }
        String current = Jenkins.getAuthentication2().getName();
        if (!Approvers.sameUser(captured.user, current)) {
            return null;
        }
        int slash = captured.target.lastIndexOf('/');
        String folder = slash < 0 ? "" : captured.target.substring(0, slash);
        return folder.equals(groupFullName) ? new String[] {captured.command, captured.target} : null;
    }
}
