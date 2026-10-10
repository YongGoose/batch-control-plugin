package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.Result;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.io.InputStream;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.WebResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.FailureBuilder;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Issue #43 (Wave C-UI contract, owner decision 2026-10-10 "BOM by default", to be recorded as
 * D-85; matrix rows T-12-37..41, note 328): every CSV export starts with the UTF-8 byte order mark
 * (EF BB BF), followed directly by the header row; the content type stays
 * {@code text/csv; charset=UTF-8} and non-ASCII values are UTF-8. The exports are the four of SPEC
 * 12 ("실행 기록, 오류 건, 변경 기록, 요청 이력 각각에 ... CSV 내보내기"): {@code runs.csv},
 * {@code incidents.csv}, {@code changes.csv} and {@code requests.csv} under
 * {@code batch-control/history/}.
 *
 * <p>The raw bytes are read from the response stream, never from a decoded string (a client may drop
 * a BOM while decoding). Each row first runs its guards, which hold today and must keep holding:
 * a user without ViewHistory gets 403 (SPEC 12); the content type; the body after an optional BOM is
 * well-formed UTF-8 and carries the fixture's non-ASCII value as UTF-8 bytes; the first line is a
 * header row, not data. Then the defect: the first three bytes are EF BB BF, there is exactly one
 * BOM, and the header row follows it directly.
 *
 * <p>Written from docs/SPEC.md, docs/TEST-MATRIX.md, issue #43 and the Wave C-UI contract only (no
 * src/main knowledge).
 */
@WithJenkins
public class CsvByteOrderMarkTest {

