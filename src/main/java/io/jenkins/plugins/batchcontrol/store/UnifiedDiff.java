package io.jenkins.plugins.batchcontrol.store;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Minimal unified-diff generator for config.xml change records (SPEC item 9). LCS-based,
 * three context lines per hunk, standard {@code @@ -a,b +c,d @@} hunk headers.
 *
 * <p>Size guard: pathological inputs (huge configs or a diff longer than
 * {@value #MAX_DIFF_LINES} lines) are replaced by a short "diff too large" note so a single
 * change can never bloat the store or the UI.
 */
@Restricted(NoExternalUse.class)
public final class UnifiedDiff {

    /** Context lines before and after each change run (the unified-diff convention). */
    private static final int CONTEXT = 3;

    /** Upper bound on the emitted diff body; beyond it only a note is stored. */
    private static final int MAX_DIFF_LINES = 2000;

    /** Upper bound on the LCS table size (cells); config files are far below this. */
    private static final long MAX_LCS_CELLS = 20_000_000L;

    private UnifiedDiff() {
    }

    /**
     * Computes the unified diff between two texts.
     *
     * @return the diff text, or {@code null} when the texts are line-identical
     */
    public static String diff(String oldText, String newText) {
        Objects.requireNonNull(oldText, "oldText");
        Objects.requireNonNull(newText, "newText");
        List<String> oldLines = splitLines(oldText);
        List<String> newLines = splitLines(newText);
        if (oldLines.equals(newLines)) {
            return null;
        }
        if ((long) oldLines.size() * newLines.size() > MAX_LCS_CELLS) {
            return tooLarge(Math.max(oldLines.size(), newLines.size()));
        }

        boolean[] oldKept = new boolean[oldLines.size()];
        boolean[] newKept = new boolean[newLines.size()];
        markCommonLines(oldLines, newLines, oldKept, newKept);

        List<String> body = hunks(oldLines, newLines, oldKept, newKept);
        if (body.size() > MAX_DIFF_LINES) {
            return tooLarge(body.size());
        }
        StringBuilder sb = new StringBuilder();
        sb.append("--- before\n+++ after\n");
        for (String line : body) {
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    private static String tooLarge(int lines) {
        return "Diff too large to store (" + lines
                + " lines); the change was recorded without the full diff.";
    }

    private static List<String> splitLines(String text) {
        List<String> lines = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                int end = i > start && text.charAt(i - 1) == '\r' ? i - 1 : i;
                lines.add(text.substring(start, end));
                start = i + 1;
            }
        }
        if (start < text.length()) {
            lines.add(text.substring(start));
        }
        return lines;
    }

    /** Classic LCS dynamic program; marks the lines that belong to the common subsequence. */
    private static void markCommonLines(List<String> oldLines, List<String> newLines,
                                        boolean[] oldKept, boolean[] newKept) {
        int m = oldLines.size();
        int n = newLines.size();
        int[][] lcs = new int[m + 1][n + 1];
        for (int i = m - 1; i >= 0; i--) {
            for (int j = n - 1; j >= 0; j--) {
                if (oldLines.get(i).equals(newLines.get(j))) {
                    lcs[i][j] = lcs[i + 1][j + 1] + 1;
                } else {
                    lcs[i][j] = Math.max(lcs[i + 1][j], lcs[i][j + 1]);
                }
            }
        }
        int i = 0;
        int j = 0;
        while (i < m && j < n) {
            if (oldLines.get(i).equals(newLines.get(j))) {
                oldKept[i] = true;
                newKept[j] = true;
                i++;
                j++;
            } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
                i++;
            } else {
                j++;
            }
        }
    }

    /** Emits hunks with {@value #CONTEXT} context lines; adjacent change runs merge naturally. */
    private static List<String> hunks(List<String> oldLines, List<String> newLines,
                                      boolean[] oldKept, boolean[] newKept) {
        List<String> out = new ArrayList<>();
        int i = 0;
        int j = 0;
        int m = oldLines.size();
        int n = newLines.size();
        // Old-side index just past the last line emitted by the previous hunk, so a new hunk's
        // leading context never overlaps the previous hunk's trailing context.
        int prevHunkOldEnd = 0;
        while (i < m || j < n) {
            // Skip a stretch of common lines.
            while (i < m && j < n && oldKept[i] && newKept[j]) {
                i++;
                j++;
            }
            if (i >= m && j >= n) {
                break;
            }
            // A change begins here; open a hunk with up to CONTEXT common lines before it.
            int hunkOldStart = Math.max(prevHunkOldEnd, i - CONTEXT);
            int hunkNewStart = j - (i - hunkOldStart);
            List<String> hunkBody = new ArrayList<>();
            for (int k = hunkOldStart; k < i; k++) {
                hunkBody.add(" " + oldLines.get(k));
            }
            int hunkOldCount = i - hunkOldStart;
            int hunkNewCount = i - hunkOldStart;
            // Alternate change runs and short common runs until a common run longer than
            // 2*CONTEXT (or the end) closes the hunk.
            while (i < m || j < n) {
                while (i < m && !oldKept[i]) {
                    hunkBody.add("-" + oldLines.get(i));
                    i++;
                    hunkOldCount++;
                }
                while (j < n && !newKept[j]) {
                    hunkBody.add("+" + newLines.get(j));
                    j++;
                    hunkNewCount++;
                }
                // Measure the following common run.
                int commonLen = 0;
                while (i + commonLen < m && j + commonLen < n
                        && oldKept[i + commonLen] && newKept[j + commonLen]) {
                    commonLen++;
                }
                boolean atEnd = i + commonLen >= m && j + commonLen >= n;
                if (commonLen > 2 * CONTEXT || atEnd) {
                    int trailing = Math.min(commonLen, CONTEXT);
                    for (int k = 0; k < trailing; k++) {
                        hunkBody.add(" " + oldLines.get(i + k));
                    }
                    hunkOldCount += trailing;
                    hunkNewCount += trailing;
                    prevHunkOldEnd = i + trailing;
                    i += commonLen;
                    j += commonLen;
                    break;
                }
                for (int k = 0; k < commonLen; k++) {
                    hunkBody.add(" " + oldLines.get(i + k));
                }
                hunkOldCount += commonLen;
                hunkNewCount += commonLen;
                i += commonLen;
                j += commonLen;
            }
            out.add("@@ -" + (hunkOldCount == 0 ? hunkOldStart : hunkOldStart + 1) + ","
                    + hunkOldCount + " +" + (hunkNewCount == 0 ? hunkNewStart : hunkNewStart + 1)
                    + "," + hunkNewCount + " @@");
            out.addAll(hunkBody);
        }
        return out;
    }
}
