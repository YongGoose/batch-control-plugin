package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.Result;
import hudson.model.StringParameterDefinition;
import hudson.model.StringParameterValue;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.Incident;
import io.jenkins.plugins.batchcontrol.ops.IncidentService;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import jenkins.model.Jenkins;
import org.htmlunit.WebResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.FailureBuilder;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.ActivationFixtures.submitActivationOk;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.assertSuccess;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.decideGrant;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.excerpt;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.submitGrantOk;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * security-39 S-39-01, beyond the values-file suffix of T-SEC-91: a record's detail URL answers 404
 * for anything that is not the record's own identifier, and for a stored file that is not a valid
 * record of that store, never 500 and never another record's page. CLAUDE.md: "Anywhere user input
 * ends up in a file path ... it must be validated"; SPEC item 8 (D-68): records get UUID identifiers
 * (earlier-format identifiers still load); SPEC section 6 (usability: no bare error page from our own
 * code); P-09 (an unknown record answers 404). Matrix rows T-SEC-98 (aliases of an identifier) and
 * T-SEC-99 (corrupt and foreign files) (note 275).
 *
 * <p>Records: u1's PENDING run request on the approval-required job {@code vals} (a string parameter,
 * so it has a values file), u1's PENDING grant request and an active window (CONFIGURE, approved by
 * a1), u1's PENDING activation request, and the incident of a failed build of {@code boom}. Each
 * detail URL answers 200 to the administrator first (premise). The administrator is the viewer of
 * every row: no visibility rule hides a record from them, so a 404 here can only come from the
 * lookup itself.
 *
 * <p>The stored files are planted under the directories ARCHITECTURE section 5 names
 * ({@code requests/run}, {@code requests/grant}, {@code activation-requests}, {@code incidents}) with
 * a fresh, well-formed UUID as file name, so the identifier's shape cannot be what refuses them.
 *
 * <p>Written from docs/SPEC.md items 6, 8 and 11, docs/DECISIONS.md D-68 and D-74, CLAUDE.md,
 * docs/ARCHITECTURE.md section 5 and docs/reports/security-39.md S-39-01 only (no src/main
 * knowledge).
 */
@WithJenkins
public class RecordLookupPathTest {