    private static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private static final String HISTORY = "batch-control/history/";
    /** A job name in Hangul and Kanji: mojibake in CP949 or CP1252 if decoded without the BOM. */
    private static final String JOB = "야간정산-山田";
    private static final String REASON = "월말 정산 승인 요청 – 山田 «batch»";
    /** A month without any record, for the header-only exports. */
    private static final String EMPTY_PERIOD = "?from=2025-09-01&to=2025-09-30";

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, Item.BUILD, BatchControlPermissions.REQUEST).everywhere().to("u1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.VIEW_HISTORY).everywhere().to("viewer")
                // SPEC 2 (#31): a user with no Batch Control permission gets 404; with one, but not ViewHistory, 403
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.REQUEST).everywhere().to("nohist"));
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();

        // One record of every kind naming the non-ASCII job: CREATE (changes), the failed run (runs),
        // its incident (incidents) and a run request with a non-ASCII reason (requests).
        FreeStyleProject job = j.createFreeStyleProject(JOB);
        assertEquals(JOB, job.getFullName(), "premise: core accepts the non-ASCII job name");
        BatchControlFixtures.uncontrolled(job);
        BatchControlFixtures.activateAsAdmin(job); // D-46: a cause-less submission needs an activation (note 109)
        job.getBuildersList().add(new FailureBuilder());
        j.buildAndAssertStatus(Result.FAILURE, job);
        j.waitUntilNoActivity();
        try (ACLContext ignored = ACL.as2(User.getById("u1", true).impersonate2())) {
            RunRequestService.get().create(job, new LinkedHashMap<>(), REASON, "a1");
        }
    }

    /** T-12-37 (#43): {@code runs.csv} starts with EF BB BF and the header row. */
    @Test
    public void t_12_37_runsCsvStartsWithTheUtf8Bom() throws Exception {
        assertBomExport("runs.csv", JOB, null);
    }

    /** T-12-38 (#43): {@code requests.csv} starts with EF BB BF and the header row (which names {@code decidedBy}, D-37). */
    @Test
    public void t_12_38_requestsCsvStartsWithTheUtf8Bom() throws Exception {
        assertBomExport("requests.csv", REASON, "decidedBy");
    }

    /** T-12-39 (#43): {@code changes.csv} starts with EF BB BF and the header row. */
    @Test
    public void t_12_39_changesCsvStartsWithTheUtf8Bom() throws Exception {
        assertBomExport("changes.csv", JOB, null);
    }

    /** T-12-40 (#43): {@code incidents.csv} starts with EF BB BF and the header row. */
    @Test
    public void t_12_40_incidentsCsvStartsWithTheUtf8Bom() throws Exception {
        assertBomExport("incidents.csv", JOB, null);
    }

    /**
     * T-12-41 (#43): an export of a month without records is the BOM followed by the same header row
     * as the full export, and nothing else but line ends. Guard: 403 without ViewHistory, the content
     * type, and the header equal to the full export's.
     */
    @Test
    public void t_12_41_exportWithoutRowsIsTheBomAndTheHeader() throws Exception {
        StringBuilder problems = new StringBuilder();
        for (String csv : new String[] {"runs.csv", "requests.csv", "changes.csv", "incidents.csv"}) {
            assertEquals(403, fetch("nohist", csv + EMPTY_PERIOD).getStatusCode(), "guard (SPEC 12): " + csv + " without ViewHistory is 403");
            byte[] full = body(csv);
            byte[] empty = body(csv + EMPTY_PERIOD);
            String fullHeader = firstLine(utf8(stripBom(full), csv));
            String emptyText = utf8(stripBom(empty), csv + EMPTY_PERIOD);
            assertEquals(fullHeader, firstLine(emptyText), "guard: the export of an empty month carries the same header row as " + csv);
            assertEquals("", emptyText.substring(firstLine(emptyText).length()).replaceAll("[\r\n]", ""),
                    "guard: the export of an empty month has no data row: " + describe(emptyText));
            String bomProblem = bomProblem(empty);
            if (bomProblem != null) {
                problems.append(csv).append(EMPTY_PERIOD).append(": ").append(bomProblem).append("; ");
            }
        }
        if (problems.length() > 0) {
            fail("#43 (D-85): every CSV export starts with the UTF-8 BOM followed by the header row: " + problems);
        }
    }

    // ---------------------------------------------------------------- the check

    private void assertBomExport(String csv, String marker, String headerColumn) throws Exception {
        // Guards: what holds today and must keep holding.
        assertEquals(403, fetch("nohist", csv).getStatusCode(), "guard (SPEC 12): " + csv + " without ViewHistory is 403");
        WebResponse response = fetch("viewer", csv);
        assertEquals(200, response.getStatusCode(), "guard: the ViewHistory holder downloads " + csv);
        String contentType = response.getResponseHeaderValue("Content-Type");
        assertEquals("text/csv;charset=utf-8", contentType == null ? null : contentType.replace(" ", "").toLowerCase(Locale.ROOT),
                "guard: " + csv + " keeps the content type text/csv; charset=UTF-8, got " + contentType);
        byte[] raw = bytes(response);
        byte[] markerBytes = marker.getBytes(StandardCharsets.UTF_8);
        assertTrue(indexOf(raw, markerBytes) >= 0, "guard: " + csv + " carries " + marker + " as UTF-8 bytes ("
                + HexFormat.ofDelimiter(" ").formatHex(markerBytes) + "); head: " + head(raw));
        String text = utf8(stripBom(raw), csv);
        String header = firstLine(text);
        assertFalse(header.isBlank(), "guard: " + csv + " begins with a header row: " + describe(text));
        assertFalse(header.contains(marker), "guard: the first line of " + csv + " is the header, not a data row: " + describe(header));
        if (headerColumn != null) {
            assertTrue(header.toLowerCase(Locale.ROOT).contains(headerColumn.toLowerCase(Locale.ROOT)),
                    "guard: the header row of " + csv + " names " + headerColumn + ": " + describe(header));
        }

        // #43 (D-85): the BOM, exactly once, directly followed by the header row.
        String problem = bomProblem(raw);
        if (problem != null) {
            fail("#43 (D-85): " + csv + " must start with the UTF-8 BOM followed by the header row: " + problem);
        }
    }

    /** Null when the bytes are BOM + header row; otherwise what is wrong. */
    private static String bomProblem(byte[] raw) {
        if (raw.length < BOM.length || !Arrays.equals(Arrays.copyOf(raw, BOM.length), BOM)) {
            return "the first bytes are " + head(raw) + ", not EF BB BF";
        }
        byte[] rest = Arrays.copyOfRange(raw, BOM.length, raw.length);
        if (rest.length >= BOM.length && Arrays.equals(Arrays.copyOf(rest, BOM.length), BOM)) {
            return "the BOM is written twice: " + head(raw);
        }
        if (rest.length == 0 || rest[0] == '\r' || rest[0] == '\n') {
            return "the BOM is not followed directly by the header row: " + head(raw);
        }
        return null;
    }

    // ---------------------------------------------------------------- helpers

    private byte[] body(String relative) throws Exception {
        WebResponse response = fetch("viewer", relative);
        assertEquals(200, response.getStatusCode(), "the ViewHistory holder downloads " + relative);
        return bytes(response);
    }

    private WebResponse fetch(String user, String relative) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login(user);
        return wc.getPage(new WebRequest(new URL(j.getURL(), HISTORY + relative), HttpMethod.GET)).getWebResponse();
    }

    private static byte[] bytes(WebResponse response) throws Exception {
        try (InputStream in = response.getContentAsStream()) {
            return in.readAllBytes();
        }
    }

    private static byte[] stripBom(byte[] raw) {
        if (raw.length >= BOM.length && Arrays.equals(Arrays.copyOf(raw, BOM.length), BOM)) {
            return Arrays.copyOfRange(raw, BOM.length, raw.length);
        }
        return raw;
    }

    /** Strict UTF-8 decoding: malformed input fails the guard. */
    private static String utf8(byte[] bytes, String what) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new AssertionError("guard: " + what + " must be well-formed UTF-8: " + e, e);
        }
    }

    private static String firstLine(String text) {
        int end = text.indexOf('\n');
        String line = end < 0 ? text : text.substring(0, end);
        return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int k = 0; k < needle.length; k++) {
                if (haystack[i + k] != needle[k]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    /** The first 16 bytes in hex. */
    private static String head(byte[] raw) {
        return HexFormat.ofDelimiter(" ").formatHex(Arrays.copyOf(raw, Math.min(16, raw.length)));
    }

    private static String describe(String value) {
        String visible = value.replace("\t", "\\t").replace("\r", "\\r").replace("\n", "\\n");
        return "\"" + (visible.length() <= 400 ? visible : visible.substring(0, 400) + "...") + "\"";
    }
}
