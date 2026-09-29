package io.jenkins.plugins.batchcontrol;

import hudson.model.Job;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.security.SecurityRealm;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.ActivationRequest;
import io.jenkins.plugins.batchcontrol.policy.ActivationService;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fixture helpers for job-level batch-control settings.
 *
 * <p>D-31 (SPEC item 8) makes every job that is <em>created</em> while run control is enabled
 * start out with a {@link BatchControlJobProperty} of its own ({@code approvalRequired=true}).
 * Two Jenkins core properties turn that default into a trap for test fixtures:
 *
 * <ul>
 *   <li>{@code Job#addProperty} <em>appends</em> a property, it never replaces one of the same
 *       class, and</li>
 *   <li>{@code Job#getProperty(Class)} returns the <em>first</em> match.</li>
 * </ul>
 *
 * <p>So a fixture that enables run control, then creates a job, then calls
 * {@code job.addProperty(new BatchControlJobProperty(...))} ends up with two properties and the
 * plugin reads the D-31 default — the settings the fixture meant to install
 * ({@code blockTimer}, {@code blockUpstream}, {@code allowedUpstreamJobs}, {@code jobApprovers})
 * are silently shadowed and the row measures nothing.
 *
 * <p>The two ways out, both of which this class expresses:
 *
 * <ul>
 *   <li>{@link #setBatchControl} — make the fixture's property the job's only one
 *       (remove first, then add);</li>
 *   <li>{@link #uncontrolled} — strip the property entirely, for a scenario whose premise is
 *       "run control is on but <em>this</em> job is not controlled". (Creating the job before
 *       {@code setRunControlEnabled(true)} is an equivalent alternative, and is preferable when
 *       the fixture must produce no extra save of the job.)</li>
 * </ul>
 */
final class BatchControlFixtures {

    private BatchControlFixtures() {
        // utility class
    }

    /**
     * Installs {@code property} as the job's <em>only</em> {@link BatchControlJobProperty},
     * removing any the job already carries (the D-31 default, typically). Use this instead of a
     * bare {@code addProperty} whenever the fixture sets anything beyond
     * {@code approvalRequired}, and keep the returned instance if the test mutates the property
     * later: after this call it is the instance the plugin actually reads — which this method
     * asserts, so no fixture can go back to measuring a shadowed property unnoticed.
     */
    static <P extends BatchControlJobProperty> P setBatchControl(Job<?, ?> job, P property)
            throws IOException {
        uncontrolled(job);
        addProperty(job, property);
        assertSame(property, job.getProperty(BatchControlJobProperty.class), "fixture: the installed BatchControlJobProperty must be the one the plugin"
                + " reads back from " + job.getFullName());
        return property;
    }

    /**
     * Removes every {@link BatchControlJobProperty} from the job and returns it, so that run
     * control does not control it even while run control is globally enabled. Models a job that
     * predates run control (or that an administrator has deliberately taken out of it) without
     * weakening any assertion: the job is left exactly as a pre-D-31 {@code createFreeStyleProject}
     * would have left it.
     */
    static <J extends Job<?, ?>> J uncontrolled(J job) throws IOException {
        while (job.removeProperty(BatchControlJobProperty.class) != null) {
            // Job#removeProperty(Class) drops the first match only; D-31 plus a fixture's own
            // addProperty can leave more than one behind.
        }
        assertNull(job.getProperty(BatchControlJobProperty.class), "fixture: " + job.getFullName() + " must carry no BatchControlJobProperty");
        return job;
    }

    /**
     * SPEC item 6a (#15, D-39): brings {@code job} into service the only way SPEC allows — an
     * {@code ACTIVATE} request by {@code requester}, approved by the designated {@code approver}
     * through {@link ActivationService}. A job created after the plugin's first start is not
     * activated, so every row whose premise is "this run-controlled job's timer/upstream door is
     * open" (or "only the job switch blocks it") must establish that through this helper (matrix
     * note 91). The helper asserts the premise it establishes, so a row can never go on measuring
     * a job that is still not activated.
     *
     * <p>The approver is added to the global approver list for the duration of the call if it is
     * not already there, and the list is restored afterwards. The users act through a plain
     * authenticated token, so the helper also works on an unsecured instance (the default
     * {@code JenkinsRule}); on a secured one the ids must hold what item 6a names
     * ({@code BatchControl/Request} + {@code Item/Read} for the requester, {@code Approve} for the
     * approver).
     */
    static void activate(Job<?, ?> job, String requester, String approver) throws IOException {
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        List<String> before = cfg.getApprovers() == null
                ? new ArrayList<>() : new ArrayList<>(cfg.getApprovers());
        boolean added = !before.contains(approver);
        if (added) {
            List<String> widened = new ArrayList<>(before);
            widened.add(approver);
            cfg.setApprovers(widened);
            cfg.save();
        }
        try {
            ActivationRequest request;
            try (ACLContext ignored = ACL.as2(token(requester))) {
                request = ActivationService.get().create(job, ActivationRequest.Action.ACTIVATE,
                        "fixture: bring " + job.getFullName() + " into service",
                        Collections.singletonList(approver));
            }
            try (ACLContext ignored = ACL.as2(token(approver))) {
                ActivationService.get().approve(request.getId(), "fixture: activation approved");
            }
        } finally {
            if (added) {
                cfg.setApprovers(before);
                cfg.save();
            }
        }
        assertTrue(ActivationService.get().isActivated(job), "fixture: " + job.getFullName()
                + " must be activated after the approved ACTIVATE request");
    }

    /** {@link #activate(Job, String, String)} with the ids most secured fixtures here use. */
    static void activate(Job<?, ?> job) throws IOException {
        activate(job, "u1", "a1");
    }

    /** An authenticated principal that needs no security realm (works on unsecured instances). */
    static Authentication token(String userId) {
        return new UsernamePasswordAuthenticationToken(userId, "",
                Collections.singletonList(SecurityRealm.AUTHENTICATED_AUTHORITY2));
    }

    /**
     * {@code Job#addProperty} is typed {@code JobProperty<? super JobT>}, which a
     * {@code Job<?, ?>} reference cannot satisfy; the concrete job types used in the tests all
     * accept a {@link BatchControlJobProperty} (see any {@code FreeStyleProject.addProperty}
     * call site), so the raw call is safe here.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void addProperty(Job<?, ?> job, BatchControlJobProperty property)
            throws IOException {
        ((Job) job).addProperty(property);
    }
}
