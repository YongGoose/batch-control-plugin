package io.jenkins.plugins.batchcontrol.model;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Helpers for a designated approver set (D-37): order-preserving, duplicate-free, trimmed,
 * blank entries dropped. Shared by the request models and the policy layer.
 */
@Restricted(NoExternalUse.class)
public final class Approvers {

    private Approvers() {
    }

    /** Trimmed, de-duplicated (first occurrence wins) copy with blank entries removed. */
    public static List<String> normalize(List<String> approvers) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        if (approvers != null) {
            for (String approver : approvers) {
                if (approver != null && !approver.trim().isEmpty()) {
                    set.add(approver.trim());
                }
            }
        }
        return new ArrayList<>(set);
    }

    /** The one-element set for a single (legacy) approver id; empty for {@code null}/blank. */
    public static List<String> of(String approver) {
        List<String> list = new ArrayList<>();
        if (approver != null && !approver.trim().isEmpty()) {
            list.add(approver.trim());
        }
        return list;
    }

    /** Members joined by {@code ", "} for display; empty string for an empty set. */
    public static String display(List<String> approvers) {
        return approvers == null ? "" : String.join(", ", approvers);
    }

    /** Members joined by {@code ";"}, the CSV form of the {@code approver} column (D-37). */
    public static String csv(List<String> approvers) {
        return approvers == null ? "" : String.join(";", approvers);
    }
}
