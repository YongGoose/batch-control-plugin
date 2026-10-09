package io.jenkins.plugins.batchcontrol;

import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.HtmlAnchor;
import org.htmlunit.html.HtmlCheckBoxInput;
import org.htmlunit.html.HtmlForm;
import org.htmlunit.html.HtmlInput;
import org.htmlunit.html.HtmlOption;
import org.htmlunit.html.HtmlPage;
import org.htmlunit.html.HtmlSelect;
import org.htmlunit.html.HtmlTextArea;
import org.jvnet.hudson.test.JenkinsRule;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Shared checks for the e2e-03 part 2 regression rows (DEF-08 .. DEF-27, matrix notes 123-139),
 * all derived from the SPEC section 6 usability line: "every button, link and form is shown only
 * to users who can use it; every refusal, on the web, the CLI or a Replay, tells the user in plain
 * words why and what to do instead (no bare "Access Denied", stack trace, "Oops!" page or generic
 * toast from our own code); invalid input is refused with a message next to the field and the
 * user's input is kept; no link leads to a 404 or 403 page; recorded history names who did what".
 *
 * <p>Written from docs/SPEC.md, docs/reports/e2e-03.md and docs/TEST-MATRIX.md only (no src/main
 * knowledge).
 */
final class UsabilityFixtures {

    /** A Java stack frame as a servlet error page or the CLI prints it. */
    private static final Pattern STACK_FRAME = Pattern.compile("(?m)^\\s*at [\\w$.]+\\.[\\w$<>]+\\([^)]*\\)");

    private UsabilityFixtures() {
        // utility class
    }

