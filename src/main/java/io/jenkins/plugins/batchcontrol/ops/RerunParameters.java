package io.jenkins.plugins.batchcontrol.ops;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.model.FileParameterValue;
import hudson.model.Job;
import hudson.model.ParameterDefinition;
import hudson.model.ParameterValue;
import hudson.model.ParametersAction;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.PasswordParameterDefinition;
import hudson.model.Run;
import hudson.model.SimpleParameterDefinition;
import io.jenkins.plugins.batchcontrol.model.Incident;
import io.jenkins.plugins.batchcontrol.policy.ParameterFiles;
import io.jenkins.plugins.batchcontrol.store.ParameterDisplay;
import io.jenkins.plugins.batchcontrol.store.SecretMasker;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * D-72 (SPEC item 11): the parameter values an incident rerun carries, taken from the failed
 * run's own {@link ParametersAction}, original secrets included.
 *
 * <ul>
 *   <li>A core {@link FileParameterValue} is recovered from the copy core keeps under the build
 *       directory ({@code fileParameters/<location>}): a new value is created from it, with its
 *       own temporary copy, which the rerun request then owns. When that copy is gone the value
 *       cannot be recovered.</li>
 *   <li>Any other value that keeps a temporary file its build consumed (the file-parameters
 *       plugin's stashed file, whose stash the build clears when it completes) cannot be
 *       recovered. A Base64 file value carries its content and is reused.</li>
 *   <li>Every other value is reused as the build received it.</li>
 *   <li>When the build no longer exists nothing can be recovered from it, unless the failed run
 *       had no parameters.</li>
 *   <li>D-72b (6), security-35 S-35-06: a build with the incident's number that is not the
 *       incident's own run (a job deleted and re-created under the same name has its own #n) is
 *       treated like a deleted build. The incident records the failed build's own timestamp
 *       ({@code Run#getTimeInMillis}, kept by Jenkins in the build); a build is the incident's run
 *       only when its timestamp is the recorded one. An incident stored before the timestamp was
 *       recorded cannot confirm its build, so its build is not used either.</li>
 *   <li>D-72b (1), security-35 S-35-01: a failed run that holds a parameter name more than once
 *       is refused with {@link IllegalArgumentException}; its values are never carried.</li>
 * </ul>
 *
 * <p>When a value cannot be recovered, {@link RerunNeedsFormException} carries the values the
 * Request Run form may be prefilled with, and nothing is left on disk by this class.
 */
@Restricted(NoExternalUse.class)
final class RerunParameters {

    private static final Logger LOGGER = Logger.getLogger(RerunParameters.class.getName());

    /** Core's folder under the build directory holding the build's file parameters. */
    private static final String CORE_FILE_FOLDER = "fileParameters";

    private RerunParameters() {
    }

    /**
     * The values of the failed run of {@code incident} on {@code job}, ready for a new request.
     *
     * @throws RerunNeedsFormException when a value cannot be recovered
     */
    static List<ParameterValue> recover(Job<?, ?> job, Incident incident) {
        Run<?, ?> found = failedRun(job, incident.getRunId());
        Run<?, ?> build = found != null && isIncidentRun(found, incident) ? found : null;
        if (found != null && build == null) {
            LOGGER.info(() -> "Build " + found.getExternalizableId() + " is not the failed run of incident "
                    + incident.getId() + " (a different build with the same number); its values are not reused");
        }
        if (build == null) {
            Map<String, String> recorded = incident.getParameters();
            if (recorded.isEmpty()) {
                return new ArrayList<>();
            }
            throw new RerunNeedsFormException("The failed run " + incident.getRunId()
                    + " no longer exists (it was deleted, or its job was re-created), so its parameter values"
                    + " cannot be reused; request the rerun on the job's Request Run form.",
                    prefillFromRecord(job, recorded));
        }
        ParametersAction action = build.getAction(ParametersAction.class);
        List<ParameterValue> original = new ArrayList<>();
        if (action != null) {
            for (ParameterValue value : action.getParameters()) {
                if (value != null) {
                    original.add(value);
                }
            }
        }
        Set<String> names = new HashSet<>();
        for (ParameterValue value : original) {
            if (!names.add(value.getName())) {
                throw new IllegalArgumentException("Parameter '" + value.getName() + "' occurs more than once in the"
                        + " failed run " + incident.getRunId() + ", so its values cannot be reused for a rerun;"
                        + " request the run on the job's Request Run form instead.");
            }
        }
        Map<ParameterValue, Path> coreCopies = new IdentityHashMap<>();
        List<String> lost = new ArrayList<>();
        for (ParameterValue value : original) {
            if (value.getClass() == FileParameterValue.class) {
                Path copy = buildCopy(build, (FileParameterValue) value);
                if (copy == null) {
                    lost.add(value.getName());
                } else {
                    coreCopies.put(value, copy);
                }
            } else if (ParameterFiles.keepsTemporaryFile(value)) {
                lost.add(value.getName());
            }
        }
        if (!lost.isEmpty()) {
            throw needsForm(incident, lost, prefill(original));
        }
        List<ParameterValue> values = new ArrayList<>(original.size());
        List<ParameterValue> created = new ArrayList<>(coreCopies.size());
        for (ParameterValue value : original) {
            Path copy = coreCopies.get(value);
            if (copy == null) {
                values.add(value);
                continue;
            }
            FileParameterValue file = (FileParameterValue) value;
            try {
                FileParameterValue recreated = new FileParameterValue(file.getName(), copy.toFile(),
                        file.getOriginalFileName());
                recreated.setDescription(file.getDescription());
                created.add(recreated);
                values.add(recreated);
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, e, () -> "Could not copy the file of parameter '" + file.getName()
                        + "' of " + incident.getRunId() + " for the rerun of incident " + incident.getId());
                ParameterFiles.dispose(created, "the rerun of incident " + incident.getId());
                throw needsForm(incident, List.of(file.getName()), prefill(original));
            }
        }
        return values;
    }

    private static RerunNeedsFormException needsForm(Incident incident, List<String> lost,
                                                     Map<String, String> prefill) {
        return new RerunNeedsFormException("The value of parameter(s) " + String.join(", ", lost)
                + " of the failed run " + incident.getRunId() + " can no longer be recovered; request the rerun"
                + " on the job's Request Run form and provide them again.", prefill);
    }

    /**
     * D-72b (6): whether {@code build} is the failed run of {@code incident}: its own timestamp is
     * the one the incident recorded. Both come from the build (never from the plugin clock).
     */
    private static boolean isIncidentRun(Run<?, ?> build, Incident incident) {
        Long recorded = incident.getRunTimestampMillis();
        return recorded != null && recorded == build.getTimeInMillis();
    }

    /** The failed run named by {@code runId} ({@code <job full name>#<number>}), or {@code null}. */
    @CheckForNull
    private static Run<?, ?> failedRun(Job<?, ?> job, @CheckForNull String runId) {
        int hash = runId == null ? -1 : runId.lastIndexOf('#');
        if (hash < 0) {
            return null;
        }
        try {
            return job.getBuildByNumber(Integer.parseInt(runId.substring(hash + 1)));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * The copy of a core file parameter under the build directory, when it still exists as a
     * regular file inside {@code fileParameters/}; {@code null} otherwise (including a location
     * that would leave that folder).
     */
    @CheckForNull
    private static Path buildCopy(Run<?, ?> build, FileParameterValue value) {
        String location = value.getLocation();
        if (location == null || location.isBlank()) {
            return null;
        }
        try {
            Path base = build.getRootDir().toPath().resolve(CORE_FILE_FOLDER).toAbsolutePath().normalize();
            Path candidate = base.resolve(location).normalize();
            if (!candidate.startsWith(base) || candidate.equals(base)
                    || !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
                return null;
            }
            Path real = candidate.toRealPath();
            return real.startsWith(base.toRealPath()) ? real : null;
        } catch (IOException | InvalidPathException e) {
            return null;
        }
    }

    /**
     * The recoverable values that are neither sensitive nor files and read as text (string,
     * boolean, number), by name in the run's order.
     */
    private static Map<String, String> prefill(List<ParameterValue> values) {
        Map<String, String> prefill = new LinkedHashMap<>();
        for (ParameterValue value : values) {
            if (value.getName() == null || value.isSensitive() || ParameterDisplay.isFile(value)) {
                continue;
            }
            Object raw = value.getValue();
            if (raw instanceof String || raw instanceof Boolean || raw instanceof Number) {
                prefill.putIfAbsent(value.getName(), String.valueOf(raw));
            }
        }
        return prefill;
    }

    /**
     * For a deleted build: the incident's recorded (masked) values that the job's form can take
     * as text and that are not masks or file names (a password or file parameter, or a value
     * shown as the mask, is never prefilled).
     */
    private static Map<String, String> prefillFromRecord(Job<?, ?> job, Map<String, String> recorded) {
        Map<String, String> prefill = new LinkedHashMap<>();
        ParametersDefinitionProperty property = job.getProperty(ParametersDefinitionProperty.class);
        if (property == null) {
            return prefill;
        }
        for (Map.Entry<String, String> entry : recorded.entrySet()) {
            String text = entry.getValue();
            if (entry.getKey() == null || text == null || SecretMasker.MASK.equals(text)
                    || text.startsWith(ParameterDisplay.FILE_PREFIX)) {
                continue;
            }
            ParameterDefinition definition = property.getParameterDefinition(entry.getKey());
            if (definition instanceof SimpleParameterDefinition
                    && !(definition instanceof PasswordParameterDefinition)) {
                prefill.put(entry.getKey(), text);
            }
        }
        return prefill;
    }
}
