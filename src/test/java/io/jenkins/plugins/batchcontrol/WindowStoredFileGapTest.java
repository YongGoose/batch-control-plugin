package io.jenkins.plugins.batchcontrol;

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.model.Item;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.security.HudsonPrivateSecurityRealm;
import io.jenkins.plugins.batchcontrol.model.Grant;
import io.jenkins.plugins.batchcontrol.model.GrantAction;
import io.jenkins.plugins.batchcontrol.model.GrantRequest;
import io.jenkins.plugins.batchcontrol.model.GrantScope;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.policy.GrantRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlMatrixAuthorizationStrategy;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.WebResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.JenkinsSessionExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage lane 2, scenarios L2-07 (F) and L2-08 (F) (matrix rows T-GAP-223 and T-GAP-229, note 277):
 * stored window and request files edited on disk are read fail-safe after a restart.
 *
 * <p>Basis: SPEC item 8, the D-40 line ("It is validated at submission (an invalid regex is refused)
 * ... With a restriction, the grant lets its holder create only items ... whose name matches in
 * full"); the D-71 line ("The request records the item's kind ...; approval is refused when no item
 * exists at that name any more or its kind has changed"; "a request for CREATE or DELETE on an item
 * kind where it cannot apply is refused at submission"); the D-74 line ("The stored scope is the
 * item's canonical full name, whatever spelling was typed"); LIMITATIONS 11 (the same); ARCHITECTURE
 * section 5 (the file layout {@code grants/<id>.xml}, {@code requests/grant/<id>.xml}; an in-memory
 * index of requests and grants is built once per session at startup, hence the restart). The files are
 * edited between two sessions of the same JENKINS_HOME; each edit is made from the file the plugin
 * wrote, and its premise (the replaced text occurs exactly once) is asserted.
 *
 * <p>Written from docs/SPEC.md, docs/DECISIONS.md, docs/LIMITATIONS.md, docs/ARCHITECTURE.md and
 * docs/TEST-MATRIX.md only (no src/main knowledge).
 */
public class WindowStoredFileGapTest {

    @RegisterExtension
    final JenkinsSessionExtension session = new JenkinsSessionExtension();

    private String restrictedWindowId;
    private final Map<String, String> requestIds = new LinkedHashMap<>();

    /**
     * T-GAP-223 (L2-07 (F), D-40 fail-safe): bob holds a CREATE window on folder {@code ff} restricted to
     * {@code /ok-.*}{@code /} (premise: {@code ff/ok-0} is created through it) and an unrestricted CREATE
     * window on folder {@code gg}. Between sessions the restriction in the window's file is replaced by
     * the invalid regular expression {@code /ok-(}{@code /}. After the restart bob can create no item in
     * {@code ff} ({@code ok-1} matches the former restriction, {@code any} matches nothing): each refused
     * with 4xx and nothing created. Guard: bob still creates {@code gg/other} through his other window.
     */
    @Test
    public void t_gap_223_storedRestrictionEditedIntoAnInvalidRegexConfersNothing() throws Throwable {
        session.then(r -> {
            setUp(r);
            r.jenkins.createProject(Folder.class, "ff");
            r.jenkins.createProject(Folder.class, "gg");
            restrictedWindowId = window(r, "ff", "/ok-.*/").getId();
            window(r, "gg", null);
            assertTrue(createItem(r, "bob", "ff", "ok-0") < 400, "premise: the restricted window admits ok-0");
            assertNotNull(r.jenkins.getItemByFullName("ff/ok-0"), "premise: ff/ok-0 exists");
            Path file = store(r).resolve("grants/" + restrictedWindowId + ".xml");
            replaceOnce(file, "<createNamePattern>/ok-.*/</createNamePattern>", "<createNamePattern>/ok-(/</createNamePattern>");
        });
        session.then(r -> {
            for (String name : new String[] {"ok-1", "any"}) {
                int code = createItem(r, "bob", "ff", name);
                assertTrue(code >= 400 && code < 500, "with an invalid stored restriction the creation of ff/" + name
                        + " must be refused with 4xx, got " + code);
                assertNull(r.jenkins.getItemByFullName("ff/" + name), "no item ff/" + name + " may be created");
            }
            assertTrue(createItem(r, "bob", "gg", "other") < 400, "guard: bob's other window still admits a creation in gg");
            assertNotNull(r.jenkins.getItemByFullName("gg/other"), "guard: gg/other exists");
        });
    }

    /**
     * T-GAP-229 (L2-08 (F), D-71, D-74): three PENDING requests by bob, edited between sessions: one
     * loses its item kind ({@code <itemKind>} removed), one asks CREATE on a job (its CONFIGURE replaced
     * by CREATE), one names its item in a non-canonical spelling ({@code jz} written as {@code JZ}).
     * After the restart a1's approval of each is refused with a message, the request stays PENDING and
     * bob has no window. Guard: an intact request next to them is approved and its window confers
     * Configure.
     */
    @Test
    public void t_gap_229_editedStoredRequestsCannotBeApproved() throws Throwable {
        session.then(r -> {
            setUp(r);
            for (String name : new String[] {"jx", "jy", "jz", "jok"}) {
                r.createFreeStyleProject(name);
                requestIds.put(name, request(r, name).getId());
            }
            Path dir = store(r).resolve("requests/grant");
            String kind = Files.readString(dir.resolve(requestIds.get("jx") + ".xml"), StandardCharsets.UTF_8);
            int from = kind.indexOf("<itemKind>");
            int to = kind.indexOf("</itemKind>") + "</itemKind>".length();
            assertTrue(from > 0 && to > from, "premise: the request file records the item kind: " + kind);
            Files.writeString(dir.resolve(requestIds.get("jx") + ".xml"), kind.substring(0, from) + kind.substring(to),
                    StandardCharsets.UTF_8);
            replaceOnce(dir.resolve(requestIds.get("jy") + ".xml"),
                    ">CONFIGURE</io.jenkins.plugins.batchcontrol.model.GrantAction>",
                    ">CREATE</io.jenkins.plugins.batchcontrol.model.GrantAction>");
            replaceOnce(dir.resolve(requestIds.get("jz") + ".xml"), "<fullName>jz</fullName>", "<fullName>JZ</fullName>");
        });
        session.then(r -> {
            for (String name : new String[] {"jx", "jy", "jz"}) {
                String id = requestIds.get(name);
                WebResponse approval = ApproverFormFixtures.decideGrant(r, "a1", id, "approve", "ok");
                assertTrue(approval.getStatusCode() >= 400 && approval.getStatusCode() < 500,
                        "the edited request on " + name + " must not be approved, got " + approval.getStatusCode() + ": "
                                + ApproverFormFixtures.excerpt(approval.getContentAsString()));
                String text = RenameRefusalFixtures.visible(approval.getContentAsString());
                assertFalse(text.isBlank(), "the refusal on " + name + " must carry a message");
                UsabilityFixtures.assertPlainRefusal("approval of the edited request on " + name, text, null);
                GrantRequest stored = GrantRequestService.get().load(id);
                assertNotNull(stored, "the edited request on " + name + " still loads");
                assertEquals(RequestStatus.PENDING, stored.getStatus(), "the edited request on " + name + " stays PENDING");
                Item item = r.jenkins.getItemByFullName(name);
                assertFalse(StrategyFixtures.has(item, "bob", Item.CONFIGURE), "no window may confer anything on " + name);
            }
            ApproverFormFixtures.assertSuccess(ApproverFormFixtures.decideGrant(r, "a1", requestIds.get("jok"), "approve", "ok"),
                    "guard: the intact request is approved");
            assertTrue(StrategyFixtures.has(r.jenkins.getItemByFullName("jok"), "bob", Item.CONFIGURE),
                    "guard: the intact request's window confers Configure");
        });
    }

    // ---------------------------------------------------------------- helpers

    /** Jenkins' own user database (persisted across the restart), the Batch Control matrix strategy, change control on. */
    private static void setUp(JenkinsRule r) throws Exception {
        HudsonPrivateSecurityRealm realm = new HudsonPrivateSecurityRealm(false, false, null);
        for (String id : new String[] {"admin", "alice", "bob", "carol", "a1", "m1", "c1"}) {
            realm.createAccount(id, id);
        }
        r.jenkins.setSecurityRealm(realm);
        r.jenkins.setAuthorizationStrategy(StrategyFixtures.matrix(new BatchControlMatrixAuthorizationStrategy()));
        r.jenkins.save();
        StrategyFixtures.changeControlOn();
    }

    private static Path store(JenkinsRule r) {
        return r.jenkins.getRootDir().toPath().resolve("batch-control");
    }

    private static Grant window(JenkinsRule r, String folder, String pattern) {
        GrantRequest request;
        try (ACLContext ignored = ACL.as2(User.getById("bob", true).impersonate2())) {
            request = GrantRequestService.get().create(new GrantScope(GrantScope.Type.ITEM, folder), Arrays.asList(GrantAction.CREATE),
                    120, "create jobs in " + folder, Collections.singletonList("a1"), pattern);
        }
        try (ACLContext ignored = ACL.as2(User.getById("a1", true).impersonate2())) {
            return GrantRequestService.get().approve(request.getId(), "ok");
        }
    }

    private static GrantRequest request(JenkinsRule r, String job) {
        try (ACLContext ignored = ACL.as2(User.getById("bob", true).impersonate2())) {
            return GrantRequestService.get().create(new GrantScope(GrantScope.Type.ITEM, job), Arrays.asList(GrantAction.CONFIGURE),
                    120, "maintenance of " + job, "a1");
        }
    }

    private static void replaceOnce(Path file, String from, String to) throws Exception {
        assertTrue(Files.isRegularFile(file), "premise (ARCHITECTURE 5): the file is stored at " + file);
        String xml = Files.readString(file, StandardCharsets.UTF_8);
        assertEquals(1, xml.split(java.util.regex.Pattern.quote(from), -1).length - 1,
                "premise: '" + from + "' occurs exactly once in " + file + ": " + ApproverFormFixtures.excerpt(xml));
        Files.writeString(file, xml.replace(from, to), StandardCharsets.UTF_8);
    }

    private static int createItem(JenkinsRule r, String user, String folder, String name) throws Exception {
        JenkinsRule.WebClient wc = r.createWebClient().withThrowExceptionOnFailingStatusCode(false).login(user, user);
        URL url = new URL(wc.createCrumbedUrl("job/" + folder + "/createItem").toExternalForm()
                + "&name=" + URLEncoder.encode(name, StandardCharsets.UTF_8));
        WebRequest req = new WebRequest(url, HttpMethod.POST);
        req.setAdditionalHeader("Content-Type", "application/xml; charset=UTF-8");
        req.setRequestBody(WindowCreateCliGapTest.MINIMAL_JOB_XML);
        return wc.getPage(req).getWebResponse().getStatusCode();
    }
}
