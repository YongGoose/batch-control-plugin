package io.jenkins.plugins.batchcontrol;

import hudson.security.HudsonPrivateSecurityRealm;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.model.GrantAction;
import io.jenkins.plugins.batchcontrol.security.BatchControlMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.store.FileStore;
import java.io.File;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.JenkinsSessionExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage lane 2, scenario L2-11 (F) (matrix row T-GAP-245, note 277): an item listed as changed under
 * a grant whose directory disappears while Jenkins is down can still be marked as reviewed.
 *
 * <p>Basis: SPEC item 2, the D-58b line ("The state ends only through the explicit 'Mark as reviewed'
 * action (POST ...), which writes a {@code GUARD_REVIEWED} record"); DECISIONS D-58a (4) ("An
 * administrator can see which items are in the 'changed under a grant' state") and (5) (the state is the
 * optional {@code changedItems} list of the grant file; retention keeps a grant file while that list is
 * non-empty); ARCHITECTURE section 5 (grant file fields); SPEC 6 usability (the list must not keep an
 * entry that no action can clear).
 *
 * <p>Written from docs/SPEC.md, docs/DECISIONS.md, docs/ARCHITECTURE.md and docs/TEST-MATRIX.md only (no
 * src/main knowledge).
 */
public class StrategyMonitorRestartGapTest {

    @RegisterExtension
    final JenkinsSessionExtension session = new JenkinsSessionExtension();

    /**
     * T-GAP-245 (L2-11 (F)): bob changes job {@code gone-j} under his CONFIGURE window (an HTTP save), so
     * Manage Jenkins lists it (premise). While Jenkins is down the job's directory is removed. After the
     * restart the list still names {@code gone-j} (premise), and the administrator's {@code markReviewed}
     * of that name succeeds (no 4xx or 5xx), writes a GUARD_REVIEWED record, and the list no longer names it.
     * Guard: the other listed job {@code kept-j} stays listed.
     */
    @Test
    public void t_gap_245_listedItemWhoseDirectoryVanishedCanBeReviewed() throws Throwable {
        session.then(r -> {
            HudsonPrivateSecurityRealm realm = new HudsonPrivateSecurityRealm(false, false, null);
            for (String id : new String[] {"admin", "alice", "bob", "carol", "a1", "m1", "c1"}) {
                realm.createAccount(id, id);
            }
            r.jenkins.setSecurityRealm(realm);
            r.jenkins.setAuthorizationStrategy(StrategyFixtures.matrix(new BatchControlMatrixAuthorizationStrategy()));
            r.jenkins.save();
            StrategyFixtures.changeControlOn();
            StrategyFixtures.configureBuildAuthenticator();
            for (String name : new String[] {"gone-j", "kept-j"}) {
                r.createFreeStyleProject(name);
                StrategyFixtures.grant("bob", name, Arrays.asList(GrantAction.CONFIGURE));
                editAsBob(r, "job/" + name + "/");
            }
            String text = manageText(r);
            assertTrue(text.contains("gone-j") && text.contains("kept-j"), "premise: both jobs are listed as changed under a"
                    + " grant: " + UsabilityFixtures.excerpt(text));
        });
        File home = session.getHome();
        Path dir = home.toPath().resolve("jobs/gone-j");
        assertTrue(Files.isDirectory(dir), "premise: the job directory is " + dir);
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
        session.then(r -> {
            assertNull(r.jenkins.getItemByFullName("gone-j"), "premise: gone-j no longer exists");
            String before = manageText(r);
            assertTrue(before.contains("kept-j"), "guard: kept-j is still listed: " + UsabilityFixtures.excerpt(before));
            assertTrue(before.contains("gone-j"), "premise (scenario L2-11 (F)): after the restart the list still names the"
                    + " vanished gone-j: " + UsabilityFixtures.excerpt(before));
            int reviews = reviews().size();
            JenkinsRule.WebClient wc = r.createWebClient().withThrowExceptionOnFailingStatusCode(false).login("admin", "admin");
            wc.getOptions().setRedirectEnabled(false);
            URL url = new URL(wc.createCrumbedUrl("manage/administrativeMonitor/" + StrategyFixtures.MONITOR_ID + "/markReviewed")
                    .toExternalForm() + "&item=gone-j");
            int code = wc.getPage(new WebRequest(url, HttpMethod.POST)).getWebResponse().getStatusCode();
            assertTrue(code < 400, "markReviewed of the listed name of a vanished item must succeed, got " + code);
            assertEquals(reviews + 1, reviews().size(), "the review must write one GUARD_REVIEWED record");
            String after = manageText(r);
            assertFalse(after.contains("gone-j"), "after the review the list must not name gone-j: " + UsabilityFixtures.excerpt(after));
            assertTrue(after.contains("kept-j"), "guard: kept-j stays listed: " + UsabilityFixtures.excerpt(after));
        });
    }

    private static List<ChangeRecord> reviews() {
        return FileStore.get().listChangeRecords(YearMonth.now(io.jenkins.plugins.batchcontrol.store.BatchClock.clock())).stream()
                .filter(c -> c.getType() == ChangeType.GUARD_REVIEWED).collect(Collectors.toList());
    }

    private static String manageText(JenkinsRule r) throws Exception {
        JenkinsRule.WebClient wc = r.createWebClient().withThrowExceptionOnFailingStatusCode(false).login("admin", "admin");
        HtmlPage manage = wc.goTo("manage/");
        assertEquals(200, manage.getWebResponse().getStatusCode(), "Manage Jenkins must render");
        return manage.asNormalizedText();
    }

    private static void editAsBob(JenkinsRule r, String itemUrl) throws Exception {
        JenkinsRule.WebClient wc = r.createWebClient().withThrowExceptionOnFailingStatusCode(false).login("bob", "bob");
        String xml = wc.goTo(itemUrl + "config.xml", "application/xml").getWebResponse().getContentAsString();
        String edited = xml.contains("<description/>") ? xml.replace("<description/>", "<description>edited by bob</description>")
                : xml.replaceFirst("(<project[^>]*>)", "$1<description>edited by bob</description>");
        assertFalse(edited.equals(xml), "fixture: the edit must change " + itemUrl);
        WebRequest req = new WebRequest(wc.createCrumbedUrl(itemUrl + "config.xml"), HttpMethod.POST);
        req.setAdditionalHeader("Content-Type", "application/xml; charset=UTF-8");
        req.setRequestBody(edited);
        int code = wc.getPage(req).getWebResponse().getStatusCode();
        assertTrue(code < 400, "fixture: bob's edit of " + itemUrl + " must be saved, got " + code);
    }
}
