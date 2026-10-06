package io.jenkins.plugins.batchcontrol;

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.model.GrantAction;
import io.jenkins.plugins.batchcontrol.model.GrantRequest;
import io.jenkins.plugins.batchcontrol.model.GrantScope;
import io.jenkins.plugins.batchcontrol.policy.GrantRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlMatrixAuthorizationStrategy;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.htmlunit.html.HtmlPage;
import org.htmlunit.util.NameValuePair;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.excerpt;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.grantRequestIds;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage lane 2, scenario L2-07 first bullet (matrix row T-GAP-222, note 277): a CREATE request's
 * name restriction is validated when the request is submitted.
 *
 * <p>Basis: SPEC item 8, the D-40 line: "A CREATE request may carry an optional name restriction
 * (form field {@code createNamePattern}): an exact item name, or a Java regular expression written as
 * {@code /regex/}. It is validated at submission (an invalid regex is refused) ... Names longer than 255
 * characters are refused"; SPEC 6 usability "invalid input is refused with a message next to the
 * field and the user's input is kept". The second bullet (a stored restriction edited into an invalid
 * regular expression) needs a restart and is {@link WindowStoredFileGapTest}.
 *
 * <p>Written from docs/SPEC.md, docs/DECISIONS.md D-40/D-40a and docs/TEST-MATRIX.md only (no src/main
 * knowledge).
 */
@WithJenkins
public class WindowNameRestrictionGapTest {

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(StrategyFixtures.matrix(new BatchControlMatrixAuthorizationStrategy()));
        StrategyFixtures.changeControlOn();
        j.jenkins.createProject(Folder.class, "team");
    }

    /**
     * T-GAP-222 (L2-07, SPEC 8 D-40): a CREATE request on folder {@code team} whose restriction is 1,001
     * characters long, is {@code //}, is an exact name of 256 characters, or is an exact name that is
     * not a valid item name ({@code a/b}, {@code ..}) is refused at submission: through the service
     * (an exception, nothing stored) and through the form (4xx, the form again with a message and the
     * typed reason kept, no crash page, nothing stored). Guard: {@code /ok-.*}{@code /} and the exact
     * name {@code ok-1} are accepted and stored as submitted.
     */
    @Test
    public void t_gap_222_invalidRestrictionsAreRefusedAtSubmission() throws Exception {
        String[] invalid = {
            "/" + "a".repeat(999) + "/",
            "//",
            "n".repeat(256),
            "a/b",
            "..",
        };
        assertEquals(1001, invalid[0].length(), "fixture: a restriction of 1,001 characters");
        for (String pattern : invalid) {
            String shown = pattern.length() > 20 ? pattern.substring(0, 20) + "...(" + pattern.length() + ")" : pattern;
            int before = GrantRequestService.get().list().size();
            boolean refused = false;
            try (ACLContext ignored = ACL.as2(User.getById("bob", true).impersonate2())) {
                GrantRequestService.get().create(new GrantScope(GrantScope.Type.ITEM, "team"), Arrays.asList(GrantAction.CREATE),
                        30, "a new job in team", Collections.singletonList("a1"), pattern);
            } catch (RuntimeException expected) {
                refused = true;
                assertNotNull(expected.getMessage(), "the refusal of '" + shown + "' must carry a message");
            }
            assertTrue(refused, "the service must refuse the restriction '" + shown + "' at submission");
            assertEquals(before, GrantRequestService.get().list().size(), "nothing may be stored for '" + shown + "'");
            assertFormRefuses(pattern, shown);
        }

        for (String pattern : new String[] {"/ok-.*/", "ok-1"}) {
            GrantRequest request;
            try (ACLContext ignored = ACL.as2(User.getById("bob", true).impersonate2())) {
                request = GrantRequestService.get().create(new GrantScope(GrantScope.Type.ITEM, "team"),
                        Arrays.asList(GrantAction.CREATE), 30, "a new job in team", Collections.singletonList("a1"), pattern);
            }
            assertEquals(pattern, GrantRequestService.get().load(request.getId()).getCreateNamePattern(),
                    "guard: the valid restriction '" + pattern + "' is stored as submitted");
        }
    }

    private void assertFormRefuses(String pattern, String shown) throws Exception {
        String reason = "typed reason for " + shown.replace("/", "");
        Set<String> before = grantRequestIds();
        JenkinsRule.WebClient wc = UsabilityFixtures.clientNoJs(j, "bob");
        WebRequest request = new WebRequest(wc.createCrumbedUrl("batch-control/grants/create"), HttpMethod.POST);
        List<NameValuePair> params = Arrays.asList(new NameValuePair("scopeFullName", "team"), new NameValuePair("actions", "CREATE"),
                new NameValuePair("durationMinutes", "30"), new NameValuePair("reason", reason),
                new NameValuePair("createNamePattern", pattern), new NameValuePair("approvers", "a1"));
        request.setRequestParameters(params);
        Page answer = wc.getPage(request);
        int code = answer.getWebResponse().getStatusCode();
        assertTrue(code >= 400 && code < 500, "the form must refuse the restriction '" + shown + "' with 4xx, got " + code + ": "
                + excerpt(answer.getWebResponse().getContentAsString()));
        assertEquals(before, grantRequestIds(), "the refused form submission of '" + shown + "' stores nothing");
        assertTrue(answer instanceof HtmlPage, "the refusal of '" + shown + "' must be an HTML page");
        HtmlPage page = (HtmlPage) answer;
        UsabilityFixtures.assertPlainRefusal("restriction '" + shown + "'", page.asNormalizedText(), null);
        assertFalse(UsabilityFixtures.formsEndingWith(page, "batch-control/grants/create").isEmpty(),
                "the refusal of '" + shown + "' must show the grant request form again");
        assertTrue(UsabilityFixtures.pageKeepsValue(page, reason), "the typed reason must be kept for '" + shown + "'");
    }
}
