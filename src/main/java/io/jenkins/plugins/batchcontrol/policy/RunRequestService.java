package io.jenkins.plugins.batchcontrol.policy;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.model.Action;
import hudson.model.CauseAction;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.ParameterDefinition;
import hudson.model.ParameterValue;
import hudson.model.ParametersAction;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.Queue;
import hudson.model.Run;
import hudson.model.SimpleParameterDefinition;
import hudson.model.StringParameterValue;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.Approvers;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.model.PendingCount;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import io.jenkins.plugins.batchcontrol.ops.IncidentService;
import io.jenkins.plugins.batchcontrol.ops.NotificationDispatcher;
import io.jenkins.plugins.batchcontrol.ops.NotificationEvent;
import io.jenkins.plugins.batchcontrol.queue.ApprovedCause;
import io.jenkins.plugins.batchcontrol.queue.ApprovedRunAction;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import io.jenkins.plugins.batchcontrol.store.BlockedAttemptAudit;
import io.jenkins.plugins.batchcontrol.store.ParameterDisplay;
import io.jenkins.plugins.batchcontrol.store.Store;
import io.jenkins.plugins.batchcontrol.store.XmlChars;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import jenkins.model.ParameterizedJobMixIn;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;

/**
 * The single entry point for every {@link RunRequest} state transition (SPEC items 3, 5, 7;
 * D-20/D-21/D-22/D-23). No other class may change a request's status.
 *
 * <p><b>Concurrency (D-20)</b>: every load→validate→transition→persist sequence runs under one
 * {@link ReentrantLock}, which makes each transition an effective compare-and-set: concurrent
 * approve/approve or approve/cancel calls serialize, the loser sees the already-changed status
 * and is refused. The lock is never held across {@link Queue} operations (queue submission and
 * queue inspection happen outside it) to avoid lock-order inversion with the queue gate, which
 * calls back into {@link #consumeMarker} while Jenkins holds the queue lock.
 *
 * <p><b>Failure families</b>: {@link IllegalArgumentException} for input validation,
 * {@link IllegalStateException} for wrong-state transitions, {@link AccessDeniedException}
 * (403 on the web layer) for authorization refusals.
 */
@Restricted(NoExternalUse.class)
public final class RunRequestService {

    private static final Logger LOGGER = Logger.getLogger(RunRequestService.class.getName());

    /**
     * D-22 size limits: the reason, and each stored parameter value (D-72b (2): the stored text,
     * a secret's plaintext included, and its display text; a file's content is bounded by the
     * body cap only).
     */
    private static final int MAX_REASON_LENGTH = 4000;
    private static final int MAX_PARAMETER_VALUE_LENGTH = 10000;

    private static final RunRequestService INSTANCE = new RunRequestService();

    private final ReentrantLock lock = new ReentrantLock();
    /**
     * security-33 S-33-09 (#75): {@link #requesterLacksBuild} impersonates the requester, which
     * reaches the security realm; its answer is cached per request id, job and requester for a
     * short time so page views and notifications do not hit the realm each time.
     */
    private final RequesterBuildCache requesterBuildCache =
            new RequesterBuildCache(Duration.ofMinutes(5), 1000);
    private final Store store = Store.get();

    /**
     * T-GAP-384, D-72b (7): queue cancellations of approved runs that are in effect but could not be
     * written to their request file yet (request id to the moment of the cancellation). Every read of
     * a request file in this service applies them ({@link #loadCurrent}), so no other write puts a
     * request back without its mark; the periodic work writes them again
     * ({@link #retryUnsavedQueueCancels}), and so does any later write of the request, until one
     * succeeds. Kept in memory only: a restart while the store still refuses writes loses them, the
     * storage-failure class LIMITATIONS documents.
     */
    private final Map<String, Instant> unsavedQueueCancels = new java.util.concurrent.ConcurrentHashMap<>();

    private RunRequestService() {
    }

    public static RunRequestService get() {
        return INSTANCE;
    }

    // ---------------------------------------------------------------- read API

    /**
     * Loads a request by id, or {@code null}. Its typed values stay in their own file (D-74); a
     * screen, listing or listener never needs them.
     */
    public RunRequest load(String id) {
        return loadCurrent(id);
    }

    /**
     * The stored request {@code id}, or {@code null}, with a queue cancellation that could not be
     * written yet applied ({@link #unsavedQueueCancels}). Every read of a request file in this service
     * goes through here.
     */
    @CheckForNull
    private RunRequest loadCurrent(String id) {
        RunRequest request = store.loadRunRequest(id);
        if (request != null && request.getQueueCancelledAt() == null) {
            Instant cancelledAt = unsavedQueueCancels.get(id);
            if (cancelledAt != null) {
                request.setQueueCancelledAt(cancelledAt);
            }
        }
        return request;
    }

    /** All stored requests, in creation order. */
    public List<RunRequest> list() {
        return store.listRunRequests();
    }

    /**
     * D-61 / #76: the PENDING run requests that concern {@code auth}, read from the open-request index only
     * (no history scan): those awaiting their decision as a designated approver holding
     * Jenkins-level {@code BatchControl/Approve}, else their own. Every counted request is visible
     * to {@code auth} under P-09. The single source for the tab badge and the section.
     */
    public PendingCount countPendingFor(Authentication auth) {
        return PendingCounter.count(store.listOpenRunRequests(), auth, RunRequest::getStatus, RunRequest::isDesignatedApprover,
                RunRequest::getRequester);
    }

    /**
     * D-38b: whether {@code auth} filed at least one run request, in any status. The requester
     * always sees their own requests (P-09), so this tells the web layer that a user without
     * Jenkins-level {@code BatchControl/Request} still has something to see in the run requests
     * section. Answered from the store's in-memory index; no item is visited.
     */
    public boolean hasOwnRequests(Authentication auth) {
        if (auth == null || ACL.isAnonymous2(auth)) {
            return false;
        }
        return store.hasRunRequestBy(auth.getName());
    }

    /**
     * D-38b: whether the current user may cancel {@code request}: its requester holding
     * {@code BatchControl/Request} on the job, or a Manage holder. Permission only; the PENDING
     * status is checked by {@link #cancel(String)}.
     */
    public boolean canCancel(RunRequest request) {
        return ApprovalPolicy.callerIsRequesterWithRequest(request.getRequester(), request.getJobFullName())
                || Jenkins.get().hasPermission(BatchControlPermissions.MANAGE);
    }

    /**
     * D-38b: whether the current user may change the approvers of {@code request}: its requester
     * holding {@code BatchControl/Request} on the job. Permission only.
     */
    public boolean canChangeApprovers(RunRequest request) {
        return ApprovalPolicy.callerIsRequesterWithRequest(request.getRequester(), request.getJobFullName());
    }

    // ---------------------------------------------------------------- creation (SPEC 5, D-22)

    /** Single-approver form of {@link #create(Job, List, String, List, String)}, without an incident. */
    public RunRequest create(Job<?, ?> job, List<ParameterValue> values, String reason,
                             String approver) {
        return create(job, values, reason, Approvers.of(approver), null);
    }

    /**
     * Whether the current user could submit a run request for {@code job} (e2e-03 DEF-12, SPEC
     * section 6 usability): {@code BatchControl/Request} and {@code Item/Read} on the job (D-38a), the
     * permission checks of {@link #create}. {@code Item/Build} is not required (D-38a). Screens
     * use it to show the Request Run entry and form only to such a user; {@link #create} still
     * checks for real.
     */
    public boolean canRequest(Job<?, ?> job) {
        return job != null && job.hasPermission(BatchControlPermissions.REQUEST)
                && job.hasPermission(Item.READ);
    }

