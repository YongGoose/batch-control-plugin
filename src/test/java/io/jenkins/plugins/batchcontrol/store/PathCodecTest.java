package io.jenkins.plugins.batchcontrol.store;

import io.jenkins.plugins.batchcontrol.model.GrantScope;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests (no Jenkins). Matrix rows T-SEC-04 (path codec hardening, RT-12 extension),
 * T-SEC-03 (FOLDER scope boundary, prefix misjudgment prevention) and T-SEC-16
 * (empty scope full name matches nothing — DECISIONS P-10 / security-02 S-13a).
 *
 * Written from docs/SPEC.md, docs/ARCHITECTURE.md section 5 and docs/TEST-MATRIX.md only.
 */
public class PathCodecTest {

    /**
     * T-SEC-04: job names "../x", names with control characters, and names longer than 255 chars
     * must never escape the store directory, lose records, or overwrite another job's file.
     */
    @Test
    public void t_sec_04_pathEscapeControlCharsAndOverlongNamesAreSafe() {
        Path base = Paths.get("batch-control", "snapshots");

        // 1. Path traversal: a raw "../x" must never be usable as a store file name.
        assertThrows(IllegalArgumentException.class, () -> PathCodec.resolveUnder(base, "../x"));
        assertThrows(IllegalArgumentException.class, () -> PathCodec.resolveUnder(base, "..\\x"));
        // Decoding validates too: a traversal string masquerading as an encoded file name is rejected.
        assertThrows(IllegalArgumentException.class, () -> PathCodec.decode("../x"));

        // Encoding a hostile job name must produce a single, non-escaping path segment.
        String encoded = PathCodec.encode("../x");
        assertFalse(encoded.contains("/"));
        assertFalse(encoded.contains("\\"));
        assertNotEquals(".", encoded);
        assertNotEquals("..", encoded);
        Path resolved = PathCodec.resolveUnder(base, encoded);
        assertTrue("the resolved path must stay inside the store directory",
                resolved.normalize().startsWith(base.normalize()));
        assertEquals("encode/decode must round-trip for short names", "../x", PathCodec.decode(encoded));

        // 2. Control characters: encoded to a safe printable file name, lossless round-trip (no record loss).
        String controlName = "job" + (char) 7 + "name\nwith\tcontrol" + (char) 31 + "chars";
        String encodedControl = PathCodec.encode(controlName);
        for (char c : encodedControl.toCharArray()) {
            assertTrue("encoded name must not contain control characters, found 0x"
                    + Integer.toHexString(c), c >= 0x20);
        }
        assertFalse(encodedControl.contains("/"));
        assertFalse(encodedControl.contains("\\"));
        assertEquals(controlName, PathCodec.decode(encodedControl));

        // 3. Over-long names: bounded length (file system writable) without collisions (no overwrite).
        String longPrefix = "team/batch/" + "a".repeat(300);
        String longNameA = longPrefix + "-ONE";
        String longNameB = longPrefix + "-TWO";
        String encodedA = PathCodec.encode(longNameA);
        String encodedB = PathCodec.encode(longNameB);
        assertTrue("encoded name must leave room for an extension within the 255-char file name limit",
                encodedA.length() <= 250);
        assertTrue(encodedB.length() <= 250);
        assertNotEquals("two long names differing only at the tail must never map to the same file",
                encodedA, encodedB);
        assertFalse(encodedA.contains("/"));
        assertFalse(encodedA.contains("\\"));
        assertTrue(PathCodec.resolveUnder(base, encodedA).normalize().startsWith(base.normalize()));
        // Determinism: the same name must always map to the same file (records stay findable).
        assertEquals(encodedA, PathCodec.encode(longNameA));
    }

    /**
     * T-SEC-03: FOLDER scope "team/batch" must not include "team/batch-other" —
     * prefix matching happens on path-segment boundaries only.
     */
    @Test
    public void t_sec_03_folderScopeBoundaryIsSegmentExact() {
        GrantScope folder = new GrantScope(GrantScope.Type.FOLDER, "team/batch");
        assertTrue(folder.includes("team/batch/job1"));
        assertTrue(folder.includes("team/batch/sub/job2"));
        assertTrue("the folder itself is in scope (CREATE is checked on the folder ACL)",
                folder.includes("team/batch"));
        assertFalse("prefix must match on segment boundary only", folder.includes("team/batch-other"));
        assertFalse(folder.includes("team/batch-other/job1"));
        assertFalse(folder.includes("team/batchx"));
        assertFalse(folder.includes("team"));
        assertFalse(folder.includes("other/batch/job1"));

        GrantScope job = new GrantScope(GrantScope.Type.JOB, "team/batch/job1");
        assertTrue(job.includes("team/batch/job1"));
        assertFalse("JOB scope is exact match only", job.includes("team/batch/job10"));
        assertFalse(job.includes("team/batch"));
        assertFalse(job.includes("team/batch/job1/sub"));
    }

    /**
     * T-SEC-16 (SPEC item 8 / DECISIONS P-10, security-02 S-13a): a scope whose full name is
     * empty is not "the Jenkins root", it is nothing. For BOTH scope types
     * {@code includes(anything)} must be false — including the empty full name itself — so that
     * a scope rebuilt by XStream from a store file written before the rule existed (or edited by
     * hand) can never confer instance-wide CREATE/CONFIGURE/DELETE.
     */
    @Test
    public void t_sec_16_emptyScopeNameMatchesNothing() {
        String[] candidates = {
            "", "batch-x", "team", "team/batch", "team/batch/job1", "/", "a/b/c/d",
        };
        for (GrantScope.Type type : GrantScope.Type.values()) {
            // The model object itself is constructible with an empty name (XStream rebuilds
            // persisted scopes without any constructor anyway), so the fail-closed guard has to
            // live in includes() — that is what this row pins.
            GrantScope empty = new GrantScope(type, "");
            for (String fullName : candidates) {
                assertFalse("an empty " + type + " scope must include nothing, but it claimed to "
                        + "include \"" + fullName + "\" (P-10: root-scope grants are not supported)",
                        empty.includes(fullName));
            }
        }
    }
}
