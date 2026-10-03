package io.jenkins.plugins.batchcontrol;

import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.htmlunit.html.DomAttr;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.DomNode;
import org.htmlunit.html.HtmlForm;
import org.htmlunit.html.HtmlPage;
import org.jvnet.hudson.test.JenkinsRule;

/**
 * Black-box helpers for the D-66 rows (notes 245-247): finding request entries, detail links and
 * controls by the URLs they lead to, never by caption or by assumed markup. An "entry" is any
 * element outside the Batch Control tab bar whose {@code href} or {@code data-*} attribute holds
 * a same-instance URL; a dialog opener and a plain link both qualify, because a dialog loads its
 * content from such a URL.
 *
 * <p>Written from docs/SPEC.md (D-66) and docs/TEST-MATRIX.md only (no src/main knowledge).
 */
final class RequestPageFixtures {

    private RequestPageFixtures() {
    }

    static boolean insideTabBar(DomNode node) {
        for (DomNode n = node; n != null; n = n.getParentNode()) {
            if (n instanceof DomElement e && e.hasAttribute("data-batch-control-tabs")) {
                return true;
            }
        }
        return false;
    }

    /** Same-instance URLs (query kept, fragment dropped) named by href/data-* attributes outside the tab bar, in document order. */
    static List<URL> entryTargets(JenkinsRule j, HtmlPage page) throws Exception {
        String root = j.getURL().toExternalForm();
        Set<String> out = new LinkedHashSet<>();
        for (DomElement e : page.getDocumentElement().getHtmlElementDescendants()) {
            if (insideTabBar(e)) {
                continue;
            }
            for (DomAttr attr : e.getAttributesMap().values()) {
                String name = attr.getName();
                if (!"href".equals(name) && !name.startsWith("data-")) {
                    continue;
                }
                String v = attr.getValue().trim();
                if (v.isEmpty() || v.startsWith("#") || v.startsWith("javascript:") || v.startsWith("mailto:")
                        || v.contains(" ") || v.startsWith("{") || v.startsWith("[")) {
                    continue;
                }
                if (!v.startsWith("/") && !v.startsWith("http") && !v.contains("/") && !v.startsWith("?")) {
                    continue; // not a URL
                }
                URL url;
                try {
                    url = page.getFullyQualifiedUrl(v);
                } catch (java.net.MalformedURLException ex) {
                    continue;
                }
                String s = url.toExternalForm();
                int hash = s.indexOf('#');
                if (hash >= 0) {
                    s = s.substring(0, hash);
                }
                String path = url.getPath();
                if (!s.startsWith(root) || path.contains("/static/") || path.contains("/adjuncts/")
                        || path.matches(".*\\.(css|js|png|svg|gif|ico)$") || path.contains("/logout")) {
                    continue;
                }
                out.add(s);
            }
        }
        List<URL> urls = new ArrayList<>();
        for (String s : out) {
            urls.add(new URL(s));
        }
        return urls;
    }

    /**
     * Entry targets of {@code page} (other than the page itself) whose GET renders a form posting to
     * a path ending in {@code actionSuffix}, mapped to that form.
     */
    static Map<URL, HtmlForm> entriesRenderingForm(JenkinsRule j, String userId, HtmlPage page, String actionSuffix)
            throws Exception {
        String self = UsabilityFixtures.stripQueryAndSlash(page.getUrl().toExternalForm());
        JenkinsRule.WebClient wc = UsabilityFixtures.clientNoJs(j, userId);
        Map<URL, HtmlForm> out = new LinkedHashMap<>();
        int fetched = 0;
        for (URL target : entryTargets(j, page)) {
            String path = target.getPath();
            if (!path.contains("batch-control") || path.endsWith(actionSuffix) || path.endsWith("/submit")
                    || path.endsWith("/create") || path.endsWith("/revoke") || path.endsWith("/cancel")) {
                continue;
            }
            if (UsabilityFixtures.stripQueryAndSlash(target.toExternalForm()).equals(self) && target.getQuery() == null) {
                continue;
            }
            if (++fetched > 40) {
                break;
            }
            Page p = wc.getPage(new WebRequest(target, HttpMethod.GET));
            if (!(p instanceof HtmlPage html) || p.getWebResponse().getStatusCode() != 200) {
                continue;
            }
            List<HtmlForm> forms = UsabilityFixtures.formsEndingWith(html, actionSuffix);
            if (!forms.isEmpty()) {
                out.put(target, forms.get(0));
            }
        }
        return out;
    }

    /** Resolved targets (href, data-*, form action, formaction) on {@code page} whose path ends in {@code suffix}. */
    static List<URL> controlsEndingWith(HtmlPage page, String suffix) throws Exception {
        Set<String> out = new LinkedHashSet<>();
        for (DomElement e : page.getDocumentElement().getHtmlElementDescendants()) {
            for (DomAttr attr : e.getAttributesMap().values()) {
                String name = attr.getName();
                if (!"href".equals(name) && !"action".equals(name) && !"formaction".equals(name) && !name.startsWith("data-")) {
                    continue;
                }
                String v = attr.getValue().trim();
                if (v.isEmpty() || v.contains(" ")) {
                    continue;
                }
                try {
                    URL url = page.getFullyQualifiedUrl(v);
                    String path = url.getPath().replaceAll("/+$", "");
                    if (path.endsWith(suffix)) {
                        out.add(new URL(url.getProtocol(), url.getHost(), url.getPort(), url.getPath()).toExternalForm());
                    }
                } catch (java.net.MalformedURLException ex) {
                    // not a URL
                }
            }
        }
        List<URL> urls = new ArrayList<>();
        for (String s : out) {
            urls.add(new URL(s));
        }
        return urls;
    }

    /** Index (document order among the main panel's anchors outside the tab bar) of the first anchor whose path contains {@code token}; -1 if none. */
    static int firstLinkIndex(HtmlPage page, String token) throws Exception {
        List<String> paths = mainLinkPaths(page);
        for (int i = 0; i < paths.size(); i++) {
            if (paths.get(i).contains(token)) {
                return i;
            }
        }
        return -1;
    }

    /** First resolved anchor URL in the main panel (outside the tab bar) whose path contains {@code token}, or null. */
    static URL firstLink(HtmlPage page, String token) throws Exception {
        DomElement main = page.getElementById("main-panel");
        if (main == null) {
            return null;
        }
        for (DomElement a : main.getElementsByTagName("a")) {
            if (insideTabBar(a) || !a.hasAttribute("href")) {
                continue;
            }
            URL url = page.getFullyQualifiedUrl(a.getAttribute("href"));
            if (url.getPath().contains(token)) {
                return url;
            }
        }
        return null;
    }

    static List<String> mainLinkPaths(HtmlPage page) throws Exception {
        List<String> out = new ArrayList<>();
        DomElement main = page.getElementById("main-panel");
        if (main == null) {
            return out;
        }
        for (DomElement a : main.getElementsByTagName("a")) {
            if (insideTabBar(a) || !a.hasAttribute("href")) {
                continue;
            }
            try {
                out.add(page.getFullyQualifiedUrl(a.getAttribute("href")).getPath());
            } catch (java.net.MalformedURLException e) {
                out.add("");
            }
        }
        return out;
    }

    /** Document-order position of {@code node} among all nodes of the page. */
    static int position(HtmlPage page, DomNode node) {
        int i = 0;
        for (DomNode n : page.getDocumentElement().getDescendants()) {
            if (n == node) {
                return i;
            }
            i++;
        }
        return -1;
    }
}
