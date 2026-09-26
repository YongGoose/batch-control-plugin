package io.jenkins.plugins.batchcontrol;

import hudson.model.Job;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import java.io.IOException;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

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
        assertSame("fixture: the installed BatchControlJobProperty must be the one the plugin"
                + " reads back from " + job.getFullName(),
                property, job.getProperty(BatchControlJobProperty.class));
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
        assertNull("fixture: " + job.getFullName() + " must carry no BatchControlJobProperty",
                job.getProperty(BatchControlJobProperty.class));
        return job;
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
