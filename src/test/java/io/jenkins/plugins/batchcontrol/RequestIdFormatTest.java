package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleProject;
import hudson.model.Item;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.ActivationRequest;
import io.jenkins.plugins.batchcontrol.model.Grant;
import io.jenkins.plugins.batchcontrol.model.GrantRequest;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import io.jenkins.plugins.batchcontrol.policy.ActivationService;
import io.jenkins.plugins.batchcontrol.policy.GrantRequestService;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.security.GrantService;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.WebResponse;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.JenkinsSessionExtension;

import static io.jenkins.plugins.batchcontrol.ActivationFixtures.decideActivation;
import static io.jenkins.plugins.batchcontrol.ActivationFixtures.submitActivationOk;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.assertSuccess;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.decideGrant;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.decideRun;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.excerpt;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.submitGrantOk;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.submitRunOk;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC item 8 (D-68): new run requests, activation requests, grant requests and grants get UUID
 * identifiers (canonical 36-character form), their detail URLs resolve, and a record stored
 * with an identifier in the earlier format ({@code yyyyMMdd-HHmmss-<6 random>}, ARCHITECTURE
 * section 5) still loads after a restart, its detail page resolves and it can still be decided.
 * Matrix rows T-08-90 .. T-08-95 (note 231).
 *
 * <p>How the earlier-format records are made: the plugin stores each record as
 * {@code <id>.xml} under its documented directory (ARCHITECTURE section 5). After the first
 * session every new UUID is replaced by an earlier-format id in all files under
 * {@code batch-control/} (content and file names), so cross references (a grant naming its
 * request, an activation state naming its request) stay consistent; the premise (no file names
 * the UUID any more, the renamed file exists) is asserted before the restart.
 *
 * <p>Written from docs/SPEC.md, docs/DECISIONS.md D-68, docs/ARCHITECTURE.md section 5 and
 * docs/TEST-MATRIX.md only (no src/main knowledge).
 */
public class RequestIdFormatTest {

    private static final String RUN_JOB = "id-run";
    private static final String ACT_JOB = "id-act";

    @RegisterExtension
    final JenkinsSessionExtension session = new JenkinsSessionExtension();

    private String runId;
    private String grantRequestId;
    private String approvedGrantRequestId;
    private String grantId;
    private String activationId;

    // ------------------------------------------------------------------ new identifiers

    /** T-08-90: a new run request has a canonical UUID id, distinct per request; its page resolves. */
    @Test
    public void t_08_90_newRunRequestHasUuidAndPageResolves() throws Throwable {
        session.then(r -> {
            prepare(r);
            FreeStyleProject job = r.jenkins.getItemByFullName(RUN_JOB, FreeStyleProject.class);
            String first = submitRunOk(r, "u1", job, "uuid run one", "a1");
            String second = submitRunOk(r, "u1", job, "uuid run two", "a1");
            assertCanonicalUuid(first, "run request id");
            assertCanonicalUuid(second, "run request id");
            assertNotEquals(first, second, "two requests must not share an id");

            WebResponse page = getFollowing(r, "u1", "batch-control/requests/" + first + "/");
            assertEquals(200, page.getStatusCode(), "the run request detail page must resolve: " + excerpt(page.getContentAsString()));
            assertTrue(page.getContentAsString().contains(RUN_JOB), "the detail page must show the request's job");
            // guard: an unknown UUID does not resolve, so the 200 above is the record's own page
            assertEquals(404, getFollowing(r, "admin", "batch-control/requests/" + UUID.randomUUID() + "/").getStatusCode(),
                    "an unknown UUID must not resolve");
        });
    }

    /** T-08-91: a new grant request and the grant its approval creates have UUID ids; both URLs resolve. */
    @Test
    public void t_08_91_newGrantRequestAndGrantHaveUuidAndPagesResolve() throws Throwable {
        session.then(r -> {
            prepare(r);
            String requestId = submitGrantOk(r, "u1", RUN_JOB, List.of("CONFIGURE"), 60,
                    "uuid window", null, "a1");
            assertCanonicalUuid(requestId, "grant request id");
            WebResponse page = getFollowing(r, "u1", "batch-control/grants/" + requestId + "/");
            assertEquals(200, page.getStatusCode(), "the grant request detail page must resolve: " + excerpt(page.getContentAsString()));
            assertTrue(page.getContentAsString().contains(RUN_JOB), "the grant request page must show its scope");

            assertSuccess(decideGrant(r, "a1", requestId, "approve", "ok"), "approval of the grant request");
            Grant grant = onlyActiveGrant();
            assertCanonicalUuid(grant.getId(), "grant id");
            assertEquals(200, getFollowing(r, "admin", "batch-control/grants/active/" + grant.getId() + "/").getStatusCode(),
                    "the window's URL must resolve");
            // guard: unknown ids do not resolve
            assertTrue(getFollowing(r, "admin", "batch-control/grants/" + UUID.randomUUID() + "/").getStatusCode() >= 400,
                    "an unknown grant request UUID must not resolve");
            assertTrue(getFollowing(r, "admin", "batch-control/grants/active/" + UUID.randomUUID() + "/").getStatusCode() >= 400,
                    "an unknown window UUID must not resolve");
        });
    }

