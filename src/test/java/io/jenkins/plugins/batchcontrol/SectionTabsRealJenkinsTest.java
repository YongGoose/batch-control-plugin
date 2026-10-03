package io.jenkins.plugins.batchcontrol;

import hudson.model.Item;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.util.Arrays;
import jenkins.model.Jenkins;
import org.htmlunit.Page;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.RealJenkinsExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T-UI-46 (note 191): the Batch Control pages render in a real Jenkins, where each plugin has its
 * own class loader. A plain JenkinsRule puts every plugin on one flat class path, which hid a 500
 * on every Batch Control page during the PR6 work (a view resolved a plugin class through core's
 * class loader). Here /batch-control/ and every section answer 200 with the tab bar, for the
 * administrator and for a requester.
 *
 * <p>Written from the coordinator's brief and docs/TEST-MATRIX.md only (no src/main knowledge).
 */
public class SectionTabsRealJenkinsTest {

    @RegisterExtension
    final RealJenkinsExtension rr = new RealJenkinsExtension();

    /** T-UI-46: /batch-control/ and its sections render with 200 under real plugin class loaders. */
    @Test
    public void t_ui_46_batchControlPagesRenderInARealJenkins() throws Throwable {
        rr.then(SectionTabsRealJenkinsTest::render);
    }

    private static void render(JenkinsRule r) throws Throwable {
        r.jenkins.setSecurityRealm(r.createDummySecurityRealm());
        r.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, Item.BUILD, BatchControlPermissions.REQUEST).everywhere().to("u1"));
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setChangeControlEnabled(true);
        cfg.setApprovers(Arrays.asList("admin"));
        cfg.save();

        String[] sections = {"", "requests/", "activations/", "grants/", "history/", "changes/", "dashboard/", "incidents/"};
        for (String section : sections) {
            check(r, "admin", "batch-control/" + section);
        }
        check(r, "u1", "batch-control/");
        check(r, "u1", "batch-control/requests/");
    }

    private static void check(JenkinsRule r, String user, String path) throws Exception {
        JenkinsRule.WebClient wc = r.createWebClient().withThrowExceptionOnFailingStatusCode(false);
        wc.getOptions().setJavaScriptEnabled(false);
        wc.login(user);
        Page p = wc.goTo(path);
        String body = p.getWebResponse().getContentAsString();
        String excerpt = body.length() > 1500 ? body.substring(0, 1500) : body;
        assertEquals(200, p.getWebResponse().getStatusCode(), user + " GET /" + path + " must answer 200 in a real Jenkins: " + excerpt);
        assertTrue(body.contains("data-batch-control-tabs"), user + " GET /" + path + " must render the tab bar in a real Jenkins");
    }
}
