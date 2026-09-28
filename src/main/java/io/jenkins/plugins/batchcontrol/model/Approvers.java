package io.jenkins.plugins.batchcontrol.model;

import hudson.model.User;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import jenkins.model.Jenkins;
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

    /**
     * Whether two user ids name the same user under Jenkins' configured user id strategy
     * (SPEC item 3, #23; security-08 S-12), for example case-insensitively by default.
     */
    public static boolean sameUser(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return Jenkins.getInstanceOrNull() == null ? a.equals(b) : User.idStrategy().equals(a, b);
    }

    /** Whether {@code userId} is a member of {@code ids} under the user id strategy. */
    public static boolean contains(List<String> ids, String userId) {
        if (ids == null || userId == null) {
            return false;
        }
        for (String id : ids) {
            if (sameUser(id, userId)) {
                return true;
            }
        }
        return false;
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
