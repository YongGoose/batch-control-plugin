package io.jenkins.plugins.batchcontrol.ui;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.kohsuke.stapler.StaplerRequest2;

/**
 * Parses and validates the history filter query parameters (SPEC item 12):
 * {@code from}, {@code to} (ISO dates, inclusive), {@code job} (case-insensitive substring of
 * the full name), {@code user} (exact id), {@code result} and {@code status} (enum-name tokens).
 *
 * <p>All parsing is forgiving: garbage values fall back to the default instead of failing, so a
 * hand-edited URL can never produce a stack trace. The date range is capped at
 * {@value #MAX_MONTHS} months to bound how many monthly store files a single request may read.
 */
public final class FilterParser {

    /** Hard cap on the number of monthly buckets one request may scan. */
    public static final int MAX_MONTHS = 36;

    /** Default range length in days (inclusive of today) when no dates are given. */
    public static final int DEFAULT_RANGE_DAYS = 30;

    private static final int MAX_TEXT_LENGTH = 256;

    private FilterParser() {
    }

    /** Parses the filter from the current request; never returns null. */
    public static Filter parse(@CheckForNull StaplerRequest2 req) {
        LocalDate today = LocalDate.now(BatchClock.clock());
        LocalDate from = parseDate(param(req, "from"));
        LocalDate to = parseDate(param(req, "to"));
        if (to == null) {
            to = today;
        }
        if (from == null) {
            from = to.minusDays(DEFAULT_RANGE_DAYS - 1L);
        }
        if (from.isAfter(to)) {
            LocalDate swap = from;
            from = to;
            to = swap;
        }
        // Cap the span so one request cannot scan an unbounded number of monthly files.
        YearMonth firstAllowed = YearMonth.from(to).minusMonths(MAX_MONTHS - 1L);
        if (YearMonth.from(from).isBefore(firstAllowed)) {
            from = firstAllowed.atDay(1);
        }
        return new Filter(from, to,
                text(param(req, "job")),
                text(param(req, "user")),
                token(param(req, "result")),
                token(param(req, "status")));
    }

    @CheckForNull
    private static String param(@CheckForNull StaplerRequest2 req, String name) {
        return req == null ? null : req.getParameter(name);
    }

    @CheckForNull
    private static LocalDate parseDate(@CheckForNull String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** Free-text filter value: trimmed, length-capped, empty means absent. */
    @CheckForNull
    private static String text(@CheckForNull String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() > MAX_TEXT_LENGTH ? trimmed.substring(0, MAX_TEXT_LENGTH) : trimmed;
    }

    /** Enum-name filter value: upper-cased, letters and underscores only, else absent. */
    @CheckForNull
    private static String token(@CheckForNull String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim().toUpperCase(Locale.ROOT);
        if (trimmed.isEmpty() || trimmed.length() > 32 || !trimmed.matches("[A-Z_]+")) {
            return null;
        }
        return trimmed;
    }

    static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException("UTF-8 is a mandatory charset", e);
        }
    }

    /**
     * The validated filter. Immutable; date bounds are always present (defaulted), text filters
     * are null when absent.
     */
    public static final class Filter {

        private final LocalDate from;
        private final LocalDate to;
        private final String job;
        private final String user;
        private final String result;
        private final String status;

        Filter(LocalDate from, LocalDate to, @CheckForNull String job, @CheckForNull String user,
                @CheckForNull String result, @CheckForNull String status) {
            this.from = from;
            this.to = to;
            this.job = job;
            this.user = user;
            this.result = result;
            this.status = status;
        }

        // ------------------------------------------------------------ values (form redisplay)

        public String getFromValue() {
            return from.toString();
        }

        public String getToValue() {
            return to.toString();
        }

        @CheckForNull
        public String getJob() {
            return job;
        }

        @CheckForNull
        public String getUser() {
            return user;
        }

        @CheckForNull
        public String getResult() {
            return result;
        }

        @CheckForNull
        public String getStatus() {
            return status;
        }

        // ------------------------------------------------------------ range helpers

        /** Start of the range (inclusive), in the controller zone. */
        public Instant fromInstant() {
            return from.atStartOfDay(zone()).toInstant();
        }

        /** End of the range (exclusive: start of the day after {@code to}). */
        public Instant toInstantExclusive() {
            return to.plusDays(1).atStartOfDay(zone()).toInstant();
        }

        /** The monthly store buckets covered by the range, oldest first (capped by the parser). */
        public List<YearMonth> months() {
            List<YearMonth> months = new ArrayList<>();
            YearMonth last = YearMonth.from(to);
            for (YearMonth m = YearMonth.from(from); !m.isAfter(last); m = m.plusMonths(1)) {
                months.add(m);
            }
            return months;
        }

        // ------------------------------------------------------------ matching

        /** Whether the timestamp lies inside the [from, to] date range; null never matches. */
        public boolean inRange(@CheckForNull Instant at) {
            return at != null && !at.isBefore(fromInstant()) && at.isBefore(toInstantExclusive());
        }

        /** Case-insensitive substring match on the job full name (or change target). */
        public boolean matchesJob(@CheckForNull String jobFullName) {
            if (job == null) {
                return true;
            }
            return jobFullName != null
                    && jobFullName.toLowerCase(Locale.ROOT).contains(job.toLowerCase(Locale.ROOT));
        }

        /** Exact match on the user id. */
        public boolean matchesUser(@CheckForNull String userId) {
            return user == null || user.equals(userId);
        }

        /** Case-insensitive match on the result token (build result / incident result). */
        public boolean matchesResult(@CheckForNull Object recordResult) {
            return result == null
                    || (recordResult != null
                            && result.equalsIgnoreCase(String.valueOf(recordResult)));
        }

        /** Case-insensitive match on the status token (enum name). */
        public boolean matchesStatus(@CheckForNull Object recordStatus) {
            return status == null
                    || (recordStatus != null
                            && status.equalsIgnoreCase(String.valueOf(recordStatus)));
        }

        // ------------------------------------------------------------ links

        /**
         * The filter as a URL query fragment ({@code from=...&to=...}, plus the optional
         * parameters when present), for paging and CSV export links. Values are URL-encoded.
         */
        public String toQueryString() {
            StringBuilder sb = new StringBuilder();
            sb.append("from=").append(encode(from.toString()));
            sb.append("&to=").append(encode(to.toString()));
            if (job != null) {
                sb.append("&job=").append(encode(job));
            }
            if (user != null) {
                sb.append("&user=").append(encode(user));
            }
            if (result != null) {
                sb.append("&result=").append(encode(result));
            }
            if (status != null) {
                sb.append("&status=").append(encode(status));
            }
            return sb.toString();
        }

        private static ZoneId zone() {
            return BatchClock.clock().getZone();
        }
    }
}
