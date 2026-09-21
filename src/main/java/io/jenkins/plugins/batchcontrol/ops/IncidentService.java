package io.jenkins.plugins.batchcontrol.ops;

import hudson.model.Job;
import hudson.model.ParameterValue;
import hudson.model.ParametersAction;
import hudson.model.Run;
import hudson.util.Secret;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.Incident;
import io.jenkins.plugins.batchcontrol.model.IncidentStatus;
import io.jenkins.plugins.batchcontrol.model.IncidentTransition;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import io.jenkins.plugins.batchcontrol.store.FileStore;
import io.jenkins.plugins.batchcontrol.store.Ids;
import io.jenkins.plugins.batchcontrol.store.SecretMasker;
import io.jenkins.plugins.batchcontrol.store.Store;
import java.io.IOException;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * The single entry point for every {@link Incident} lifecycle operation (SPEC item 11, D-10,
 * D-19): automatic registration from completed builds, forward-only handling transitions
 * ({@code OPEN -> ACKNOWLEDGED -> RESOLVED}), comments, rerun requests and the automatic
 * {@code resolvedByRunId} link. No other class may change an incident's status.
 *
 * <p>Every load→validate→mutate→persist sequence runs under one {@link ReentrantLock}, so
 * concurrent transitions serialize and the loser is refused on the already-changed status.
 *
 * <p>Failure families follow {@link RunRequestService}: {@link IllegalArgumentException} for
 * bad input (unknown id, blank comment where required), {@link IllegalStateException} for
 * refused transitions.
 */
@Restricted(NoExternalUse.class)
public final class IncidentService {

    private static final Logger LOGGER = Logger.getLogger(IncidentService.class.getName());

    /** SPEC item 11: the console excerpt keeps at most the last 100 lines. */
    private static final int LOG_TAIL_LINES = 100;

    private static final IncidentService INSTANCE = new IncidentService();

    private final ReentrantLock lock = new ReentrantLock();
    private final Store store = FileStore.get();

    private IncidentService() {
    }

    public static IncidentService get() {
        return INSTANCE;
    }

    // ---------------------------------------------------------------- read API

    /** Loads an incident by id, or {@code null}. */
    public Incident load(String id) {
        return store.loadIncident(id);
    }

    /** Every incident created in the given month, in creation order. */
    public List<Incident> list(YearMonth month) {
        return store.listIncidents(month);
    }

    // ---------------------------------------------------------------- automatic registration

    /**
     * Opens an incident for a completed build whose result is in the configured
     * {@code incidentResults} — regardless of what caused the build (SPEC item 11, D-10).
     * Called by the run listener; returns {@code null} when the result is outside the
     * configured set.
     *
     * <p>D-19: the stored logTail masks the build's sensitive parameter values and encrypted
     * {@code Secret} payloads; other secrets echoed to the console are a documented detection
     * limit. The stored parameters mask sensitive values entirely.
     */
    public Incident openForRun(Run<?, ?> run) {
        Objects.requireNonNull(run, "run");
        hudson.model.Result rawResult = run.getResult();
        String result = rawResult == null ? null : rawResult.toString();
        if (result == null
                || !BatchControlGlobalConfiguration.get().getIncidentResults().contains(result)) {
            return null;
        }
        String runId = run.getParent().getFullName() + "#" + run.getNumber();
        Incident incident = new Incident(Ids.newId(), runId, run.getParent().getFullName(),
                result, BatchClock.now());
        incident.setParameters(maskedParameters(run));
        incident.setLogTail(maskedLogTail(run));
        incident.addTransition(new IncidentTransition(IncidentStatus.OPEN,
                Jenkins.getAuthentication2().getName(), BatchClock.now(), null));
        lock.lock();
        try {
            store.createIncident(incident);
        } finally {
            lock.unlock();
        }
        return incident;
    }

    // ---------------------------------------------------------------- handling (SPEC section 4)

    /** {@code OPEN -> ACKNOWLEDGED}; any other current status is refused (forward-only). */
    public Incident acknowledge(String id, String comment) {
        lock.lock();
        try {
            Incident incident = require(id);
            if (incident.getStatus() != IncidentStatus.OPEN) {
                throw new IllegalStateException("Incident " + id + " is " + incident.getStatus()
                        + "; only OPEN incidents can be acknowledged (no reverse transitions).");
            }
            incident.setStatus(IncidentStatus.ACKNOWLEDGED);
            incident.addTransition(new IncidentTransition(IncidentStatus.ACKNOWLEDGED,
                    Jenkins.getAuthentication2().getName(), BatchClock.now(), comment));
            store.saveIncident(incident);
            return incident;
        } finally {
            lock.unlock();
        }
    }

