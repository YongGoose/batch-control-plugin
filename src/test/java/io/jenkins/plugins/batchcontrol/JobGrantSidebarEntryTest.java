package io.jenkins.plugins.batchcontrol;

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.security.GlobalMatrixAuthorizationStrategy;
import hudson.security.Permission;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.Grant;
import io.jenkins.plugins.batchcontrol.model.GrantAction;
import io.jenkins.plugins.batchcontrol.model.GrantRequest;
import io.jenkins.plugins.batchcontrol.model.GrantScope;
import io.jenkins.plugins.batchcontrol.policy.GrantRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.security.GrantService;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.html.HtmlAnchor;
import org.htmlunit.html.HtmlCheckBoxInput;
import org.htmlunit.html.HtmlInput;
import org.htmlunit.html.HtmlPage;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Matrix rows T-UI-09 .. T-UI-15 — finding U-01: nothing on a job told a user that permission
 * windows exist or where they are requested, because core's stock 403 cannot be intercepted
 * (D-33). The job sidebar now carries a "Request Change Permission" entry into the grant screen
 * with the job prefilled.
 *
 * <p>The entry appears under three ANDed conditions, and each row here names which one it is
 * measuring, because a row that only says "the entry is absent" cannot tell them apart:
 * <ol>
 *   <li>change control is on (T-UI-09),</li>
 *   <li>the caller holds {@code BatchControl/RequestGrant} (T-UI-11),</li>
 *   <li>the caller does <em>not</em> hold {@code Item/Configure} on that job (T-UI-11 for a
 *       standing permission, T-UI-12 for a permission window).</li>
 * </ol>
 *
 * <p>Screen-contract rows (matrix note 47): SPEC item 8 has no acceptance criterion about a
 * sidebar entry, so this is the implementation's contract, on the same footing as the other
 * {@code T-UI-*} rows (note 40).
 */
@WithJenkins
public class JobGrantSidebarEntryTest {

    /** The sidebar caption under test. */
    private static final String ENTRY = "Request Change Permission";

    private static final Instant T0 = Instant.parse("2026-09-20T00:00:00Z");
    private static final int WINDOW_MINUTES = 30;

    private JenkinsRule j;

    private BatchControlGlobalConfiguration cfg;

