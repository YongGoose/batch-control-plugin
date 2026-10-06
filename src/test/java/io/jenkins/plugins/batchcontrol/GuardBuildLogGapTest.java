package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.security.AuthorizationMatrixProperty;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.model.GrantAction;
import io.jenkins.plugins.batchcontrol.security.BatchControlMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.jenkinsci.plugins.matrixauth.inheritance.InheritParentStrategy;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.StrategyFixtures.T0;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage lane 2, scenario L2-14 (matrix row T-GAP-259, note 277): no build-log notice for a save of
 * another job.
 *
 * <p>Basis: LIMITATIONS 35 "A Pipeline build whose own save was put back gets a line in its build log
 * naming the reverted entries. A save whose build cannot be identified gets no such line, only the
 * {@code GRANT_VIOLATION} record: for example a seed job saving another job, or a Freestyle build"; SPEC
 * item 2, the D-58 line ("A Pipeline build's reverted save is named in its build log").
 *
 * <p>Written from docs/SPEC.md, docs/DECISIONS.md D-58/D-58a, docs/LIMITATIONS.md and docs/TEST-MATRIX.md
 * only (no src/main knowledge).
 */
@WithJenkins
public class GuardBuildLogGapTest {

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(StrategyFixtures.matrix(new BatchControlMatrixAuthorizationStrategy()));
        StrategyFixtures.changeControlOn();
        BatchClock.setForTest(Clock.fixed(T0, ZoneOffset.UTC));
    }

    @AfterEach
    public void resetClock() {
        BatchClock.reset();
    }

    /**
     * T-GAP-259 (L2-14, LIMITATIONS 35): job {@code qq} is guarded (bob's CONFIGURE window); the Pipeline
     * {@code seed-p} (trusted script, not guarded) saves {@code qq} with an added Item/Build entry for carol.
     * The widening of {@code qq} is put back and a GRANT_VIOLATION names {@code qq}; {@code seed-p}'s build
     * log has no line naming the reverted entry. Guard: the guarded Pipeline {@code self-p} whose own
     * {@code properties([authorizationMatrix(...)])} step gives dave Job/Configure gets a log line naming
     * the reverted entry.
     */
    @Test
    public void t_gap_259_seedJobSavingAnotherJobGetsNoLogNotice() throws Exception {
        FreeStyleProject q = j.createFreeStyleProject("qq");
        AuthorizationMatrixProperty amp = new AuthorizationMatrixProperty(new HashMap<>(), new InheritParentStrategy());
        amp.add(Item.CONFIGURE, PermissionEntry.user("alice"));
        q.addProperty(amp);
        StrategyFixtures.grant("bob", "qq", Arrays.asList(GrantAction.CONFIGURE));

        WorkflowJob seed = j.jenkins.createProject(WorkflowJob.class, "seed-p");
        // a trusted (non-sandboxed) script, approved by the administrator
        String script = "def q = jenkins.model.Jenkins.get().getItemByFullName('qq')\n"
                + "def xml = q.getConfigFile().asString()\n"
                + "def close = '</hudson.security.AuthorizationMatrixProperty>'\n"
                + "def entry = '<permission>USER:hudson.model.Item.Build:' + 'ca' + 'rol</permission>'\n"
                + "q.updateByXml((javax.xml.transform.Source) new javax.xml.transform.stream.StreamSource("
                + "new java.io.StringReader(xml.replace(close, entry + close))))\n"
                + "echo 'seed finished'\n";
        org.jenkinsci.plugins.scriptsecurity.scripts.ScriptApproval.get().preapprove(script,
                org.jenkinsci.plugins.scriptsecurity.scripts.languages.GroovyLanguage.get());
        seed.setDefinition(new CpsFlowDefinition(script, false));
        int before = violations().size();
        WorkflowRun seedRun = j.buildAndAssertSuccess(seed);

        AuthorizationMatrixProperty after = j.jenkins.getItemByFullName("qq", FreeStyleProject.class).getProperty(AuthorizationMatrixProperty.class);
        assertTrue(after != null && after.getGrantedPermissionEntries().values().stream().flatMap(java.util.Set::stream)
                .noneMatch(e -> "carol".equals(e.getSid())), "the seed job's widening of qq must be put back");
        List<ChangeRecord> recorded = violations();
        assertTrue(recorded.size() > before && recorded.stream().skip(before).anyMatch(r -> "qq".equals(r.getTarget())),
                "the put-back widening must be recorded as GRANT_VIOLATION naming qq: " + recorded);
        String seedLog = JenkinsRule.getLog(seedRun);
        assertTrue(seedLog.contains("seed finished"), "fixture: the seed build ran its script");
        for (String line : seedLog.split("\\R")) {
            assertFalse(line.contains("carol"), "the seed job's log must not name the entry reverted on another job: " + line);
        }

        WorkflowJob self = j.jenkins.createProject(WorkflowJob.class, "self-p");
        self.setDefinition(new CpsFlowDefinition("properties([authorizationMatrix(entries: [user(name: 'dave', permissions:"
                + " ['Job/Configure'])])])\necho 'self finished'", true));
        StrategyFixtures.grant("bob", "self-p", Arrays.asList(GrantAction.CONFIGURE));
        WorkflowRun selfRun = j.buildAndAssertSuccess(self);
        String selfLog = JenkinsRule.getLog(selfRun);
        boolean named = false;
        for (String line : selfLog.split("\\R")) {
            if (line.contains("dave") && line.toLowerCase(Locale.ROOT).contains("configure")) {
                named = true;
            }
        }
        assertTrue(named, "guard: a Pipeline build whose own save was put back gets a log line naming the reverted entry: " + selfLog);
    }

    private static List<ChangeRecord> violations() {
        return StrategyFixtures.records(ChangeType.GRANT_VIOLATION);
    }
}
