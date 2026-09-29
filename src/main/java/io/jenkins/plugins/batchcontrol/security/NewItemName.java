package io.jenkins.plugins.batchcontrol.security;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.cli.CLICommand;
import hudson.cli.CopyJobCommand;
import hudson.cli.CreateJobCommand;
import hudson.model.Item;
import hudson.model.ItemGroup;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.Stapler;
import org.kohsuke.stapler.StaplerRequest2;

/**
 * D-40, D-40a: works out, while a permission is being checked, the name an item is about to get,
 * so a name-restricted Create grant can be refused <em>before</em> anything changes. Core checks
 * Create on the group before it looks at the name and passes no name to the ACL, so the name is
 * read from the context of the check, and only from the parameter of the endpoint that actually
 * performs the creation or rename (security-08 S-02):
 *
 * <ul>
 *   <li>{@code createItem} (new item form, REST with a config.xml body, copy): {@code name}, for
 *       the item group the request is dispatched to;</li>
 *   <li>{@code checkJobName} (new item page validation): {@code value};</li>
 *   <li>{@code confirmRename} and {@code checkNewName} (rename, and its validation):
 *       {@code newName}, else {@code value}, for the item being renamed and its parent group;</li>
 *   <li>CLI {@code create-job} and {@code copy-job}: the command's own target argument, bound to
 *       the command's user and target folder (S-13).</li>
 * </ul>
 *
 * <p>Anything else is {@link Kind#UNKNOWN} and a restricted grant confers nothing there
 * (fail-safe): a script, a build running as the user, another CLI command, a POST to any other
 * endpoint (for example moving an item into the scope). A read-only page view (GET or HEAD) of
 * another endpoint is {@link Kind#UNNAMED}: it cannot create anything, and answering it keeps the
 * "New Item" link and page available.
 *
 * <p>Names are returned exactly as submitted, untrimmed (S-06).
 */
@Restricted(NoExternalUse.class)
final class NewItemName {

    enum Kind {
        /** The new name is known. */
        NAMED,
        /** A read-only page view; nothing can be created by it. */
        UNNAMED,
        /** The name cannot be determined; a restricted grant must not confer Create. */
        UNKNOWN
    }

    private static final NewItemName UNNAMED = new NewItemName(Kind.UNNAMED, null, false, null);
    private static final NewItemName UNKNOWN = new NewItemName(Kind.UNKNOWN, null, false, null);

    private final Kind kind;
    @CheckForNull
    private final String name;
    /** Whether a refusal in this context is an actual attempt worth a GRANT_VIOLATION record. */
    private final boolean recordable;
    /** The operation that would create or rename (endpoint or CLI command), for the record key. */
    @CheckForNull
    private final String operation;

    private NewItemName(Kind kind, @CheckForNull String name, boolean recordable, @CheckForNull String operation) {
        this.kind = kind;
        this.name = name;
        this.recordable = recordable;
        this.operation = operation;
    }

    /**
     * The operation (for example {@code createItem} or {@code cli:copy-job}); two refusals of the
     * same name through different operations are separate attempts and each is recorded.
     */
    @CheckForNull
    String getOperation() {
        return operation;
    }

    Kind getKind() {
        return kind;
    }

    /** The new name (untrimmed, last path segment); non-null only for {@link Kind#NAMED}. */
    @CheckForNull
    String getName() {
        return name;
    }

    /**
     * Whether a refusal here is recorded (S-05): not for a validation request while typing a name,
     * nor for any GET/HEAD request, which cannot change anything.
     */
    boolean isRecordable() {
        return recordable;
    }

    /** The context of a Create check on the group whose full name is {@code groupFullName}. */
    static NewItemName forCreate(String groupFullName) {
        CLICommand command = CLICommand.getCurrent();
        if (command != null) {
            return fromCli(command, groupFullName);
        }
        String[] cliTarget = CliCreateContext.currentTarget(groupFullName);
        if (cliTarget != null) {
            return named(cliTarget[1], true, "cli:" + cliTarget[0]);
        }
        StaplerRequest2 req = Stapler.getCurrentRequest2();
        if (req == null) {
            return UNKNOWN;
        }
        boolean readOnly = readOnly(req);
        String endpoint = endpoint(req);
        if ("createItem".equals(endpoint) || "checkJobName".equals(endpoint)) {
            if (groupFullName.equals(groupOf(req))) {
                String value = req.getParameter("createItem".equals(endpoint) ? "name" : "value");
                if (value != null) {
                    return named(value, !readOnly && "createItem".equals(endpoint), endpoint);
                }
            }
        } else if (isRenameEndpoint(endpoint)) {
            Item renamed = req.findAncestorObject(Item.class);
            if (renamed != null && groupFullName.equals(renamed.getParent().getFullName())) {
                String value = renameValue(req);
                if (value != null) {
                    return named(value, !readOnly && "confirmRename".equals(endpoint), endpoint);
                }
            }
        }
        return readOnly ? UNNAMED : UNKNOWN;
    }

