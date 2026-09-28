package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleProject;
import hudson.model.Item;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import jenkins.model.Jenkins;
import org.htmlunit.WebResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.assertSuccess;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.decideRun;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.get;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.submitRunOk;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC item 13, D-37 CSV criterion, matrix rows T-13-18 and T-13-19: the {@code approver} column
 * of the request export holds the designated set joined by {@code ;}, and a {@code decidedBy}
 * column is appended at the end.
 *
 * <p>Header names are compared case-insensitively: SPEC names the columns but not their
 * capitalisation, and "keep the existing columns" forbids renaming an existing header.
 *
 * <p>Written from docs/SPEC.md, docs/DECISIONS.md D-37 and docs/TEST-MATRIX.md only
 * (no src/main knowledge).
 */
@WithJenkins
public class ApproverCsvExportTest {

    private JenkinsRule j;
    private FreeStyleProject job;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, Item.BUILD, BatchControlPermissions.REQUEST).everywhere().to("u1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1", "a2", "a3")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.VIEW_HISTORY).everywhere().to("viewer"));
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1", "a2", "a3"));
        cfg.save();
        job = j.createFreeStyleProject("batch-x");
        setBatchControl(job, new BatchControlJobProperty(true));
    }

    /** T-13-18: a decided request exports approver "a1;a2" and decidedBy "a2", decidedBy being the last column. */
    @Test
    public void t_13_18_approverColumnJoinsTheSetAndDecidedByIsAppended() throws Exception {
        String id = submitRunOk(j, "u1", job, "csv-marker-decided", "a1", "a2");
        assertSuccess(decideRun(j, "a2", id, "approve", "ok"), "approval by a2");
        j.waitUntilNoActivity();

        List<List<String>> csv = requestsCsv();
        List<String> header = csv.get(0);
        int approverCol = column(header, "approver");
        int decidedByCol = column(header, "decidedBy");
        assertEquals(header.size() - 1, decidedByCol, "decidedBy must be appended as the last column, header was " + header);
        assertTrue(approverCol < decidedByCol, "the existing approver column keeps its place before decidedBy");
        assertEquals(1, header.stream().filter(h -> h.trim().equalsIgnoreCase("approver")).count(), "the approver column must not be duplicated");

        List<String> row = rowOf(csv, id);
        assertEquals("a1;a2", row.get(approverCol).trim(), "the approver cell holds the designated set joined by ';'");
        assertEquals("a2", row.get(decidedByCol).trim(), "the decidedBy cell names the decider");
    }

    /** T-13-19: a PENDING single-approver request exports "a1" (no separator) and an empty decidedBy cell. */
    @Test
    public void t_13_19_pendingSingleApproverRowHasNoSeparatorAndEmptyDecidedBy() throws Exception {
        String id = submitRunOk(j, "u1", job, "csv-marker-pending", "a1");

        List<List<String>> csv = requestsCsv();
        List<String> header = csv.get(0);
        List<String> row = rowOf(csv, id);
        assertEquals(header.size(), row.size(), "every row has one cell per header column");
        assertEquals("a1", row.get(column(header, "approver")).trim());
        assertEquals("", row.get(column(header, "decidedBy")).trim(), "nobody decided a PENDING request");
    }

    // ---------------------------------------------------------------- helpers

    private List<List<String>> requestsCsv() throws Exception {
        WebResponse response = get(j, "viewer", "batch-control/history/requests.csv");
        assertEquals(200, response.getStatusCode());
        List<List<String>> rows = parse(response.getContentAsString());
        assertTrue(!rows.isEmpty(), "the export must have a header");
        return rows;
    }

    private static int column(List<String> header, String name) {
        for (int i = 0; i < header.size(); i++) {
            if (header.get(i).trim().replace("﻿", "").equalsIgnoreCase(name)) {
                return i;
            }
        }
        throw new AssertionError("no '" + name + "' column in the header " + header);
    }

    private static List<String> rowOf(List<List<String>> csv, String id) {
        List<String> row = csv.stream().skip(1).filter(r -> r.stream().anyMatch(c -> c.trim().equals(id)))
                .findFirst().orElse(null);
        assertNotNull(row, "the export must carry a row for request " + id);
        return row;
    }

    /** RFC 4180 parser: quoted cells, doubled quotes, CRLF or LF line ends. */
    static List<List<String>> parse(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cell.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                row.add(cell.toString());
                cell.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                row.add(cell.toString());
                cell.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
            } else {
                cell.append(c);
            }
        }
        if (cell.length() > 0 || !row.isEmpty()) {
            row.add(cell.toString());
            rows.add(row);
        }
        return rows;
    }
}
