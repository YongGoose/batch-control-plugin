package io.jenkins.plugins.batchcontrol;

import hudson.model.Cause;
import hudson.model.CauseAction;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;
import jenkins.model.Jenkins;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.ActivationFixtures.assertBlocked;
import static io.jenkins.plugins.batchcontrol.ActivationFixtures.isActivated;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.activateAsAdmin;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The deprecated {@link Cause.UserCause} is a human submission, exactly like {@link Cause.UserIdCause}
 * (issue #37). A queue submission of an approval-required, activated job whose only cause is a
 * {@code UserCause} made by a user is refused, with the same refusal and the same change records as the
 * {@code UserIdCause} submission of the same user; a {@code UserCause} on a job that needs no approval is
 * not refused, whether or not the job is activated, exactly as a {@code UserIdCause} is not. Matrix rows
 * T-06-108, T-06-109 (note 308).
 *
 * <p>Basis: SPEC 6 (every manual run without approval is refused at queue entry), SPEC 6a (only causes
 * that are not a human submission need an activation; an activated job lets unattended causes through
 * regardless of {@code approvalRequired}, D-46), and the Wave A contract for #37 frozen by the main session
 * on 2026-10-10 ({@code Cause.UserCause} counts as a human submission exactly like {@code UserIdCause}).
 *
 * <p>The causes are made while authenticated as u1 (Overall/Read, Item/Read, Item/Build), as a script or a
 * plugin running for u1 would make them ({@code new Cause.UserCause()} records the current
 * authentication's name). A refusal is a {@code null} from {@code scheduleBuild2} or a thrown guidance
 * {@link RuntimeException} (the T-06a-04 pattern); "the same refusal" means the same form (both null, or
 * the same exception class with the same message once the job name is normalised).
 *
 * <p>Written from docs/SPEC.md items 6 and 6a, docs/TEST-MATRIX.md, the issue text of #37 and the Wave A
 * contract only (no src/main knowledge).
 */
@WithJenkins
@Tag("core")
public class DeprecatedUserCauseTest {

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, Item.BUILD, BatchControlPermissions.REQUEST).everywhere().to("u1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1"));
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
    }

    /**
     * T-06-108 (#37): the approval-required, activated jobs {@code uc-id} and {@code uc-old} (timer and
     * upstream switches off, so only the approval rule can refuse). Guard first: u1's {@code UserIdCause}
     * submission of {@code uc-id} is refused (blocking triple). Then u1's {@code UserCause} submission of
     * {@code uc-old} is refused the same way (blocking triple, same refusal form) and writes, per change
     * type, as many records on {@code uc-old} as the guard wrote on {@code uc-id}. Guard 2: the same
     * {@code UserCause} submission of the activated {@code uc-free}, which needs no approval, runs once.
     */
    @Test
    public void t_06_108_deprecatedUserCauseIsRefusedLikeUserIdCause() throws Exception {
        FreeStyleProject idJob = job("uc-id", true, true);
        FreeStyleProject oldJob = job("uc-old", true, true);
        FreeStyleProject freeJob = job("uc-free", false, true);
        Map<ChangeType, Integer> idBefore = recordCounts("uc-id");
        Map<ChangeType, Integer> oldBefore = recordCounts("uc-old");

        String idRefusal = submit(idJob, userIdCause("u1"));
        assertNotEquals("accepted", idRefusal, "guard: u1's UserIdCause submission of the approval-required job is refused");
        assertBlocked(j, idJob, 1, 0);
        Map<ChangeType, Integer> idDelta = delta(idBefore, recordCounts("uc-id"));
        System.out.println("T-06-108 observation: UserIdCause refusal " + idRefusal + ", records " + idDelta);

        String oldRefusal = submit(oldJob, userCause("u1"));
        assertEquals(normalise(idRefusal, "uc-id"), normalise(oldRefusal, "uc-old"),
                "#37: a submission whose only cause is the deprecated Cause.UserCause is refused like a UserIdCause submission");
        assertBlocked(j, oldJob, 1, 0);
        Map<ChangeType, Integer> oldDelta = delta(oldBefore, recordCounts("uc-old"));
        System.out.println("T-06-108 observation: UserCause refusal " + oldRefusal + ", records " + oldDelta);
        assertEquals(idDelta, oldDelta, "#37: the UserCause refusal writes the same change records as the UserIdCause refusal");

        assertEquals("accepted", submit(freeJob, userCause("u1")),
                "guard: a UserCause submission of an activated job that needs no approval is accepted");
        j.waitUntilNoActivity();
        assertEquals(1, freeJob.getBuilds().size(), "guard: uc-free runs exactly once");
    }

    /**
     * T-06-109 (#37, parity on the human side of SPEC 6a): the non-activated {@code uc-new}, which needs no
     * approval (timer and upstream switches off). Guard first: u1's {@code UserIdCause} submission runs it
     * (a human submission needs no activation). Then u1's {@code UserCause} submission is accepted too and
     * the job has exactly two builds.
     */
    @Test
    public void t_06_109_deprecatedUserCauseNeedsNoActivationLikeUserIdCause() throws Exception {
        FreeStyleProject job = job("uc-new", false, false);
        assertFalse(isActivated(job), "premise: uc-new is not activated");

        assertEquals("accepted", submit(job, userIdCause("u1")),
                "guard: u1's UserIdCause submission of a job that needs no approval needs no activation");
        j.waitUntilNoActivity();
        assertEquals(1, job.getBuilds().size(), "guard: the UserIdCause submission runs once");

        assertEquals("accepted", submit(job, userCause("u1")),
                "#37: a Cause.UserCause submission is human like a UserIdCause one, so it needs no activation either");
        j.waitUntilNoActivity();
        assertEquals(2, job.getBuilds().size(), "#37: the UserCause submission runs once too");
    }

    // ------------------------------------------------------------------ helpers

    private FreeStyleProject job(String name, boolean approvalRequired, boolean activated) throws Exception {
        FreeStyleProject job = j.createFreeStyleProject(name);
        BatchControlJobProperty property = new BatchControlJobProperty(approvalRequired);
        property.setBlockTimer(false);
        property.setBlockUpstream(false);
        setBatchControl(job, property);
        if (activated) {
            activateAsAdmin(job);
            assertTrue(isActivated(job), "premise: " + name + " is activated");
        }
        return job;
    }

    /** "accepted", or the refusal's form: "null" or the thrown exception's class and message. */
    private static String submit(FreeStyleProject job, Cause cause) {
        try (ACLContext ignored = as("u1")) {
            return job.scheduleBuild2(0, new CauseAction(cause)) == null ? "null" : "accepted";
        } catch (RuntimeException refusal) {
            return refusal.getClass().getName() + ": " + refusal.getMessage();
        }
    }

    private static String normalise(String refusal, String jobName) {
        return refusal.replace(jobName, "<job>");
    }

    private static Cause userIdCause(String userId) {
        try (ACLContext ignored = as(userId)) {
            return new Cause.UserIdCause();
        }
    }

    @SuppressWarnings("deprecation")
    private static Cause userCause(String userId) {
        try (ACLContext ignored = as(userId)) {
            return new Cause.UserCause();
        }
    }

    private static Map<ChangeType, Integer> recordCounts(String target) {
        Map<ChangeType, Integer> counts = new TreeMap<>();
        for (ChangeType type : ChangeType.values()) {
            counts.put(type, ActivationFixtures.recordsFor(type, target).size());
        }
        return counts;
    }

    private static Map<ChangeType, Integer> delta(Map<ChangeType, Integer> before, Map<ChangeType, Integer> after) {
        Map<ChangeType, Integer> out = new TreeMap<>();
        for (Map.Entry<ChangeType, Integer> e : after.entrySet()) {
            int d = e.getValue() - before.getOrDefault(e.getKey(), 0);
            if (d != 0) {
                out.put(e.getKey(), d);
            }
        }
        return out;
    }

    private static ACLContext as(String userId) {
        return ACL.as2(User.getById(userId, true).impersonate2());
    }
}
