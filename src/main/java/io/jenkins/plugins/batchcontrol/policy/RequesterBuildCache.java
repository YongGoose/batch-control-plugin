package io.jenkins.plugins.batchcontrol.policy;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * A small, bounded, time-limited cache of {@link RunRequestService#requesterLacksBuild} answers
 * (security-33 S-33-09, #75). Keys start with the request id followed by a NUL separator, so an
 * answer is never shared between different requests and {@link #invalidate} can drop every entry
 * of one request. Expiry is decided against {@link BatchClock}; no timer is involved.
 */
@Restricted(NoExternalUse.class)
final class RequesterBuildCache {

    private record Entry(boolean value, Instant expiresAt) {}

    private final Duration ttl;
    private final int maxSize;
    private final LinkedHashMap<String, Entry> entries;

    RequesterBuildCache(Duration ttl, int maxSize) {
        this.ttl = ttl;
        this.maxSize = maxSize;
        this.entries = new LinkedHashMap<>(16, 0.75f, true) {
            private static final long serialVersionUID = 1L;

            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
                return size() > RequesterBuildCache.this.maxSize;
            }
        };
    }

    /** The cached answer for {@code key}, or {@code null} when absent or expired. */
    @CheckForNull
    synchronized Boolean get(String key) {
        Entry e = entries.get(key);
        if (e == null) {
            return null;
        }
        if (e.expiresAt().isBefore(BatchClock.now())) {
            entries.remove(key);
            return null;
        }
        return e.value();
    }

    synchronized void put(String key, boolean value) {
        entries.put(key, new Entry(value, BatchClock.now().plus(ttl)));
    }

    /** Drops every entry of the request {@code requestId}. */
    synchronized void invalidate(@CheckForNull String requestId) {
        if (requestId == null) {
            return;
        }
        String prefix = requestId + '\u0000';
        for (Iterator<String> it = entries.keySet().iterator(); it.hasNext(); ) {
            if (it.next().startsWith(prefix)) {
                it.remove();
            }
        }
    }
}
