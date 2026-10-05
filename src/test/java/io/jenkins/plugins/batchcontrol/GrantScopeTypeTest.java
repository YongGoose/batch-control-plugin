package io.jenkins.plugins.batchcontrol;

import io.jenkins.plugins.batchcontrol.model.GrantScope;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit row (no Jenkins), SPEC section 3 and item 8 (D-71): the scope type of a request and a
 * window is {@code ITEM} and nothing else; the earlier types JOB, FOLDER and FOLDER_ONLY are gone
 * (not kept next to ITEM, D-71's rejected alternative). {@code GrantScope.item(fullName)} builds
 * the same scope as the constructor, and its reach is the named item exactly. Matrix row
 * T-08-125 (note 260).
 *
 * <p>Written from docs/SPEC.md, docs/DECISIONS.md D-71 and docs/TEST-MATRIX.md only (no src/main
 * knowledge).
 */
public class GrantScopeTypeTest {

    /** T-08-125: ITEM is the only scope type; item() equals the constructor; the reach is exact. */
    @Test
    public void t_08_125_itemIsTheOnlyScopeType() {
        assertArrayEquals(new GrantScope.Type[] {GrantScope.Type.ITEM}, GrantScope.Type.values(),
                "D-71: ITEM must be the only scope type");
        for (String withdrawn : new String[] {"JOB", "FOLDER", "FOLDER_ONLY"}) {
            assertThrows(IllegalArgumentException.class, () -> GrantScope.Type.valueOf(withdrawn),
                    "D-71: the scope type " + withdrawn + " must be gone");
        }

        GrantScope viaFactory = GrantScope.item("team/x");
        GrantScope viaConstructor = new GrantScope(GrantScope.Type.ITEM, "team/x");
        assertEquals(GrantScope.Type.ITEM, viaFactory.getType());
        assertEquals("team/x", viaFactory.getFullName());
        assertEquals(viaConstructor.getType(), viaFactory.getType());
        assertEquals(viaConstructor.getFullName(), viaFactory.getFullName());

        assertTrue(viaFactory.includes("team/x"), "the named item is in scope");
        assertFalse(viaFactory.includes("team/x/child"), "an item inside the named one is not");
        assertFalse(viaFactory.includes("team"), "the parent is not");
        assertFalse(viaFactory.includes("team/x2"), "a name with the same prefix is not");
    }
}