    private FreeStyleProject jobX;  // the job the entry is asked about
    private FreeStyleProject jobY;  // outside any grant scope: the isolation control
    private FreeStyleProject nested; // team/j, for the '/' round trip

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());

        GlobalMatrixAuthorizationStrategy delegate = new GlobalMatrixAuthorizationStrategy();
        delegate.add(Jenkins.ADMINISTER, PermissionEntry.user("admin"));
        for (String userId : new String[] {"g1", "n1", "a1"}) {
            delegate.add(Jenkins.READ, PermissionEntry.user(userId));
            delegate.add(Item.READ, PermissionEntry.user(userId));
        }
        // g1 may request a window and may not configure anything: the entry's whole audience.
        delegate.add(BatchControlPermissions.REQUEST_GRANT, PermissionEntry.user("g1"));
        // n1 is identical to g1 except for the grant permission.
        delegate.add(BatchControlPermissions.APPROVE, PermissionEntry.user("a1"));
        delegate.add(BatchControlPermissions.MANAGE, PermissionEntry.user("admin"));
        j.jenkins.setAuthorizationStrategy(new BatchControlAuthorizationStrategy(delegate));

        cfg = BatchControlGlobalConfiguration.get();
        cfg.setChangeControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();

        jobX = j.createFreeStyleProject("batch-x");
        jobY = j.createFreeStyleProject("batch-y");
        Folder team = j.jenkins.createProject(Folder.class, "team");
        nested = team.createProject(FreeStyleProject.class, "j");
        assertEquals("team/j", nested.getFullName(), "fixture: the nested job must have a full name containing a slash");

        BatchClock.setForTest(Clock.fixed(T0, ZoneOffset.UTC));
    }

    @AfterEach
    public void resetClock() {
        BatchClock.reset();
    }

    /**
     * T-UI-09 (condition 1): with change control off the entry is absent — SPEC item 1 forbids
     * any change-control UI while the switch is off. Turning the switch on in the same test is the
     * falsifiability guard: an entry that never renders would satisfy the absence alone.
     */
    @Test
    public void t_ui_09_entryIsAbsentWhileChangeControlIsOff() throws Exception {
        cfg.setChangeControlEnabled(false);
        cfg.save();
        assertFalse(BatchControlGlobalConfiguration.get().isChangeControlEnabled(), "fixture: change control must really be off");

        assertNull(entryOn("g1", jobX), "with change control off no change-control entry may appear on a job");
        assertFalse(bodyOf("g1", jobX).contains(ENTRY), "the caption must not appear anywhere on the page either");

        cfg.setChangeControlEnabled(true);
        cfg.save();
        assertNotNull(entryOn("g1", jobX), "guard: the same user and job must get the entry once the switch is on, so the"
                + " absence above measures the switch and nothing else");
    }

    /**
     * T-UI-10 (condition 2, positive): a grant-permission holder without {@code Item/Configure}
     * gets the entry, and it points at the grant screen with this job's scope prefilled.
     */
    @Test
    public void t_ui_10_entryIsPresentAndLinksToThePrefilledGrantScreen() throws Exception {
        assertFalse(hasConfigure("g1", jobX), "fixture: g1 must not already hold Item/Configure on the job");

        HtmlAnchor entry = entryOn("g1", jobX);
        assertNotNull(entry, "a RequestGrant holder without Item/Configure must see the entry");
        assertEquals(j.contextPath + "/batch-control/grants/?scopeType=JOB&scopeFullName=batch-x",
                entry.getHrefAttribute(), "the entry must link to the grant screen with the JOB scope and this job's full"
                        + " name");
    }

    /**
     * T-UI-11 (conditions 2 and 3, negatives): an administrator — who holds {@code Item/Configure}
     * everywhere — does not see the entry, and neither does a user who lacks the grant permission.
     * g1 on the same page is the control that separates the two refusals from a broken page.
     */
    @Test
    public void t_ui_11_administratorAndNonGrantHolderDoNotSeeTheEntry() throws Exception {
        assertTrue(hasConfigure("admin", jobX), "fixture: the administrator must hold Item/Configure (condition 3)");
        assertNull(entryOn("admin", jobX), "an administrator who can already configure the job must not be offered a"
                + " permission request for it");

        assertFalse(hasPermission("n1", BatchControlPermissions.REQUEST_GRANT), "fixture: n1 must not hold BatchControl/RequestGrant (condition 2)");
        assertFalse(hasConfigure("n1", jobX), "fixture: n1 must not hold Item/Configure either, so only condition 2 differs"
                + " between n1 and g1");
        assertNull(entryOn("n1", jobX), "a user without the grant permission must not be offered a link that would 403");

        assertNotNull(entryOn("g1", jobX), "guard: g1 must see the entry on the very same job, so the two absences above"
                + " are about the caller and not about the page");
    }

    /**
     * T-UI-12 (condition 3, the window): <b>this row measures condition 3 only</b> — whether the
     * caller holds {@code Item/Configure} on this job at this moment. Change control stays on and
     * the caller keeps the grant permission throughout; the only thing that moves is the window.
     *
     * <p>So the entry disappears while a CONFIGURE window is open on the job, and comes back both
     * ways a window can end: revocation and expiry. The job outside the window's scope keeps the
     * entry the whole time, which is what distinguishes "this job is configurable now" from "a
     * grant exists somewhere".
     */
    @Test
    public void t_ui_12_entryDisappearsWhileAConfigureWindowIsOpenAndReturnsWhenItEnds()
            throws Exception {
        assertNotNull(entryOn("g1", jobX), "precondition: with no window open the entry is present");

        // ---------- window open: condition 3 now fails, and only condition 3
        Grant grant = grantConfigureOn("batch-x");
        assertTrue(hasConfigure("g1", jobX), "premise: inside the window g1 holds Item/Configure on batch-x");
        assertTrue(BatchControlGlobalConfiguration.get().isChangeControlEnabled(), "premise: condition 1 is unchanged - change control is still on");
        assertTrue(hasPermission("g1", BatchControlPermissions.REQUEST_GRANT), "premise: condition 2 is unchanged - g1 still holds RequestGrant");
        assertNull(entryOn("g1", jobX), "while a CONFIGURE window is open on the job the entry must be absent");
        assertNotNull(entryOn("g1", jobY), "the entry must stay on a job the window does not cover: the condition is"
                + " Item/Configure on THIS job, not the existence of a grant");

        // ---------- revoked: the permission is gone, the entry is back
        try (ACLContext ignored = as("admin")) {
            GrantService.get().revoke(grant.getId());
        }
        assertFalse(hasConfigure("g1", jobX), "premise: revocation must remove Item/Configure again");
        assertNotNull(entryOn("g1", jobX), "after revocation the entry must return - that is the moment the user needs it");

        // ---------- expired: the same, without anybody acting
        Grant second = grantConfigureOn("batch-x");
        assertTrue(hasConfigure("g1", jobX), "premise: the second window must confer Item/Configure");
        assertNull(entryOn("g1", jobX), "premise: the entry must be absent again inside the second window");

        BatchClock.setForTest(Clock.fixed(T0.plusSeconds(60L * (WINDOW_MINUTES + 1)), ZoneOffset.UTC));
        assertFalse(GrantService.get().hasActiveGrant("g1", "batch-x", Item.CONFIGURE), "premise: the window must have expired once the clock passes its deadline");
        assertFalse(hasConfigure("g1", jobX), "premise: expiry must remove Item/Configure");
        assertNotNull(entryOn("g1", jobX), "after expiry the entry must return by itself, with nobody revoking anything"
                + " (grant " + second.getId() + ")");
    }

    /**
     * T-UI-13: following the entry lands on a form that is already about the job — the scope field
     * carries the job's full name and the CONFIGURE action is checked, because that is the only
     * thing the entry is for.
     */
    @Test
    public void t_ui_13_grantScreenPrefillsTheScopeAndChecksConfigure() throws Exception {
        HtmlPage page = grantScreen("g1", "scopeType=JOB&scopeFullName=batch-x");
        assertEquals(200, page.getWebResponse().getStatusCode());

        HtmlInput scope = scopeField(page);
        assertEquals("batch-x", scope.getValue(), "the scope field must be prefilled with the job's full name");

        HtmlCheckBoxInput configure = configureCheckbox(page);
        assertTrue(configure.isChecked(), "the CONFIGURE action must start checked when the form was opened for a job");

        // Falsifiability guard: without the ?scopeFullName= parameter the same form is blank and
        // CONFIGURE is unchecked, so the two assertions above measure the prefill and not a
        // form that is always filled in.
        HtmlPage bare = grantScreen("g1", "");
        assertEquals("", scopeField(bare).getValue(), "guard: opening the grant screen directly must leave the scope field empty");
        assertFalse(configureCheckbox(bare).isChecked(), "guard: opening the grant screen directly must leave CONFIGURE unchecked");
    }

    /**
     * T-UI-14: the prefill is resolved through the model, never echoed. A markup payload in
     * {@code ?scopeFullName=} names no item, so the field stays empty and <b>the payload does not
     * appear in the page in any form</b> — not raw, not HTML-escaped, not attribute-escaped. The
     * absence of the reflected string is the assertion, because "it was escaped" is a weaker
     * guarantee than "it was never echoed".
     */
    @Test
    public void t_ui_14_prefillNeverReflectsTheQueryValue() throws Exception {
        String payload = "<img src=x onerror=1>";
        HtmlPage page = grantScreen("g1", "scopeType=JOB&scopeFullName=" + urlEncode(payload));
        assertEquals(200, page.getWebResponse().getStatusCode(), "an unresolvable scope name must not break the screen");

        assertEquals("", scopeField(page).getValue(), "an unresolvable name must leave the field empty rather than echoing the input");

        String html = page.getWebResponse().getContentAsString();
        for (String forbidden : new String[] {
                payload,                       // raw
                "&lt;img src=x onerror=1&gt;",  // HTML-escaped
                "img src=x",                    // any partial echo of the tag
                "onerror"}) {                   // the payload's active part, however encoded
            assertFalse(html.contains(forbidden), "the query value must not be reflected into the page in any form, but "
                    + forbidden + " appears; the implementation echoes a resolved"
                    + " getFullName(), so any occurrence means it stopped doing that");
        }

        // Falsifiability guard: a resolvable name IS echoed, so the absence above is about this
        // input and not about a form that never renders a prefill at all.
        assertEquals("batch-x", scopeField(grantScreen("g1", "scopeFullName=batch-x")).getValue(), "guard: a real job name must still reach the field");
    }

    /**
     * T-UI-15: a job inside a folder survives the round trip through the URL. The entry encodes
     * the {@code /} of the full name, and the grant screen resolves it back to the same job.
     */
    @Test
    public void t_ui_15_folderedJobSurvivesTheSlashRoundTrip() throws Exception {
        assertFalse(hasConfigure("g1", nested), "fixture: g1 must not already be able to configure the nested job");

        HtmlAnchor entry = entryOn("g1", nested);
        assertNotNull(entry, "the entry must appear on a job inside a folder too");
        assertEquals(j.contextPath + "/batch-control/grants/?scopeType=JOB&scopeFullName=team%2Fj",
                entry.getHrefAttribute(), "the '/' of the full name must be URL-encoded, or the sidebar entry is dropped"
                        + " by core's action-URL parsing");

        // Follow the link the way a browser would, and the form must come back about team/j.
        HtmlPage page = (HtmlPage) j.createWebClient()
                .withThrowExceptionOnFailingStatusCode(false).login("g1")
                .getPage(new WebRequest(new URL(j.getURL(), entry.getHrefAttribute()
                        .substring(j.contextPath.length() + 1)), HttpMethod.GET));
        assertEquals(200, page.getWebResponse().getStatusCode());
        assertEquals("team/j", scopeField(page).getValue(), "the folder path must survive the round trip through the query string");
        assertTrue(configureCheckbox(page).isChecked(), "the nested job's form must also start with CONFIGURE checked");
    }

    // ---------------------------------------------------------------- helpers

    /** The sidebar entry under test on a job page, or null when it is not rendered. */
    private HtmlAnchor entryOn(String userId, Job<?, ?> job) throws Exception {
        HtmlPage page = (HtmlPage) j.createWebClient()
                .withThrowExceptionOnFailingStatusCode(false).login(userId).getPage(job);
        assertEquals(200, page.getWebResponse().getStatusCode(), "fixture: " + userId + " must be able to open " + job.getFullName());
        List<HtmlAnchor> matches = page.getAnchors().stream()
                .filter(a -> a.asNormalizedText().trim().contains(ENTRY))
                .collect(Collectors.toList());
        assertTrue(matches.size() <= 1, "the sidebar must never carry the entry twice, but had " + matches.size());
        return matches.isEmpty() ? null : matches.get(0);
    }

    private String bodyOf(String userId, Job<?, ?> job) throws Exception {
        return j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login(userId)
                .getPage(job).getWebResponse().getContentAsString();
    }

    private HtmlPage grantScreen(String userId, String query) throws Exception {
        String url = "batch-control/grants/" + (query.isEmpty() ? "" : "?" + query);
        return (HtmlPage) j.createWebClient().withThrowExceptionOnFailingStatusCode(false)
                .login(userId).getPage(new WebRequest(new URL(j.getURL(), url), HttpMethod.GET));
    }

    private static HtmlInput scopeField(HtmlPage page) {
        HtmlInput input = page.getElementsByTagName("input").stream()
                .filter(HtmlInput.class::isInstance)
                .map(HtmlInput.class::cast)
                .filter(i -> "scopeFullName".equals(i.getAttribute("name")))
                .findFirst().orElse(null);
        assertNotNull(input, "the new-request form must carry a scopeFullName field");
        return input;
    }

    private static HtmlCheckBoxInput configureCheckbox(HtmlPage page) {
        HtmlCheckBoxInput box = page.getElementsByTagName("input").stream()
                .filter(HtmlCheckBoxInput.class::isInstance)
                .map(HtmlCheckBoxInput.class::cast)
                .filter(i -> "actions".equals(i.getAttribute("name"))
                        && "CONFIGURE".equals(i.getValue()))
                .findFirst().orElse(null);
        assertNotNull(box, "the new-request form must offer the CONFIGURE action as a checkbox");
        return box;
    }

    private ACLContext as(String userId) {
        return ACL.as2(User.getById(userId, true).impersonate2());
    }

    private boolean hasConfigure(String userId, Job<?, ?> job) {
        try (ACLContext ignored = as(userId)) {
            return job.hasPermission(Item.CONFIGURE);
        }
    }

    private boolean hasPermission(String userId, Permission permission) {
        try (ACLContext ignored = as(userId)) {
            return Jenkins.get().hasPermission(permission);
        }
    }

    /** A CONFIGURE window on the named job for g1, approved by a1. */
    private Grant grantConfigureOn(String jobFullName) {
        GrantRequest request;
        try (ACLContext ignored = as("g1")) {
            request = GrantRequestService.get().create(
                    new GrantScope(GrantScope.Type.JOB, jobFullName),
                    Arrays.asList(GrantAction.CONFIGURE), WINDOW_MINUTES,
                    "scheduled maintenance", "a1");
        }
        Grant grant;
        try (ACLContext ignored = as("a1")) {
            grant = GrantRequestService.get().approve(request.getId(), "ok");
        }
        assertNotNull(grant, "fixture: the approval must produce a grant");
        assertTrue(GrantService.get().hasActiveGrant("g1", jobFullName, Item.CONFIGURE), "fixture: the window must be active for CONFIGURE on " + jobFullName);
        return grant;
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
