package io.jenkins.plugins.batchcontrol.security;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.cli.CLICommand;
import hudson.cli.CopyJobCommand;
import hudson.cli.CreateJobCommand;
import hudson.model.ItemGroup;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.Stapler;
import org.kohsuke.stapler.StaplerRequest2;

/**
 * D-40: works out, while {@code Item/Create} is being checked on an item group, the name of the
 * item about to be created, so a name-restricted Create grant can be refused <em>before</em> the
 * item exists. Core checks Create on the group before it looks at the name and passes no name to
 * the ACL, so the name is read from the context of the check:
 *
 * <ul>
 *   <li><b>HTTP</b> ({@code createItem}: new item form, REST with a config.xml body, copy with
 *       {@code mode=copy}): core's {@code ItemGroupMixIn#createTopLevelItem} reads the new name from
 *       the {@code name} parameter of the same request, and fails without one. The parameter is
 *       used when the request is aimed at the group being checked. The name check of the new-item
 *       page ({@code checkJobName?value=}) is treated the same way.</li>
 *   <li><b>CLI</b> {@code create-job} and {@code copy-job}: the command's target name field.</li>
 * </ul>
 *
 * <p>Anything else is {@link Kind#UNKNOWN} and a restricted grant does not confer Create there
 * (fail-safe): a script, a build running as the user, another CLI command, or an HTTP request
 * that is not a read-only page view but carries no name (for example moving an item into the
 * scope). A read-only page view (GET or HEAD) without a name is {@link Kind#UNNAMED}: it cannot
 * create anything, and answering it keeps the "New Item" link and page available.
 */
@Restricted(NoExternalUse.class)
final class NewItemName {

    enum Kind {
        /** The new item's name is known. */
        NAMED,
        /** A read-only page view; nothing can be created by it. */
        UNNAMED,
        /** The name cannot be determined; a restricted grant must not confer Create. */
        UNKNOWN
    }

    private static final NewItemName UNNAMED = new NewItemName(Kind.UNNAMED, null);
    private static final NewItemName UNKNOWN = new NewItemName(Kind.UNKNOWN, null);

    private final Kind kind;
    @CheckForNull
    private final String name;

    private NewItemName(Kind kind, @CheckForNull String name) {
        this.kind = kind;
        this.name = name;
    }

    Kind getKind() {
        return kind;
    }

    /** The new item's name (trimmed, last path segment); non-null only for {@link Kind#NAMED}. */
    @CheckForNull
    String getName() {
        return name;
    }

    /**
     * Resolves the context of a Create check on the group whose full name is
     * {@code groupFullName}.
     */
    static NewItemName resolve(String groupFullName) {
        CLICommand command = CLICommand.getCurrent();
        if (command != null) {
            return fromCli(command);
        }
        StaplerRequest2 req = Stapler.getCurrentRequest2();
        if (req != null) {
            return fromHttp(req, groupFullName);
        }
        return UNKNOWN;
    }

    private static NewItemName fromCli(CLICommand command) {
        String target = null;
        if (command instanceof CreateJobCommand) {
            target = ((CreateJobCommand) command).name;
        } else if (command instanceof CopyJobCommand) {
            target = ((CopyJobCommand) command).dst;
        }
        return target == null ? UNKNOWN : named(target);
    }

    private static NewItemName fromHttp(StaplerRequest2 req, String groupFullName) {
        String method = req.getMethod();
        boolean readOnly = "GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method);
        if (!aimedAt(req, groupFullName)) {
            // A check on another group while serving this request (rendering, for example): the
            // parameter does not describe an item in this group.
            return readOnly ? UNNAMED : UNKNOWN;
        }
        String name = req.getParameter("name");
        if (name == null) {
            String uri = req.getRequestURI();
            if (uri != null && uri.endsWith("/checkJobName")) {
                name = req.getParameter("value");
            }
        }
        if (name != null) {
            return named(name);
        }
        return readOnly ? UNNAMED : UNKNOWN;
    }

    /** Whether the nearest item group the request was dispatched through is the checked group. */
    private static boolean aimedAt(StaplerRequest2 req, String groupFullName) {
        Object group = req.findAncestorObject(ItemGroup.class);
        if (!(group instanceof ItemGroup)) {
            return false;
        }
        String fullName = ((ItemGroup<?>) group).getFullName();
        return groupFullName.equals(fullName);
    }

    private static NewItemName named(String target) {
        String trimmed = target.trim();
        String last = trimmed.substring(trimmed.lastIndexOf('/') + 1);
        return new NewItemName(Kind.NAMED, last);
    }
}