    private JenkinsRule j;
    private Path store;
    private String runId;
    private String grantRequestId;
    private String windowId;
    private String activationId;
    private String incidentId;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.REQUEST, BatchControlPermissions.REQUEST_GRANT).everywhere().to("u1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1"));
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setChangeControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
        store = j.jenkins.getRootDir().toPath().resolve("batch-control");

        FreeStyleProject vals = j.createFreeStyleProject("vals");
        vals.addProperty(new ParametersDefinitionProperty(new StringParameterDefinition("TARGET", "default-target")));
        setBatchControl(vals, new BatchControlJobProperty(true));
        try (ACLContext ignored = ACL.as2(User.getById("u1", true).impersonate2())) {
            runId = RunRequestService.get().create(vals, List.of(new StringParameterValue("TARGET", "staging")), "month-end batch", "a1").getId();
        }
        grantRequestId = submitGrantOk(j, "u1", "vals", List.of("CONFIGURE"), 30, "pending window", null, "a1");
        j.createFreeStyleProject("cfg-job");
        String approved = submitGrantOk(j, "u1", "cfg-job", List.of("CONFIGURE"), 30, "approved window", null, "a1");
        assertSuccess(decideGrant(j, "a1", approved, "approve", "ok"), "fixture: approval by a1");
        windowId = WindowStateFixtures.windowId("u1", "cfg-job");
        activationId = submitActivationOk(j, "u1", vals, "ACTIVATE", "bring vals into service", "a1");

        FreeStyleProject boom = j.createFreeStyleProject("boom");
        boom.getBuildersList().add(new FailureBuilder());
        BatchControlFixtures.activateAsAdmin(boom); // D-46: a cause-less submission needs an activation
        j.assertBuildStatus(Result.FAILURE, boom.scheduleBuild2(0));
        j.waitUntilNoActivity();
        Incident incident = IncidentService.get().list(YearMonth.now(BatchClock.clock())).stream()
                .filter(i -> "boom#1".equals(i.getRunId())).findFirst().orElse(null);
        assertNotNull(incident, "fixture: the failed build of boom opened an incident (SPEC 11)");
        incidentId = incident.getId();

        for (Map.Entry<String, String> page : detailPages().entrySet()) {
            WebResponse response = ApproverFormFixtures.get(j, "admin", page.getValue());
            assertEquals(200, response.getStatusCode(), "premise: the administrator opens the " + page.getKey() + " page " + page.getValue()
                    + ": " + excerpt(response.getContentAsString()));
        }
    }

    /**
     * T-SEC-98 (S-39-01, D-68): for the run request, the grant request, the window, the activation
     * request and the incident, the administrator's GET of the detail URL with the identifier in
     * upper case ({@code <ID>/}, an alias of the same file on a case-insensitive file system), with
     * the 8.3-style alias {@code <id>~1/} and with {@code x.y/} answers 404, never 500 and never the
     * record's page. Asserted on any file system; the message says whether this one is
     * case-insensitive.
     */
    @Test
    public void t_sec_98_aliasesOfARecordIdAnswer404() throws Exception {
        boolean caseInsensitive = Files.exists(store.resolve("requests/run/" + runId.toUpperCase(Locale.ROOT) + ".xml"));
        Map<String, String> sections = new LinkedHashMap<>();
        sections.put("batch-control/requests/", runId);
        sections.put("batch-control/grants/", grantRequestId);
        sections.put("batch-control/grants/ ", windowId); // the window's page is under grants/ too (key padded to stay unique)
        sections.put("batch-control/activations/", activationId);
        sections.put("batch-control/incidents/", incidentId);

        List<String> failures = new ArrayList<>();
        for (Map.Entry<String, String> section : sections.entrySet()) {
            String base = section.getKey().trim();
            String id = section.getValue();
            List<String> aliases = new ArrayList<>();
            String upper = id.toUpperCase(Locale.ROOT);
            if (!upper.equals(id)) {
                aliases.add(upper);
            }
            aliases.add(id + "~1");
            aliases.add("x.y");
            for (String alias : aliases) {
                WebResponse response = ApproverFormFixtures.get(j, "admin", base + alias + "/");
                if (response.getStatusCode() != 404) {
                    failures.add("GET " + base + alias + "/ -> HTTP " + response.getStatusCode() + ": " + excerpt(response.getContentAsString()));
                }
            }
        }
        assertTrue(failures.isEmpty(), "S-39-01: an alias of a record id must answer 404 like an unknown id, never 500 or the record's page"
                + " (case-insensitive file system here: " + caseInsensitive + "): " + failures);
    }

    /**
     * T-SEC-99 (S-39-01 fix direction (3) and (4), SPEC 6 usability): planted under fresh UUID names,
     * a truncated copy of a stored record of the same store (corrupt XML) and a copy of a record of
     * another type with its id replaced by the planted one (a run request in {@code requests/grant},
     * {@code activation-requests}; a grant request in {@code requests/run} and {@code incidents};
     * the run request's values file, {@code RunRequestValues}, in {@code requests/run}) answer 404 at
     * their detail URL to the administrator, never 500. Guard: every real record's page still
     * answers 200 and each section's list page answers 200 afterwards.
     */
    @Test
    public void t_sec_99_corruptOrForeignRecordFilesAnswer404() throws Exception {
        String run = read("requests/run/" + runId + ".xml");
        String values = read("requests/run/" + runId + ".values.xml");
        String grantRequest = read("requests/grant/" + grantRequestId + ".xml");
        String activation = read("activation-requests/" + activationId + ".xml");
        String incident = read("incidents/" + incidentId + ".xml");

        Map<String, String> planted = new LinkedHashMap<>();
        planted.put(plant("requests/run", truncated(run), null), "batch-control/requests/");
        planted.put(plant("requests/run", grantRequest, grantRequestId), "batch-control/requests/");
        planted.put(plant("requests/run", values, runId), "batch-control/requests/");
        planted.put(plant("requests/grant", truncated(grantRequest), null), "batch-control/grants/");
        planted.put(plant("requests/grant", run, runId), "batch-control/grants/");
        planted.put(plant("activation-requests", truncated(activation), null), "batch-control/activations/");
        planted.put(plant("activation-requests", run, runId), "batch-control/activations/");
        planted.put(plant("incidents", truncated(incident), null), "batch-control/incidents/");
        planted.put(plant("incidents", grantRequest, grantRequestId), "batch-control/incidents/");

        List<String> failures = new ArrayList<>();
        for (Map.Entry<String, String> p : planted.entrySet()) {
            String path = p.getValue() + p.getKey() + "/";
            WebResponse response = ApproverFormFixtures.get(j, "admin", path);
            if (response.getStatusCode() != 404) {
                failures.add("GET " + path + " -> HTTP " + response.getStatusCode() + ": " + excerpt(response.getContentAsString()));
            }
        }
        assertTrue(failures.isEmpty(), "S-39-01: a stored file that is not a valid record of its store must answer 404 at its detail URL,"
                + " never 500: " + failures);

        for (Map.Entry<String, String> page : detailPages().entrySet()) {
            assertEquals(200, ApproverFormFixtures.get(j, "admin", page.getValue()).getStatusCode(),
                    "guard: the real " + page.getKey() + " page still opens after the planting");
        }
        for (String list : new String[] {"batch-control/requests/", "batch-control/grants/", "batch-control/activations/", "batch-control/incidents/"}) {
            WebResponse response = ApproverFormFixtures.get(j, "admin", list);
            assertEquals(200, response.getStatusCode(), "guard: the list page " + list + " still opens with the planted files in its store: "
                    + excerpt(response.getContentAsString()));
        }
    }

    // ---------------------------------------------------------------- helpers

    private Map<String, String> detailPages() {
        Map<String, String> pages = new LinkedHashMap<>();
        pages.put("run request", "batch-control/requests/" + runId + "/");
        pages.put("grant request", "batch-control/grants/" + grantRequestId + "/");
        pages.put("window", "batch-control/grants/" + windowId + "/");
        pages.put("activation request", "batch-control/activations/" + activationId + "/");
        pages.put("incident", "batch-control/incidents/" + incidentId + "/");
        return pages;
    }

    private String read(String relative) throws Exception {
        Path file = store.resolve(relative);
        assertTrue(Files.isRegularFile(file), "premise (ARCHITECTURE 5): the record is stored at " + file);
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    /**
     * Writes {@code content} as {@code <dir>/<fresh UUID>.xml} and returns the UUID. When
     * {@code ownId} is given, {@code content} is a record of another store and every occurrence of
     * its own id is replaced by the planted UUID, so only its type is wrong.
     */
    private String plant(String dir, String content, String ownId) throws Exception {
        String id = UUID.randomUUID().toString();
        if (ownId != null) {
            assertTrue(content.contains(ownId), "premise: the copied record names its id " + ownId);
            content = content.replace(ownId, id);
        }
        Path file = store.resolve(dir).resolve(id + ".xml");
        Files.writeString(file, content, StandardCharsets.UTF_8);
        assertTrue(Files.isRegularFile(file), "fixture: planted " + file);
        return id;
    }

    private static String truncated(String xml) {
        return xml.substring(0, xml.length() / 2);
    }
}
