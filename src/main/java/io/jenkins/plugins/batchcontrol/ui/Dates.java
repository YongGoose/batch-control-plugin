package io.jenkins.plugins.batchcontrol.ui;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** Timestamp formatting for the Jelly views (controller-local zone, second precision). */
public final class Dates {

    private static final DateTimeFormatter FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z").withZone(ZoneId.systemDefault());

    private Dates() {
    }

    /** @return the formatted timestamp, or an empty string for null (e.g. undecided requests). */
    public static String format(@CheckForNull Instant instant) {
        return instant == null ? "" : FORMAT.format(instant);
    }
}
