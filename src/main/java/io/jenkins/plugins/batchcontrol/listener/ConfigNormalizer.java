package io.jenkins.plugins.batchcontrol.listener;

import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Reduces an item's {@code config.xml} to its user-editable content before it is compared and
 * diffed (SPEC item 9).
 *
 * <p>Three things are removed, and nothing else:
 * <ul>
 *   <li>the {@code plugin="name@version"} attribute XStream writes on elements contributed by a
 *       plugin. It changes on every plugin upgrade without anyone touching the item, so saving an
 *       unchanged item after an upgrade must not look like a configuration change;</li>
 *   <li>the root element's {@code <actions>} child. It is {@code Actionable}'s persisted action
 *       list, which is written by Jenkins and plugins (for example Pipeline's declarative tracking
 *       actions after a run) and has no configuration form. Computed folders
 *       (multibranch projects, organization folders) save themselves on every indexing; with
 *       their computed state held in {@code state.xml} and {@code indexing/}, the persisted
 *       actions and the plugin versions are what such a self-save can change in
 *       {@code config.xml};</li>
 *   <li>every {@code <configVersion>} leaf element holding a whole number, at any depth (e2e
 *       re-audit DEF-30) and whose start tag has no attributes; any other {@code configVersion} is
 *       kept (S-18-02, S-19-02). It is a
 *       plugin's own format-version bookkeeping (for example throttle-concurrents'
 *       {@code ThrottleJobProperty}), written by the plugin's {@code readResolve}/migration code and
 *       not by any form field, so an upgraded plugin re-serialising an unchanged item adds or bumps
 *       it without a user edit. Core, workflow-job and cloudbees-folder configurations carry no
 *       other upgrade-only element: their version bookkeeping is the {@code plugin} attribute.</li>
 * </ul>
 *
 * <p>Whitespace is kept as it is: the unified diff compares lines verbatim, so a whitespace
 * change is still a change. Comments, CDATA sections and processing instructions are copied
 * untouched. The input is never rejected: text that is not well-formed is copied as far as it
 * can be scanned, so a broken file still compares and diffs.
 */
@Restricted(NoExternalUse.class)
final class ConfigNormalizer {

    private static final String PLUGIN = "plugin";

    private static final String ACTIONS = "actions";

    /** A plugin's persisted format version (DEF-30); dropped wherever it appears. */
    private static final String CONFIG_VERSION = "configVersion";

    private ConfigNormalizer() {
    }

    /** The user-editable form of {@code xml}; equal inputs give equal outputs. */
    static String normalize(String xml) {
        int n = xml.length();
        StringBuilder out = new StringBuilder(n);
        int depth = 0;
        int skipDepth = -1; // depth at which the element being dropped started, -1 when copying
        int i = 0;
        while (i < n) {
            char c = xml.charAt(i);
            if (c != '<') {
                if (skipDepth < 0) {
                    out.append(c);
                }
                i++;
                continue;
            }
            int end;
            if (xml.startsWith("<!--", i)) {
                end = endOf(xml, "-->", i + 4);
            } else if (xml.startsWith("<![CDATA[", i)) {
                end = endOf(xml, "]]>", i + 9);
            } else if (xml.startsWith("<?", i) || xml.startsWith("<!", i)) {
                end = endOf(xml, ">", i + 2);
            } else {
                end = tagEnd(xml, i);
                if (end < 0) {
                    // Unterminated tag: copy the rest verbatim.
                    if (skipDepth < 0) {
                        out.append(xml, i, n);
                    }
                    break;
                }
                boolean closing = i + 1 < n && xml.charAt(i + 1) == '/';
                boolean selfClosing = !closing && xml.charAt(end - 2) == '/';
                if (closing) {
                    depth--;
                    if (skipDepth >= 0) {
                        if (depth == skipDepth) {
                            skipDepth = -1;
                            i = skipLineBreak(xml, end, out);
                        } else {
                            i = end;
                        }
                        continue;
                    }
                    out.append(xml, i, end);
                    i = end;
                    continue;
                }
                if (skipDepth < 0 && !selfClosing && depth >= 1 && CONFIG_VERSION.equals(tagName(xml, i + 1))
                        && xml.substring(i + 1 + CONFIG_VERSION.length(), end - 1).isBlank()) {
                    // S-19-02: only the bare start tag (no attributes) qualifies.
                    int after = numericLeafEnd(xml, end);
                    if (after >= 0) {
                        // S-18-02: only a leaf holding a whole number is version bookkeeping.
                        trimTrailingIndent(out);
                        i = skipLineBreak(xml, after, out);
                        continue;
                    }
                }
                if (skipDepth < 0 && isDropped(depth, tagName(xml, i + 1))) {
                    trimTrailingIndent(out);
                    if (selfClosing) {
                        i = skipLineBreak(xml, end, out);
                    } else {
                        skipDepth = depth;
                        depth++;
                        i = end;
                    }
                    continue;
                }
                if (skipDepth < 0) {
                    String tag = xml.substring(i, end);
                    out.append(tag.contains(PLUGIN) ? withoutPluginAttribute(tag) : tag);
                }
                if (!selfClosing) {
                    depth++;
                }
                i = end;
                continue;
            }
            if (skipDepth < 0) {
                out.append(xml, i, end);
            }
            i = end;
        }
        return out.toString();
    }

