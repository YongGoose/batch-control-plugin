package io.jenkins.plugins.batchcontrol.store;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import java.time.Instant;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * One line of the monthly incident index ({@code incidents/index/YYYY-MM.jsonl}). A page query
 * filters on these fields before it loads an incident's XML (security-10 S-06).
 *
 * @param id          incident id
 * @param runId       the failed run
 * @param jobFullName the job
 * @param result      the run result
 * @param createdAt   when the incident was opened, or {@code null} if the line lacks it
 */
@Restricted(NoExternalUse.class)
public record IncidentSummary(String id, @CheckForNull String runId, @CheckForNull String jobFullName,
                              @CheckForNull String result, @CheckForNull Instant createdAt) {
}
