package io.jenkins.plugins.batchcontrol.policy;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import java.time.Duration;
import java.time.Instant;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * A small, bounded, time-limited cache of {@link RunRequestService#requesterLacksBuild} answers
 * (security-33 S-33-09, #75), keyed by request id: an id is unique, so an answer is never shared
 * between requests. Backed by Caffeine (R4-2); its ticker reads {@link BatchClock}, so expiry
 * follows the plugin clock that tests install, and no timer is involved.
 */
@Restricted(NoExternalUse.class)
final class RequesterBuildCache {

    /** Caffeine time source on the plugin clock, in nanoseconds since the epoch. */
    private static final Ticker BATCH_CLOCK_TICKER = () -> {
        Instant now = BatchClock.now();
        return now.getEpochSecond() * 1_000_000_000L + now.getNano();
    };

    private final Cache<String, Boolean> entries;

    RequesterBuildCache(Duration ttl, int maxSize) {
        this.entries = Caffeine.newBuilder()
                .ticker(BATCH_CLOCK_TICKER)
                .expireAfterWrite(ttl)
                .maximumSize(maxSize)
                .build();
    }

    /** The cached answer for {@code requestId}, or {@code null} when absent or expired. */
    @CheckForNull
    Boolean get(String requestId) {
        return entries.getIfPresent(requestId);
    }

    void put(String requestId, boolean value) {
        entries.put(requestId, value);
    }

    /** Drops the cached answer of one request. */
    void invalidate(String requestId) {
        entries.invalidate(requestId);
    }
}
