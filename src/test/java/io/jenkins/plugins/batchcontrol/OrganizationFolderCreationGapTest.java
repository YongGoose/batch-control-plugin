package io.jenkins.plugins.batchcontrol;

import hudson.model.AbstractItem;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import java.util.Arrays;
import java.util.List;
import jenkins.branch.OrganizationFolder;
import jenkins.model.Jenkins;
import org.htmlunit.WebResponse;
import org.htmlunit.util.NameValuePair;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.excerpt;
import static io.jenkins.plugins.batchcontrol.SnapshotBaselineGapTest.describe;
import static io.jenkins.plugins.batchcontrol.SnapshotBaselineGapTest.postConfigXml;
import static io.jenkins.plugins.batchcontrol.SnapshotBaselineGapTest.records;
import static io.jenkins.plugins.batchcontrol.SnapshotBaselineGapTest.withDescription;
import static io.jenkins.plugins.batchcontrol.SnapshotRefreshGapTest.addsLine;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The other ways an organization folder comes into being, besides {@code createItem} with its XML (T-GAP-415):
 * created programmatically and copied. Each writes its CREATE record and no CONFIGURE record. Matrix rows
 * T-GAP-427 and T-GAP-428 (note 282).
 *
 * <p>Basis: DECISIONS D-76 (2): "Saves made while an item is being created (including an organization folder or
 * multibranch project created from config.xml) are part of its CREATE, not CONFIGURE records"; SPEC 9
 * ("생성·수정·삭제·이름변경·이동은 경로와 무관하게 ... 자동으로 기록", "a save that changes no user-editable configuration
 * writes no CONFIGURE record", "a computed folder ... saving itself during indexing writes no CONFIGURE record
 * unless its user-editable configuration changed").
 *
 * <p>Both switches are on, as in {@link CreationRecordGapTest}. Each row ends with a guard: a real change of the
 * new organization folder writes a CONFIGURE record whose diff adds the new description (the count is printed,
 * not asserted, because a {@code config.xml} POST of a computed folder may start an indexing of its own, note
 * 281).
 *
 * <p>Written from docs/SPEC.md item 9 and docs/DECISIONS.md D-76 only (no src/main knowledge).
 */
@WithJenkins
public class OrganizationFolderCreationGapTest {

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin"));
    }

    /**
     * T-GAP-427 (D-76 (2); SPEC 9): both switches on; {@code Jenkins#createProject(OrganizationFolder.class,
     * "api-org")}. One CREATE record and no CONFIGURE record for {@code api-org}. Guard: a real change then writes
     * a CONFIGURE record whose diff adds it.
     */
    @Test
    public void t_gap_427_organizationFolderCreatedProgrammaticallyWritesOnlyCreate() throws Exception {
        switchesOn();
        OrganizationFolder created = j.jenkins.createProject(OrganizationFolder.class, "api-org");
        assertNotNull(created, "fixture: the organization folder is created");
        j.waitUntilNoActivity();
        assertCreateOneConfigureNone("api-org", "Jenkins#createProject");
        assertRealChangeRecorded("api-org");
    }

    /**
     * T-GAP-428 (D-76 (2); SPEC 9): both switches on; organization folder {@code src-org} created and given a
     * description (so that it differs from a blank organization folder); the administrator copies it to
     * {@code copy-org} ({@code createItem} with {@code mode=copy}). One CREATE record and no CONFIGURE record for
     * {@code copy-org}; the copy writes no CONFIGURE record for {@code src-org}. Guard: a real change of the copy
     * then writes a CONFIGURE record whose diff adds it.
     */
    @Test
    public void t_gap_428_copiedOrganizationFolderWritesOnlyCreate() throws Exception {
        switchesOn();
        OrganizationFolder source = j.jenkins.createProject(OrganizationFolder.class, "src-org");
        j.waitUntilNoActivity();
        source.setDescription("the copied organization folder");
        j.waitUntilNoActivity();
        int sourceConfigure = records(ChangeType.CONFIGURE, "src-org").size();

        WebResponse copied = ApproverFormFixtures.post(j, "admin", "createItem", Arrays.asList(
                new NameValuePair("name", "copy-org"), new NameValuePair("mode", "copy"), new NameValuePair("from", "src-org")));
        assertTrue(copied.getStatusCode() < 400, "fixture: the copy is created, got " + copied.getStatusCode() + ": "
                + excerpt(copied.getContentAsString()));
        j.waitUntilNoActivity();
        OrganizationFolder copy = j.jenkins.getItemByFullName("copy-org", OrganizationFolder.class);
        assertNotNull(copy, "premise: copy-org is an organization folder");
        assertEquals("the copied organization folder", copy.getDescription(), "premise: the copy carries the source's configuration");
        assertEquals(sourceConfigure, records(ChangeType.CONFIGURE, "src-org").size(),
                "SPEC 9: copying writes no CONFIGURE record for the source: " + describe(records(ChangeType.CONFIGURE, "src-org")));
        assertCreateOneConfigureNone("copy-org", "a copy");
        assertRealChangeRecorded("copy-org");
    }

    // ------------------------------------------------------------------ helpers

    private void switchesOn() throws Exception {
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setChangeControlEnabled(true);
        cfg.save();
        assertTrue(BatchControlGlobalConfiguration.get().isRunControlEnabled() && BatchControlGlobalConfiguration.get().isChangeControlEnabled(),
                "fixture: both switches are on");
    }

    private void assertCreateOneConfigureNone(String name, String path) {
        List<ChangeRecord> create = records(ChangeType.CREATE, name);
        List<ChangeRecord> configure = records(ChangeType.CONFIGURE, name);
        System.out.println("T-GAP organization folder observation (" + path + "): CREATE " + create.size() + ", CONFIGURE "
                + configure.size() + " " + describe(configure));
        assertEquals(1, create.size(), "SPEC 9: " + path + " writes exactly one CREATE record: " + describe(create));
        assertEquals(0, configure.size(), "D-76 (2): the saves made while " + path + " creates the organization folder are part of its"
                + " CREATE, not CONFIGURE records: " + describe(configure));
    }

    /** Guard: a real change writes a CONFIGURE record whose diff adds the new description (count printed). */
    private void assertRealChangeRecorded(String name) throws Exception {
        AbstractItem item = (AbstractItem) j.jenkins.getItemByFullName(name);
        assertNotNull(item, "fixture: " + name + " exists");
        String xml = item.getConfigFile().asString();
        String edited = withDescription(xml, "guard edit of " + name);
        assertNotEquals(xml, edited, "fixture: the guard edit changes the XML of " + name);
        assertEquals(200, postConfigXml(j, "admin", item, edited), "fixture: the guard edit of " + name + " is saved");
        j.waitUntilNoActivity();
        List<ChangeRecord> configure = records(ChangeType.CONFIGURE, name);
        System.out.println("T-GAP organization folder guard (" + name + "): CONFIGURE " + configure.size() + " after one real change");
        assertTrue(configure.stream().anyMatch(r -> addsLine(r, "guard edit of " + name)),
                "guard (SPEC 9): a real change of " + name + " writes a CONFIGURE record whose diff adds the description: " + describe(configure));
    }
}
