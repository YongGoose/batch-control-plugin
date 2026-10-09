package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.StringParameterDefinition;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.WebResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bug hunt B, R4-02 (matrix row T-06-107, note 294): a scripted build submission of an
 * approval-required job (curl, python requests, any API-token client) is refused with an HTTP 4xx
 * that carries the plain approval-required message, not a 303 to the HTML Request Run form.
 *
 * <p>Basis: SPEC 6 ("차단 시 사용자에게 '승인 필요' 안내와 요청 화면 링크가 표시된다(조용한 실패 금지)" and
 * D-60: the refusal leads to the prefilled Request Run form for "{@code /job/X/buildWithParameters}
 * from a browser") and the frozen bug-hunt contract: a refused submission whose {@code Accept}
 * header does not include {@code text/html} gets a 4xx with the plain approval-required message (no
 * redirect) and nothing is queued; one whose {@code Accept} includes {@code text/html} keeps the 303
 * to the prefilled Request Run form. A 303 answers a script with a page it cannot use and lets
 * {@code curl -fL} exit 0 although nothing was queued: a silent failure.
 *
 * <p>Users: {@code u1} requester (Item/Read, Item/Build, BatchControl/Request), {@code a1} approver.
 * The scripted calls authenticate with u1's API token over basic authentication (exempt from the
 * crumb), through {@link java.net.http.HttpClient}, which sends no {@code Accept} header unless told
 * to and never follows redirects here.
 *
 * <p>Written from docs/SPEC.md item 6, DECISIONS D-60 and the bug-hunt B contract only (no src/main
 * knowledge).
 */
@WithJenkins
public class RefusedScriptedBuildTest {

    /** The Accept header a browser sends for a page (HtmlUnit's and the major browsers' shape). */
    private static final String BROWSER_ACCEPT = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8";

    private JenkinsRule j;
    private FreeStyleProject job;

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
        cfg.setApprovers(List.of("a1"));
        cfg.save();

