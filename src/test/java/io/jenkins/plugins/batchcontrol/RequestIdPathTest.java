package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.StringParameterDefinition;
import hudson.model.StringParameterValue;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import jenkins.model.Jenkins;
import org.htmlunit.WebResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.LogRecorder;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * security-39 S-39-01: a run request URL whose id carries a suffix that names the request's values
 * file in another letter case ({@code <id>.VALUES}) must not reach that file. CLAUDE.md: "Anywhere
 * user input ends up in a file path ... it must be validated"; SPEC item 5 (D-74): typed values live
 * in a separate values file per request and "listings never load it"; P-09: a request the viewer may
 * not see answers 404, like a missing one. Matrix row T-SEC-91 (note 274).
 *
 * <p>The row must hold on any file system: on a case-sensitive one {@code <id>.VALUES.xml} does not
 * exist; on a case-insensitive one (default macOS, Windows) it is the values file itself. The answer
 * is 404 either way, never 500, and the same as for an id that does not exist.
 *
 * <p>Users: {@code u1} requester (Item/Read, BatchControl/Request), {@code u2} another Request
 * holder who can read the job, {@code a1} approver, {@code admin}.
 *
 * <p>Written from docs/SPEC.md item 5, docs/DECISIONS.md D-74, CLAUDE.md, docs/ARCHITECTURE.md
 * section 5 and the Given/When/Then of docs/reports/security-39.md only (no src/main knowledge).
 */
@WithJenkins
public class RequestIdPathTest {

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.REQUEST).everywhere().to("u1", "u2")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1"));
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
    }

    /**
     * T-SEC-91 (S-39-01): u1's PENDING request on the approval-required job {@code vals} (string
     * parameter TARGET) has a values file {@code requests/run/<id>.values.xml} (premise), and its
     * page {@code batch-control/requests/<id>/} answers 200 to u1, u2 and admin (premise). For each
     * of them, {@code <id>.VALUES/}, {@code <id>.Values/}, {@code <id>.vAlUeS/}, {@code <id>.values/}
     * and {@code <id>.xml/} answer 404, as {@code no-such-id/} and {@code no-such-id.VALUES/} do;
     * none answers 500, and no ClassCastException is logged while they are served.
     */
    @Test
    public void t_sec_91_requestIdWithAValuesFileSuffixAnswers404InAnyLetterCase() throws Exception {
        FreeStyleProject job = j.createFreeStyleProject("vals");
        job.addProperty(new ParametersDefinitionProperty(new StringParameterDefinition("TARGET", "default-target")));
        setBatchControl(job, new BatchControlJobProperty(true));
        String id;
        try (ACLContext ignored = ACL.as2(User.getById("u1", true).impersonate2())) {
            RunRequest request = RunRequestService.get().create(job, List.of(new StringParameterValue("TARGET", "staging")),
                    "month-end batch", "a1");
            id = request.getId();
        }
        Path dir = j.jenkins.getRootDir().toPath().resolve("batch-control/requests/run");
        assertTrue(Files.isRegularFile(dir.resolve(id + ".values.xml")), "premise (D-74): the request has a values file");
        boolean caseInsensitive = Files.exists(dir.resolve(id + ".VALUES.xml"));

        String[] users = {"u1", "u2", "admin"};
        for (String user : users) {
            assertEquals(200, ApproverFormFixtures.get(j, user, "batch-control/requests/" + id + "/").getStatusCode(),
                    "premise: " + user + " may open the request page");
        }

        List<String> paths = new ArrayList<>();
        for (String suffix : new String[] {".VALUES", ".Values", ".vAlUeS", ".values", ".xml"}) {
            paths.add("batch-control/requests/" + id + suffix + "/");
        }
        paths.add("batch-control/requests/no-such-id/");
        paths.add("batch-control/requests/no-such-id.VALUES/");

        try (LogRecorder log = new LogRecorder().record("", Level.WARNING).capture(500)) {
            for (String user : users) {
                for (String path : paths) {
                    WebResponse response = ApproverFormFixtures.get(j, user, path);
                    assertEquals(404, response.getStatusCode(), "S-39-01: " + user + " GET " + path + " must answer 404 like a missing"
                            + " request, never 500 (case-insensitive file system here: " + caseInsensitive + "): "
                            + UsabilityFixtures.excerpt(response.getContentAsString()));
                }
            }
            List<String> casts = new ArrayList<>();
            for (LogRecord record : log.getRecords()) {
                if (mentionsClassCast(record)) {
                    casts.add(record.getLevel() + " " + record.getMessage());
                }
            }
            assertTrue(casts.isEmpty(), "S-39-01: serving the suffixed ids must not read the values file as a request"
                    + " (no ClassCastException logged), got " + casts);
        }
    }

    private static boolean mentionsClassCast(LogRecord record) {
        if (String.valueOf(record.getMessage()).contains("ClassCastException")) {
            return true;
        }
        for (Throwable t = record.getThrown(); t != null; t = t.getCause()) {
            if (t instanceof ClassCastException) {
                return true;
            }
        }
        return false;
    }
}
