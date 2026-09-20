package io.jenkins.plugins.batchcontrol.store;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Generates store identifiers of the form {@code yyyyMMdd-HHmmss-<6 random alnum>}:
 * file-name safe and sortable by creation time. Time comes from {@link BatchClock}.
 */
@Restricted(NoExternalUse.class)
public final class Ids {

    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    /** Lowercase only: some file systems (Windows, macOS default) are case-insensitive. */
    private static final String ALNUM = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final int RANDOM_LENGTH = 6;
    private static final SecureRandom RANDOM = new SecureRandom();

    private Ids() {
    }

    public static String newId() {
        LocalDateTime now = LocalDateTime.ofInstant(BatchClock.now(), BatchClock.clock().getZone());
        StringBuilder sb = new StringBuilder(FORMAT.format(now)).append('-');
        for (int i = 0; i < RANDOM_LENGTH; i++) {
            sb.append(ALNUM.charAt(RANDOM.nextInt(ALNUM.length())));
        }
        return sb.toString();
    }
}