    /** T-08-92: a new activation request has a UUID id and its page resolves. */
    @Test
    public void t_08_92_newActivationRequestHasUuidAndPageResolves() throws Throwable {
        session.then(r -> {
            prepare(r);
            FreeStyleProject job = r.jenkins.getItemByFullName(ACT_JOB, FreeStyleProject.class);
            String id = submitActivationOk(r, "u1", job, "ACTIVATE", "uuid activation", "a1");
            assertCanonicalUuid(id, "activation request id");
            WebResponse page = getFollowing(r, "u1", "batch-control/activations/" + id + "/");
            assertEquals(200, page.getStatusCode(), "the activation request detail page must resolve: " + excerpt(page.getContentAsString()));
            assertTrue(page.getContentAsString().contains(ACT_JOB), "the detail page must show the request's job");
            assertEquals(404, getFollowing(r, "admin", "batch-control/activations/" + UUID.randomUUID() + "/").getStatusCode(),
                    "an unknown UUID must not resolve");
        });
    }

    // ------------------------------------------------------------------ earlier-format identifiers

    /** T-08-93: a run request stored with an earlier-format id loads, its page resolves and it can be approved. */
    @Test
    public void t_08_93_earlierFormatRunRequestLoadsAndResolves() throws Throwable {
        String legacy = "20261001-093000-k3x9qa";
        session.then(r -> {
            prepare(r);
            FreeStyleProject job = r.jenkins.getItemByFullName(RUN_JOB, FreeStyleProject.class);
            runId = submitRunOk(r, "u1", job, "earlier-format run", "a1");
            rewriteIds(r, Map.of(runId, legacy), "requests/run/");
        });
        session.then(r -> {
            RunRequest loaded = RunRequestService.get().load(legacy);
            assertNotNull(loaded, "the earlier-format request must load");
            assertEquals(legacy, loaded.getId());
            assertEquals(RequestStatus.PENDING, loaded.getStatus());
            assertTrue(RunRequestService.get().list().stream().anyMatch(q -> legacy.equals(q.getId())),
                    "the earlier-format request must be listed");

            WebResponse page = getFollowing(r, "u1", "batch-control/requests/" + legacy + "/");
            assertEquals(200, page.getStatusCode(), "the earlier-format request's page must resolve: " + excerpt(page.getContentAsString()));
            assertTrue(page.getContentAsString().contains(RUN_JOB));
            // guard: the old UUID is gone, so the page above is served from the earlier-format record
            assertEquals(404, getFollowing(r, "admin", "batch-control/requests/" + runId + "/").getStatusCode());

            assertSuccess(decideRun(r, "a1", legacy, "approve", "ok"), "approval of the earlier-format request");
            r.waitUntilNoActivity();
            assertEquals(RequestStatus.EXECUTED, RunRequestService.get().load(legacy).getStatus());
            assertEquals(1, r.jenkins.getItemByFullName(RUN_JOB, FreeStyleProject.class).getBuilds().size(),
                    "exactly one approved build");
        });
    }