    /**
     * The context of a Configure check on the item {@code itemFullName}: the new name when the
     * current request renames exactly that item (S-01), else {@code null} (not a rename).
     */
    @CheckForNull
    static NewItemName forRename(String itemFullName) {
        StaplerRequest2 req = Stapler.getCurrentRequest2();
        if (req == null || !isRenameEndpoint(endpoint(req))) {
            return null;
        }
        Item renamed = req.findAncestorObject(Item.class);
        if (renamed == null || !itemFullName.equals(renamed.getFullName())) {
            return null;
        }
        String value = renameValue(req);
        if (value == null) {
            return UNKNOWN;
        }
        return named(value, !readOnly(req) && "confirmRename".equals(endpoint(req)), endpoint(req));
    }

    /**
     * Whether {@code operation} is one of the name checks the New Item and Rename pages run while
     * a name is typed ({@code checkJobName}, {@code checkNewName}); a refusal there is answered with
     * a form validation message (e2e-03 DEF-19).
     */
    static boolean isValidationOperation(@CheckForNull String operation) {
        return "checkJobName".equals(operation) || "checkNewName".equals(operation);
    }

    /**
     * Whether {@code operation} is a web endpoint that creates or renames an item
     * ({@code createItem}, {@code confirmRename}); a refusal there is answered with the
     * explanation of the restriction (e2e-03 DEF-19).
     */
    static boolean isWebChangeOperation(@CheckForNull String operation) {
        return "createItem".equals(operation) || "confirmRename".equals(operation);
    }

    private static boolean isRenameEndpoint(@CheckForNull String endpoint) {
        return "confirmRename".equals(endpoint) || "checkNewName".equals(endpoint);
    }

    @CheckForNull
    private static String renameValue(StaplerRequest2 req) {
        String value = req.getParameter("newName");
        return value != null ? value : req.getParameter("value");
    }

    private static boolean readOnly(StaplerRequest2 req) {
        String method = req.getMethod();
        return "GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method);
    }

    /** The last path segment of the request URI (the web method), or {@code null}. */
    @CheckForNull
    private static String endpoint(StaplerRequest2 req) {
        String uri = req.getRequestURI();
        if (uri == null) {
            return null;
        }
        while (uri.endsWith("/")) {
            uri = uri.substring(0, uri.length() - 1);
        }
        return uri.substring(uri.lastIndexOf('/') + 1);
    }

    /** Full name of the nearest item group the request was dispatched through, or {@code null}. */
    @CheckForNull
    private static String groupOf(StaplerRequest2 req) {
        Object group = req.findAncestorObject(ItemGroup.class);
        return group instanceof ItemGroup ? ((ItemGroup<?>) group).getFullName() : null;
    }

    private static NewItemName fromCli(CLICommand command, String groupFullName) {
        String target = null;
        if (command instanceof CreateJobCommand) {
            target = ((CreateJobCommand) command).name;
        } else if (command instanceof CopyJobCommand) {
            target = ((CopyJobCommand) command).dst;
        }
        if (target == null) {
            return UNKNOWN;
        }
        // create-job has already cut its argument to the last segment by the time Create is
        // checked; a copy-job destination may still carry the folder, which must be this group.
        int slash = target.lastIndexOf('/');
        if (slash >= 0 && !groupFullName.equals(target.substring(0, slash))) {
            return UNKNOWN;
        }
        return named(target, true, "cli:" + command.getName());
    }

    private static NewItemName named(String target, boolean recordable, @CheckForNull String operation) {
        return new NewItemName(Kind.NAMED, target.substring(target.lastIndexOf('/') + 1), recordable, operation);
    }
}
