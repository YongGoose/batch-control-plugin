package io.jenkins.plugins.batchcontrol.security;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.cli.CLICommand;
import hudson.cli.CopyJobCommand;
import hudson.cli.CreateJobCommand;
import hudson.model.Item;
import hudson.model.ItemGroup;
import java.util.ArrayList;
import java.util.List;
import java.util.StringTokenizer;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.Stapler;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.TokenList;

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
 *   <li>{@code confirmRename}, {@code doRename} (core's deprecated {@code Job#doDoRename}) and
 *       {@code checkNewName} (rename, and its validation): {@code newName}, else {@code value},
 *       for the item being renamed and its parent group. D-71c: no window confers anything for a
 *       rename at all ({@link #forRename}, {@link #forRenameIn}); the new name is only recorded;</li>
 *   <li>CLI {@code create-job} and {@code copy-job}: the command's own target argument, bound to
 *       the command's user and target folder (S-13);</li>
 *   <li>D-59: a POST to the folders plugin's {@code move/move}: the name of the item being moved,
 *       for the group named by {@code destination} only. Not recordable here: {@link MoveGuard}
 *       decides the move as a whole and writes the one record of a refusal.</li>
 * </ul>
 *
 * <p>Anything else is {@link Kind#UNKNOWN} and a restricted grant confers nothing there
 * (fail-safe): a script, a build running as the user, another CLI command, a POST to any other
 * endpoint. A read-only page view (GET or HEAD) of
 * another endpoint is {@link Kind#UNNAMED}: it cannot create anything, and answering it keeps the
 * "New Item" link and page available.
 *
 * <p>D-71c (security-36 S-36-01): the endpoint is the web method Stapler actually dispatches: the
 * last token of the canonical request path, percent-decoded the way Stapler decodes it
 * ({@link #webMethodOf}). Comparing the raw URI missed {@code confirm%52ename}, {@code %63onfirmRename}
 * and a trailing slash, which all run {@code doConfirmRename}.
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

    /** The web method of the folders plugin's move action ({@code <item>/move/move}). */
    static final String MOVE_OPERATION = "move";

    private static final String RELOCATION_ACTION = "com.cloudbees.hudson.plugins.folder.relocate.RelocationAction";

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
        } else if (MOVE_OPERATION.equals(endpoint) && !readOnly) {
            Item moved = movedItem(req);
            if (moved != null && ("/" + groupFullName).equals(req.getParameter("destination"))) {
                return named(moved.getName(), false, MOVE_OPERATION);
            }
        }
        // D-71c: a rename is not a creation; no window confers Create for it (forRenameIn).
        return readOnly ? UNNAMED : UNKNOWN;
    }

    /**
     * The context of a permission check on the item {@code itemFullName}: the new name when the
     * current request renames exactly that item (S-01), else {@code null} (not a rename). A rename
     * whose new name is not given is {@link Kind#UNKNOWN}.
     */
    @CheckForNull
    static NewItemName forRename(String itemFullName) {
        StaplerRequest2 req = Stapler.getCurrentRequest2();
        String endpoint = req == null ? null : endpoint(req);
        if (req == null || !isRenameEndpoint(endpoint)) {
            return null;
        }
        Item renamed = req.findAncestorObject(Item.class);
        if (renamed == null || !itemFullName.equals(renamed.getFullName())) {
            return null;
        }
        return renameContext(req, endpoint);
    }

    /**
     * D-71c: the context of a Create check on the group {@code groupFullName} when the current
     * request renames an item directly inside that group (core's second rename rule checks Create
     * in the parent), else {@code null}.
     */
    @CheckForNull
    static NewItemName forRenameIn(String groupFullName) {
        StaplerRequest2 req = Stapler.getCurrentRequest2();
        String endpoint = req == null ? null : endpoint(req);
        if (req == null || !isRenameEndpoint(endpoint)) {
            return null;
        }
        Item renamed = req.findAncestorObject(Item.class);
        if (renamed == null || !groupFullName.equals(renamed.getParent().getFullName())) {
            return null;
        }
        return renameContext(req, endpoint);
    }

    private static NewItemName renameContext(StaplerRequest2 req, String endpoint) {
        String value = renameValue(req);
        if (value == null) {
            return new NewItemName(Kind.UNKNOWN, null, !readOnly(req) && isRenameChange(endpoint), endpoint);
        }
        return named(value, !readOnly(req) && isRenameChange(endpoint), endpoint);
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
     * ({@code createItem}, {@code confirmRename}, {@code doRename}); a refusal there is answered
     * with the explanation of the restriction (e2e-03 DEF-19).
     */
    static boolean isWebChangeOperation(@CheckForNull String operation) {
        return "createItem".equals(operation) || isRenameChange(operation);
    }

    /** Whether {@code operation} is a CLI {@code create-job}/{@code copy-job} (e2e-03 DEF-36). */
    static boolean isCliOperation(@CheckForNull String operation) {
        return operation != null && operation.startsWith("cli:");
    }

    /**
     * D-59: the item the current request moves, when it is a POST to the folders plugin's
     * {@code <item>/move/move}; else {@code null}.
     */
    @CheckForNull
    static Item movedItem(@CheckForNull StaplerRequest2 req) {
        if (req == null || readOnly(req) || !MOVE_OPERATION.equals(endpoint(req))
                || !isMoveAction(req)) {
            return null;
        }
        return req.findAncestorObject(Item.class);
    }

    /**
     * Whether the web method was dispatched through the folders plugin's move action (its class
     * is restricted to that plugin, so it is matched by name).
     */
    private static boolean isMoveAction(StaplerRequest2 req) {
        for (org.kohsuke.stapler.Ancestor ancestor : req.getAncestors()) {
            Object object = ancestor.getObject();
            if (object != null && RELOCATION_ACTION.equals(object.getClass().getName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Every core endpoint that renames an item or validates a rename (D-71c, S-36-01): core's
     * {@code AbstractItem#doConfirmRename}, the deprecated {@code Job#doDoRename} (which runs the
     * same check) and the {@code checkNewName} validation.
     */
    private static boolean isRenameEndpoint(@CheckForNull String endpoint) {
        return isRenameChange(endpoint) || "checkNewName".equals(endpoint);
    }

    /** The endpoints that actually rename ({@code confirmRename}, {@code doRename}). */
    private static boolean isRenameChange(@CheckForNull String endpoint) {
        return "confirmRename".equals(endpoint) || "doRename".equals(endpoint);
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

    /**
     * The web method of the request as Stapler dispatches it (S-36-01), or {@code null}: see
     * {@link #webMethodOf}. The context path is removed first, as Stapler does.
     */
    @CheckForNull
    private static String endpoint(StaplerRequest2 req) {
        String uri = req.getRequestURI();
        if (uri == null) {
            return null;
        }
        String context = req.getContextPath();
        if (context != null && !context.isEmpty() && uri.startsWith(context)) {
            uri = uri.substring(context.length());
        }
        return webMethodOf(uri);
    }

    /**
     * D-71c (security-36 S-36-01): the last token of {@code path} as Stapler sees it when it picks
     * the web method: {@code Stapler#canonicalPath} (empty and {@code .} segments dropped,
     * {@code ..} removes the segment before it), then the {@link TokenList} split on {@code /} and
     * {@code \}, then the token percent-decoded with Stapler's own {@link TokenList#decode}. So
     * {@code confirm%52ename}, {@code %63onfirmRename} and {@code confirmRename/} are all
     * {@code confirmRename}. {@code null} for an empty path.
     */
    @CheckForNull
    static String webMethodOf(String path) {
        List<String> segments = new ArrayList<>();
        for (String segment : path.split("/+")) {
            if (segment.isEmpty() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                if (!segments.isEmpty()) {
                    segments.remove(segments.size() - 1);
                }
                continue;
            }
            segments.add(segment);
        }
        String last = null;
        StringTokenizer tokens = new StringTokenizer(String.join("/", segments), "/\\");
        while (tokens.hasMoreTokens()) {
            last = tokens.nextToken();
        }
        if (last == null) {
            return null;
        }
        try {
            return TokenList.decode(last);
        } catch (RuntimeException e) {
            return last; // malformed escape: Stapler cannot dispatch this request either
        }
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
