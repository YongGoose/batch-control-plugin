package io.jenkins.plugins.batchcontrol.store;

import java.util.Collections;
import java.util.List;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * One page of a bounded record query: the rows to render plus what the pager needs.
 *
 * <p>A query reads the requested months newest first and stops once it has read the query's scan
 * cap of records. {@link #getMatched()} counts the matches among the records that were read;
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
    private final boolean truncated;
    private final int oversized;
    private final int unreadable;

    RecordPage(List<T> items, int offset, int matched, boolean truncated, int oversized, int unreadable) {
        this.items = Collections.unmodifiableList(items);
        this.offset = offset;
        this.matched = matched;
        this.truncated = truncated;
        this.oversized = Math.max(0, oversized);
        this.unreadable = Math.max(0, unreadable);
    }

    /** This page, marked {@linkplain #isTruncated() truncated} (D-75 (2): a read that could not be completed). */
    RecordPage<T> asTruncated() {
        return new RecordPage<>(items, offset, matched, true, oversized, unreadable);
    }

    /** The rows of this page, newest first. */
    public List<T> getItems() {
        return items;
    }

    /** Matching records among those read (exact when not {@link #isTruncated()}). */
    public int getMatched() {
        return matched;
    }

    /** Whether the scan cap stopped the query before the oldest requested record. */
    public boolean isTruncated() {
        return truncated;
    }

    /**
     * Lines of the months read that were longer than {@code ReverseLineReader.MAX_LINE_BYTES} and
     * therefore skipped (security-11 N-02). Every read path, CSV exports included, skips such a
     * line, so a screen showing a non-zero count should say that records were skipped rather than
     * look complete.
     */
    public int getOversized() {
        return oversized;
    }

    /**
     * Lines among those read that could not be parsed (a torn or damaged line) and were therefore
     * skipped. Their time is unknown; only their position tells that they were appended within the
     * part of the log the query read.
     */
    public int getUnreadable() {
        return unreadable;
    }

    /** Whether a further page of matches exists among the records read. */
    public boolean isHasNext() {
        return (long) offset + items.size() < matched;
    }
}
