package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleProject;
import hudson.model.Item;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.store.FileStore;
import io.jenkins.plugins.batchcontrol.ui.DiffSummary;
import java.net.URL;
import java.time.YearMonth;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.DomNode;
import org.htmlunit.html.HtmlForm;
import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Matrix row T-UI-19 — finding U-07: the stored configuration diff sat behind a collapsed
 * disclosure control captioned only "Diff", so learning whether a change was one line or four
 * hundred cost a click per row. The control now always carries the size, and the body stays
 * collapsed because a page holds up to 50 records.
 *
 * <p>Both halves are asserted together: the summary must be there <em>and</em> the body must still
 * be collapsed. A row that only checked the caption would pass for an implementation that expanded
 * every diff, which is the thing the compromise exists to avoid.
 *
 * <p>Screen-contract row (matrix note 47); the counting itself is pinned by the unit row T-UI-20.
 */
@WithJenkins
public class ChangeRecordDiffViewTest {

    private static final Pattern SUMMARY = Pattern.compile("Diff \\(\\+\\d+ / -\\d+ lines\\)");

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.VIEW_HISTORY)
                        .everywhere().to("viewer"));

        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setChangeControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
    }

    /**
     * T-UI-19: a CONFIGURE record that carries a diff shows a {@code Diff (+N / -M lines)} summary
     * on the disclosure control, the counts are the ones the summariser produces for that record's
     * own diff, and the control is not expanded.
     */
    @Test
    public void t_ui_19_configureRecordShowsItsDiffSizeWithoutBeingExpanded() throws Exception {
        FreeStyleProject job = j.createFreeStyleProject("diff-x");
        job.setDescription("before-ui-change");

        HtmlForm form = j.createWebClient().login("admin").getPage(job, "configure")
                .getFormByName("config");
        form.getTextAreaByName("description").setText("after-ui-change");
        j.submit(form);
        assertEquals("after-ui-change", job.getDescription(), "fixture: the configuration change must have been applied");

        // The description change is what identifies this record: a job created while a control is
        // on can carry more than one CONFIGURE record (the D-31 property default saves it too), so
        // "the newest CONFIGURE record" is not a stable handle.
        List<ChangeRecord> mine = FileStore.get().listChangeRecords(YearMonth.now()).stream()
                .filter(r -> r.getType() == ChangeType.CONFIGURE && "diff-x".equals(r.getTarget()))
                .filter(r -> r.getDiff() != null && r.getDiff().contains("after-ui-change"))
                .collect(Collectors.toList());
        assertEquals(1, mine.size(), "fixture: exactly one CONFIGURE record must carry the description change, but "
                + mine.size() + " did");
        ChangeRecord record = mine.get(0);
        String diff = record.getDiff();
        assertNotNull(diff, "fixture: the CONFIGURE record must carry a diff, or there is nothing to summarise");
        String expected = DiffSummary.of(diff);
        assertFalse(expected.isEmpty(), "fixture: this record's diff must be countable");

        HtmlPage page = changesScreen("viewer");
        assertEquals(200, page.getWebResponse().getStatusCode(), "the change-records screen must render for a ViewHistory holder");

        List<DomElement> details = page.getByXPath("//details").stream()
                .map(DomElement.class::cast)
                .collect(Collectors.toList());
        assertFalse(details.isEmpty(), "the screen must render a disclosure control for a record that has a diff");
        for (DomElement control : details) {
            assertFalse(control.hasAttribute("open"), "no diff may be expanded by default: a page holds up to 50 records and an"
                    + " expanded config.xml diff runs to hundreds of lines");
        }

        List<DomElement> matching = details.stream()
                .filter(control -> control.getTextContent().contains("after-ui-change"))
                .collect(Collectors.toList());
        assertEquals(1, matching.size(), "exactly one disclosure control must hold this test's own diff, but "
                + matching.size() + " of " + details.size() + " did");
        DomElement disclosure = matching.get(0);

        DomElement summary = disclosure.getElementsByTagName("summary").stream()
                .findFirst().orElse(null);
        assertNotNull(summary, "the disclosure control must carry a summary caption");
        String caption = summary.getTextContent().trim();
        assertTrue(SUMMARY.matcher(caption).matches(), "the caption must read \"Diff (+N / -M lines)\", but read: \"" + caption + "\"");
        assertEquals("Diff (" + expected + ")", caption, "the caption must report this record's own counts");
        assertFalse("Diff".equals(caption), "a bare \"Diff\" caption is the pre-U-07 state: the size must be visible without"
                + " opening anything");

        // The body must still be there (the summary describes a real diff) and still collapsed.
        DomElement body = disclosure.getElementsByTagName("pre").stream().findFirst().orElse(null);
        assertNotNull(body, "the diff body must still be available behind the control");
        assertTrue(body.getTextContent().contains("after-ui-change"), "the body must hold the record's own diff");

        // The row the control belongs to is the CONFIGURE one, so this is not some other record's
        // diff being measured.
        assertTrue(rowTextOf(disclosure).contains("CONFIGURE"), "the disclosure control must sit in the CONFIGURE row, but its row read: "
                + rowTextOf(disclosure));
    }

    // ---------------------------------------------------------------- helpers

    private HtmlPage changesScreen(String userId) throws Exception {
        return (HtmlPage) j.createWebClient().withThrowExceptionOnFailingStatusCode(false)
                .login(userId)
                .getPage(new WebRequest(new URL(j.getURL(), "batch-control/changes/"),
                        HttpMethod.GET));
    }

    /** The visible text of the table row a node sits in. */
    private static String rowTextOf(DomNode node) {
        for (DomNode current = node; current != null; current = current.getParentNode()) {
            if (current instanceof DomElement
                    && "tr".equalsIgnoreCase(((DomElement) current).getTagName())) {
                return current.getTextContent().replaceAll("\\s+", " ").trim();
            }
        }
        return "(no row found)";
    }
}