    /**
     * The start tag with any attribute whose name is exactly {@code plugin} removed, together with
     * the whitespace before it. The tag is tokenised into name, {@code =} and quoted value spans,
     * so text inside another attribute's value is never touched (security-07 S-02). A tag that
     * does not tokenise cleanly is returned unchanged.
     */
    static String withoutPluginAttribute(String tag) {
        int n = tag.length();
        int i = 1;
        while (i < n && !isNameEnd(tag.charAt(i))) {
            i++; // element name
        }
        StringBuilder out = new StringBuilder(n);
        out.append(tag, 0, i);
        while (i < n) {
            int wsStart = i;
            while (i < n && Character.isWhitespace(tag.charAt(i))) {
                i++;
            }
            if (i >= n || tag.charAt(i) == '>' || tag.charAt(i) == '/') {
                out.append(tag, wsStart, n);
                return out.toString();
            }
            if (i == wsStart) {
                return tag; // an attribute must be preceded by whitespace
            }
            int nameStart = i;
            while (i < n && !isNameEnd(tag.charAt(i)) && tag.charAt(i) != '=') {
                i++;
            }
            String name = tag.substring(nameStart, i);
            while (i < n && Character.isWhitespace(tag.charAt(i))) {
                i++;
            }
            if (name.isEmpty() || i >= n || tag.charAt(i) != '=') {
                return tag;
            }
            i++;
            while (i < n && Character.isWhitespace(tag.charAt(i))) {
                i++;
            }
            if (i >= n || (tag.charAt(i) != '"' && tag.charAt(i) != '\'')) {
                return tag;
            }
            int close = tag.indexOf(tag.charAt(i), i + 1);
            if (close < 0) {
                return tag;
            }
            i = close + 1;
            if (!PLUGIN.equals(name)) {
                out.append(tag, wsStart, i);
            }
        }
        return out.toString();
    }

    /** Whether the element {@code name} starting at {@code depth} is dropped with its whole subtree. */
    private static boolean isDropped(int depth, String name) {
        return depth == 1 && ACTIONS.equals(name);
    }

    /**
     * For a {@code <configVersion>} start tag ending at {@code from}: the index just past its end
     * tag when the element is a leaf whose text is a whole number ({@code \s*-?\d+\s*}, no child
     * element, comment or CDATA), else -1 (S-18-02).
     */
    static int numericLeafEnd(String xml, int from) {
        int close = xml.indexOf('<', from);
        if (close < 0 || !xml.startsWith("</" + CONFIG_VERSION, close)) {
            return -1;
        }
        int j = close + 2 + CONFIG_VERSION.length();
        while (j < xml.length() && Character.isWhitespace(xml.charAt(j))) {
            j++;
        }
        if (j >= xml.length() || xml.charAt(j) != '>') {
            return -1;
        }
        String text = xml.substring(from, close).strip();
        int k = text.startsWith("-") ? 1 : 0;
        if (k >= text.length()) {
            return -1;
        }
        for (; k < text.length(); k++) {
            char c = text.charAt(k);
            if (c < '0' || c > '9') {
                return -1;
            }
        }
        return j + 1;
    }

    private static boolean isNameEnd(char c) {
        return Character.isWhitespace(c) || c == '>' || c == '/';
    }

    /** Index just past {@code terminator} searched from {@code from}, or the end of the text. */
    private static int endOf(String xml, String terminator, int from) {
        int idx = xml.indexOf(terminator, from);
        return idx < 0 ? xml.length() : idx + terminator.length();
    }

    /** Index just past the {@code >} closing the tag at {@code start}, honouring quotes; -1 if none. */
    private static int tagEnd(String xml, int start) {
        char quote = 0;
        for (int j = start + 1; j < xml.length(); j++) {
            char c = xml.charAt(j);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '>') {
                return j + 1;
            }
        }
        return -1;
    }

    private static String tagName(String xml, int from) {
        int j = from;
        while (j < xml.length()) {
            char c = xml.charAt(j);
            if (Character.isWhitespace(c) || c == '>' || c == '/') {
                break;
            }
            j++;
        }
        return xml.substring(from, j);
    }

    /** Drops the indentation that preceded a removed element, so no blank line is left. */
    private static void trimTrailingIndent(StringBuilder out) {
        int len = out.length();
        while (len > 0 && (out.charAt(len - 1) == ' ' || out.charAt(len - 1) == '\t')) {
            len--;
        }
        if (len == 0 || out.charAt(len - 1) == '\n') {
            out.setLength(len);
        }
    }

    /** After a removed element that stood on its own line, also consumes its line break. */
    private static int skipLineBreak(String xml, int at, StringBuilder out) {
        int len = out.length();
        if (len != 0 && out.charAt(len - 1) != '\n') {
            return at;
        }
        if (xml.startsWith("\r\n", at)) {
            return at + 2;
        }
        if (xml.startsWith("\n", at)) {
            return at + 1;
        }
        return at;
    }
}