    /** T-08-94: a grant request and a window stored with earlier-format ids load and resolve; the window still confers. */
    @Test
    public void t_08_94_earlierFormatGrantRequestAndGrantLoadAndResolve() throws Throwable {
        String legacyPending = "20261001-094500-p7m2zt";
        String legacyApproved = "20261001-095000-w4n8rd";
        String[] legacyGrant = {"20261001-095001-h6c1vb"};
        session.then(r -> {
            prepare(r);
            grantRequestId = submitGrantOk(r, "u1", RUN_JOB, List.of("CONFIGURE"), 60,
                    "earlier-format pending", null, "a1");
            approvedGrantRequestId = submitGrantOk(r, "u1", ACT_JOB, List.of("CONFIGURE"), 120,
                    "earlier-format window", null, "a1");
            assertSuccess(decideGrant(r, "a1", approvedGrantRequestId, "approve", "ok"), "fixture: approval");
            grantId = onlyActiveGrant().getId();
            Map<String, String> map = new LinkedHashMap<>();
            map.put(grantRequestId, legacyPending);
            map.put(approvedGrantRequestId, legacyApproved);
            if (grantId.equals(approvedGrantRequestId)) {
                legacyGrant[0] = legacyApproved; // SPEC does not say whether a window has an id of its own
            } else {
                map.put(grantId, legacyGrant[0]);
            }
            rewriteIds(r, map, "requests/grant/", "grants/");
        });
        session.then(r -> {
            GrantRequest pending = GrantRequestService.get().load(legacyPending);
            assertNotNull(pending, "the earlier-format grant request must load");
            assertEquals(RequestStatus.PENDING, pending.getStatus());
            assertEquals(RequestStatus.APPROVED, GrantRequestService.get().load(legacyApproved).getStatus());
            assertTrue(GrantService.get().listActive().stream().anyMatch(g -> legacyGrant[0].equals(g.getId())),
                    "the earlier-format window must be active after the restart");
            assertTrue(GrantService.get().hasActiveGrant("u1", ACT_JOB, Item.CONFIGURE),
                    "the earlier-format window must still confer Configure");

            assertEquals(200, getFollowing(r, "u1", "batch-control/grants/" + legacyPending + "/").getStatusCode(),
                    "the earlier-format grant request's page must resolve");
            assertEquals(200, getFollowing(r, "u1", "batch-control/grants/" + legacyApproved + "/").getStatusCode(),
                    "the approved earlier-format grant request's page must resolve");
            assertEquals(200, getFollowing(r, "admin", "batch-control/grants/active/" + legacyGrant[0] + "/").getStatusCode(),
                    "the earlier-format window's URL must resolve");
            // guard: the replaced UUIDs no longer resolve
            assertTrue(getFollowing(r, "admin", "batch-control/grants/" + grantRequestId + "/").getStatusCode() >= 400);

            assertSuccess(decideGrant(r, "a1", legacyPending, "approve", "ok"), "approval of the earlier-format grant request");
            assertEquals(RequestStatus.APPROVED, GrantRequestService.get().load(legacyPending).getStatus());
            assertTrue(GrantService.get().hasActiveGrant("u1", RUN_JOB, Item.CONFIGURE),
                    "approving the earlier-format request must open its window");
        });
    }

    /** T-08-95: an activation request stored with an earlier-format id loads, resolves and can be approved. */
    @Test
    public void t_08_95_earlierFormatActivationRequestLoadsAndResolves() throws Throwable {
        String legacy = "20261001-100000-q2d5fy";
        session.then(r -> {
            prepare(r);
            FreeStyleProject job = r.jenkins.getItemByFullName(ACT_JOB, FreeStyleProject.class);
            activationId = submitActivationOk(r, "u1", job, "ACTIVATE", "earlier-format activation", "a1");
            rewriteIds(r, Map.of(activationId, legacy), "activation-requests/");
        });
        session.then(r -> {
            ActivationRequest loaded = ActivationService.get().load(legacy);
            assertNotNull(loaded, "the earlier-format activation request must load");
            assertEquals(RequestStatus.PENDING, loaded.getStatus());
            WebResponse page = getFollowing(r, "u1", "batch-control/activations/" + legacy + "/");
            assertEquals(200, page.getStatusCode(), "the earlier-format activation page must resolve: " + excerpt(page.getContentAsString()));
            assertEquals(404, getFollowing(r, "admin", "batch-control/activations/" + activationId + "/").getStatusCode());

            assertSuccess(decideActivation(r, "a1", legacy, "approve", "ok"), "approval of the earlier-format activation request");
            FreeStyleProject job = r.jenkins.getItemByFullName(ACT_JOB, FreeStyleProject.class);
            assertTrue(ActivationService.get().isActivated(job), "approving the earlier-format request must activate the job");
        });
    }

    // ------------------------------------------------------------------ helpers

    private static void assertCanonicalUuid(String id, String what) {
        assertNotNull(id, what);
        assertEquals(36, id.length(), what + " must be a 36-character UUID, was " + id);
        UUID parsed;
        try {
            parsed = UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new AssertionError(what + " must be a UUID, was " + id, e);
        }
        assertEquals(parsed.toString(), id, what + " must be in canonical (lower-case) form");
    }