    /**
     * D-38a: the sentence the request detail page and the approver notification show when the
     * requester does not hold {@code Item/Build} on the request's job.
     */
    public static final String REQUESTER_LACKS_BUILD_NOTICE =
            "The requester does not have Build permission on this job.";

    /**
     * D-38a (SPEC item 6): whether the <em>requester</em> of {@code request} (not the current user)
     * lacks {@code Item/Build} on the request's job, so an approval also authorises a run the
     * requester could not start. {@code true} also when that cannot be confirmed because the
     * requester's account no longer resolves; {@code false} when the requester holds it, or when
     * the job does not exist or is not visible to the current user (who then cannot see the
     * request's job either). Read only. Evaluated as the current user, with no switch to
     * {@code ACL.SYSTEM2}: the job is looked up with the caller's own Read, and the requester's
     * permission is asked of the job's ACL with the requester's impersonated authentication,
     * which needs no elevation.
     */
    public boolean requesterLacksBuild(RunRequest request) {
        if (request == null || request.getJobFullName() == null) {
            return false;
        }
        Job<?, ?> job;
        try {
            job = Jenkins.get().getItemByFullName(request.getJobFullName(), Job.class);
        } catch (org.springframework.security.access.AccessDeniedException e) {
            return false; // discoverable but not readable
        }
        if (job == null) {
            return false;
        }
        String requester = request.getRequester();
        org.springframework.security.core.Authentication current = Jenkins.getAuthentication2();
        if (requester != null && requester.equals(current.getName())) {
            return !job.hasPermission(Item.BUILD);
        }
        Boolean cached = requesterBuildCache.get(request.getId());
        if (cached != null) {
            return cached;
        }
        hudson.model.User user = requester == null ? null : hudson.model.User.getById(requester, false);
        boolean lacks;
        if (user == null) {
            lacks = true; // fail-safe: the account no longer resolves
        } else {
            try {
                lacks = !job.getACL().hasPermission2(user.impersonate2(), Item.BUILD);
            } catch (RuntimeException e) {
                // UsernameNotFoundException and realm failures: the permission cannot be confirmed.
                // Not cached, so a transient realm failure is retried on the next view.
                LOGGER.log(java.util.logging.Level.FINE, "Cannot evaluate Item/Build for requester " + requester, e);
                return true;
            }
        }
        requesterBuildCache.put(request.getId(), lacks);
        return lacks;
    }

    /**
     * Persists {@code request} and drops its cached {@link #requesterLacksBuild} answer, so a
     * decided, cancelled, executed or otherwise changed request is evaluated afresh (#75).
     */
    private void persist(RunRequest request) {
        store.saveRunRequest(request);
        requesterBuildCache.invalidate(request.getId());
        if (request.getQueueCancelledAt() != null) {
            unsavedQueueCancels.remove(request.getId()); // written now (T-GAP-384)
        }
    }

    /**
     * Whether a person's Build Now, Rebuild, Retry or Replay of {@code job} is refused by the queue
     * gate and needs an approved run request instead (SPEC item 6): run control is on and the job
     * requires approval. Screens use it to replace or hide those entries (e2e-03 DEF-25).
     */
    public static boolean requiresApprovalToRun(Job<?, ?> job) {
        if (job == null || !BatchControlGlobalConfiguration.get().isRunControlEnabled()) {
            return false;
        }
        BatchControlJobProperty property = job.getProperty(BatchControlJobProperty.class);
        return property != null && property.isApprovalRequired();
    }

    /**
     * Creates a PENDING run request from string values, designating a single approver: each value
     * becomes a typed value through the job's parameter definition
     * ({@link SimpleParameterDefinition#createValue(String)}), or a {@link StringParameterValue}
     * where the job defines no such simple parameter, and the request is created as by
     * {@link #create(Job, List, String, List, String)}. A value the definition refuses (a choice
     * outside its choices) is an {@link IllegalArgumentException}. The requester is the current
     * authentication.
     */
    public RunRequest create(Job<?, ?> job, Map<String, String> parameters, String reason,
                             String approver) {
        Objects.requireNonNull(job, "job");
        Objects.requireNonNull(parameters, "parameters");
        checkCanRequest(job);
        checkReason(reason);
        return create(job, typedValues(job, parameters), reason, Approvers.of(approver), null);
    }

    /**
     * Creates a PENDING run request holding the submitted typed values (D-72), linked to an
     * incident when {@code incidentId} is given (SPEC item 11: the run listener links a successful
     * rerun back to the incident). The requester is the current authentication; every designated
     * approver must pass the SPEC item 3 checks (D-37); validation failures throw
     * {@link IllegalArgumentException}. The masked display map is derived from the values
     * here, once. The approved build is scheduled with the values unchanged.
     *
     * <p>D-72b (1)(2), security-35 S-35-01/03: before anything is stored, each parameter name may
     * occur once, every value has a name, every stored text (a secret's plaintext included) and
     * every display text obeys the 10,000-character limit, and names, texts and the reason hold
     * only characters XML 1.0 can store ({@link XmlChars}); otherwise
     * {@link IllegalArgumentException} with a message naming the parameter (or the reason), so the
     * form can show it next to the field.
     *
     * <p>SPEC item 11, D-72a: a linked request names an existing incident of {@code job} (else
     * {@link IllegalArgumentException}, nothing stored) and is listed among the incident's rerun
     * requests once stored, whoever created it: the incident rerun, or the Request Run form after
     * {@link IncidentService#linkableIncident} validated the reference it carried. A successful
     * run of the request then records {@code resolvedByRunId} on the incident.
     *
     * <p>The request takes over the temporary files of the values' file parameters: when the
     * request is refused (permission, reason, size, approvers) they are disposed of before the
     * exception propagates ({@link ParameterFiles}).
     *
     * <p>D-74 (2): right after the permission checks, a request whose values would keep more than
     * the body cap ({@link RequestBodyLimit#keptSize}) is refused with
     * {@link RequestTooLargeException} (an {@link IllegalArgumentException}), its files disposed of
     * and nothing stored. This holds for every overload and for an incident rerun, which all end
     * here; a caller without uploaded parts passes none.
     */
    public RunRequest create(Job<?, ?> job, List<ParameterValue> values, String reason,
                             List<String> approvers, String incidentId) {
        return create(job, values, Map.of(), reason, approvers, incidentId);
    }

