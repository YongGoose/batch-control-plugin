package io.jenkins.plugins.batchcontrol.ui;

import io.jenkins.plugins.batchcontrol.store.BatchClock;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.TimeZone;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit row (no Jenkins) for SPEC item 4, "Dates on screens are rendered in the plugin clock's
 * zone" (#17). Matrix row T-04-11 (note 63). Uses the one date helper the screens render through
 * (T-UI-21), {@code Dates.format(Instant)}.
 *
 * Written from docs/SPEC.md, issue #17 and docs/TEST-MATRIX.md only (no src/main knowledge).
 */
public class DatesClockZoneTest {

    private static final Instant AT = Instant.parse("2026-09-20T20:00:00Z");

    private TimeZone savedZone;

    @BeforeEach
    public void saveZone() {
        savedZone = TimeZone.getDefault();
    }

    @AfterEach
    public void restore() {
        TimeZone.setDefault(savedZone);
        BatchClock.reset();
    }

    /**
     * T-04-11 (#17): with the plugin clock in Asia/Tokyo and the default zone in UTC,
     * 2026-09-20T20:00Z renders on 2026-09-21 (Tokyo); with the zones swapped it renders on
     * 2026-09-20 (UTC). Either direction alone would pass for a helper that happens to use the
     * one zone the test picked.
     */
    @Test
    public void t_04_11_screenDatesFollowThePluginClockZone() {
        TimeZone.setDefault(TimeZone.getTimeZone(ZoneOffset.UTC));
        BatchClock.setForTest(Clock.fixed(AT, ZoneId.of("Asia/Tokyo")));
        String tokyo = Dates.format(AT);
        assertTrue(tokyo.startsWith("2026-09-21"), "clock zone Asia/Tokyo, default UTC: the date must be Tokyo's 2026-09-21, was " + tokyo);
        assertTrue(tokyo.contains("05:00"), "clock zone Asia/Tokyo: the time must be Tokyo's 05:00, was " + tokyo);

        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"));
        BatchClock.setForTest(Clock.fixed(AT, ZoneOffset.UTC));
        String utc = Dates.format(AT);
        assertTrue(utc.startsWith("2026-09-20"), "clock zone UTC, default Asia/Tokyo: the date must be UTC's 2026-09-20, was " + utc);
        assertTrue(utc.contains("20:00"), "clock zone UTC: the time must be UTC's 20:00, was " + utc);
    }
}