    private static Grant onlyActiveGrant() {
        List<Grant> active = new ArrayList<>(GrantService.get().listActive());
        assertEquals(1, active.size(), "fixture: exactly one active window, got " + active.size());
        return active.get(0);
    }

    /** GET as {@code userId}, following redirects; failing statuses are returned, not thrown. */
    private static WebResponse getFollowing(JenkinsRule r, String userId, String path) throws Exception {
        JenkinsRule.WebClient wc = r.createWebClient().withThrowExceptionOnFailingStatusCode(false);
        wc.login(userId);
        return wc.getPage(new WebRequest(new URL(r.getURL(), path), HttpMethod.GET)).getWebResponse();
    }

    /**
     * Replaces every UUID key of {@code ids} by its earlier-format value in the content and the
     * names of all files under {@code batch-control/}, and asserts that each record now lives at
     * {@code <dir><legacy>.xml} for one of {@code dirs}.
     */
    private static void rewriteIds(JenkinsRule r, Map<String, String> ids, String... dirs) throws IOException {
        Path root = r.jenkins.getRootDir().toPath().resolve("batch-control");
        for (Map.Entry<String, String> e : ids.entrySet()) {
            boolean found = false;
            for (String dir : dirs) {
                found |= Files.isRegularFile(root.resolve(dir + e.getKey() + ".xml"));
            }
            assertTrue(found, "premise: the record " + e.getKey() + " must be stored as <id>.xml under one of " + List.of(dirs));
        }
        List<Path> files;
        try (Stream<Path> walk = Files.walk(root)) {
            files = walk.filter(Files::isRegularFile).collect(Collectors.toList());
        }
        for (Path file : files) {
            String name = file.getFileName().toString();
            String newName = name;
            for (Map.Entry<String, String> e : ids.entrySet()) {
                newName = newName.replace(e.getKey(), e.getValue());
            }
            if (name.endsWith(".xml") || name.endsWith(".jsonl")) {
                String content = Files.readString(file, StandardCharsets.UTF_8);
                String rewritten = content;
                for (Map.Entry<String, String> e : ids.entrySet()) {
                    rewritten = rewritten.replace(e.getKey(), e.getValue());
                }
                if (!rewritten.equals(content)) {
                    Files.writeString(file, rewritten, StandardCharsets.UTF_8);
                }
            }
            if (!newName.equals(name)) {
                Files.move(file, file.resolveSibling(newName));
            }
        }
        for (Map.Entry<String, String> e : ids.entrySet()) {
            boolean found = false;
            for (String dir : dirs) {
                Path moved = root.resolve(dir + e.getValue() + ".xml");
                if (Files.isRegularFile(moved)) {
                    found = true;
                    String xml = Files.readString(moved, StandardCharsets.UTF_8);
                    assertTrue(xml.contains(e.getValue()) && !xml.contains(e.getKey()),
                            "premise: the record file must carry the earlier-format id only: " + excerpt(xml));
                }
            }
            assertTrue(found, "premise: the record must now be stored as " + e.getValue() + ".xml");
        }
    }

    /** Run and change control on, the persistable Batch Control matrix strategy, two jobs. */
    private static void prepare(JenkinsRule r) throws Exception {
        r.jenkins.setSecurityRealm(r.createDummySecurityRealm());
        BatchControlMatrixAuthorizationStrategy strategy = new BatchControlMatrixAuthorizationStrategy();
        strategy.add(Jenkins.ADMINISTER, PermissionEntry.user("admin"));
        for (String userId : new String[] {"u1", "a1"}) {
            strategy.add(Jenkins.READ, PermissionEntry.user(userId));
            strategy.add(Item.READ, PermissionEntry.user(userId));
        }
        strategy.add(Item.BUILD, PermissionEntry.user("u1"));
        strategy.add(BatchControlPermissions.REQUEST, PermissionEntry.user("u1"));
        strategy.add(BatchControlPermissions.REQUEST_GRANT, PermissionEntry.user("u1"));
        strategy.add(BatchControlPermissions.APPROVE, PermissionEntry.user("a1"));
        r.jenkins.setAuthorizationStrategy(strategy);
        r.jenkins.save();

        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setChangeControlEnabled(true);
        cfg.setApprovers(List.of("a1"));
        cfg.save();

        FreeStyleProject run = r.createFreeStyleProject(RUN_JOB);
        setBatchControl(run, new BatchControlJobProperty(true));
        r.createFreeStyleProject(ACT_JOB); // created under run control: not activated (D-46)
    }
}