    /**
     * As {@link #create(Job, List, String, List, String)} for a web submission: {@code uploaded}
     * maps a parameter name to the size of the uploaded parts its value was created from
     * ({@link RequestBodyLimit#uploadedSize}), which the kept-size check counts for a value whose
     * own size cannot be read (D-74 (2)).
     */
    public RunRequest create(Job<?, ?> job, List<ParameterValue> values, Map<String, Long> uploaded,
                             String reason, List<String> approvers, String incidentId) {
        Objects.requireNonNull(job, "job");
        Objects.requireNonNull(values, "values");
        Objects.requireNonNull(uploaded, "uploaded");
        List<ParameterValue> submitted = new ArrayList<>(values);
        RunRequest request;
        boolean stored = false;
        try {
            checkCanRequest(job);
            // D-74 (2): what the request would keep, measured on the values and the uploaded parts
            // they were created from; the declared Content-Length is an early filter only. Over the
            // cap: RequestTooLargeException, files disposed of below.
            RequestBodyLimit.checkKept(submitted, uploaded);
            String requester = Jenkins.getAuthentication2().getName();
            checkReason(reason);
            checkValues(submitted);
            Map<String, String> display = ParameterDisplay.masked(submitted);
            List<String> designated = ApprovalPolicy.checkDesignation(requester, approvers, job);

            request = RunRequest.create(job.getFullName(), display, reason, requester, designated);
            if (incidentId != null) {
                // SPEC item 11: only an incident of this job; the id stored is the incident's own.
                request.setIncidentId(IncidentService.get().requireRerunTarget(incidentId, job).getId());
            }
            List<ParameterValue> present = new ArrayList<>(submitted.size());
            for (ParameterValue value : submitted) {
                if (value != null) {
                    present.add(value);
                }
            }
            lock.lock();
            try {
                store.saveNewRunRequest(request, present);
                requesterBuildCache.invalidate(request.getId());
            } finally {
                lock.unlock();
            }
            stored = true;
        } finally {
            if (!stored) {
                ParameterFiles.dispose(submitted, "a refused run request for job '" + job.getFullName() + "'");
            }
        }
        if (request.getIncidentId() != null) {
            // The link back from the incident, outside this service's lock (lock order). The
            // request's incidentId alone drives resolvedByRunId (RunRecordListener).
            IncidentService.get().recordRerunRequest(request.getIncidentId(), request.getId());
        }
        NotificationDispatcher.run(NotificationEvent.REQUEST_CREATED, request);
        return request;
    }

    /**
     * The permission checks of a run request submission (D-38a): {@code BatchControl/Request} on
     * the requested job (a grant on another job or folder does not count; a global grant is
     * inherited) and {@code Item/Read}, not {@code Item/Build}. Request decides who may ask; the
     * approver sees when the requester lacks Build ({@link #requesterLacksBuild}). Checked before
     * anything is stored; AccessDeniedException3 answers 403 on the web layer.
     */
    private static void checkCanRequest(Job<?, ?> job) {
        job.checkPermission(BatchControlPermissions.REQUEST);
        job.checkPermission(Item.READ);
    }

    private static void checkReason(String reason) {
        if (reason == null || reason.trim().isEmpty()) {
            throw new IllegalArgumentException("A reason is required to create a run request.");
        }
        if (reason.length() > MAX_REASON_LENGTH) {
            throw new IllegalArgumentException("The reason must not exceed "
                    + MAX_REASON_LENGTH + " characters.");
        }
        int bad = XmlChars.firstInvalid(reason);
        if (bad >= 0) {
            throw new IllegalArgumentException("The reason contains a character that cannot be stored ("
                    + XmlChars.describe(reason, bad) + "); remove it and submit again.");
        }
    }

    /**
     * D-72b (2): a decision comment holds only characters XML 1.0 can store (it is written into
     * the request file). The message names the comment, so the form shows it next to that field.
     */
    private static void checkComment(String comment) {
        int bad = XmlChars.firstInvalid(comment);
        if (bad >= 0) {
            throw new IllegalArgumentException("The comment contains a character that cannot be stored ("
                    + XmlChars.describe(comment, bad) + "); remove it and submit again.");
        }
    }

    /**
     * D-72b (1)(2), security-35 S-35-01/02/03: the checks on the typed values of a new request,
     * made before anything is stored. Each message starts with "Parameter" and names it, so the
     * form maps it to its parameters field.
     */
    private static void checkValues(List<ParameterValue> values) {
        Set<String> names = new HashSet<>();
        for (ParameterValue value : values) {
            if (value == null) {
                continue; // dropped before storing
            }
            String name = value.getName();
            if (name == null || name.isEmpty()) {
                throw new IllegalArgumentException("Parameter values must have a name; one has none.");
            }
            int badName = XmlChars.firstInvalid(name);
            if (badName >= 0) {
                throw new IllegalArgumentException("A parameter name contains a character that cannot be stored ("
                        + XmlChars.describe(name, badName) + ").");
            }
            if (!names.add(name)) {
                throw new IllegalArgumentException("Parameter '" + name + "' was submitted more than once;"
                        + " give each parameter one value.");
            }
            checkText(name, ParameterDisplay.text(value));
            checkText(name, ParameterDisplay.storedText(value));
        }
    }

    private static void checkText(String name, String text) {
        if (text == null) {
            return;
        }
        if (text.length() > MAX_PARAMETER_VALUE_LENGTH) {
            throw new IllegalArgumentException("Parameter '" + name
                    + "' exceeds " + MAX_PARAMETER_VALUE_LENGTH + " characters.");
        }
        int bad = XmlChars.firstInvalid(text);
        if (bad >= 0) {
            throw new IllegalArgumentException("Parameter '" + name + "' contains a character that cannot be stored ("
                    + XmlChars.describe(text, bad) + "); remove it and submit again.");
        }
    }

    /**
     * D-72b (1), security-35 S-35-01/04: why the stored typed values {@code values} of
     * {@code request} cannot be scheduled, or {@code null} when they can. The build must receive
     * exactly the values the approver saw, so it fails closed when:
     * <ul>
     *   <li>the request has parameters but its values file is missing ({@code values} is
     *       {@code null}; a request without parameters has none);</li>
     *   <li>a stored value could not be loaded (its class is gone, so XStream dropped it or left
     *       {@code null}), has no name, or repeats a name;</li>
     *   <li>the typed names differ from the names of the display map.</li>
     * </ul>
     */
    @CheckForNull
    static String valuesProblem(RunRequest request, @CheckForNull List<ParameterValue> values) {
        Map<String, String> display = request.getParameters();
        Set<String> names = new java.util.LinkedHashSet<>();
        String detail = null;
        for (ParameterValue value : values == null ? List.<ParameterValue>of() : values) {
            if (value == null || value.getName() == null) {
                detail = "a stored value could not be loaded";
                break;
            }
            if (!names.add(value.getName())) {
                detail = "parameter '" + value.getName() + "' is stored more than once";
                break;
            }
        }
        if (detail == null && !names.equals(display.keySet())) {
            detail = values == null ? "the stored values are missing"
                    : "the stored names " + names + " differ from the names shown " + display.keySet();
        }
        return detail == null ? null
                : "The stored parameter values of request " + request.getId() + " do not match the values shown ("
                + detail + "), so it cannot be approved or run: the build must receive exactly the values the"
                + " approver saw. Reject it and ask the requester to submit the run again.";
    }

    // ---------------------------------------------------------------- decisions (SPEC 3, 5)

