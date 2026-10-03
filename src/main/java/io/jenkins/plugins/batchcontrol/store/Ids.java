package io.jenkins.plugins.batchcontrol.store;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DecimalStyle;
import java.util.Locale;
import java.util.UUID;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Generates store identifiers. Requests and permission windows get random UUIDs (D-68); history
 * records (change records, incidents) keep the form {@code yyyyMMdd-HHmmss-<6 random alnum>},
 * because retention recovers the month of a record's diff patch from that prefix. Identifiers are
 * opaque everywhere else: nothing parses them, and ordering uses the stored timestamps. Requests
 * stored with the earlier timestamp form still load and resolve, since an id is only ever used
 * as an exact key, a file name and a URL segment, all of which both forms satisfy.
 */
@Restricted(NoExternalUse.class)
public final class Ids {

    /**
     * ASCII digits whatever the controller's default locale (#17): ids become file names, and
     * retention recovers a record's month from the id prefix.
     */
    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT)
            .withDecimalStyle(DecimalStyle.STANDARD);
    /** Lowercase only: some file systems (Windows, macOS default) are case-insensitive. */
    private static final String ALNUM = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final int RANDOM_LENGTH = 6;
    private static final SecureRandom RANDOM = new SecureRandom();

    private Ids() {
    }

    /**
     * A new identifier for a run, grant or activation request (and the window a grant request
     * opens, which reuses the request id): a random UUID in lowercase, file-name safe (D-68).
     */
    public static String newRequestId() {
        return UUID.randomUUID().toString();
    }

    /** A new history record identifier, prefixed with the creation time on {@link BatchClock}. */
    public static String newId() {
        LocalDateTime now = LocalDateTime.ofInstant(BatchClock.now(), BatchClock.clock().getZone());
        StringBuilder sb = new StringBuilder(FORMAT.format(now)).append('-');
        for (int i = 0; i < RANDOM_LENGTH; i++) {
            sb.append(ALNUM.charAt(RANDOM.nextInt(ALNUM.length())));
        }
        return sb.toString();
    }
}
