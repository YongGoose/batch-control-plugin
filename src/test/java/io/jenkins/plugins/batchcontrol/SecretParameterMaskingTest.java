package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.ParameterValue;
import hudson.model.ParametersAction;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.PasswordParameterDefinition;
import hudson.model.PasswordParameterValue;
import hudson.model.StringParameterDefinition;
import hudson.model.StringParameterValue;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.util.Secret;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import jenkins.model.Jenkins;
import org.htmlunit.WebResponse;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.DomNode;
import org.htmlunit.html.HtmlForm;
import org.htmlunit.html.HtmlInput;
import org.htmlunit.html.HtmlPage;
import org.htmlunit.html.HtmlSelect;
import org.htmlunit.html.HtmlTextArea;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.uncontrolled;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * T-SEC-07 and T-SEC-19 (SPEC section 6: "a secret parameter (Password parameter) is stored masked
 * in the history", ARCHITECTURE section 5: "{@code PasswordParameterValue} and {@code Secret}
 * values are stored as {@code ********}") under decision P-03 (owner ruling: keep the current
 * behaviour).
 *
 * <p>P-03 fixes two things at once, and this class pins both halves:
 *
 * <ul>
 *   <li><b>the guarantee</b> — the plaintext of a secret parameter is never persisted, rendered
 *       or exported. Not on the request detail screen, not in any file under
 *       {@code $JENKINS_HOME/batch-control/}, not in {@code requests.csv} / {@code runs.csv};</li>
 *   <li><b>the accepted limitation</b> — because the masking happens before the value is stored,
 *       an approved request cannot reproduce the original secret: the build that the approval
 *       launches receives the mask, not the secret. This is documented behaviour (README), not a
 *       defect, and it is asserted here so that a future "convenience" change which starts
 *       persisting recoverable secrets fails this class instead of passing it silently.</li>
 * </ul>
 *
 * <p>Every negative assertion below is paired with a positive twin on the <em>same</em> surface,
 * using a non-sensitive parameter of the same job (matrix row T-SEC-07, "false-positive guard"):
 * an implementation that stores, renders or exports nothing at all fails this class rather than
 * passing it. The secret parameter's <em>name</em> is also asserted to be present wherever the
 * plain one is, so "silently drop the whole secret parameter" is not a way to pass either.
 *
 * <p>Written from docs/SPEC.md, docs/ARCHITECTURE.md sections 2/5 and docs/DECISIONS.md (P-03)
 * only; no src/main knowledge.
 */
@WithJenkins
public class SecretParameterMaskingTest {

    /** The mask ARCHITECTURE section 5 and P-03 both name. */
    private static final String MASK = "********";

    private static final String SECRET_PARAM = "SECRET_TOKEN";
    private static final String PLAIN_PARAM = "PLAIN_PARAM";

    /** Distinctive enough that a substring match cannot collide with anything else. */
    private static final String SECRET_VALUE = "pl41nt3xt-s3cr3t-7b2c9e";
    /**
     * The Password parameter's <em>definition</em> default, a second distinctive plaintext. Jenkins
     * core renders a concealed password control with the sentinel {@code <DEFAULT>} rather than the
     * stored value, so a submission either carries the value this test types ({@link #SECRET_VALUE})
     * or resolves to this default. Both are forbidden everywhere, which is what makes the row
     * falsifiable without having to know which of the two core chose: an implementation that stops
     * masking leaks a non-empty plaintext on either path and fails.
     */
    private static final String SECRET_DEFAULT = "d3fault-s3cr3t-1a4b8c";

    /** Every plaintext that must never be persisted, rendered or exported. */
    private static final List<String> FORBIDDEN_PLAINTEXTS =
            Arrays.asList(SECRET_VALUE, SECRET_DEFAULT);
    private static final String PLAIN_VALUE = "plain-visible-9f3a1d";
    /** Differs from the definition default, so a stored value proves the form binding carried it. */
    private static final String PLAIN_DEFAULT = "plain-default-do-not-use";

    private JenkinsRule j;