    /**
     * Approves a PENDING request and submits the build. The caller must be the designated
     * approver holding the Approve permission (or the admin self-approval path). The status is
     * committed to APPROVED before submission and is never rolled back by a submission failure
     * (quiet-down tolerance); startup recovery and expiry handle stragglers.
     *
     * <p>D-72b (1): the stored typed values are read and checked ({@link #valuesProblem}) before
     * the APPROVED commit; a request whose values do not match what the approver saw, or whose
     * values file is missing although it has parameters, is refused with
     * {@link IllegalStateException} and stays PENDING (it can still be rejected), and nothing is
     * scheduled.
     */
    public RunRequest approve(String id, String comment) {
        checkComment(comment);
        RunRequest request;
        List<ParameterValue> values;
        lock.lock();
        try {
            request = require(id);
            if (request.getStatus() != RequestStatus.PENDING) {
                throw new IllegalStateException("Request " + id + " is "
                        + request.getStatus() + " and can no longer be approved.");
            }
            boolean selfApproval = ApprovalPolicy.checkDecision(request);
            Instant now = BatchClock.now();
            // D-20 check-at-submit: a request whose pending timeout has already passed is
            // expired here instead of being approved, so it can never be submitted.
            if (pendingExpired(request, now)) {
                String reason = EndReasons.pendingExpired();
                request.setStatus(RequestStatus.EXPIRED);
                request.setDecisionComment(reason);
                persistEnded(request, true);
                NotificationDispatcher.runEnded(NotificationEvent.EXPIRED, request, true, reason);
                throw new IllegalStateException("Request " + id
                        + " passed its pending timeout and is now EXPIRED.");
            }
            // D-55 (e2e-04 FD-06): an approval of a disabled job could never start, so it is
            // refused and the request stays PENDING; Reject is still possible.
            if (jobDisabled(request.getJobFullName())) {
                throw new IllegalStateException("The job '" + request.getJobFullName() + "' is disabled; enable it"
                        + " first, then approve. You can still reject the request.");
            }
            // D-72b (1): the values the build would receive, read and checked before the commit.
            values = requireValues(id);
            String problem = valuesProblem(request, values);
            if (problem != null) {
                LOGGER.warning(() -> "Refused to approve run request " + id + ": " + problem);
                throw new IllegalStateException(problem);
            }
            request.setStatus(RequestStatus.APPROVED);
            request.setDecidedAt(now);
            request.setDecidedBy(Jenkins.getAuthentication2().getName());
            request.setDecisionComment(comment);
            request.setSelfApproved(selfApproval);
            request.setExpiryBase(now);
            persist(request);
        } finally {
            lock.unlock();
        }
        NotificationDispatcher.run(NotificationEvent.APPROVED, request);
        // Submission happens outside the lock; the queue gate claims the consumption ticket.
        submitApproved(request, values);
        RunRequest reloaded = load(id);
        return reloaded != null ? reloaded : request;
    }

    /**
     * D-55: whether the target job exists and is disabled. Looked up through
     * {@link ApprovalPolicy#jobForPolicy} (the decision checks are complete by then), and only read.
     */
    private static boolean jobDisabled(String jobFullName) {
        Job<?, ?> job = ApprovalPolicy.jobForPolicy(jobFullName);
        return job instanceof jenkins.model.ParameterizedJobMixIn.ParameterizedJob
                && ((jenkins.model.ParameterizedJobMixIn.ParameterizedJob<?, ?>) job).isDisabled();
    }

    /** Rejects a PENDING request; the comment is mandatory (SPEC 5). */
    public RunRequest reject(String id, String comment) {
        if (comment == null || comment.trim().isEmpty()) {
            throw new IllegalArgumentException("A comment is required to reject a request.");
        }
        checkComment(comment);
        RunRequest request;
        lock.lock();
        try {
            request = require(id);
            if (request.getStatus() != RequestStatus.PENDING) {
                throw new IllegalStateException("Request " + id + " is "
                        + request.getStatus() + " and can no longer be rejected.");
            }
            ApprovalPolicy.checkDecision(request);
            request.setStatus(RequestStatus.REJECTED);
            request.setDecidedAt(BatchClock.now());
            request.setDecidedBy(Jenkins.getAuthentication2().getName());
            request.setDecisionComment(comment);
            persistEnded(request, true);
        } finally {
            lock.unlock();
        }
        NotificationDispatcher.run(NotificationEvent.REJECTED, request);
        return request;
    }

    /** Cancels a PENDING request; requester or a Manage holder only (SPEC 7). */
    public RunRequest cancel(String id) {
        String caller = Jenkins.getAuthentication2().getName();
        lock.lock();
        try {
            RunRequest request = require(id);
            if (!canCancel(request)) {
                throw new AccessDeniedException("Only the requester, holding BatchControl/Request on the job, "
                        + "or a Manage holder may cancel request " + id + ".");
            }
            if (request.getStatus() != RequestStatus.PENDING) {
                throw new IllegalStateException("Request " + id + " is "
                        + request.getStatus() + "; only PENDING requests can be cancelled.");
            }
            request.setStatus(RequestStatus.CANCELLED);
            // e2e-03 DEF-13: the history names who cancelled and when (the requester or a
            // Manage holder), in the same fields a decision uses.
            request.setDecidedAt(BatchClock.now());
            request.setDecidedBy(caller);
            persistEnded(request, true);
            // D-54: the approvers (and the requester, when a Manage holder cancelled) are told.
            NotificationDispatcher.runEnded(NotificationEvent.CANCELLED, request, true, "Cancelled by " + caller);
            return request;
        } finally {
            lock.unlock();
        }
    }

    /** Single-approver form of {@link #changeApprovers(String, List)}. */
    public RunRequest changeApprover(String id, String newApprover) {
        return changeApprovers(id, Approvers.of(newApprover));
    }

    /**
     * Replaces the designated approver set of a PENDING request; requester only (SPEC item 3,
     * D-26, D-37). Every new member must pass the designation checks; the change is recorded as
     * (previous set, new set, changed by, time).
     */
    public RunRequest changeApprovers(String id, List<String> newApprovers) {
        String caller = Jenkins.getAuthentication2().getName();
        RunRequest request;
        lock.lock();
        try {
            request = require(id);
            if (!canChangeApprovers(request)) {
                throw new AccessDeniedException("Only the requester, holding BatchControl/Request on the job, "
                        + "may change the approvers of request " + id + ".");
            }
            if (request.getStatus() != RequestStatus.PENDING) {
                throw new IllegalStateException("Request " + id + " is "
                        + request.getStatus() + "; the approvers can only be changed while PENDING.");
            }
            // #23: resolved as SYSTEM after the requester check above (ApprovalPolicy.jobForPolicy).
            Job<?, ?> job = ApprovalPolicy.jobForPolicy(request.getJobFullName());
            List<String> designated = ApprovalPolicy.checkDesignation(request.getRequester(), newApprovers, job);
            List<String> previous = request.getApprovers();
            request.addApproverChange(new RunRequest.ApproverChange(
                    previous, designated, caller, BatchClock.now()));
            request.setApprovers(designated);
            persist(request);
        } finally {
            lock.unlock();
        }
        NotificationDispatcher.run(NotificationEvent.APPROVERS_CHANGED, request);
        return request;
    }

    // ---------------------------------------------------------------- queue gate integration

