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
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.StaplerRequest2;

/**
 * Parses and validates the history filter query parameters (SPEC item 12):
 * {@code from}, {@code to} (ISO dates, inclusive), {@code job} (case-insensitive substring of
 * the full name), {@code user} (exact id), {@code result} and {@code status} (enum-name tokens).
 *
 * <p>All parsing is forgiving: garbage values fall back to the default instead of failing, so a
 * hand-edited URL can never produce a stack trace.
 *
 * <p>Bounds (#13, SPEC item 4): a query span is capped by records, not by files — the store's
 * page queries stop after {@code Store.MAX_SCANNED_RECORDS} and the screen asks the user to
 * narrow the filter. There is no month cap on the span. Dates themselves are validated: only
 * plain {@code yyyy-MM-dd} with a year in {@value #MIN_YEAR}..{@value #MAX_YEAR} is accepted,
 * and {@link Filter#months(Collection)} only yields months that exist in the store, so a wide
 * span never iterates over empty months.
 */
@Restricted(NoExternalUse.class)
public final class FilterParser {

    /** Lowest accepted year of a {@code from}/{@code to} date (input validation). */
    public static final int MIN_YEAR = 1970;

    /** Highest accepted year of a {@code from}/{@code to} date (input validation). */
    public static final int MAX_YEAR = 9999;

    /** Default range length in days (inclusive of today) when no dates are given. */
    public static final int DEFAULT_RANGE_DAYS = 30;

    private static final int MAX_TEXT_LENGTH = 256;

    private FilterParser() {
    }

    /** Parses the filter from the current request; never returns null. */
    public static Filter parse(@CheckForNull StaplerRequest2 req) {
        LocalDate today = LocalDate.now(BatchClock.clock());
        String rawFrom = param(req, "from");
        String rawTo = param(req, "to");
        LocalDate from = parseDate(rawFrom);
        LocalDate to = parseDate(rawTo);
        // e2e-04 FD-11: a date that was given but cannot be used, or a reversed range, is
        // reported next to its field (the screen then lists nothing). The fallback values below
        // stay as before for the CSV exports and any other caller that ignores the errors.
        String fromError = given(rawFrom) && from == null ? DATE_MESSAGE : null;
        String toError = given(rawTo) && to == null ? DATE_MESSAGE : null;
        if (from != null && to != null && from.isAfter(to)) {
            toError = "The end date is before the start date. Enter an end date on or after "
                    + from + ".";
        }
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
        Filter filter = new Filter(from, to,
                text(param(req, "job")),
                text(param(req, "user")),
                token(param(req, "result")),
                token(param(req, "status")));
        filter.fromError = fromError;
        filter.toError = toError;
        filter.fromInput = fromError != null || toError != null ? text(rawFrom) : null;
        filter.toInput = fromError != null || toError != null ? text(rawTo) : null;
        return filter;
    }

    /** The message for a date that is not a plain ISO date in the accepted years. */
    static final String DATE_MESSAGE = "Enter a date as YYYY-MM-DD (a real calendar day, year "
            + MIN_YEAR + " to " + MAX_YEAR + ").";

    private static boolean given(@CheckForNull String raw) {
        return raw != null && !raw.trim().isEmpty();
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
        String trimmed = raw.trim();
        // Plain ISO dates only: no signed or extended years.
        if (!trimmed.matches("\\d{4}-\\d{2}-\\d{2}")) {
            return null;
        }
        try {
            LocalDate date = LocalDate.parse(trimmed);
            return date.getYear() < MIN_YEAR || date.getYear() > MAX_YEAR ? null : date;
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

        // Set once by FilterParser#parse, before the filter is handed out.
        private String fromError;
        private String toError;
        private String fromInput;
        private String toInput;

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

        /** e2e-04 FD-11: the refusal of the {@code from} date, or {@code null}. */
        @CheckForNull
        public String getFromError() {
            return fromError;
        }

        /** e2e-04 FD-11: the refusal of the {@code to} date (or of a reversed range), or {@code null}. */
        @CheckForNull
        public String getToError() {
            return toError;
        }

        /** Whether both dates were usable; the screen lists nothing otherwise. */
        public boolean isValid() {
            return fromError == null && toError == null;
        }

        /** The {@code from} field's value for redisplay: what the user entered after a refusal. */
        public String getFromInput() {
            return isValid() ? getFromValue() : (fromInput == null ? "" : fromInput);
        }

        /** The {@code to} field's value for redisplay: what the user entered after a refusal. */
        public String getToInput() {
            return isValid() ? getToValue() : (toInput == null ? "" : toInput);
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

        /**
         * The months of {@code stored} (the store's existing buckets) that the range covers,
         * oldest first. Bounded by what exists, not by the span, so a wide range is cheap.
         */
        public List<YearMonth> months(Collection<YearMonth> stored) {
            YearMonth first = YearMonth.from(from);
            YearMonth last = YearMonth.from(to);
            List<YearMonth> months = new ArrayList<>();
            for (YearMonth m : new TreeSet<>(stored)) {
                if (!m.isBefore(first) && !m.isAfter(last)) {
                    months.add(m);
                }
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