        job = j.createFreeStyleProject("scripted-x");
        job.addProperty(new ParametersDefinitionProperty(new StringParameterDefinition("P", "p-default")));
        setBatchControl(job, new BatchControlJobProperty(true));
    }

    /**
     * T-06-107 (R4-02): u1's API token POSTs {@code buildWithParameters?P=scripted} and {@code build}
     * with a {@code json} parameter form, each without an {@code Accept} header, with
     * {@code Accept: *}{@code /*} (curl's and python requests' default) and with
     * {@code Accept: application/json}. Every answer is a 4xx without {@code Location} whose body names
     * the approval requirement; nothing is queued, no build number is used and no run request is
     * stored. Guards first: the browser paths still answer 303 to the job's Request Run form with
     * {@code p.P} carried (D-60), both u1's signed-in HtmlUnit session and the same API token with a
     * browser {@code Accept} header, and they queue and store nothing either.
     */
    @Test
    public void t_06_107_scriptedRefusalAnswers4xxWithoutRedirect() throws Exception {
        Set<String> before = ApproverFormFixtures.runRequestIds();
        int nextBuild = job.getNextBuildNumber();
        String token = RawHttpFixtures.apiToken("u1");

        // Guard 1: u1's browser session (HtmlUnit sends a text/html Accept header), as T-06-101.
        JenkinsRule.WebClient wc = ApproverFormFixtures.client(j, "u1");
        URL crumbed = new URL(wc.createCrumbedUrl(job.getUrl() + "buildWithParameters").toExternalForm() + "&P=from-browser");
        WebResponse browser = wc.getPage(new WebRequest(crumbed, HttpMethod.POST)).getWebResponse();
        assertRedirectToRequestForm("guard: u1's refused buildWithParameters from a signed-in browser",
                browser.getStatusCode(), browser.getResponseHeaderValue("Location"), "from-browser",
                browser.getContentAsString());
        assertNothingQueuedOrStored("after the browser guard", before, nextBuild);

        // Guard 2: the same API token with a browser Accept header still gets the D-60 redirect.
        HttpResponse<String> tokenAsBrowser = send(buildWithParameters(token, "P=token-browser"), BROWSER_ACCEPT);
        assertRedirectToRequestForm("guard: an API-token buildWithParameters with Accept " + BROWSER_ACCEPT,
                tokenAsBrowser.statusCode(), tokenAsBrowser.headers().firstValue("Location").orElse(null),
                "token-browser", tokenAsBrowser.body());
        assertNothingQueuedOrStored("after the API-token browser guard", before, nextBuild);

        // The contract: every scripted submission is a plain 4xx refusal, no redirect.
        List<Executable> checks = new ArrayList<>();
        for (String accept : Arrays.asList(null, "*/*", "application/json")) {
            String acceptLabel = accept == null ? "no Accept header" : "Accept " + accept;
            HttpResponse<String> bwp = send(buildWithParameters(token, "P=scripted"), accept);
            checks.add(() -> assertPlainRefusal("buildWithParameters?P=scripted with " + acceptLabel, bwp));
            HttpResponse<String> buildJson = send(buildWithJson(token), accept);
            checks.add(() -> assertPlainRefusal("build with a json parameter form with " + acceptLabel, buildJson));
        }
        assertAll("a refused scripted build of an approval-required job must answer 4xx with the approval-required"
                + " message, not a 303 to the HTML Request Run form (curl -fL would exit 0 with nothing queued)", checks);
        assertNothingQueuedOrStored("after the scripted submissions", before, nextBuild);
    }

    // ---------------------------------------------------------------- helpers

    private HttpRequest.Builder buildWithParameters(String token, String query) throws Exception {
        return HttpRequest.newBuilder(URI.create(j.getURL() + job.getUrl() + "buildWithParameters?" + query))
                .header("Authorization", RawHttpFixtures.basic("u1", token))
                .POST(HttpRequest.BodyPublishers.noBody());
    }

    private HttpRequest.Builder buildWithJson(String token) throws Exception {
        String json = "{\"parameter\":[{\"name\":\"P\",\"value\":\"scripted-json\"}]}";
        String body = "json=" + URLEncoder.encode(json, StandardCharsets.UTF_8);
        return HttpRequest.newBuilder(URI.create(j.getURL() + job.getUrl() + "build"))
                .header("Authorization", RawHttpFixtures.basic("u1", token))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
    }

    private static HttpResponse<String> send(HttpRequest.Builder request, String accept) throws Exception {
        if (accept != null) {
            request.header("Accept", accept);
        }
        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        return http.send(request.timeout(Duration.ofSeconds(60)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private void assertRedirectToRequestForm(String what, int status, String location, String carried, String body)
            throws Exception {
        assertEquals(303, status, what + " must answer 303 to the Request Run form (D-60): " + UsabilityFixtures.excerpt(body));
        assertNotNull(location, what + ": the 303 must carry a Location");
        URL target = new URL(j.getURL(), location);
        assertEquals(new URL(j.getURL(), job.getUrl() + "batch-control/").getPath(), target.getPath(),
                what + ": the redirect must lead to the job's Request Run form, got " + location);
        Map<String, String> query = TypedParameterFixtures.query(target);
        assertEquals(carried, query.get("p.P"), what + ": the submitted value must be carried, got " + location);
    }

    private static void assertPlainRefusal(String what, HttpResponse<String> answer) {
        int status = answer.statusCode();
        Optional<String> location = answer.headers().firstValue("Location");
        assertTrue(status >= 400 && status < 500, what + ": must be refused with HTTP 4xx, got HTTP " + status
                + location.map(l -> " Location " + l).orElse("") + ": " + UsabilityFixtures.excerpt(answer.body()));
        assertFalse(location.isPresent(), what + ": the refusal must not redirect, got Location " + location.orElse(""));
        assertTrue(answer.body() != null && answer.body().toLowerCase(Locale.ROOT).contains("approv"),
                what + ": the refusal must carry the approval-required message: " + UsabilityFixtures.excerpt(answer.body()));
    }

    private void assertNothingQueuedOrStored(String when, Set<String> before, int nextBuild) throws Exception {
        assertTrue(j.jenkins.getQueue().isEmpty(), when + ": the queue must be empty");
        j.waitUntilNoActivity();
        assertTrue(job.getBuilds().isEmpty(), when + ": no build may run");
        assertEquals(nextBuild, job.getNextBuildNumber(), when + ": the next build number must be unchanged");
        assertEquals(before, ApproverFormFixtures.runRequestIds(), when + ": no run request may be stored");
    }
}
