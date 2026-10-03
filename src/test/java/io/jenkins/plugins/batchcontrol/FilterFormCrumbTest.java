package io.jenkins.plugins.batchcontrol;

import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.htmlunit.Page;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.HtmlButton;
import org.htmlunit.html.HtmlElement;
import org.htmlunit.html.HtmlForm;
import org.htmlunit.html.HtmlInput;
import org.htmlunit.html.HtmlPage;
import org.htmlunit.html.HtmlSubmitInput;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.PluginInteractionFixtures.secureWithRunControl;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * e2e-12 DEF-07: the History "Filter" form and the Changes and Incidents "Show" forms are GET
 * forms whose resulting URL must be a clean, bookmarkable query: it carries the filter
 * parameters, but neither the session's {@code Jenkins-Crumb} nor core's {@code json} form blob
 * (core's {@code hudson-behavior.js} adds both to every form it sees). Matrix row T-UI-106
 * (note 250).
 *
 * <p>JavaScript is enabled on purpose: the defect is caused by core's form behaviour, so a
 * client without JavaScript could not reproduce it.
 *
 * <p>Written from docs/SPEC.md, docs/TEST-MATRIX.md and docs/reports/e2e-12.md only (no
 * src/main knowledge).
 */
@WithJenkins
public class FilterFormCrumbTest {

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        secureWithRunControl(j);
    }

    /** T-UI-106 (History): Filter with Job = fast lands on a URL with job=fast and no crumb/json. */
    @Test
    public void t_ui_106_historyFilterKeepsTheCrumbOutOfTheUrl() throws Exception {
        j.createFreeStyleProject("fast");
        HtmlPage page = open("batch-control/history/?kind=runs");
        HtmlForm form = formWithSubmit(page, "Filter");
        HtmlInput job = form.getInputByName("job");
        job.setValue("fast");
        HtmlPage result = submit(page, form, "Filter");
        assertCleanFilterUrl(result, "job", "fast");
    }

    /** T-UI-106 (Changes): Show with a month lands on a URL with that month and no crumb/json. */
    @Test
    public void t_ui_106_changesShowKeepsTheCrumbOutOfTheUrl() throws Exception {
        assertMonthShowIsClean("batch-control/changes/");
    }

    /** T-UI-106 (Incidents): Show with a month lands on a URL with that month and no crumb/json. */
    @Test
    public void t_ui_106_incidentsShowKeepsTheCrumbOutOfTheUrl() throws Exception {
        assertMonthShowIsClean("batch-control/incidents/");
    }

    private void assertMonthShowIsClean(String relative) throws Exception {
        HtmlPage page = open(relative);
        HtmlForm form = formWithSubmit(page, "Show");
        HtmlInput month = monthField(form);
        assertNotNull(month, relative + ": fixture: the Show form must have a month field: " + form.asXml());
        String name = month.getNameAttribute();
        month.setValue("2025-03");
        HtmlPage result = submit(page, form, "Show");
        assertCleanFilterUrl(result, name, "2025-03");
    }

    private HtmlPage open(String relative) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().login("admin");
        wc.getOptions().setJavaScriptEnabled(true);
        Page page = wc.getPage(new URL(j.getURL(), relative));
        assertTrue(page instanceof HtmlPage, "admin GET " + relative + " must answer an HTML page, got HTTP "
                + page.getWebResponse().getStatusCode());
        assertEquals(200, page.getWebResponse().getStatusCode(), "admin GET " + relative);
        return (HtmlPage) page;
    }

    private static HtmlForm formWithSubmit(HtmlPage page, String caption) {
        for (HtmlForm form : page.getForms()) {
            if (submitControl(form, caption) != null) {
                return form;
            }
        }
        throw new AssertionError(page.getUrl() + ": fixture: no form with a \"" + caption + "\" submit control");
    }

    private static HtmlElement submitControl(HtmlForm form, String caption) {
        for (DomElement e : form.getElementsByTagName("button")) {
            if (e instanceof HtmlButton b && caption.equals(b.asNormalizedText().trim())) {
                return b;
            }
        }
        for (DomElement e : form.getElementsByTagName("input")) {
            if (e instanceof HtmlSubmitInput s && caption.equals(s.getValueAttribute().trim())) {
                return s;
            }
        }
        return null;
    }

    private static HtmlInput monthField(HtmlForm form) {
        for (DomElement e : form.getElementsByTagName("input")) {
            if (e instanceof HtmlInput in) {
                String type = in.getTypeAttribute();
                if ("month".equalsIgnoreCase(type)
                        || (!"hidden".equalsIgnoreCase(type) && in.getNameAttribute().toLowerCase().contains("month"))) {
                    return in;
                }
            }
        }
        return null;
    }

    private HtmlPage submit(HtmlPage page, HtmlForm form, String caption) throws Exception {
        Page next = submitControl(form, caption).click();
        j.waitUntilNoActivity();
        Page landed = next.getEnclosingWindow().getEnclosedPage();
        assertTrue(landed instanceof HtmlPage, page.getUrl() + ": " + caption + " must land on an HTML page, got HTTP "
                + landed.getWebResponse().getStatusCode());
        assertEquals(200, landed.getWebResponse().getStatusCode(), caption + " must land on a page that opens: "
                + landed.getUrl());
        assertFalse(landed.getUrl().equals(page.getUrl()), caption + " must navigate: still on " + page.getUrl());
        return (HtmlPage) landed;
    }

    private static void assertCleanFilterUrl(HtmlPage result, String name, String value) {
        URL url = result.getUrl();
        Map<String, List<String>> query = query(url);
        assertFalse(query.containsKey("Jenkins-Crumb"), "the filter URL must not carry the CSRF crumb: " + url);
        assertFalse(query.containsKey(".crumb"), "the filter URL must not carry the CSRF crumb: " + url);
        assertFalse(query.containsKey("json"), "the filter URL must not carry core's json form blob: " + url);
        assertTrue(query.getOrDefault(name, List.of()).contains(value),
                "the filter URL must keep " + name + "=" + value + ": " + url);
        HtmlInput again = null;
        for (HtmlForm form : result.getForms()) {
            for (DomElement e : form.getElementsByTagName("input")) {
                if (e instanceof HtmlInput in && name.equals(in.getNameAttribute()) && !"hidden".equalsIgnoreCase(in.getTypeAttribute())) {
                    again = in;
                }
            }
        }
        assertNotNull(again, "the result page must show the filter form again with " + name + ": " + url);
        assertEquals(value, again.getValue(), "the result page must keep the filter value " + name + ": " + url);
    }

    private static Map<String, List<String>> query(URL url) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        String q = url.getQuery();
        if (q == null || q.isEmpty()) {
            return out;
        }
        for (String pair : q.split("&")) {
            int eq = pair.indexOf('=');
            String k = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            String v = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            out.computeIfAbsent(k, x -> new ArrayList<>()).add(v);
        }
        return out;
    }
}