    private BatchControlGlobalConfiguration cfg;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.REQUEST)
                        .everywhere().to("u1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE)
                        .everywhere().to("a1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.VIEW_HISTORY)
                        .everywhere().to("viewer"));

        cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
    }

    /**
     * T-SEC-07 (main path): a run request created through the per-job request form for a job that
     * has a Password parameter keeps no plaintext anywhere, while the job's non-sensitive
     * parameter stays fully visible on every surface.
     *
     * <p>The request is created through the screen a human uses (POST
     * {@code job/X/batch-control/submit} driven by the rendered form) rather than through
     * {@code RunRequestService}, because P-03 states the masking happens while the submitted
     * {@code ParameterValue}s are turned into the stored {@code Map<String,String>} — the service
     * API takes strings and can no longer tell which of them was sensitive.
     */
    @Test
    public void t_sec_07_secretParameterNeverAppearsInPlaintextWhilePlainParameterStaysVisible()
            throws Exception {
        FreeStyleProject job = j.createFreeStyleProject("secret-x");
        job.addProperty(new ParametersDefinitionProperty(
                new PasswordParameterDefinition(SECRET_PARAM,
                        Secret.fromString(SECRET_DEFAULT), "service token"),
                new StringParameterDefinition(PLAIN_PARAM, PLAIN_DEFAULT, "batch date")));
        setBatchControl(job, new BatchControlJobProperty(true));

        RunRequest request = submitRequestForm(job);

        // ---------- the stored request record (storage layer, model view)
        Map<String, String> stored = RunRequestService.get().load(request.getId()).getParameters();
        assertTrue(stored.containsKey(SECRET_PARAM), "the secret parameter must still be recorded by name (dropping it entirely is not"
                + " masking) - stored: " + stored.keySet());
        assertMasked("the stored request parameter", stored.get(SECRET_PARAM));
        assertEquals(PLAIN_VALUE, stored.get(PLAIN_PARAM), "the non-sensitive parameter must be stored verbatim, exactly as submitted"
                + " through the form (false-positive guard: this also proves the form"
                + " binding carried the values this test typed)");

        // ---------- the request detail screen (SPEC 5: the approver reviews the raw parameters)
        for (String userId : new String[] {"u1", "a1", "admin"}) {
            String detail = body(get(webClient(userId),
                    "batch-control/requests/" + request.getId() + "/"));
            assertNoSecret("the request detail screen seen by " + userId, detail);
            assertTrue(detail.contains(PLAIN_VALUE), "the request detail screen seen by " + userId + " must show the"
                    + " non-sensitive parameter value (else this row measures an empty"
                    + " screen)");
            assertTrue(detail.contains(SECRET_PARAM), "the request detail screen seen by " + userId + " must still name the"
                    + " secret parameter, so the approver can see that one was supplied");
        }

        // ---------- approve: the build runs (and the P-03 limitation becomes observable)
        try (ACLContext ignored = as("a1")) {
            RunRequestService.get().approve(request.getId(), "reviewed the raw parameters");
        }
        j.waitUntilNoActivity();

        FreeStyleBuild build = job.getBuildByNumber(1);
        assertNotNull(build, "the approved request must have produced build #1");
        ParametersAction parameters = build.getAction(ParametersAction.class);
        assertNotNull(parameters, "the launched build must carry its parameters");
        assertEquals(PLAIN_VALUE, plainTextOf(parameters.getParameter(PLAIN_PARAM)), "the non-sensitive parameter must reach the build exactly as requested"
                + " (SPEC 5), which is what makes the next assertion meaningful");
        // P-03, accepted limitation: the original secret is NOT reproduced on execution.
        String secretOnBuild = plainTextOf(parameters.getParameter(SECRET_PARAM));
        assertNotNull(secretOnBuild, "the secret parameter must still be passed to the build by name");
        assertFalse(FORBIDDEN_PLAINTEXTS.stream().anyMatch(secretOnBuild::contains), "P-03 (accepted limitation, README): the approved run must NOT be able to"
                + " reproduce the original secret - if this ever fails, a recoverable"
                + " plaintext is being persisted somewhere and the whole row must be"
                + " re-decided, not relaxed");
        assertTrue(secretOnBuild.contains(MASK), "the build must receive the mask for the secret parameter, was: " + secretOnBuild);

        // ---------- the storage layer as bytes on disk, and the CSV exports
        assertStoreHasNoPlaintext();
        assertCsvExportsCarryNoPlaintext();

        // ---------- the dashboard, which renders the recorded run parameters
        String dashboard = body(get(webClient("viewer"), "batch-control/dashboard/"));
        assertNoSecret("the dashboard", dashboard);
        assertTrue(dashboard.contains(PLAIN_VALUE), "the dashboard must show the recorded non-sensitive parameter value");
    }

    /**
     * T-SEC-19 (the recording path): the guarantee must not depend on the request screen. A build
     * started with a real {@link PasswordParameterValue} by any other path still gets recorded
     * masked — that is the ARCHITECTURE section 5 store rule ("PasswordParameterValue and Secret
     * are stored as ********"), and it is the surface that a job with a Password parameter hits
     * every time it runs without going through an approval (cron, upstream, an uncontrolled job).
     */
    @Test
    public void t_sec_19_runRecordOfADirectBuildMasksPasswordParameterValues() throws Exception {
        FreeStyleProject job = uncontrolled(j.createFreeStyleProject("secret-direct"));
        job.addProperty(new ParametersDefinitionProperty(
                new PasswordParameterDefinition(SECRET_PARAM,
                        Secret.fromString(SECRET_DEFAULT), "service token"),
                new StringParameterDefinition(PLAIN_PARAM, PLAIN_DEFAULT, "batch date")));

        j.assertBuildStatusSuccess(job.scheduleBuild2(0, (hudson.model.Cause) null,
                new ParametersAction(
                        new PasswordParameterValue(SECRET_PARAM, SECRET_VALUE),
                        new StringParameterValue(PLAIN_PARAM, PLAIN_VALUE))));
        j.waitUntilNoActivity();

        // the run record, as bytes on disk and through the two surfaces that publish it
        assertStoreHasNoPlaintext();

        String runsCsv = body(get(webClient("viewer"), "batch-control/history/runs.csv"));
        assertNoSecret("runs.csv", runsCsv);
        assertTrue(runsCsv.contains(PLAIN_VALUE), "runs.csv must export the non-sensitive parameter value of the direct build");
        assertTrue(runsCsv.contains(SECRET_PARAM), "runs.csv must still name the secret parameter of the direct build");

        String dashboard = body(get(webClient("viewer"), "batch-control/dashboard/"));
        assertNoSecret("the dashboard", dashboard);
        assertTrue(dashboard.contains(PLAIN_VALUE), "the dashboard must show the non-sensitive parameter of the direct build");
    }

    // ------------------------------------------------------------ request form driving

    /**
     * Fills in and submits the per-job run request form as {@code u1}, and returns the request it
     * created. Everything this method asserts is a fixture control: if the form does not render
     * the parameters the way a browser needs, the row would otherwise "pass" while measuring an
     * empty submission (an implementation that masks unconditionally cannot be distinguished from
     * one that never received the secret at all).
     */
    private RunRequest submitRequestForm(FreeStyleProject job) throws Exception {
        JenkinsRule.WebClient wc = webClient("u1");
        HtmlPage page = (HtmlPage) wc.getPage(new URL(j.getURL(), job.getUrl() + "batch-control/"));
        assertEquals(200, page.getWebResponse().getStatusCode(), "the run request form must render for a Request holder");

        HtmlForm form = page.getForms().stream()
                .filter(f -> f.getActionAttribute().contains("submit"))
                .findFirst()
                .orElse(null);
        assertNotNull(form, "the request screen must carry a form submitting to the request endpoint; forms: "
                + page.getForms().stream().map(HtmlForm::getActionAttribute)
                        .collect(Collectors.toList()));

        // The secret parameter must be offered through a concealed control (Jenkins core's
        // password widget: type=password, or the "complex-password-field" whose value the widget
        // replaces), never as a visible text box - a text box would already put the value on
        // screen. Typing into it is what a browser does when the user changes the password.
        HtmlInput secretInput = parameterValueInput(form, SECRET_PARAM);
        assertTrue(isConcealed(secretInput), SECRET_PARAM + " must be rendered by a concealed password control, not a"
                + " visible text box; the control was: " + excerpt(secretInput.asXml()));
        secretInput.setValue(SECRET_VALUE);
        assertEquals(SECRET_VALUE, secretInput.getValue(), "fixture: the concealed control must carry the typed secret, so that the"
                + " submission really puts a plaintext on the wire");

        HtmlInput plainInput = parameterValueInput(form, PLAIN_PARAM);
        assertFalse(isConcealed(plainInput), "fixture: " + PLAIN_PARAM + " is the non-sensitive control of this row and"
                + " must not be concealed, otherwise the two halves are not comparable");
        plainInput.setValue(PLAIN_VALUE);

        setReason(form, "month-end batch with a credential");
        selectApprover(form, "a1");

        int before = RunRequestService.get().list().size();
        j.submit(form);

        List<RunRequest> created = RunRequestService.get().list().stream()
                .filter(r -> job.getFullName().equals(r.getJobFullName()))
                .collect(Collectors.toList());
        assertEquals(before + 1, RunRequestService.get().list().size(), "the submitted form must have created exactly one run request");
        assertEquals(1, created.size());
        return created.get(0);
    }

    /**
     * The one value-carrying input of the given job parameter. Jenkins core renders each parameter
     * as a {@code <div name="parameter">} holding a hidden {@code name} input and the
     * {@code value} control; this picks the {@code value} control whose block identifies
     * {@code parameterName}, so the test can never type into an unrelated field.
     */
    private static HtmlInput parameterValueInput(HtmlForm form, String parameterName) {
        List<HtmlInput> matches = form.getElementsByTagName("input").stream()
                .filter(HtmlInput.class::isInstance)
                .map(HtmlInput.class::cast)
                .filter(input -> "value".equals(input.getAttribute("name")))
                .filter(input -> parameterName.equals(boundParameterName(input)))
                .collect(Collectors.toList());
        assertEquals(1, matches.size(), "fixture: the request form must offer exactly one input for the job parameter "
                + parameterName + " (found " + matches.size() + "). Form was: "
                + excerpt(form.asXml()));
        return matches.get(0);
    }

    /** True for Jenkins core's concealed password control (type=password or the password widget). */
    private static boolean isConcealed(HtmlInput input) {
        return "password".equalsIgnoreCase(input.getTypeAttribute())
                || input.getAttribute("class").contains("password");
    }

    /**
     * The parameter name a form control belongs to: the nearest ancestor that mentions exactly one
     * of the job's parameter names. Returns {@code null} when no ancestor identifies one, or when
     * the walk reaches a node covering several (it went past the control's own block).
     */
    private static String boundParameterName(DomElement input) {
        DomNode node = input;
        for (int depth = 0; depth < 8 && node != null; depth++) {
            String xml = node.asXml();
            List<String> hits = Stream.of(SECRET_PARAM, PLAIN_PARAM)
                    .filter(xml::contains)
                    .collect(Collectors.toList());
            if (hits.size() == 1) {
                return hits.get(0);
            }
            if (hits.size() > 1) {
                return null;
            }
            node = node.getParentNode();
        }
        return null;
    }

    /** The reason field may be a textarea or a text input; both are accepted. */
    private static void setReason(HtmlForm form, String reason) {
        for (DomElement element : form.getElementsByTagName("textarea")) {
            if (element instanceof HtmlTextArea && "reason".equals(element.getAttribute("name"))) {
                ((HtmlTextArea) element).setText(reason);
                return;
            }
        }
        for (DomElement element : form.getElementsByTagName("input")) {
            if (element instanceof HtmlInput && "reason".equals(element.getAttribute("name"))) {
                ((HtmlInput) element).setValue(reason);
                return;
            }
        }
        fail("the request form must carry a reason field (SPEC 5 makes the reason mandatory);"
                + " form was: " + excerpt(form.asXml()));
    }

    /** Selects the approver if the form offers a choice; a single-option control needs nothing. */
    private static void selectApprover(HtmlForm form, String approver) {
        for (DomElement element : form.getElementsByTagName("select")) {
            if (element instanceof HtmlSelect && "approver".equals(element.getAttribute("name"))) {
                ((HtmlSelect) element).setSelectedAttribute(approver, true);
                return;
            }
        }
        for (DomElement element : form.getElementsByTagName("input")) {
            if (element instanceof HtmlInput && "approver".equals(element.getAttribute("name"))) {
                HtmlInput input = (HtmlInput) element;
                if ("radio".equalsIgnoreCase(input.getTypeAttribute())) {
                    if (approver.equals(input.getValue())) {
                        input.setChecked(true);
                    }
                } else {
                    input.setValue(approver);
                }
            }
        }
    }

    // ------------------------------------------------------------ assertions

    /** A masked value: present, carrying the documented mask, and not the secret. */
    private static void assertMasked(String where, String value) {
        assertNotNull(value, where + " must hold a value");
        assertFalse(value.trim().isEmpty(), where + " must not be blank - masking replaces the value, it does not erase it");
        for (String plaintext : FORBIDDEN_PLAINTEXTS) {
            assertFalse(value.contains(plaintext), where + " must never contain a secret in plaintext, was: " + value);
        }
        assertTrue(value.contains(MASK), where + " must carry the documented mask " + MASK
                + " (ARCHITECTURE section 5), was: " + value);
    }

    private static void assertNoSecret(String where, String content) {
        for (String plaintext : FORBIDDEN_PLAINTEXTS) {
            int index = content.indexOf(plaintext);
            assertTrue(index < 0, where + " must not contain a secret parameter value in plaintext, but "
                    + plaintext + " appears at offset " + index + ": "
                    + excerpt(content.substring(Math.max(0, index - 120))));
        }
    }

    /**
     * The storage layer itself: no file under {@code $JENKINS_HOME/batch-control/} may contain the
     * plaintext. The paired positive assertion — the non-sensitive value must be found in at least
     * one of those files — is what keeps this from passing against an empty or wrong directory.
     */
    private void assertStoreHasNoPlaintext() throws IOException {
        File root = new File(j.jenkins.getRootDir(), "batch-control");
        assertTrue(root.isDirectory(), "fixture: the plugin store must live at $JENKINS_HOME/batch-control (ARCHITECTURE"
                + " section 5), but " + root + " is not a directory");

        List<String> offenders = new ArrayList<>();
        List<String> plainCarriers = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(root.toPath())) {
            for (Path path : paths.filter(Files::isRegularFile).collect(Collectors.toList())) {
                String content = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
                String relative = root.toPath().relativize(path).toString();
                for (String plaintext : FORBIDDEN_PLAINTEXTS) {
                    if (content.contains(plaintext)) {
                        offenders.add(relative + " (" + plaintext + ")");
                    }
                }
                if (content.contains(PLAIN_VALUE)) {
                    plainCarriers.add(relative);
                }
            }
        }
        assertTrue(offenders.isEmpty(), "no file under $JENKINS_HOME/batch-control may contain the secret in plaintext,"
                + " but these do: " + offenders);
        assertFalse(plainCarriers.isEmpty(), "false-positive guard: the store must actually hold the non-sensitive"
                + " parameter value somewhere - otherwise the scan above proves nothing"
                + " (files present: " + storeFileCount(root) + ")");
    }

    /** Both audit exports that carry parameters (SPEC 12) must be clean, and non-empty. */
    private void assertCsvExportsCarryNoPlaintext() throws Exception {
        JenkinsRule.WebClient viewer = webClient("viewer");
        for (String path : new String[] {
                "batch-control/history/requests.csv",
                "batch-control/history/runs.csv",
                "batch-control/history/changes.csv",
                "batch-control/history/incidents.csv"}) {
            assertNoSecret(path, body(get(viewer, path)));
        }
        String requestsCsv = body(get(viewer, "batch-control/history/requests.csv"));
        String runsCsv = body(get(viewer, "batch-control/history/runs.csv"));
        assertTrue(requestsCsv.contains(PLAIN_VALUE), "requests.csv must export the non-sensitive parameter value (else the"
                + " clean-export assertion measures an empty file)");
        assertTrue(requestsCsv.contains(SECRET_PARAM), "requests.csv must still name the secret parameter");
        assertTrue(runsCsv.contains(PLAIN_VALUE), "runs.csv must export the non-sensitive parameter value of the approved run");
        assertTrue(runsCsv.contains(SECRET_PARAM), "runs.csv must still name the secret parameter of the approved run");
    }

    // ------------------------------------------------------------ helpers

    private static long storeFileCount(File root) throws IOException {
        try (Stream<Path> paths = Files.walk(root.toPath())) {
            return paths.filter(Files::isRegularFile).count();
        }
    }

    /** Plaintext of a parameter value, whatever concrete type carries it. */
    private static String plainTextOf(ParameterValue parameter) {
        if (parameter == null) {
            return null;
        }
        Object value = parameter.getValue();
        if (value instanceof Secret) {
            return ((Secret) value).getPlainText();
        }
        return String.valueOf(value);
    }

    private ACLContext as(String userId) {
        return ACL.as2(User.getById(userId, true).impersonate2());
    }

    private JenkinsRule.WebClient webClient(String userId) throws Exception {
        return j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login(userId);
    }

    private WebResponse get(JenkinsRule.WebClient wc, String relative) throws Exception {
        return wc.getPage(new URL(j.getURL(), relative)).getWebResponse();
    }

    private static String body(WebResponse response) {
        assertTrue(response.getStatusCode() < 400, "the surface under test must be readable, got "
                + response.getStatusCode());
        return response.getContentAsString();
    }

    private static String excerpt(String text) {
        return text.length() <= 600 ? text : text.substring(0, 600) + "...";
    }
}