    static JenkinsRule.WebClient client(JenkinsRule j, String userId) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false);
        if (userId != null) {
            wc.login(userId);
        }
        return wc;
    }

    static JenkinsRule.WebClient clientNoJs(JenkinsRule j, String userId) throws Exception {
        JenkinsRule.WebClient wc = client(j, userId);
        wc.getOptions().setJavaScriptEnabled(false);
        return wc;
    }

    static Page get(JenkinsRule j, JenkinsRule.WebClient wc, String relative) throws Exception {
        return wc.getPage(new WebRequest(new URL(j.getURL(), relative), HttpMethod.GET));
    }

    static HtmlPage htmlPage(JenkinsRule j, String userId, String relative) throws Exception {
        Page page = get(j, clientNoJs(j, userId), relative);
        assertTrue(page instanceof HtmlPage, userId + " GET " + relative + " must answer an HTML page, got "
                + page.getWebResponse().getContentType() + " HTTP " + page.getWebResponse().getStatusCode());
        return (HtmlPage) page;
    }

    static String text(Page page) {
        return page instanceof HtmlPage ? ((HtmlPage) page).asNormalizedText() : page.getWebResponse().getContentAsString();
    }

    /**
     * The usability line's refusal rule: the answer names {@code why} (a case-insensitive
     * pattern), and carries no "Oops!" page, no stack trace, no "Unexpected exception" and no bare
     * "Access Denied" from Jenkins' generic permission refusal.
     */
    static void assertPlainRefusal(String what, String text, Pattern why) {
        String lower = text.toLowerCase(Locale.ROOT);
        assertFalse(text.contains("Oops!"), what + ": the refusal must not be the \"Oops!\" crash page: " + excerpt(text));
        assertFalse(lower.contains("a problem occurred while processing the request"), what + ": the refusal must not be core's crash page: " + excerpt(text));
        assertFalse(STACK_FRAME.matcher(text).find(), what + ": the refusal must not print a stack trace: " + excerpt(text));
        assertFalse(text.contains("Caused by:"), what + ": the refusal must not print a stack trace: " + excerpt(text));
        assertFalse(lower.contains("unexpected exception"), what + ": the refusal must not be reported as an unexpected exception: " + excerpt(text));
        assertFalse(text.contains("Access Denied"), what + ": the refusal must not be a bare \"Access Denied\": " + excerpt(text));
        if (why != null) {
            assertTrue(why.matcher(text).find(), what + ": the refusal must say why in plain words (" + why.pattern() + "): " + excerpt(text));
        }
    }

    /** A bare error page is titled "Error" (core's generic error page). */
    static void assertNotBareErrorPage(String what, HtmlPage page) {
        String title = page.getTitleText() == null ? "" : page.getTitleText().trim();
        assertFalse(title.matches("(?i)^error\\b.*"), what + ": the refusal must not be core's bare page titled \"Error\" (title was '"
                + title + "'): " + excerpt(page.asNormalizedText()));
    }

    /** Forms whose action attribute, resolved against the page, ends with {@code suffix}. */
    static List<HtmlForm> formsEndingWith(HtmlPage page, String suffix) throws Exception {
        List<HtmlForm> out = new ArrayList<>();
        for (HtmlForm form : page.getForms()) {
            String action = form.getActionAttribute();
            String resolved = action == null || action.isEmpty()
                    ? page.getUrl().toExternalForm() : page.getFullyQualifiedUrl(action).toExternalForm();
            resolved = stripQueryAndSlash(resolved);
            if (resolved.endsWith(suffix)) {
                out.add(form);
            }
        }
        return out;
    }

    static List<String> formActions(HtmlPage page) {
        List<String> out = new ArrayList<>();
        for (HtmlForm form : page.getForms()) {
            out.add(form.getActionAttribute());
        }
        return out;
    }

    /** Resolved hrefs (query, fragment and trailing slash stripped) of every anchor on the page. */
    static List<String> resolvedHrefs(HtmlPage page) throws Exception {
        List<String> out = new ArrayList<>();
        for (HtmlAnchor a : page.getAnchors()) {
            String href = a.getHrefAttribute();
            if (href == null || href.isEmpty() || href.startsWith("#") || href.startsWith("javascript:")
                    || href.startsWith("mailto:")) {
                continue;
            }
            out.add(stripQueryAndSlash(page.getFullyQualifiedUrl(href).toExternalForm()));
        }
        return out;
    }

    /** True if some anchor resolves (query/fragment/trailing slash ignored) to {@code rootRelative}. */
    static boolean hasLinkTo(JenkinsRule j, HtmlPage page, String rootRelative) throws Exception {
        String target = stripQueryAndSlash(new URL(j.getURL(), rootRelative).toExternalForm());
        return resolvedHrefs(page).contains(target);
    }

    static List<HtmlAnchor> anchorsCaptioned(HtmlPage page, Pattern caption) {
        List<HtmlAnchor> out = new ArrayList<>();
        for (HtmlAnchor a : page.getAnchors()) {
            if (caption.matcher(a.asNormalizedText()).find()) {
                out.add(a);
            }
        }
        return out;
    }

    static String stripQueryAndSlash(String url) {
        int cut = url.indexOf('#');
        if (cut >= 0) {
            url = url.substring(0, cut);
        }
        cut = url.indexOf('?');
        if (cut >= 0) {
            url = url.substring(0, cut);
        }
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    /**
     * "No link leads to a 404 or 403 page": every anchor inside {@code #main-panel} that stays on
     * this Jenkins is opened by the same user and must answer below 400. Returns the number of
     * links checked, so a caller can assert the page offered some.
     */
    static int assertNoDeadLinks(JenkinsRule j, String userId, HtmlPage page, String where) throws Exception {
        DomElement main = page.getElementById("main-panel");
        assertTrue(main != null, where + ": fixture: the page must have a main panel");
        String root = j.getURL().toExternalForm();
        Set<String> targets = new LinkedHashSet<>();
        for (DomElement element : main.getElementsByTagName("a")) {
            if (!(element instanceof HtmlAnchor)) {
                continue;
            }
            String href = ((HtmlAnchor) element).getHrefAttribute();
            if (href == null || href.isEmpty() || href.startsWith("#") || href.startsWith("javascript:")
                    || href.startsWith("mailto:")) {
                continue;
            }
            String resolved = page.getFullyQualifiedUrl(href).toExternalForm();
            if (resolved.startsWith(root) && !resolved.contains("/logout")) {
                targets.add(resolved);
            }
        }
        JenkinsRule.WebClient wc = clientNoJs(j, userId);
        List<String> dead = new ArrayList<>();
        for (String target : targets) {
            int code = wc.getPage(new WebRequest(new URL(target), HttpMethod.GET)).getWebResponse().getStatusCode();
            if (code >= 400) {
                dead.add(code + " " + target);
            }
        }
        assertTrue(dead.isEmpty(), where + ": no link offered to " + userId + " may lead to a 404 or 403 page, but these did: " + dead);
        return targets.size();
    }

    // ------------------------------------------------------------------ form driving

    /** Sets the first input or textarea whose name is {@code field}, {@code _.field} or ends with {@code .field}. */
    static void setField(HtmlForm form, String field, String value) {
        for (DomElement element : form.getElementsByTagName("textarea")) {
            if (element instanceof HtmlTextArea && nameMatches(element.getAttribute("name"), field)) {
                ((HtmlTextArea) element).setText(value);
                return;
            }
        }
        for (DomElement element : form.getElementsByTagName("input")) {
            if (element instanceof HtmlInput && nameMatches(element.getAttribute("name"), field)) {
                ((HtmlInput) element).setValue(value);
                return;
            }
        }
        fail("fixture: the form must offer a field named " + field + "; form was: " + excerpt(form.asXml()));
    }

    static boolean hasField(HtmlForm form, String field) {
        for (String tag : new String[] {"textarea", "input", "select"}) {
            for (DomElement element : form.getElementsByTagName(tag)) {
                if (nameMatches(element.getAttribute("name"), field)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean nameMatches(String name, String field) {
        return name != null && (name.equals(field) || name.equals("_." + field) || name.endsWith("." + field));
    }

    /** True if some input or textarea of the page carries {@code value} (the user's input kept). */
    static boolean pageKeepsValue(HtmlPage page, String value) {
        for (DomElement element : page.getElementsByTagName("textarea")) {
            if (element instanceof HtmlTextArea && value.equals(((HtmlTextArea) element).getText())) {
                return true;
            }
        }
        for (DomElement element : page.getElementsByTagName("input")) {
            if (element instanceof HtmlInput && value.equals(((HtmlInput) element).getValue())) {
                return true;
            }
        }
        return false;
    }

    /** Selects {@code approver} in the {@code approvers} control (select, checkbox, radio or text). */
    static void selectApprover(HtmlForm form, String approver) {
        for (DomElement element : form.getElementsByTagName("select")) {
            if (element instanceof HtmlSelect && "approvers".equals(element.getAttribute("name"))) {
                ((HtmlSelect) element).setSelectedAttribute(approver, true);
                return;
            }
        }
        for (DomElement element : form.getElementsByTagName("input")) {
            if (element instanceof HtmlInput && "approvers".equals(element.getAttribute("name"))) {
                HtmlInput input = (HtmlInput) element;
                if ("radio".equalsIgnoreCase(input.getTypeAttribute())
                        || "checkbox".equalsIgnoreCase(input.getTypeAttribute())) {
                    if (approver.equals(input.getValue())) {
                        input.setChecked(true);
                    }
                } else {
                    input.setValue(approver);
                }
            }
        }
    }

    /**
     * The user ids the form's {@code approvers} control offers, in page order: the values of its
     * checkboxes, or the options of a multiple select, as they appear in the document.
     */
    static List<String> approverChoices(HtmlForm form) {
        List<String> out = new ArrayList<>();
        for (DomElement element : form.getHtmlElementDescendants()) {
            if (!"approvers".equals(element.getAttribute("name"))) {
                continue;
            }
            if (element instanceof HtmlCheckBoxInput) {
                out.add(((HtmlCheckBoxInput) element).getValue());
            } else if (element instanceof HtmlSelect) {
                for (HtmlOption option : ((HtmlSelect) element).getOptions()) {
                    out.add(option.getValueAttribute());
                }
            }
        }
        return out;
    }

    /**
     * Ticks exactly {@code approvers} in the form's {@code approvers} control (every other choice
     * is unticked), as a user does; each one must be offered (fixture assertion).
     */
    static void tickApprovers(HtmlForm form, String... approvers) {
        List<String> wanted = List.of(approvers);
        List<String> offered = approverChoices(form);
        assertTrue(offered.containsAll(wanted), "fixture: the form must offer every approver to tick " + wanted
                + " as a checkbox (or multiple select option) named approvers; offered " + offered + ": "
                + excerpt(form.asXml()));
        for (DomElement element : form.getHtmlElementDescendants()) {
            if (!"approvers".equals(element.getAttribute("name"))) {
                continue;
            }
            if (element instanceof HtmlCheckBoxInput) {
                HtmlCheckBoxInput box = (HtmlCheckBoxInput) element;
                box.setChecked(wanted.contains(box.getValue()));
            } else if (element instanceof HtmlSelect) {
                for (HtmlOption option : ((HtmlSelect) element).getOptions()) {
                    option.setSelected(wanted.contains(option.getValueAttribute()));
                }
            }
        }
    }

    static String excerpt(String text) {
        if (text == null) {
            return "<null>";
        }
        String flat = text.replaceAll("\\s+", " ");
        return flat.length() > 1200 ? flat.substring(0, 1200) + "..." : flat;
    }
}