    /** {@code OPEN|ACKNOWLEDGED -> RESOLVED}; a RESOLVED incident stays RESOLVED. */
    public Incident resolve(String id, String comment) {
        lock.lock();
        try {
            Incident incident = require(id);
            if (incident.getStatus() == IncidentStatus.RESOLVED) {
                throw new IllegalStateException("Incident " + id
                        + " is already RESOLVED (no reverse transitions).");
            }
            incident.setStatus(IncidentStatus.RESOLVED);
            incident.addTransition(new IncidentTransition(IncidentStatus.RESOLVED,
                    Jenkins.getAuthentication2().getName(), BatchClock.now(), comment));
            store.saveIncident(incident);
            return incident;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Appends a comment to the incident history without changing the status — allowed in every
     * status, RESOLVED included (SPEC section 4). The entry is recorded against the incident's
     * current status.
     */
    public Incident addComment(String id, String comment) {
        if (comment == null || comment.trim().isEmpty()) {
            throw new IllegalArgumentException("A comment must not be empty.");
        }
        lock.lock();
        try {
            Incident incident = require(id);
            incident.addTransition(new IncidentTransition(incident.getStatus(),
                    Jenkins.getAuthentication2().getName(), BatchClock.now(), comment));
            store.saveIncident(incident);
            return incident;
        } finally {
            lock.unlock();
        }
    }

    // ---------------------------------------------------------------- rerun (SPEC item 11)

    /**
     * Creates a rerun {@link RunRequest} prefilled with the incident's original (stored,
     * sensitive-masked) parameters and links it both ways: {@code request.incidentId} and
     * {@code incident.rerunRequestIds}. The caller becomes the requester; permission and
     * approver checks are those of {@link RunRequestService#create}.
     */
    public RunRequest rerun(String incidentId, String approver) {
        Incident incident = require(incidentId);
        Job<?, ?> job = Jenkins.get().getItemByFullName(incident.getJobFullName(), Job.class);
        if (job == null) {
            throw new IllegalArgumentException("The incident's job '" + incident.getJobFullName()
                    + "' no longer exists; a rerun cannot be requested.");
        }
        String reason = "Rerun requested from incident " + incidentId
                + " (failed run " + incident.getRunId() + ")";
        // Created outside this service's lock: the request service takes its own lock and
        // there is no call path back into this service from request creation.
        RunRequest request = RunRequestService.get().create(job, incident.getParameters(),
                reason, approver, incidentId);
        lock.lock();
        try {
            Incident reloaded = require(incidentId);
            reloaded.addRerunRequestId(request.getId());
            store.saveIncident(reloaded);
        } finally {
            lock.unlock();
        }
        return request;
    }

    /**
     * Records the successful linked rerun on the incident (SPEC item 11): sets
     * {@code resolvedByRunId} but never touches the status — resolution stays a human
     * decision. Called by the run listener when a request-linked build ends SUCCESS.
     */
    public void linkResolvedBy(String incidentId, String runId) {
        Objects.requireNonNull(runId, "runId");
        lock.lock();
        try {
            Incident incident = store.loadIncident(incidentId);
            if (incident == null) {
                LOGGER.warning(() -> "Cannot link successful rerun " + runId
                        + " to missing incident " + incidentId);
                return;
            }
            incident.setResolvedByRunId(runId);
            store.saveIncident(incident);
        } finally {
            lock.unlock();
        }
    }

    // ---------------------------------------------------------------- capture helpers (D-19)

    /**
     * The build's parameters as strings, sensitive values replaced by the mask. The plaintext
     * of a sensitive value never leaves this method.
     */
    public static Map<String, String> maskedParameters(Run<?, ?> run) {
        Map<String, String> parameters = new LinkedHashMap<>();
        ParametersAction action = run.getAction(ParametersAction.class);
        if (action == null) {
            return parameters;
        }
        for (ParameterValue value : action.getParameters()) {
            if (value == null) {
                continue;
            }
            if (value.isSensitive()) {
                parameters.put(value.getName(), SecretMasker.MASK);
            } else {
                Object raw = value.getValue();
                parameters.put(value.getName(), raw == null ? "" : String.valueOf(raw));
            }
        }
        return parameters;
    }

    /**
     * The last {@value #LOG_TAIL_LINES} console lines with D-19 masking applied: the build's
     * sensitive parameter plaintexts and encrypted {@code Secret} payloads become the mask.
     */
    private static List<String> maskedLogTail(Run<?, ?> run) {
        List<String> lines;
        try {
            lines = run.getLog(LOG_TAIL_LINES);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, e, () -> "Failed to read the console tail of "
                    + run.getFullDisplayName() + "; the incident is stored without a logTail");
            return new ArrayList<>();
        }
        List<String> secrets = sensitivePlaintexts(run);
        List<String> masked = new ArrayList<>(lines.size());
        for (String line : lines) {
            String out = line;
            for (String secret : secrets) {
                out = out.replace(secret, SecretMasker.MASK);
            }
            masked.add(SecretMasker.mask(out));
        }
        return masked;
    }

    /** The plaintexts of the build's sensitive parameter values (D-19 masking targets). */
    private static List<String> sensitivePlaintexts(Run<?, ?> run) {
        List<String> secrets = new ArrayList<>();
        ParametersAction action = run.getAction(ParametersAction.class);
        if (action == null) {
            return secrets;
        }
        for (ParameterValue value : action.getParameters()) {
            if (value == null || !value.isSensitive()) {
                continue;
            }
            Object raw = value.getValue();
            String plain;
            if (raw instanceof Secret) {
                plain = ((Secret) raw).getPlainText();
            } else {
                plain = raw == null ? null : String.valueOf(raw);
            }
            if (plain != null && !plain.isEmpty()) {
                secrets.add(plain);
            }
        }
        return secrets;
    }

    // ---------------------------------------------------------------- internals

    private Incident require(String id) {
        Incident incident = store.loadIncident(id);
        if (incident == null) {
            throw new IllegalArgumentException("No such incident: " + id);
        }
        return incident;
    }
}