    /**
     * Atomically claims the single consumption ticket of an approved request (D-23), including
     * the D-20 check-at-submit expiry re-check. Called by the queue gate while Jenkins holds
     * the queue lock; must therefore never be called while holding this service's lock on
     * another thread path that also takes the queue lock.
     *
     * <p>A refusal that is a genuine <em>re-use</em> of a marker the plugin once minted also
     * lands in the audit history as {@code ChangeRecord(MARKER_REUSE_BLOCKED)} (D-30), so the
     * attempt is visible on the history screen and in {@code changes.csv} and not only in the
     * log. Two refusals qualify: the ticket is already spent (re-queue, rebuild, replay of the
     * executed build), and the marker is presented on a job it was not issued for. The three
     * remaining refusals are not re-use of a granted authorization and stay log-only: an
     * unknown request id (nothing was ever approved under it), a request that was never
     * approved or is no longer approved, and an approval that timed out before submission
     * (D-20) — that one is the request's own EXPIRED transition, not an actor's attempt.
     *
     * @return {@code true} if the submission is authorized (ticket claimed just now)
     */
    public boolean consumeMarker(String requestId, String jobFullName) {
        lock.lock();
        try {
            RunRequest request = loadCurrent(requestId);
            if (request == null) {
                LOGGER.warning(() -> "Refusing approval marker for unknown request " + requestId);
                return false;
            }
            // Checked before the status check: consumption sets queuedAt and the run then moves
            // the request to EXECUTED, so a replay of an executed approval must be reported as
            // the spent ticket it is rather than as a mere wrong status.
            if (request.getQueuedAt() != null || request.getExecutedRunId() != null
                    || request.getStatus() == RequestStatus.EXECUTED) {
                LOGGER.warning(() -> "Refusing re-use of the already consumed approval marker of request "
                        + requestId);
                recordMarkerReuseBlocked(requestId, jobFullName,
                        "its single submission ticket was already claimed");
                return false;
            }
            if (request.getStatus() != RequestStatus.APPROVED) {
                LOGGER.warning(() -> "Refusing approval marker for request " + requestId
                        + " in status " + request.getStatus());
                return false;
            }
            if (!request.getJobFullName().equals(jobFullName)) {
                LOGGER.warning(() -> "Refusing approval marker of request " + requestId
                        + " (bound to job '" + request.getJobFullName() + "') on job '" + jobFullName + "'");
                recordMarkerReuseBlocked(requestId, jobFullName,
                        "the approval is bound to job '" + request.getJobFullName() + "'");
                return false;
            }
            Instant now = BatchClock.now();
            if (approvedExpired(request, now)) {
                // D-20 check-at-submit: an expired approval is never submitted.
                String reason = EndReasons.approvedNotStarted();
                request.setStatus(RequestStatus.EXPIRED);
                request.setDecisionComment(EndReasons.withEarlierComment(reason, request.getDecisionComment()));
                // D-72: this submission is refused, so the values never reach the queue (the
                // ticket is unclaimed: checked above).
                persistEnded(request, true);
                NotificationDispatcher.runEnded(NotificationEvent.EXPIRED, request, false, reason);
                LOGGER.warning(() -> "Refusing approval marker of request " + requestId
                        + ": the approved-run timeout passed before submission (now EXPIRED)");
                return false;
            }
            request.setQueuedAt(now);
            // The typed values stay in their own file until the run starts (D-72b (5), D-74).
            persist(request);
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Appends the audit record of a blocked marker re-use (D-30).
     *
     * <p>Field layout (SPEC leaves it open; see P-12):
     * <ul>
     *   <li>{@code target} — the job the marker was <em>presented on</em>, so the history
     *       screen's job filter finds the attempt on the job that would have run;</li>
     *   <li>{@code user} — the account that made the attempt, taken from the current
     *       authentication (this runs on the caller's thread inside the queue gate), never the
     *       requester of the original approval;</li>
     *   <li>{@code detail} — the consumed request id, the job and the refusal reason, comma-free
     *       so the CSV export keeps one cell per column;</li>
     *   <li>{@code grantId}/{@code diff} — left {@code null}: a blocked run submission happens
     *       outside any change window and has no before/after configuration.</li>
     * </ul>
     *
     * <p>No switch check is needed: the queue gate only reaches this code while run control is
     * on, which is one of the two switches that make change recording active (D-13).
     *
     * <p>The append goes through {@link BlockedAttemptAudit} rather than straight to the store
     * (S-21): this runs with the global queue lock held, so a repeated attempt must not be able to
     * append without bound. The merge key is the request id together with the job the marker was
     * presented on — the two facts D-30 requires the record to identify — so every distinct
     * attempt is still recorded and only a repetition of the identical one is merged.
     */
    private void recordMarkerReuseBlocked(String requestId, String jobFullName, String reason) {
        String actor = Jenkins.getAuthentication2().getName();
        BlockedAttemptAudit.get().record(ChangeType.MARKER_REUSE_BLOCKED,
                requestId + " on " + jobFullName, jobFullName, actor,
                "Blocked re-use of the approved-run marker of request " + requestId
                        + " on job '" + jobFullName + "' - " + reason);
    }

    /**
     * Marks an APPROVED request as EXECUTED once its build has started (SPEC section 4). D-72b (5):
     * the typed values file is deleted now; the build has its own copy and owns the files, and the
     * masked display map stays as the record.
     */
    public void markExecuted(String requestId, String runId) {
        lock.lock();
        try {
            RunRequest request = loadCurrent(requestId);
            if (request == null) {
                return;
            }
            if (request.getStatus() == RequestStatus.APPROVED) {
                request.setStatus(RequestStatus.EXECUTED);
                request.setExecutedRunId(runId);
                // Retention measures a request's last activity from this (security-10 S-09).
                request.setExecutedAt(BatchClock.now());
                persistEnded(request, false);
            }
        } finally {
            lock.unlock();
        }
    }

    // ---------------------------------------------------------------- expiry (SPEC 7)

    /**
     * Expires overdue requests: PENDING past {@code pendingTimeoutHours} and APPROVED,
     * never-executed requests past {@code approvedRunTimeoutMinutes} whose submission is not
     * sitting in the queue right now.
     *
     * <p>Boundary-window hardening (spec-review-S2 MINOR 1): the queue snapshot is taken
     * outside this service's lock (lock-order discipline with the queue gate), so a marker
     * consumed between the snapshot and the per-request lock acquisition is missing from the
     * snapshot even though the request WAS submitted in time. The consumption ticket
     * ({@code queuedAt}), re-read here under the lock, closes that window: a ticket claimed at
     * or after the snapshot instant proves the snapshot is stale for this request, so it is
     * skipped this cycle (the next cycle sees it in the queue, executed, or genuinely gone).
     *
     * @param queuedRequestIds ids of requests that currently have a queue item carrying their
     *        approval marker (collected by the caller outside this service's lock)
     * @param queueSnapshotAt the instant just before the caller collected the snapshot
     */
    public void expireOverdue(Set<String> queuedRequestIds, Instant queueSnapshotAt) {
        Instant now = BatchClock.now();
        for (RunRequest snapshot : store.listOpenRunRequests()) {
            RequestStatus status = snapshot.getStatus();
            if (status != RequestStatus.PENDING && status != RequestStatus.APPROVED) {
                continue;
            }
            lock.lock();
            try {
                RunRequest request = loadCurrent(snapshot.getId());
                if (request == null) {
                    continue;
                }
                if (request.getStatus() == RequestStatus.PENDING && pendingExpired(request, now)) {
                    String reason = EndReasons.pendingExpired();
                    request.setStatus(RequestStatus.EXPIRED);
                    request.setDecisionComment(reason);
                    persistEnded(request, true);
                    NotificationDispatcher.runEnded(NotificationEvent.EXPIRED, request, true, reason);
                    LOGGER.info(() -> "Run request " + request.getId()
                            + " expired (pending timeout)");
                } else if (request.getStatus() == RequestStatus.APPROVED
                        && request.getExecutedRunId() == null
                        && !queuedRequestIds.contains(request.getId())
                        && ticketNotFresherThan(request, queueSnapshotAt)
                        && approvedExpired(request, now)) {
                    boolean cancelled = request.getQueueCancelledAt() != null;
                    String reason = cancelled ? EndReasons.approvedRunCancelled() : EndReasons.approvedNotStarted();
                    request.setStatus(RequestStatus.EXPIRED);
                    request.setDecisionComment(EndReasons.withEarlierComment(reason, request.getDecisionComment()));
                    // D-72: not in the queue and never executed. An unclaimed ticket (never queued,
                    // or a submission refused after the gate claimed it, whose claim was released:
                    // S7 M-1) means no build owns the values, so their files are disposed of. A
                    // claimed ticket means the queue took them; when that queue item was cancelled
                    // (D-72b (7)) the cancellation disposed of the files and the typed values were
                    // removed then.
                    persistEnded(request, request.getQueuedAt() == null);
                    NotificationDispatcher.runEnded(NotificationEvent.EXPIRED, request, false, reason);
                    LOGGER.info(() -> "Run request " + request.getId()
                            + " expired (approved-run timeout)");
                }
            } finally {
                lock.unlock();
            }
        }
    }

    /**
     * D-36: sends {@link NotificationEvent#EXPIRING} once for every PENDING request whose pending
     * timeout falls within {@code notifyBeforeExpiryMinutes} from now. The "notified" flag is
     * persisted before dispatch, so a restart never resends. Expiry itself stays the clock
     * comparison of {@link #expireOverdue}. Each request is handled on its own: one that cannot be
     * read or written is logged and tried again on the next run, and never keeps the others from
     * their notice.
     */
    public void notifyExpiring() {
        Instant now = BatchClock.now();
        Duration lead = Duration.ofMinutes(
                BatchControlGlobalConfiguration.get().getNotifyBeforeExpiryMinutes());
        for (RunRequest snapshot : store.listOpenRunRequests()) {
            if (snapshot.getStatus() != RequestStatus.PENDING || snapshot.isExpiringNotified()) {
                continue;
            }
            RunRequest notified = null;
            lock.lock();
            try {
                RunRequest request = loadCurrent(snapshot.getId());
                if (request != null && request.getStatus() == RequestStatus.PENDING
                        && !request.isExpiringNotified()) {
                    Instant expiresAt = pendingExpiry(request);
                    if (now.isBefore(expiresAt) && !now.isBefore(expiresAt.minus(lead))) {
                        request.setExpiringNotified(true);
                        persist(request);
                        notified = request;
                    }
                }
            } catch (RuntimeException e) {
                // Not marked (the flag is only ever stored before the dispatch), so the next run tries again.
                LOGGER.log(java.util.logging.Level.WARNING, "Could not send the EXPIRING notification of run request "
                        + snapshot.getId() + "; it is tried again on the next run", e);
            } finally {
                lock.unlock();
            }
            if (notified != null) {
                NotificationDispatcher.run(NotificationEvent.EXPIRING, notified);
            }
        }
    }

    /**
     * Whether the queue snapshot is authoritative for this request: true when the ticket was
     * never claimed, or was claimed strictly before the snapshot was taken (absent from the
     * snapshot then really means gone — e.g. the queue item was cleared). A ticket claimed at
     * or after the snapshot instant means the snapshot is stale for this request.
     */
    private static boolean ticketNotFresherThan(RunRequest request, Instant queueSnapshotAt) {
        Instant queuedAt = request.getQueuedAt();
        return queuedAt == null || queuedAt.isBefore(queueSnapshotAt);
    }

    // ---------------------------------------------------------------- invalidation (D-21)

    /**
     * Invalidates every PENDING/APPROVED request that targets the given (old) job full name
     * after a rename or move (D-21).
     *
     * @return the ids of the requests that were invalidated
     */
    public List<String> invalidateForJob(String oldFullName, String reason) {
        List<String> invalidated = new ArrayList<>();
        // D-72: read before this service's lock is taken (lock order with the queue).
        Jenkins jenkins = Jenkins.getInstanceOrNull();
        Set<String> queued = jenkins == null ? new HashSet<>() : queuedMarkerRequestIds(jenkins);
        for (RunRequest snapshot : store.listOpenRunRequests()) {
            if (!oldFullName.equals(snapshot.getJobFullName())) {
                continue;
            }
            RequestStatus status = snapshot.getStatus();
            if (status != RequestStatus.PENDING && status != RequestStatus.APPROVED) {
                continue;
            }
            lock.lock();
            try {
                RunRequest request = loadCurrent(snapshot.getId());
                if (request == null) {
                    continue;
                }
                if (request.getStatus() == RequestStatus.PENDING
                        || request.getStatus() == RequestStatus.APPROVED) {
                    boolean wasPending = request.getStatus() == RequestStatus.PENDING;
                    request.setStatus(RequestStatus.INVALIDATED);
                    request.setInvalidationReason(reason);
                    // e2e-03 DEF-17: the request explains why it was invalidated. The reason is
                    // also the decision comment unless an approver already left one, which stays.
                    String comment = request.getDecisionComment();
                    if (comment == null || comment.trim().isEmpty()) {
                        request.setDecisionComment(reason);
                    }
                    // D-72: an approval already handed to the queue is cancelled there
                    // (RequestInvalidationListener), and the cancelled item disposes of its files.
                    // A run that never reached the queue (S7 M-1 included: a claim released after a
                    // later handler refused the submission) has its files disposed of here; a
                    // cancelled queue item (D-72b (7)) already disposed of its own. The typed values
                    // are removed either way (D-72b (5)).
                    boolean notQueued = request.getQueuedAt() == null;
                    persistEnded(request, wasPending || notQueued && !queued.contains(request.getId()));
                    NotificationDispatcher.runEnded(NotificationEvent.INVALIDATED, request, wasPending, reason);
                    invalidated.add(request.getId());
                    LOGGER.info(() -> "Run request " + request.getId() + " invalidated: " + reason);
                }
            } finally {
                lock.unlock();
            }
        }
        return invalidated;
    }

    // ---------------------------------------------------------------- startup recovery (SPEC 4, 7)

    /**
     * Re-submits APPROVED, never-executed requests exactly once after a restart (SPEC item 4).
     * Idempotency (T-RT-17): a request whose marker is already sitting in the restored queue,
     * or whose build already exists, is skipped; otherwise its consumption ticket is re-issued
     * and the request is submitted again. D-72b (7), security-35 S-35-07: a request whose queue
     * item was cancelled ({@link RunRequest#getQueueCancelledAt()}) is never submitted again; the
     * approved-run timeout ends it and its files are disposed of then. The approved-run timeout
     * is judged from the recovery moment (SPEC item 7 exception), implemented by moving the
     * request's expiry base forward.
     *
     * <p>Ordering with the core queue restore: {@code Queue.init} (which restores
     * {@code queue.xml}) runs in the same {@code JOB_CONFIG_ADAPTED} initializer band as
     * {@link io.jenkins.plugins.batchcontrol.ops.StartupRecovery}, in undefined order, and
     * {@code Queue.load()} clears the live queue before reading. The whole recovery therefore
     * runs under the queue lock: if {@code queue.xml} is still present the persisted queue is
     * loaded here first (core's later {@code load()} re-reads the state re-saved below), so
     * the recovery dedup always sees the restored queue items.
     */
    public void recoverApprovedRequests() {
        Jenkins jenkins = Jenkins.getInstanceOrNull();
        if (jenkins == null) {
            return;
        }
        Queue.withLock(() -> recoverUnderQueueLock(jenkins));
    }

    private void recoverUnderQueueLock(Jenkins jenkins) {
        Queue queue = jenkins.getQueue();
        // Queue.load() renames queue.xml away after a successful restore, so an existing file
        // means the core restore has not happened yet in this session.
        java.io.File queueFile = new java.io.File(jenkins.getRootDir(), "queue.xml");
        boolean loadedHere = queueFile.exists();
        if (loadedHere) {
            queue.load();
        }
        Set<String> queuedIds = queuedMarkerRequestIds(jenkins);
        Instant now = BatchClock.now();
        for (RunRequest snapshot : store.listOpenRunRequests()) {
            if (snapshot.getStatus() != RequestStatus.APPROVED || snapshot.getExecutedRunId() != null) {
                continue;
            }
            if (snapshot.getQueueCancelledAt() != null) {
                LOGGER.info(() -> "Not recovering approved run request " + snapshot.getId()
                        + ": its queue item was cancelled");
                continue;
            }
            Job<?, ?> job = jenkins.getItemByFullName(snapshot.getJobFullName(), Job.class);
            if (job == null) {
                LOGGER.warning(() -> "Cannot recover run request " + snapshot.getId()
                        + ": job '" + snapshot.getJobFullName() + "' no longer exists");
                continue;
            }
            if (hasRunFor(job, snapshot.getId())) {
                continue; // a build for this request already exists; the run listener finishes it
            }
            boolean submit = false;
            RunRequest request = null;
            List<ParameterValue> values = null;
            lock.lock();
            try {
                request = loadCurrent(snapshot.getId());
                if (request == null || request.getStatus() != RequestStatus.APPROVED
                        || request.getExecutedRunId() != null || request.getQueueCancelledAt() != null) {
                    continue;
                }
                // SPEC 7 exception: downtime does not count against approvedRunTimeoutMinutes.
                request.setExpiryBase(now);
                if (queuedIds.contains(request.getId())) {
                    // The restored queue item will run it; just persist the new expiry base.
                    persist(request);
                } else {
                    // Re-issue the consumption ticket for exactly one recovery submission.
                    request.setQueuedAt(null);
                    persist(request);
                    // The values the build receives (D-72), read for this submission only.
                    values = requireValues(request.getId());
                    submit = true;
                }
            } catch (RuntimeException e) {
                // One unreadable request must not stop the recovery of the others; it stays
                // APPROVED and the approved-run timeout ends it.
                LOGGER.log(java.util.logging.Level.WARNING, "Could not recover approved run request "
                        + snapshot.getId(), e);
                continue;
            } finally {
                lock.unlock();
            }
            if (submit) {
                LOGGER.info("Recovering approved run request " + request.getId()
                        + " for job " + request.getJobFullName());
                submitApproved(request, values);
            }
        }
        if (loadedHere) {
            // Re-save so the core Queue.init load() (running later in the same initializer
            // band) restores exactly the state recovery left behind.
            queue.save();
        }
    }

    /** Request ids whose approval marker is currently sitting on a queue item. */
    public static Set<String> queuedMarkerRequestIds(Jenkins jenkins) {
        Set<String> ids = new java.util.HashSet<>();
        for (Queue.Item item : jenkins.getQueue().getItems()) {
            ApprovedRunAction marker = item.getAction(ApprovedRunAction.class);
            if (marker != null) {
                ids.add(marker.getRequestId());
            }
        }
        return ids;
    }

    // ---------------------------------------------------------------- internals

    private RunRequest require(String id) {
        RunRequest request = loadCurrent(id);
        if (request == null) {
            throw new IllegalArgumentException("No such run request: " + id);
        }
        return request;
    }

    /**
     * The typed values of request {@code id} (D-72), for checking and scheduling an approval;
     * {@code null} when it has no values file. A values file that cannot be read refuses with
     * {@link IllegalStateException} (fail closed).
     */
    @CheckForNull
    private List<ParameterValue> requireValues(String id) {
        try {
            return store.loadRunRequestValues(id);
        } catch (RuntimeException e) {
            LOGGER.log(java.util.logging.Level.WARNING, "Could not read the stored values of run request " + id, e);
            throw new IllegalStateException("The stored parameter values of request " + id
                    + " could not be read, so it cannot be approved or run.", e);
        }
    }

    private static boolean pendingExpired(RunRequest request, Instant now) {
        return now.isAfter(pendingExpiry(request));
    }

    private static Instant pendingExpiry(RunRequest request) {
        Duration timeout = Duration.ofHours(
                BatchControlGlobalConfiguration.get().getPendingTimeoutHours());
        return request.getCreatedAt().plus(timeout);
    }

    private static boolean approvedExpired(RunRequest request, Instant now) {
        Instant base = request.getExpiryBase();
        if (base == null) {
            return false;
        }
        Duration timeout = Duration.ofMinutes(
                BatchControlGlobalConfiguration.get().getApprovedRunTimeoutMinutes());
        return now.isAfter(base.plus(timeout));
    }

    /** Whether a run for the given request id already exists on the job (recovery dedup). */
    private static boolean hasRunFor(Job<?, ?> job, String requestId) {
        for (Run<?, ?> run : job.getBuilds()) {
            ApprovedRunAction marker = run.getAction(ApprovedRunAction.class);
            if (marker != null && requestId.equals(marker.getRequestId())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Submits the approved request to the queue. Runs as SYSTEM2 because the approver's
     * authority was already verified (ApprovalPolicy.checkDecision before the APPROVED commit,
     * or a previously committed approval during startup recovery); the submission itself must
     * not depend on the transient thread identity. The queue gate still validates and consumes
     * the marker. A refused or failed submission leaves the request APPROVED (quiet-down
     * tolerance); expiry or recovery handle it later.
     *
     * <p>{@code values} are the request's stored typed values. D-72b (1), S-35-01/04: values that do
     * not match what the approver saw ({@link #valuesProblem}) are never scheduled (fail closed,
     * WARNING); the request stays APPROVED and the approved-run timeout ends it, disposing of its
     * files. S7 M-1: when this submission is refused by a queue handler consulted after Batch
     * Control's gate, the gate has already claimed the ticket although nothing was queued; the
     * claim is released ({@link #releaseRefusedClaim}), so the request counts as never queued:
     * startup recovery may still submit it, and when it ends its files are disposed of.
     *
     * <p>#26: the job lookup is inside the SYSTEM2 block as well. Whether an approver may decide
     * is the approval policy's call alone; an approver holding only Item/Discover (lookup throws
     * AccessDeniedException) or no job permission at all (lookup returns null) must not turn a
     * committed approval into a 403 or a silently dropped run.
     */
    private void submitApproved(RunRequest request, @CheckForNull List<ParameterValue> values) {
        Jenkins jenkins = Jenkins.getInstanceOrNull();
        if (jenkins == null) {
            return;
        }
        // ACL.SYSTEM2 switch: permission checks are complete (see method javadoc) — the
        // requester's REQUEST + Item/Read at creation (D-38a), the approver's ApprovalPolicy
        // decision before the APPROVED commit. Lookup and scheduling run as SYSTEM so neither
        // depends on the approver's access to the job.
        try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) {
            Job<?, ?> job = jenkins.getItemByFullName(request.getJobFullName(), Job.class);
            if (job == null) {
                LOGGER.warning(() -> "Approved run request " + request.getId()
                        + " targets missing job '" + request.getJobFullName() + "'; not submitted");
                return;
            }
            String problem = valuesProblem(request, values);
            if (problem != null) {
                LOGGER.warning(() -> "Approved run request " + request.getId() + " was not submitted: " + problem);
                return;
            }
            List<Action> actions = new ArrayList<>();
            // D-72: the stored typed values, unchanged: original secrets and files included.
            if (values != null && !values.isEmpty()) {
                actions.add(new ParametersAction(values));
            }
            // D-37: the cause names the approver who decided (the first member for requests
            // approved before decidedBy was recorded).
            String decider = request.getDecidedBy() != null ? request.getDecidedBy() : request.getApprover();
            actions.add(new CauseAction(new ApprovedCause(
                    request.getId(), request.getRequester(), decider)));
            actions.add(new ApprovedRunAction(request.getId()));
            Queue.Item item = ParameterizedJobMixIn.scheduleBuild2(job, 0,
                    actions.toArray(new Action[0]));
            if (item == null) {
                LOGGER.info(() -> "Submission of approved run request " + request.getId()
                        + " was not scheduled; the request stays APPROVED");
                if (request.getQueuedAt() == null) {
                    releaseRefusedClaim(jenkins, request.getId());
                }
            }
        }
    }

    /**
     * S7 M-1, D-72b (7): a submission of {@code requestId} whose ticket was unclaimed has just been
     * refused ({@code scheduleBuild2} answered {@code null}, synchronously). If the request's ticket
     * is now claimed, Batch Control's gate claimed it during that call and a queue handler
     * consulted after the gate refused the submission, so nothing was queued and no build exists:
     * the claim is released. Taken under the queue lock and then this service's lock, the order
     * the gate uses; nothing is released while a queue item carries the request's marker, or once
     * its run started or its queue item was cancelled.
     */
    private void releaseRefusedClaim(Jenkins jenkins, String requestId) {
        Queue.withLock(() -> {
            if (queuedMarkerRequestIds(jenkins).contains(requestId)) {
                return;
            }
            lock.lock();
            try {
                RunRequest request = loadCurrent(requestId);
                if (request == null || request.getStatus() != RequestStatus.APPROVED
                        || request.getQueuedAt() == null || request.getExecutedRunId() != null
                        || request.getQueueCancelledAt() != null) {
                    return;
                }
                request.setQueuedAt(null);
                persist(request);
                LOGGER.warning(() -> "The approved run of request " + requestId + " was refused by another queue"
                        + " handler after Batch Control accepted it; nothing was queued. The request stays APPROVED"
                        + " until it runs after a restart or the approved-run timeout ends it.");
            } finally {
                lock.unlock();
            }
        });
    }

    /**
     * D-72b (7), security-35 S-35-07: the queue item of the approved run of {@code requestId} for
     * job {@code jobFullName} was cancelled, so that run will not happen. The cancellation is
     * recorded on the APPROVED request: startup recovery never submits it again (its ticket stays
     * claimed, so the queue gate refuses any other submission of it too), and the approved-run
     * timeout ends it. The cancelled item's own parameter types have already deleted the
     * temporary files it carried (core's and the file-parameters plugin's cancelled-item
     * listeners, see {@link ParameterFiles}); the typed values file, which nothing can use any
     * more, is deleted now (D-72b (5)). Called by the queue listener, as
     * SYSTEM, while Jenkins holds the queue lock (lock order as for {@link #consumeMarker}).
     * Never throws.
     *
     * <p>T-GAP-384: when the request file cannot be written, the mark stays in effect in memory
     * ({@link #unsavedQueueCancels}) and is written by the periodic work as soon as the store accepts
     * it ({@link #retryUnsavedQueueCancels}), so a restart after that does not submit the run again
     * either (LIMITATIONS 32).
     */
    public void recordQueueCancelled(String requestId, String jobFullName) {
        lock.lock();
        try {
            RunRequest request = loadCurrent(requestId);
            if (request == null || request.getStatus() != RequestStatus.APPROVED
                    || request.getExecutedRunId() != null || request.getQueueCancelledAt() != null
                    || !request.getJobFullName().equals(jobFullName)) {
                return;
            }
            Instant cancelledAt = BatchClock.now();
            request.setQueueCancelledAt(cancelledAt);
            try {
                // The queued values were the queue's to dispose of (done by the cancellation itself).
                persistEnded(request, false);
            } catch (RuntimeException e) {
                unsavedQueueCancels.put(requestId, cancelledAt);
                LOGGER.log(java.util.logging.Level.SEVERE, "Could not save the cancelled queue item of approved run"
                        + " request " + requestId + "; it is in effect (the run is not submitted again), and it is"
                        + " written again by the periodic work until that succeeds", e);
                return;
            }
            LOGGER.info(() -> "The queue item of approved run request " + requestId
                    + " was cancelled; it will not be submitted again");
        } catch (RuntimeException e) {
            LOGGER.log(java.util.logging.Level.WARNING, "Could not record the cancelled queue item of run request "
                    + requestId, e);
        } finally {
            lock.unlock();
        }
    }

    /**
     * T-GAP-384, D-72b (7): writes the queue cancellations that could not be written when they
     * happened ({@link #recordQueueCancelled}), with the deletion of the typed values file that goes
     * with them. One that still fails stays for the next attempt; one whose request has no file any
     * more, or whose file already carries a cancellation, is dropped. Called by the periodic work;
     * never throws.
     */
    public void retryUnsavedQueueCancels() {
        for (String id : new ArrayList<>(unsavedQueueCancels.keySet())) {
            lock.lock();
            try {
                Instant cancelledAt = unsavedQueueCancels.get(id);
                if (cancelledAt == null) {
                    continue; // written meanwhile by another write of the request
                }
                RunRequest request = store.loadRunRequest(id);
                if (request == null || request.getQueueCancelledAt() != null) {
                    unsavedQueueCancels.remove(id);
                    continue;
                }
                request.setQueueCancelledAt(cancelledAt);
                persistEnded(request, false); // persist() drops the entry
                LOGGER.info(() -> "The cancelled queue item of run request " + id + " is now written");
            } catch (RuntimeException e) {
                LOGGER.log(java.util.logging.Level.FINE, "The cancelled queue item of run request " + id
                        + " still cannot be written", e);
            } finally {
                lock.unlock();
            }
        }
    }

    /**
     * D-72, D-72b (5): persists {@code request}, which has just ended or whose run has just
     * started, and deletes its typed values file (the masked display map stays as the record).
     * With {@code dispose} the request ended without a run and its values never reached the queue,
     * so the temporary files of its values are disposed of after the end state is stored; once the
     * values were handed to the queue, the queue and the build own the files. The end state is
     * stored first: a values file that cannot be deleted is logged and goes with the request at
     * retention.
     */
    private void persistEnded(RunRequest request, boolean dispose) {
        List<ParameterValue> values = dispose ? storedValues(request.getId()) : List.of();
        persist(request);
        try {
            store.deleteRunRequestValues(request.getId());
        } catch (RuntimeException e) {
            LOGGER.log(java.util.logging.Level.WARNING, "Could not delete the parameter values of run request "
                    + request.getId(), e);
        }
        if (!values.isEmpty()) {
            ParameterFiles.dispose(values, "run request " + request.getId() + " (" + request.getStatus() + ")");
        }
    }

    /**
     * The typed values of request {@code id} for disposal; empty when there are none or they cannot
     * be read (logged).
     */
    private List<ParameterValue> storedValues(String id) {
        try {
            List<ParameterValue> values = store.loadRunRequestValues(id);
            return values == null ? List.of() : values;
        } catch (RuntimeException e) {
            LOGGER.log(java.util.logging.Level.WARNING, "Could not read the typed values of run request "
                    + id + " to dispose of their files", e);
            return List.of();
        }
    }

    /**
     * Converts string values to typed values through the job's parameter definitions (the
     * service API's string form): a {@link SimpleParameterDefinition} creates the value, any
     * other name becomes a {@link StringParameterValue}.
     */
    private static List<ParameterValue> typedValues(Job<?, ?> job, Map<String, String> parameters) {
        List<ParameterValue> values = new ArrayList<>();
        ParametersDefinitionProperty definitions = job.getProperty(ParametersDefinitionProperty.class);
        for (Map.Entry<String, String> entry : parameters.entrySet()) {
            ParameterDefinition definition = definitions == null
                    ? null : definitions.getParameterDefinition(entry.getKey());
            if (definition instanceof SimpleParameterDefinition) {
                values.add(((SimpleParameterDefinition) definition).createValue(entry.getValue()));
            } else {
                values.add(new StringParameterValue(entry.getKey(), entry.getValue()));
            }
        }
        return values;
    }
}
