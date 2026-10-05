package io.jenkins.plugins.batchcontrol.ui;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Resolves a record id that arrives as a URL segment ({@code /batch-control/requests/<id>/} and the
 * other detail URLs) or is about to become a link to one (S-39-01).
 *
 * <p>Every way a lookup can fail answers {@code null}, which Stapler renders as 404, exactly like an
 * unknown id or a record the caller may not see (P-09): a malformed id, a missing or unreadable
 * file, a file holding another kind of object, a record stored under another id, an I/O error, or a
 * visibility check that cannot complete. An HTTP 500 would tell an existing record from a missing
 * one.
 *
 * <p>The id's shape is checked before anything reaches the store. Request and window ids are UUIDs
 * (D-68), earlier request ids and history ids are {@code yyyyMMdd-HHmmss-<6>}: letters, digits and
 * {@code -} only. A {@code .} never passes, so {@code <id>.values} or {@code <id>.VALUES} cannot
 * reach a run request's values file (D-74) on any file system, and {@code / \ % ~} cannot name
 * another path or a Windows short name. The record read must carry the id that was asked for, so a
 * case-insensitive file system does not serve a record under a second URL either. Nothing but the
 * record the id names is read, and the visibility check runs before anything of it is used.
 *
 * <p>Logging never writes the raw URL segment: a malformed id is logged at FINE without it, and an
 * id that reached the store has the checked shape, so it cannot start a new log line.
 */
@Restricted(NoExternalUse.class)
public final class RecordLookup {

    private static final Logger LOGGER = Logger.getLogger(RecordLookup.class.getName());

    /** Letters, digits and {@code -}, bounded (a UUID has 36 characters, a history id 22). */
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9-]{1,100}");

    private RecordLookup() {
    }

    /** Whether {@code id} has the shape of a store id (see the class comment). */
    public static boolean isWellFormed(@CheckForNull String id) {
        return id != null && ID.matcher(id).matches();
    }

    /**
     * The record {@code id} names if the current user may see it, else {@code null} (rendered as
     * 404), whatever the reason.
     *
     * @param id      the id from the URL or the link; may be anything
     * @param what    what the record is, for the log ({@code "run request"}); a constant, never input
     * @param load    the store lookup, {@code null} for an unknown id; called only with a well-formed id
     * @param idOf    the stored id of a loaded record
     * @param visible the P-09 predicate for the current user, applied to the loaded record
     */
    @CheckForNull
    public static <T> T find(@CheckForNull String id, String what, Function<String, T> load,
                             Function<? super T, String> idOf, Predicate<? super T> visible) {
        if (!isWellFormed(id)) {
            LOGGER.log(Level.FINE, "Answering 404 for a malformed {0} id", what);
            return null;
        }
        try {
            T record = load.apply(id);
            if (record == null) {
                return null;
            }
            if (!id.equals(idOf.apply(record))) {
                // A file system alias (another letter case, for example) of a stored record.
                LOGGER.log(Level.FINE, "Answering 404 for {0} {1}: the record read carries another id",
                        new Object[] {what, id});
                return null;
            }
            return visible.test(record) ? record : null;
        } catch (RuntimeException e) {
            // The id has the checked shape here, so it cannot forge a log line; the exception's
            // message (which may quote file content) goes to FINE only.
            LOGGER.log(Level.WARNING, "Could not read {0} {1} ({2}); answering 404 as for an unknown id",
                    new Object[] {what, id, e.getClass().getName()});
            LOGGER.log(Level.FINE, e, () -> "Reading " + what + " " + id + " failed");
            return null;
        }
    }
}
