package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleProject;
import hudson.security.FullControlOnceLoggedInAuthorizationStrategy;
import hudson.security.HudsonPrivateSecurityRealm;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import io.jenkins.plugins.batchcontrol.store.FileStore;
import java.net.URL;
import java.time.YearMonth;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.util.NameValuePair;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.RealJenkinsExtension;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage lane 2, scenario L2-04 (matrix row T-GAP-207, note 277): renames and deletions are recorded
 * on a Jenkins without matrix-auth.
 *
 * <p>Basis: SPEC item 9 "생성·수정·삭제·이름변경·이동은 경로와 무관하게 누가·언제·무엇을 바꿨는지 자동으로
 * 기록"; ARCHITECTURE section 4 "matrix-auth and role-strategy are optional dependencies; each subclass is
 * an {@code @Extension(optional = true)} in its own class so a missing plugin never breaks class
 * loading"; SPEC 1 (records are active while either switch is on).
 *
 * <p>This class runs inside a {@code RealJenkinsExtension} JVM that lacks matrix-auth, so it references
 * core, test-harness and batch-control types only (no matrix-auth type, no fixture class that links one).
 *
 * <p>Written from docs/SPEC.md, docs/ARCHITECTURE.md and docs/TEST-MATRIX.md only (no src/main knowledge).
 */
public class ChangeControlWithoutMatrixAuthGapTest {

    @RegisterExtension
    final RealJenkinsExtension rr = new RealJenkinsExtension().omitPlugins("matrix-auth");

    /**
     * T-GAP-207 (L2-04, SPEC 9, ARCHITECTURE 4): matrix-auth is not installed (premise), both switches are
     * on, the administrator (FullControlOnceLoggedIn) renames {@code rn-web} through {@code confirmRename}
     * and deletes the renamed job through {@code doDelete}, and renames and deletes {@code rn-api} through
     * the API. All succeed, and RENAME and DELETE records exist for both jobs.
     */
    @Test
    public void t_gap_207_renameAndDeleteAreRecordedWithoutMatrixAuth() throws Throwable {
        rr.then(ChangeControlWithoutMatrixAuthGapTest::renameAndDelete);
    }

    private static void renameAndDelete(JenkinsRule r) throws Throwable {
        assertTrue(Jenkins.get().getPlugin("matrix-auth") == null, "premise: matrix-auth must not be installed");
        HudsonPrivateSecurityRealm realm = new HudsonPrivateSecurityRealm(false, false, null);
        realm.createAccount("admin", "admin");
        r.jenkins.setSecurityRealm(realm);
        r.jenkins.setAuthorizationStrategy(new FullControlOnceLoggedInAuthorizationStrategy());
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setApprovers(Arrays.asList("admin"));
        cfg.setRunControlEnabled(true);
        cfg.setChangeControlEnabled(true);
        cfg.save();

        r.createFreeStyleProject("rn-web");
        FreeStyleProject api = r.createFreeStyleProject("rn-api");

        // the administrator authenticates with an API token (exempt from the crumb, which this real
        // instance issues per session)
        JenkinsRule.WebClient wc = r.createWebClient().withThrowExceptionOnFailingStatusCode(false);
        wc.addRequestHeader("Authorization", RawHttpFixtures.basic("admin", RawHttpFixtures.apiToken("admin")));
        WebRequest rename = new WebRequest(new URL(r.getURL(), "job/rn-web/confirmRename"), HttpMethod.POST);
        rename.setRequestParameters(Arrays.asList(new NameValuePair("newName", "rn-web2")));
        int renamed = wc.getPage(rename).getWebResponse().getStatusCode();
        assertTrue(renamed < 400, "the administrator's rename must succeed without matrix-auth, got " + renamed);
        assertNotNull(r.jenkins.getItem("rn-web2"), "the job must carry its new name");
        int deleted = wc.getPage(new WebRequest(new URL(r.getURL(), "job/rn-web2/doDelete"), HttpMethod.POST))
                .getWebResponse().getStatusCode();
        assertTrue(deleted < 400, "the administrator's delete must succeed without matrix-auth, got " + deleted);
        assertNull(r.jenkins.getItem("rn-web2"), "the job must be deleted");

        api.renameTo("rn-api2");
        r.jenkins.getItem("rn-api2").delete();
        assertNull(r.jenkins.getItem("rn-api2"), "the API delete must succeed");

        List<ChangeRecord> records = FileStore.get().listChangeRecords(YearMonth.now(BatchClock.clock()));
        for (String[] expected : new String[][] {{"RENAME", "rn-web"}, {"DELETE", "rn-web2"}, {"RENAME", "rn-api"}, {"DELETE", "rn-api2"}}) {
            ChangeType type = ChangeType.valueOf(expected[0]);
            assertTrue(records.stream().anyMatch(c -> c.getType() == type && mentions(c, expected[1])),
                    "a " + expected[0] + " record must name " + expected[1] + ": " + records.stream()
                            .map(c -> c.getType() + " " + c.getTarget() + " " + c.getDetail()).collect(Collectors.toList()));
        }
    }

    private static boolean mentions(ChangeRecord c, String name) {
        return name.equals(c.getTarget()) || String.valueOf(c.getDetail()).contains(name);
    }
}
