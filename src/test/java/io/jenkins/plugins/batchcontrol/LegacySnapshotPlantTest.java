package io.jenkins.plugins.batchcontrol;

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.security.AuthorizationMatrixProperty;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.model.GrantAction;
import io.jenkins.plugins.batchcontrol.security.BatchControlMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.jenkinsci.plugins.matrixauth.inheritance.InheritParentStrategy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.JenkinsSessionExtension;

import static io.jenkins.plugins.batchcontrol.StrategyFixtures.T0;
import static io.jenkins.plugins.batchcontrol.StrategyFixtures.has;
import static io.jenkins.plugins.batchcontrol.store.PathCodecUniquenessTest.FOLDER_A;
import static io.jenkins.plugins.batchcontrol.store.PathCodecUniquenessTest.FOLDER_B;
import static io.jenkins.plugins.batchcontrol.store.PathCodecUniquenessTest.VICTIM;
import static io.jenkins.plugins.batchcontrol.store.PathCodecUniquenessTest.VICTIM_LEAF;
import static io.jenkins.plugins.batchcontrol.store.PathCodecUniquenessTest.legacyShortForm;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression row for security-10 S-01 (HIGH), closed by D-43 and SPEC item 4 ("Files in the
 * pre-release shortened form are not read"). Matrix row T-04-16 (note 81).
 *
 * <p>The attack: a file sitting at the pre-release shortened path of a long-named item N can only
 * be some other item's plain encoding, whose author controls its content. If it were read as N's
 * baseline, a grant holder's authorization entry already present in the plant would look
 * unchanged and survive the D-35b guard unrecorded, and N's CONFIGURE diff would be computed
 * against attacker content; if it were migrated at startup it would become N's baseline for good.
 *
 * Written from docs/SPEC.md items 2 and 4, docs/DECISIONS.md D-35b and D-43,
 * docs/reports/security-10.md and docs/TEST-MATRIX.md only (no src/main knowledge).
 */
public class LegacySnapshotPlantTest {

    private static final String PLANT_MARKER = "planted-baseline-marker";
    private static final String BOB_ENTRY = "USER:hudson.model.Item.Configure:bob";

    @RegisterExtension
    final JenkinsSessionExtension session = new JenkinsSessionExtension();

    private String plantContent;

    @AfterEach
    public void resetClock() {
        BatchClock.reset();
    }

    /**
     * T-04-16 (security-10 S-01, D-43): a planted pre-release-form snapshot for the long-named N,
     * carrying N's configuration plus bob's Item/Configure entry, is not used as N's baseline: bob,
     * holding Configure on N only through a grant, POSTs N's config.xml with that entry and it is
     * removed (in memory and on disk) with a GRANT_VIOLATION record; no CONFIGURE diff of N shows
     * the plant; after a restart the planted file is still where it was, unchanged (not moved),
     * and N's next change is still not diffed against it.
     */
    @Test
    public void t_04_16_plantedPreReleaseSnapshotIsNotABaselineAndIsNotMoved() throws Throwable {
        session.then(j -> {
            j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
            j.jenkins.setAuthorizationStrategy(StrategyFixtures.matrix(new BatchControlMatrixAuthorizationStrategy()));
            StrategyFixtures.changeControlOn();
            BatchClock.setForTest(Clock.fixed(T0, ZoneOffset.UTC));

            Folder a = j.jenkins.createProject(Folder.class, FOLDER_A);
            Folder b = a.createProject(Folder.class, FOLDER_B);
            FreeStyleProject n = b.createProject(FreeStyleProject.class, VICTIM_LEAF);
            AuthorizationMatrixProperty amp = new AuthorizationMatrixProperty(new HashMap<>(), new InheritParentStrategy());
            amp.add(Item.CONFIGURE, PermissionEntry.user("alice"));
            n.addProperty(amp);
            n.setDescription("real-baseline");
            assertEquals(VICTIM, n.getFullName(), "fixture: N's full name");
            assertFalse(has(n, "bob", Item.CONFIGURE), "premise: bob holds no native Configure on N");

            // The orphaned-plant case of S-01: N has no current-form baseline, and the pre-release
            // path holds attacker content (N's configuration + bob's entry + a marker).
            Path snapshots = snapshots(j);
            try (Stream<Path> files = Files.list(snapshots)) {
                for (Path file : files.collect(Collectors.toList())) {
                    Files.delete(file);
                }
            }
            String current = n.getConfigFile().asString();
            plantContent = withBobEntry(current).replace("<description>real-baseline</description>",
                    "<description>" + PLANT_MARKER + "</description>");
            assertTrue(plantContent.contains(BOB_ENTRY) && plantContent.contains(PLANT_MARKER), "fixture: the plant differs");
            Files.writeString(legacyFile(j), plantContent, StandardCharsets.UTF_8);

            StrategyFixtures.grant("bob", VICTIM, Arrays.asList(GrantAction.CONFIGURE));
            assertTrue(has(n, "bob", Item.CONFIGURE), "premise: the grant confers Configure on N");
            assertTrue(StrategyFixtures.records(ChangeType.GRANT_VIOLATION).isEmpty(), "premise: no violation yet");

            String payload = withBobEntry(current).replace("<description>real-baseline</description>",
                    "<description>bob-edit</description>");
            postConfigXml(j, "bob", n, payload);

            FreeStyleProject after = j.jenkins.getItemByFullName(VICTIM, FreeStyleProject.class);
            AuthorizationMatrixProperty restored = after.getProperty(AuthorizationMatrixProperty.class);
            assertNotNull(restored, "N's authorization property must be kept, not removed");
            assertFalse(restored.getGrantedPermissionEntries().values().stream().flatMap(java.util.Set::stream)
                    .anyMatch(e -> "bob".equals(e.getSid())), "bob's added entry must be removed although the plant already carried it (S-01)");
            assertFalse(after.getConfigFile().asString().contains(":bob</permission>"), "the removal must be persisted");
            List<ChangeRecord> violations = StrategyFixtures.records(ChangeType.GRANT_VIOLATION);
            assertTrue(violations.stream().anyMatch(v -> "bob".equals(v.getUser()) && VICTIM.equals(v.getTarget())), "a GRANT_VIOLATION record must name bob and N, got " + violations.size());
            assertNoDiffShowsThePlant();
            assertTrue(Files.exists(legacyFile(j)), "the planted file must not be moved or deleted (D-43)");
            assertEquals(plantContent, Files.readString(legacyFile(j), StandardCharsets.UTF_8), "the planted file must be left unchanged");
        });
        session.then(j -> {
            assertTrue(Files.exists(legacyFile(j)), "after a restart the planted file must still be where it was (not moved, D-43)");
            assertEquals(plantContent, Files.readString(legacyFile(j), StandardCharsets.UTF_8), "after a restart the planted file must be unchanged");
            BatchClock.setForTest(Clock.fixed(T0.plusSeconds(3600), ZoneOffset.UTC));
            FreeStyleProject n = j.jenkins.getItemByFullName(VICTIM, FreeStyleProject.class);
            assertNotNull(n, "fixture: N survives the restart");
            n.setDescription("after-restart");
            assertNoDiffShowsThePlant();
        });
    }

    private static void assertNoDiffShowsThePlant() {
        for (ChangeRecord rec : StrategyFixtures.records(ChangeType.CONFIGURE)) {
            if (VICTIM.equals(rec.getTarget())) {
                assertFalse(String.valueOf(rec.getDiff()).contains(PLANT_MARKER), "N's CONFIGURE diff must not be computed against the planted file (S-01):\n" + rec.getDiff());
            }
        }
    }

    private static Path snapshots(JenkinsRule j) {
        return j.jenkins.getRootDir().toPath().resolve("batch-control").resolve("snapshots");
    }

    private static Path legacyFile(JenkinsRule j) {
        return snapshots(j).resolve(legacyShortForm(VICTIM) + ".xml");
    }

    private static String withBobEntry(String xml) {
        String close = "</hudson.security.AuthorizationMatrixProperty>";
        assertTrue(xml.contains(close), "fixture: N's config must carry an AuthorizationMatrixProperty:\n" + xml);
        return xml.replace(close, "<permission>" + BOB_ENTRY + "</permission>" + close);
    }

    private static void postConfigXml(JenkinsRule j, String user, FreeStyleProject job, String xml) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login(user);
        WebRequest req = new WebRequest(wc.createCrumbedUrl(job.getUrl() + "config.xml"), HttpMethod.POST);
        req.setAdditionalHeader("Content-Type", "application/xml");
        req.setRequestBody(xml);
        wc.getPage(req);
    }
}
