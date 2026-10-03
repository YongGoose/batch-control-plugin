package io.jenkins.plugins.batchcontrol;

import hudson.model.Action;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import jenkins.model.Jenkins;
import jenkins.model.menu.Group;
import jenkins.model.menu.Semantic;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * New job page (hosting review): the per-job Request Run action is placed first in the app bar
 * and styled as the build button, through core's public {@link Action#getGroup()} and
 * {@link Action#getSemantic()}. Matrix row T-UI-55 (note 203).
 *
 * <p>The action is found by its frozen URL {@code job/<name>/batch-control} (ActionVisibilityTest)
 * among the job's actions, as seen by the requester. Written from docs/SPEC.md item 6 and the
 * coordinator's checklist only (no src/main knowledge).
 */
@WithJenkins
public class RequestRunAppBarTest {

    private JenkinsRule j;

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
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
    }

    /** T-UI-55: Request Run reports Group.FIRST_IN_APP_BAR and Semantic.BUILD. */
    @Test
    public void t_ui_55_requestRunIsFirstInAppBarWithBuildSemantic() throws Exception {
        FreeStyleProject job = j.createFreeStyleProject("batch-x");
        BatchControlFixtures.setBatchControl(job, new BatchControlJobProperty(true));
        List<Action> found = new ArrayList<>();
        try (ACLContext ignored = ACL.as2(User.getById("u1", true).impersonate2())) {
            for (Action a : job.getAllActions()) {
                if ("batch-control".equals(a.getUrlName()) && a.getIconFileName() != null) {
                    found.add(a);
                }
            }
        }
        assertEquals(1, found.size(), "exactly one visible Batch Control action at job/batch-x/batch-control for u1, got " + found);
        Action requestRun = found.get(0);
        assertSame(Group.FIRST_IN_APP_BAR, requestRun.getGroup(),
                "Request Run must be first in the app bar, got order " + (requestRun.getGroup() == null ? null : requestRun.getGroup().getOrder()));
        assertEquals(Semantic.BUILD, requestRun.getSemantic(), "Request Run must carry the build semantic");
    }
}
