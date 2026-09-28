package io.jenkins.plugins.batchcontrol.store;

import java.util.Collections;
import java.util.List;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * One page of a bounded record query (#13): the rows to render plus what the pager needs.
 *
 * <p>A query reads the requested months newest first and stops after {@link #getScanned()} reached
 * the query's scan cap. {@link #getMatched()} counts the matches among the records that were read;
 * when {@link #isTruncated()} is {@code true} older records exist that were not read, so the count
 * is a lower bound and the screen should say "narrow the filter" instead of showing it as a total.
 *
 * @param <T> record type
 */
@Restricted(NoExternalUse.class)
public final class RecordPage<T> {

    private final List<T> items;
    private final int offset;
    private final int matched;
    private final int scanned;
    private final boolean truncated;

    RecordPage(List<T> items, int offset, int matched, int scanned, boolean truncated) {
        this.items = Collections.unmodifiableList(items);
        this.offset = offset;
        this.matched = matched;
        this.scanned = scanned;
        this.truncated = truncated;
    }

    /** The rows of this page, newest first. */
    public List<T> getItems() {
        return items;
    }

    /** Matching records among those read (exact when not {@link #isTruncated()}). */
    public int getMatched() {
        return matched;
    }

    /** Records read (parsed) by this query, matching or not. */
    public int getScanned() {
        return scanned;
    }

    /** Whether the scan cap stopped the query before the oldest requested record. */
    public boolean isTruncated() {
        return truncated;
    }

    /** Whether a further page of matches exists among the records read. */
    public boolean isHasNext() {
        return (long) offset + items.size() < matched;
    }
}
