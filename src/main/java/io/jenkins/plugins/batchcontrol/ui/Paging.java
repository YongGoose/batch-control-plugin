package io.jenkins.plugins.batchcontrol.ui;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.Stapler;
import org.kohsuke.stapler.StaplerRequest2;

/**
 * Paging arithmetic shared by every list screen (D-44). Each section keeps its own
 * Jelly-facing names ({@code getPage}, {@code getPageItems}, {@code getTotal},
 * {@code isHasPrevious}, {@code isHasNext}) and delegates the arithmetic here, so the screens
 * cannot drift apart again.
 */
@Restricted(NoExternalUse.class)
public final class Paging {

    /** Rows per page on every Batch Control list screen (SPEC: 50 by default). */
    public static final int PAGE_SIZE = 50;

    private Paging() {
    }

    /** The 1-based page from the {@code page} query parameter; 1 on absence or garbage. */
    public static int currentPage() {
        return currentPage("page");
    }

    /**
     * The 1-based page from the named query parameter; 1 on absence, garbage or a value below 1.
     *
     * @param parameter the query parameter name (a screen with two tables uses two names)
     */
    public static int currentPage(String parameter) {
        StaplerRequest2 req = Stapler.getCurrentRequest2();
        if (req == null) {
            return 1;
        }
        String raw = req.getParameter(parameter);
        if (raw == null) {
            return 1;
        }
        try {
            return Math.max(1, Integer.parseInt(raw.trim()));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    /** The zero-based offset of the first row of {@code page}; saturates instead of overflowing. */
    public static int offset(int page) {
        long offset = ((long) Math.max(1, page) - 1) * PAGE_SIZE;
        return (int) Math.min(Integer.MAX_VALUE, offset);
    }

    /** The rows of {@code page} out of a fully materialised, already sorted list (a copy). */
    public static <T> List<T> slice(List<T> all, int page) {
        int from = offset(page);
        if (from >= all.size()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(all.subList(from, Math.min(from + PAGE_SIZE, all.size())));
    }

    /**
     * #45: the query string (without the leading {@code ?}) of a pager link to {@code page} of the
     * list paged with {@code parameter}: every other parameter of the current URL's query string,
     * in its order (the other lists' {@code pendingPage}, {@code activePage}, {@code endedPage}),
     * then {@code parameter=page}. Only the query string is read, never a form body, and each
     * name and value is decoded and URL-encoded again, so a hostile value travels as data; the
     * view escapes the result as an attribute value too. A pair that does not decode is dropped.
     *
     * @param parameter the query parameter of the paged list
     * @param page the 1-based page the link opens
     */
    public static String query(String parameter, long page) {
        StringBuilder query = new StringBuilder();
        StaplerRequest2 req = Stapler.getCurrentRequest2();
        String raw = req == null ? null : req.getQueryString();
        if (raw != null) {
            for (String pair : raw.split("&")) {
                int eq = pair.indexOf('=');
                String name;
                String value;
                try {
                    name = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
                    value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                } catch (IllegalArgumentException e) {
                    continue; // a malformed escape: not a parameter worth keeping
                }
                if (name.isEmpty() || name.equals(parameter)) {
                    continue;
                }
                query.append(URLEncoder.encode(name, StandardCharsets.UTF_8)).append('=')
                        .append(URLEncoder.encode(value, StandardCharsets.UTF_8)).append('&');
            }
        }
        return query.append(URLEncoder.encode(parameter, StandardCharsets.UTF_8)).append('=').append(page).toString();
    }

    /** Whether a newer page exists (every page after the first). */
    public static boolean hasPrevious(int page) {
        return page > 1;
    }

    /** Whether an older page exists for a list of {@code total} rows. */
    public static boolean hasNext(int page, int total) {
        return (long) offset(page) + PAGE_SIZE < total;
    }
}
