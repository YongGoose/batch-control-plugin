package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import io.jenkins.plugins.batchcontrol.model.CauseType;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.model.RunRecord;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import io.jenkins.plugins.batchcontrol.store.FileStore;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsSessionRule;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * SPEC item 4 (durable store, independent from builds). Matrix rows T-04-01 and T-04-03,
 * written against the store-level public API only (state-transition services come in slice S2;
 * T-04-02 and T-04-04 are deferred there).
 *
 * Written from docs/SPEC.md and docs/TEST-MATRIX.md only (no src/main knowledge).
 */
public class StoreDurabilityTest {

    private static final Instant T0 = Instant.parse("2026-09-20T10:00:00Z");

    @Rule
    public JenkinsSessionRule session = new JenkinsSessionRule();

    private String requestId;

    @After
    public void resetClock() {
        BatchClock.reset();
    }

    /** T-04-01: a PENDING RunRequest saved in the store is recovered as PENDING after a controller restart. */
    @Test
    public void t_04_01_pendingRequestSurvivesRestart() throws Throwable {
        session.then(r -> {
            BatchClock.setForTest(Clock.fixed(T0, ZoneOffset.UTC));

            Map<String, String> parameters = new LinkedHashMap<>();
            parameters.put("DATE", "2026-09-01");
            parameters.put("MODE", "FULL");
            RunRequest request = RunRequest.create("area/batch-job", parameters,
                    "monthly close run", "u1", "a1");
            assertEquals("a freshly created request must start PENDING",
                    RequestStatus.PENDING, request.getStatus());
            assertEquals("createdAt must come from BatchClock", T0, request.getCreatedAt());

            FileStore.get().saveRunRequest(request);
            requestId = request.getId();
            assertNotNull(requestId);
        });
        session.then(r -> {
            RunRequest reloaded = FileStore.get().loadRunRequest(requestId);
            assertNotNull("a PENDING request must be recovered after restart", reloaded);
            assertEquals(RequestStatus.PENDING, reloaded.getStatus());
            assertEquals("area/batch-job", reloaded.getJobFullName());
            assertEquals("monthly close run", reloaded.getReason());
            assertEquals("u1", reloaded.getRequester());
            assertEquals("a1", reloaded.getApprover());
            assertEquals(T0, reloaded.getCreatedAt());
            Map<String, String> parameters = reloaded.getParameters();
            assertEquals("2026-09-01", parameters.get("DATE"));
            assertEquals("FULL", parameters.get("MODE"));
        });
    }

    /** T-04-03: RunRecord and the related request stay readable after the underlying build is deleted. */
    @Test
    public void t_04_03_runRecordAndRequestSurviveBuildDeletion() throws Throwable {
        session.then(r -> {
            FreeStyleProject p = r.createFreeStyleProject("nightly");
            FreeStyleBuild build = r.buildAndAssertSuccess(p);

            Map<String, String> parameters = new LinkedHashMap<>();
            parameters.put("DATE", "2026-09-01");
            RunRequest request = RunRequest.create("nightly", parameters, "nightly batch", "u1", "a1");
            FileStore.get().saveRunRequest(request);

            Instant startedAt = Instant.ofEpochMilli(build.getStartTimeInMillis());
            RunRecord record = new RunRecord("nightly#1", "nightly", 1, CauseType.USER,
                    "SUCCESS", startedAt, build.getDuration());
            record.setUser("u1");
            record.setParameters(parameters);
            record.setRunRequestId(request.getId());
            FileStore.get().appendRunRecord(record);

            build.delete(); // retention-policy equivalent
            assertNull("the build itself is gone", p.getBuildByNumber(1));

            YearMonth month = YearMonth.from(startedAt.atZone(ZoneId.systemDefault()));
            RunRecord reloaded = FileStore.get().listRunRecords(month).stream()
                    .filter(rec -> "nightly#1".equals(rec.getRunId()))
                    .findFirst().orElse(null);
            assertNotNull("the RunRecord must remain readable after the build is deleted", reloaded);
            assertEquals("nightly", reloaded.getJobFullName());
            assertEquals("SUCCESS", reloaded.getResult());
            assertEquals(request.getId(), reloaded.getRunRequestId());
            assertEquals("2026-09-01", reloaded.getParameters().get("DATE"));

            RunRequest relatedRequest = FileStore.get().loadRunRequest(request.getId());
            assertNotNull("the related request must remain readable after the build is deleted",
                    relatedRequest);
        });
    }
}
