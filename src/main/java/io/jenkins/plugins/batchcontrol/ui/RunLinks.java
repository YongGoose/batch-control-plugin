package io.jenkins.plugins.batchcontrol.ui;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.Util;
import java.util.Map;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Builds root-relative build URLs from stored identifiers, and small display helpers shared by
 * the dashboard/incident/history views. Read-only, no Jenkins model lookups: records may refer
 * to builds or jobs that no longer exist, and the link is then simply a 404.
 */
@Restricted(NoExternalUse.class)
public final class RunLinks {

    private RunLinks() {
    }

    /**
     * @return {@code job/a/job/b/12/} for job full name {@code a/b} and build number 12
     *         (relative to the Jenkins root URL, each path segment URL-encoded)
     */
    public static String runUrl(String jobFullName, int number) {
        return jobUrl(jobFullName) + number + "/";
    }

    /** @return {@code job/a/job/b/} for job full name {@code a/b}. */
    public static String jobUrl(String jobFullName) {
        StringBuilder sb = new StringBuilder();
        for (String segment : jobFullName.split("/")) {
            if (!segment.isEmpty()) {
                sb.append("job/").append(Util.rawEncode(segment)).append('/');
            }
        }
        return sb.toString();
    }

    /**
     * Builds the run URL from a stored run id of the form {@code jobFullName#number}.
     *
     * @return the root-relative URL, or null when the id does not have that form (the view then
     *         renders plain text instead of a link)
     */
    @CheckForNull
    public static String runUrlFromRunId(@CheckForNull String runId) {
        if (runId == null) {
            return null;
        }
        int hash = runId.lastIndexOf('#');
        if (hash <= 0 || hash == runId.length() - 1) {
            return null;
        }
        int number;
        try {
            number = Integer.parseInt(runId.substring(hash + 1));
        } catch (NumberFormatException e) {
            return null;
        }
        return runUrl(runId.substring(0, hash), number);
    }

    /**
     * One-line {@code name=value} rendering of a parameter map for table cells. Values arrive
     * already masked from the store (secret parameters are persisted masked); output is escaped
     * by the Jelly default.
     */
    public static String formatParameters(@CheckForNull Map<String, String> parameters) {
        if (parameters == null || parameters.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : parameters.entrySet()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return sb.toString();
    }

    /** Human-readable duration ({@code 3 min 20 sec}); empty for non-positive values. */
    public static String formatDuration(long durationMs) {
        return durationMs <= 0 ? "" : Util.getTimeSpanString(durationMs);
    }
}
