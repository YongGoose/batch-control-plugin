package io.jenkins.plugins.batchcontrol;

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.ExtensionList;
import hudson.matrix.AxisList;
import hudson.matrix.MatrixProject;
import hudson.matrix.TextAxis;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.security.Permission;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.model.Grant;
import io.jenkins.plugins.batchcontrol.model.GrantRequest;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.ops.ExpiryPeriodicWork;
import io.jenkins.plugins.batchcontrol.policy.GrantRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.security.GrantService;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.htmlunit.WebResponse;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.DomNode;
import org.htmlunit.html.HtmlForm;
import org.htmlunit.html.HtmlInput;
import org.htmlunit.html.HtmlPage;
import org.htmlunit.util.NameValuePair;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.jenkinsci.plugins.matrixauth.inheritance.NonInheritingStrategy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.approverPairs;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.assertSuccess;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.decideGrant;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.excerpt;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.grantRequestIds;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.submitGrantOk;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage lane 3, scenarios L3-13, L3-14 and L3-15: the grant request form's refusals, the closed
 * Grants screen, the grant request page's actions and the Grants list rows. Matrix rows T-GAP-344 ..
 * T-GAP-356 (note 279).
 *
 * <p>Basis: SPEC 6 usability "invalid input is refused with a message next to the field and the
 * user's input is kept ... no bare 'Access Denied', stack trace, 'Oops!' page"; SPEC 8 (CREATE /
 * CONFIGURE / DELETE, duration options and {@code maxGrantMinutes}, reason; D-40 name restriction
 * "validated at submission ... Names longer than 255 characters are refused"; line 173 "a sub-item of a
 * job ... is refused at submission"; line 174 the kind and the item-group notice; line 175 the lists
 * and revoking from the detail page; line 171 / D-75 (1) the followed name only for readers); SPEC 3
 * (approvers on the list; requester changes the approver set while pending); SPEC 5 "반려 사유가 비어
 * 있으면 반려가 거부된다"; SPEC 7 "취소는 요청자 본인 또는 Manage 권한자만 ... PENDING 상태에서만"; SPEC 8 "Manage
 * 권한자는 활성 권한을 즉시 회수"; LIMITATIONS 23, 24 and 29. The LIMITATIONS 29 record of a web approval
 * while change control is off is T-GAP-230 (lane 2) and is not repeated here.
 *
 * <p>"A message next to the field" is read structurally: an error element inside the same form item
 * ({@code .jenkins-form-item}) as the input of that name. No document names a maximum number of
 * approvers or a maximum approver id length (note 276), so the 51-approver and 257-character inputs
 * use ids that are not on the approver list, which SPEC 3 refuses.
 *
 * <p>Batch Control matrix strategy, change control on; u1 and u2 hold Overall/Read, Item/Read and
 * RequestGrant; a1 and a2 (on the approver list) hold Overall/Read, Item/Read and Approve.
 *
 * <p>Written from docs/SPEC.md, docs/DECISIONS.md, docs/LIMITATIONS.md and docs/ARCHITECTURE.md only
 * (no src/main knowledge).
 */
@WithJenkins
public class GrantPageGapTest {

    static final String KEPT_REASON = "kept-reason-l3-13-Zq8";
    private static final String VAULT = "vault-l3";

    private JenkinsRule j;
    private BatchControlGlobalConfiguration cfg;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        BatchControlMatrixAuthorizationStrategy strategy = new BatchControlMatrixAuthorizationStrategy();
        strategy.add(Jenkins.ADMINISTER, PermissionEntry.user("admin"));
        for (String userId : new String[] {"u1", "u2", "a1", "a2"}) {
            strategy.add(Jenkins.READ, PermissionEntry.user(userId));
            strategy.add(Item.READ, PermissionEntry.user(userId));
        }
        for (String userId : new String[] {"u1", "u2"}) {
            strategy.add(BatchControlPermissions.REQUEST_GRANT, PermissionEntry.user(userId));
        }
        for (String userId : new String[] {"a1", "a2"}) {
            strategy.add(BatchControlPermissions.APPROVE, PermissionEntry.user(userId));
        }
        j.jenkins.setAuthorizationStrategy(strategy);
        cfg = BatchControlGlobalConfiguration.get();
        cfg.setChangeControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1", "a2"));
        cfg.save();
        j.jenkins.createProject(Folder.class, "team");
        j.createFreeStyleProject("job-k");
    }

    @AfterEach
    public void tearDown() {
        BatchClock.reset();
    }

    // ------------------------------------------------------------------ L3-13

    /**
     * T-GAP-344 (L3-13; SPEC 6 usability, SPEC 8, SPEC 3, D-40): {@code POST batch-control/grants/create}
     * by u1 with, one at a time: a blank reason; no approver; 51 approvers (a1 and 50 ids not on the
     * list); one approver id of 257 characters; an empty {@code scopeFullName}; {@code actions=RENAME};
     * no action; CREATE on {@code team} with a 1,001-character exact name restriction; empty
     * {@code durationMinutes} and {@code customDurationMinutes}; {@code customDurationMinutes=abc};
     * {@code customDurationMinutes=0}. Each is refused with HTTP 4xx and an HTML page that carries the
     * form again, a message inside the form item of the field concerned and the typed input (the reason,
     * or the item name when the reason is the field), and no grant request is stored. Guard: the same
     * form with every field valid is accepted.
     */
    @Test
    public void t_gap_344_grantFormRefusalsKeepTheInputAndMarkTheField() throws Exception {
        String[] many = new String[51];
        many[0] = "a1";
        for (int i = 1; i < many.length; i++) {
            many[i] = String.format(Locale.ROOT, "l3-approver-%02d", i);
        }
        List<Object[]> cases = new ArrayList<>();
        cases.add(new Object[] {"blank reason", form("job-k", List.of("CONFIGURE"), "30", null, "  ", null, "a1"),
                new String[] {"reason"}, "job-k"});
        cases.add(new Object[] {"no approver", form("job-k", List.of("CONFIGURE"), "30", null, KEPT_REASON, null),
                new String[] {"approvers"}, KEPT_REASON});
        cases.add(new Object[] {"51 approvers", form("job-k", List.of("CONFIGURE"), "30", null, KEPT_REASON, null, many),
                new String[] {"approvers"}, KEPT_REASON});
        cases.add(new Object[] {"a 257-character approver id", form("job-k", List.of("CONFIGURE"), "30", null, KEPT_REASON, null,
                "z".repeat(257)), new String[] {"approvers"}, KEPT_REASON});
        cases.add(new Object[] {"an empty item name", form("", List.of("CONFIGURE"), "30", null, KEPT_REASON, null, "a1"),
                new String[] {"scopeFullName"}, KEPT_REASON});
        cases.add(new Object[] {"actions=RENAME", form("job-k", List.of("RENAME"), "30", null, KEPT_REASON, null, "a1"),
                new String[] {"actions"}, KEPT_REASON});
        cases.add(new Object[] {"no action", form("job-k", List.of(), "30", null, KEPT_REASON, null, "a1"),
                new String[] {"actions"}, KEPT_REASON});
        cases.add(new Object[] {"a 1,001-character name restriction", form("team", List.of("CREATE"), "30", null, KEPT_REASON,
                "n".repeat(1001), "a1"), new String[] {"createNamePattern"}, KEPT_REASON});
        cases.add(new Object[] {"no duration", form("job-k", List.of("CONFIGURE"), "", "", KEPT_REASON, null, "a1"),
                new String[] {"durationMinutes", "customDurationMinutes"}, KEPT_REASON});
        cases.add(new Object[] {"customDurationMinutes=abc", form("job-k", List.of("CONFIGURE"), "30", "abc", KEPT_REASON, null, "a1"),
                new String[] {"durationMinutes", "customDurationMinutes"}, KEPT_REASON});
        cases.add(new Object[] {"customDurationMinutes=0", form("job-k", List.of("CONFIGURE"), "30", "0", KEPT_REASON, null, "a1"),
                new String[] {"durationMinutes", "customDurationMinutes"}, KEPT_REASON});

        for (Object[] c : cases) {
            String what = "the grant form with " + c[0];
            @SuppressWarnings("unchecked")
            List<NameValuePair> params = (List<NameValuePair>) c[1];
            Set<String> before = grantRequestIds();
            Page answer = postPage("u1", "batch-control/grants/create", params);
            assertEquals(before, grantRequestIds(), what + ": nothing may be stored");
            assertFormRefusal(answer, what, "batch-control/grants/create", (String[]) c[2], (String) c[3]);
        }

        String id = submitGrantOk(j, "u1", "job-k", List.of("CONFIGURE"), 30, KEPT_REASON, null, "a1");
        assertEquals(RequestStatus.PENDING, GrantRequestService.get().load(id).getStatus(), "guard: the valid form is stored as PENDING");
    }

    /**
     * T-GAP-345 (L3-13; SPEC 8 line 173 "a window may name only a top-level item; a sub-item of a job
     * (a multi-configuration project's configuration ...) is refused at submission"; SPEC 6 usability):
     * {@code POST batch-control/grants/checkScopeFullName} by u1 with {@code mx/X=a}, a configuration
     * of the multi-configuration project {@code mx}, answers an error that names a job (why it cannot
     * be named). Guard: the check of {@code mx} itself answers ok.
     */
    @Test
    public void t_gap_345_scopeCheckRefusesAMatrixConfiguration() throws Exception {
        MatrixProject mx = j.jenkins.createProject(MatrixProject.class, "mx");
        mx.setAxes(new AxisList(new TextAxis("X", "a", "b")));
        Item configuration = j.jenkins.getItemByFullName("mx/X=a");
        assertNotNull(configuration, "fixture: mx has the configuration mx/X=a");
        assertFalse(configuration instanceof hudson.model.TopLevelItem, "fixture: a configuration is not a top-level item");

        WebResponse sub = scopeCheck("u1", "mx/X=a");
        assertEquals(200, sub.getStatusCode(), "a validation answer is HTTP 200: " + excerpt(sub.getContentAsString()));
        assertEquals("error", validationKind(sub), "SPEC 8 line 173: a configuration of a job cannot be named: "
                + excerpt(sub.getContentAsString()));
        assertTrue(validationText(sub).toLowerCase(Locale.ROOT).contains("job"),
                "SPEC 6 usability: the error must say why (it is part of a job): " + validationText(sub));
        WebResponse whole = scopeCheck("u1", "mx");
        assertEquals("ok", validationKind(whole), "guard: the project mx itself can be named: " + excerpt(whole.getContentAsString()));
    }

    /**
     * T-GAP-346 (L3-13; LIMITATIONS 29 "With change control off the Grants screen is closed ...
     * requesting ... a window is refused with a message that says why, plus a GRANT_REQUEST_BLOCKED
     * record. The URL itself still answers"): change control off, run control on. u1's
     * {@code GET batch-control/grants/} answers (not 404 or 403, SPEC 6) with an explanation that change control is off and
     * writes no GRANT_REQUEST_BLOCKED record; u1's {@code POST batch-control/grants/create} with a
     * 5,000-character item name is refused (4xx, plain words naming change control) and writes exactly
     * one GRANT_REQUEST_BLOCKED record naming u1, whose target is a prefix of the submitted text. No
     * grant request is stored. Guard: with change control on, the same page lists no explanation of a
     * closed screen and the same POST writes no GRANT_REQUEST_BLOCKED record.
     */
    @Test
    public void t_gap_346_closedGrantsScreenExplainsAndRecordsOnlyTheRequest() throws Exception {
        String longName = "s".repeat(5000);
        int guardBlocked = ApproverFormFixtures.records(ChangeType.GRANT_REQUEST_BLOCKED).size();
        HtmlPage open = UsabilityFixtures.htmlPage(j, "u1", "batch-control/grants/");
        assertEquals(200, open.getWebResponse().getStatusCode(), "guard: the open Grants screen answers 200");
        assertFalse(mentionsChangeControlOff(open.asNormalizedText()), "guard: the open Grants screen does not say change control is off: "
                + excerpt(open.asNormalizedText()));
        Page guardPost = postPage("u1", "batch-control/grants/create", form(longName, List.of("CONFIGURE"), "30", null, KEPT_REASON, null, "a1"));
        assertTrue(guardPost.getWebResponse().getStatusCode() >= 400, "guard: an item name that names no item is refused");
        assertEquals(guardBlocked, ApproverFormFixtures.records(ChangeType.GRANT_REQUEST_BLOCKED).size(),
                "guard: with change control on a refused request is not a GRANT_REQUEST_BLOCKED");

        cfg.setRunControlEnabled(true);
        cfg.setChangeControlEnabled(false);
        cfg.save();
        int blocked = ApproverFormFixtures.records(ChangeType.GRANT_REQUEST_BLOCKED).size();
        HtmlPage closed = UsabilityFixtures.htmlPage(j, "u1", "batch-control/grants/");
        int closedCode = closed.getWebResponse().getStatusCode();
        System.out.println("T-GAP-346 observation: the closed Grants screen answers HTTP " + closedCode);
        assertTrue(closedCode != 404 && closedCode != 403 && closedCode < 500,
                "LIMITATIONS 29: the URL itself still answers (not a dead link, SPEC 6: no 404 or 403), got HTTP " + closedCode);
        assertTrue(mentionsChangeControlOff(closed.asNormalizedText()), "LIMITATIONS 29: the closed screen explains that change control is off: "
                + excerpt(closed.asNormalizedText()));
        assertEquals(blocked, ApproverFormFixtures.records(ChangeType.GRANT_REQUEST_BLOCKED).size(),
                "opening the closed screen is not a request: no GRANT_REQUEST_BLOCKED record");

        Set<String> before = grantRequestIds();
        Page refused = postPage("u1", "batch-control/grants/create", form(longName, List.of("CONFIGURE"), "30", null, KEPT_REASON, null, "a1"));
        int code = refused.getWebResponse().getStatusCode();
        assertTrue(code >= 400 && code < 500, "LIMITATIONS 29: the request is refused with 4xx, got " + code);
        UsabilityFixtures.assertPlainRefusal("the request while change control is off", UsabilityFixtures.text(refused),
                Pattern.compile("(?i)change control"));
        assertEquals(before, grantRequestIds(), "nothing may be stored");
        List<ChangeRecord> records = ApproverFormFixtures.records(ChangeType.GRANT_REQUEST_BLOCKED);
        assertEquals(blocked + 1, records.size(), "LIMITATIONS 29: exactly one GRANT_REQUEST_BLOCKED record for the refused request: "
                + WindowStateFixtures.describe(records));
        ChangeRecord rec = records.get(records.size() - 1);
        assertEquals("u1", rec.getUser(), "the record names who asked");
        String target = String.valueOf(rec.getTarget());
        System.out.println("T-GAP-346 observation: GRANT_REQUEST_BLOCKED target length " + target.length() + " of " + longName.length());
        assertTrue(rec.getTarget() == null || longName.startsWith(target),
                "the record's target is (a prefix of) what was asked for, nothing else: " + excerpt(target));
    }

    // ------------------------------------------------------------------ L3-14

    /**
     * T-GAP-347 (L3-14; SPEC 5 "반려 사유가 비어 있으면 반려가 거부된다", SPEC 6 usability): a1, the designated
     * approver, POSTs {@code batch-control/grants/<id>/reject} with a blank comment: 4xx, the request
     * page again with a message inside the comment's form item, and the request stays PENDING. Guard:
     * the same with a comment rejects it.
     */
    @Test
    public void t_gap_347_blankRejectionCommentIsRefusedOnTheField() throws Exception {
        String id = submitGrantOk(j, "u1", "job-k", List.of("CONFIGURE"), 30, KEPT_REASON, null, "a1");
        Page refused = postPage("a1", "batch-control/grants/" + id + "/reject", List.of(new NameValuePair("comment", "   ")));
        assertEquals(RequestStatus.PENDING, GrantRequestService.get().load(id).getStatus(), "SPEC 5: a blank rejection is refused");
        assertFormRefusal(refused, "a1's blank rejection", "batch-control/grants/" + id + "/reject", new String[] {"comment"}, null);

        assertSuccess(decideGrant(j, "a1", id, "reject", "not this week"), "guard: a1's rejection with a comment");
        assertEquals(RequestStatus.REJECTED, GrantRequestService.get().load(id).getStatus(), "guard: the request is REJECTED");
    }

    /**
     * T-GAP-348 (L3-14; SPEC 7 "취소는 요청자 본인 또는 Manage 권한자만 가능", SPEC 6 security "모든 상태 변경은 POST
     * + 권한 체크"): anonymous is given BatchControl/Manage and Overall/Read (so the request page is
     * visible) and POSTs {@code batch-control/grants/<id>/cancel} with a crumb. The answer is not a
     * server error, and the outcome is one the documents allow: either the request stays PENDING and
     * the answer is a refusal (4xx, or core's sign-in page, its answer to anonymous lacking a permission),
     * or the request is CANCELLED. Which of the two is not documented
     * for anonymous (note 279); the observed outcome is printed.
     */
    @Test
    public void t_gap_348_anonymousCancelIsHandledWithoutAnError() throws Exception {
        String id = submitGrantOk(j, "u1", "job-k", List.of("CONFIGURE"), 30, KEPT_REASON, null, "a1");
        permit("anonymous", Jenkins.READ, BatchControlPermissions.MANAGE);
        WebResponse page = ApproverFormFixtures.get(j, null, "batch-control/grants/" + id + "/");
        assertEquals(200, page.getStatusCode(), "premise: anonymous, holding Manage and Overall/Read, sees the request page");
        WebResponse answer = ApproverFormFixtures.post(j, null, "batch-control/grants/" + id + "/cancel", List.of());
        RequestStatus status = GrantRequestService.get().load(id).getStatus();
        System.out.println("T-GAP-348 observation: anonymous cancel answered HTTP " + answer.getStatusCode() + ", status " + status
                + ", location " + answer.getResponseHeaderValue("Location") + ": " + excerpt(answer.getContentAsString()));
        assertTrue(answer.getStatusCode() < 500, "anonymous's cancel must not end in a server error: " + excerpt(answer.getContentAsString()));
        if (status == RequestStatus.PENDING) {
            boolean signIn = answer.getContentAsString().contains("<title>Sign in");
            assertTrue(answer.getStatusCode() >= 400 || signIn, "a cancel that changed nothing must be answered as a refusal (4xx, or core's"
                    + " sign-in page for anonymous), got HTTP " + answer.getStatusCode() + ": " + excerpt(answer.getContentAsString()));
        } else {
            assertEquals(RequestStatus.CANCELLED, status, "SPEC 7: the only other outcome is CANCELLED");
        }
    }

    /**
     * T-GAP-349 (L3-14; SPEC 3 "결재 전까지 요청자가 결재자를 바꿀 수 있고" and "목록에 없는 사용자 ... 거부", LIMITATIONS
     * 23, SPEC 6 usability): u1, the requester, POSTs {@code batch-control/grants/<id>/changeApprover}
     * with no approver, with 51 approvers (a1 and 50 ids not on the list) and with one approver id of 257
     * characters: each is refused (4xx) with a message inside the approvers' form item, and the
     * designated set stays {@code [a1]} with no change recorded. Guard: changing to {@code [a2]} is
     * accepted and recorded as (previous set, new set, changed by).
     */
    @Test
    public void t_gap_349_changeApproverRefusesBadSetsOnTheField() throws Exception {
        String id = submitGrantOk(j, "u1", "job-k", List.of("CONFIGURE"), 30, KEPT_REASON, null, "a1");
        String[] many = new String[51];
        many[0] = "a1";
        for (int i = 1; i < many.length; i++) {
            many[i] = String.format(Locale.ROOT, "l3-approver-%02d", i);
        }
        for (String[] set : List.of(new String[0], many, new String[] {"z".repeat(257)})) {
            String what = "u1's change to " + set.length + " approver(s)";
            Page refused = postPage("u1", "batch-control/grants/" + id + "/changeApprover", approverPairs(set));
            assertFormRefusal(refused, what, "batch-control/grants/" + id + "/changeApprover", new String[] {"approvers"}, null);
            GrantRequest reloaded = GrantRequestService.get().load(id);
            assertEquals(List.of("a1"), reloaded.getApprovers(), what + ": the designated set is unchanged");
            assertTrue(reloaded.getApproverChanges() == null || reloaded.getApproverChanges().isEmpty(), what + ": no change is recorded");
        }
        assertSuccess(ApproverFormFixtures.changeGrantApprovers(j, "u1", id, "a2"), "guard: u1 changes the set to a2");
        GrantRequest changed = GrantRequestService.get().load(id);
        assertEquals(List.of("a2"), changed.getApprovers(), "guard: the set is now a2");
        assertEquals(1, changed.getApproverChanges().size(), "guard: one change is recorded");
    }

    /**
     * T-GAP-350 (L3-14; SPEC 3 / LIMITATIONS 23 "the requester edits the designated set", SPEC 7 cancel
     * by the requester or a Manage holder, PENDING only; SPEC 6 usability): a1, a designated approver
     * but not the requester, POSTs {@code changeApprover}: refused (4xx), set unchanged; u2, neither the
     * requester nor a Manage holder, POSTs {@code cancel}: refused (4xx), PENDING; after a1 rejects the
     * request, u1 (the requester) POSTs {@code cancel}: refused with a plain message (4xx), the request
     * stays REJECTED. Guard: on a second PENDING request u1's cancel succeeds.
     */
    @Test
    public void t_gap_350_onlyTheRequesterChangesAndCancelsAPendingRequest() throws Exception {
        String id = submitGrantOk(j, "u1", "job-k", List.of("CONFIGURE"), 30, KEPT_REASON, null, "a1");
        WebResponse byApprover = ApproverFormFixtures.changeGrantApprovers(j, "a1", id, "a2");
        ApproverFormFixtures.assertClientError(byApprover, "a1's change of the approver set");
        assertEquals(List.of("a1"), GrantRequestService.get().load(id).getApprovers(), "a1's change must leave the set unchanged");

        WebResponse byOther = ApproverFormFixtures.post(j, "u2", "batch-control/grants/" + id + "/cancel", List.of());
        ApproverFormFixtures.assertClientError(byOther, "u2's cancel of u1's request");
        assertEquals(RequestStatus.PENDING, GrantRequestService.get().load(id).getStatus(), "u2's cancel must leave the request PENDING");

        assertSuccess(decideGrant(j, "a1", id, "reject", "not now"), "fixture: a1 rejects");
        WebResponse late = ApproverFormFixtures.post(j, "u1", "batch-control/grants/" + id + "/cancel", List.of());
        ApproverFormFixtures.assertClientError(late, "u1's cancel of a REJECTED request");
        UsabilityFixtures.assertPlainRefusal("u1's cancel of a REJECTED request", late.getContentAsString(), null);
        assertEquals(RequestStatus.REJECTED, GrantRequestService.get().load(id).getStatus(), "SPEC 7: a REJECTED request stays REJECTED");

        String second = submitGrantOk(j, "u1", "job-k", List.of("CONFIGURE"), 30, KEPT_REASON, null, "a1");
        assertSuccess(ApproverFormFixtures.post(j, "u1", "batch-control/grants/" + second + "/cancel", List.of()), "guard: u1's cancel");
        assertEquals(RequestStatus.CANCELLED, GrantRequestService.get().load(second).getStatus(), "guard: the PENDING request is CANCELLED");
    }

    /**
     * T-GAP-351 (L3-14; SPEC 8 "Manage 권한자는 활성 권한을 즉시 회수할 수 있고 이력에 남는다", SPEC 6 usability):
     * the administrator POSTs {@code batch-control/grants/active/<grant id>/revoke} twice. The first
     * revokes the window (not active, one GRANT_REVOKE record); the second is a plain refusal (4xx)
     * that says the window is already revoked or ended, and still exactly one GRANT_REVOKE record names
     * the window. {@code GrantService.get().revoke("no-such-id")} writes no GRANT_REVOKE record and ends
     * no window, whether it throws or not (the exception type is not documented, note 279).
     */
    @Test
    public void t_gap_351_secondRevokeIsAPlainRefusalAndRecordsNothing() throws Exception {
        String id = openWindow("u1", "job-k");
        Grant window = WindowStateFixtures.active(WindowStateFixtures.windowId("u1", "job-k"));
        Set<String> before = WindowStateFixtures.revokeRecordIds();
        assertSuccess(ApproverFormFixtures.post(j, "admin", "batch-control/grants/active/" + window.getId() + "/revoke", List.of()),
                "the administrator's first revoke");
        assertNull(WindowStateFixtures.active(window.getId()), "the first revoke ends the window at once");
        assertEquals(1, WindowStateFixtures.revokeRecordsSince(before).size(), "the first revoke writes one GRANT_REVOKE record");

        WebResponse again = ApproverFormFixtures.post(j, "admin", "batch-control/grants/active/" + window.getId() + "/revoke", List.of());
        ApproverFormFixtures.assertClientError(again, "the second revoke of the same window");
        UsabilityFixtures.assertPlainRefusal("the second revoke", again.getContentAsString(), Pattern.compile("(?i)revoked|ended|no longer active|not active"));
        List<ChangeRecord> revokes = WindowStateFixtures.revokeRecordsSince(before);
        assertEquals(1, revokes.size(), "the second revoke writes no further GRANT_REVOKE record: " + WindowStateFixtures.describe(revokes));
        assertNotNull(id, "fixture: the request id");

        String other = openWindow("u2", "job-k");
        assertNotNull(other, "fixture: u2's window");
        Set<String> beforeUnknown = WindowStateFixtures.revokeRecordIds();
        Throwable thrown = null;
        try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) {
            GrantService.get().revoke("no-such-id");
        } catch (RuntimeException e) {
            thrown = e;
        }
        System.out.println("T-GAP-351 observation: revoke(\"no-such-id\") threw " + (thrown == null ? "nothing" : thrown.getClass().getName()));
        assertTrue(WindowStateFixtures.revokeRecordsSince(beforeUnknown).isEmpty(), "revoking an unknown id writes no GRANT_REVOKE record");
        assertTrue(GrantService.get().hasActiveGrant("u2", "job-k", Item.CONFIGURE), "revoking an unknown id ends no window (u2's stays)");
    }

    /**
     * T-GAP-352 (L3-14; SPEC 8 line 173 "The approval page of a CONFIGURE request whose item is an item
     * group states that the group's settings apply to the items inside it", line 174 "The request screens
     * show the item's kind"; SPEC 6 usability (no error page)): a pending CONFIGURE request by u1 on the
     * folder {@code team}; its stored file {@code requests/grant/<id>.xml} is edited on disk: (a) the item
     * kind element removed, (b) the kind's descriptor id set to a type that is not installed, (c) the
     * kind's icon class set to an empty string, (d) to {@code icon-folder}. After each edit a1's view of
     * the request page answers 200, shows the item {@code team}, and, for (a) and (b), still states that
     * the folder's settings apply to the items inside it; for (c) and (d) it shows the kind's name.
     * Guard: before any edit the page carries the statement and the kind "Folder".
     */
    @Test
    public void t_gap_352_handEditedItemKindStillRendersTheRequestPage() throws Exception {
        String id = submitGrantOk(j, "u1", "team", List.of("CONFIGURE"), 30, KEPT_REASON, null, "a1");
        Path file = j.jenkins.getRootDir().toPath().resolve("batch-control/requests/grant/" + id + ".xml");
        assertTrue(Files.isRegularFile(file), "premise (ARCHITECTURE 5): the request is stored at " + file);
        String original = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(original.contains("<itemKind") && original.contains("<descriptorId>") && original.contains("<iconClassName>"),
                "premise (SPEC 3 itemKind{descriptorId, displayName, iconClassName}): the stored request carries its kind: " + excerpt(original));
        HtmlPage guard = UsabilityFixtures.htmlPage(j, "a1", "batch-control/grants/" + id + "/");
        assertTrue(appliesInside(guard), "guard: the folder request page states that the settings apply inside: " + excerpt(mainText(guard)));
        assertTrue(mainText(guard).contains("Folder"), "guard: the page shows the kind Folder");

        String noKind = original.replaceFirst("(?s)<itemKind[^>]*>.*?</itemKind>", "");
        String unknownType = original.replaceFirst("<descriptorId>[^<]*</descriptorId>", "<descriptorId>org.example.NotInstalledFolder</descriptorId>");
        String emptyIcon = original.replaceFirst("<iconClassName>[^<]*</iconClassName>", "<iconClassName></iconClassName>");
        String legacyIcon = original.replaceFirst("<iconClassName>[^<]*</iconClassName>", "<iconClassName>icon-folder</iconClassName>");
        String[][] edits = {{"the kind removed", noKind, "notice"}, {"an uninstalled kind", unknownType, "notice"},
                {"an empty icon class", emptyIcon, "name"}, {"the icon class icon-folder", legacyIcon, "name"}};
        try {
            for (String[] edit : edits) {
                assertFalse(edit[1].equals(original), "fixture: the edit '" + edit[0] + "' changes the file");
                Files.writeString(file, edit[1], StandardCharsets.UTF_8);
                Page answer = UsabilityFixtures.get(j, UsabilityFixtures.clientNoJs(j, "a1"), "batch-control/grants/" + id + "/");
                assertEquals(200, answer.getWebResponse().getStatusCode(), "the request page with " + edit[0] + " must render: "
                        + excerpt(UsabilityFixtures.text(answer)));
                HtmlPage page = (HtmlPage) answer;
                UsabilityFixtures.assertPlainRefusal("the request page with " + edit[0], page.asNormalizedText(), null);
                assertTrue(mainText(page).contains("team"), "the request page with " + edit[0] + " names the item team");
                if (edit[1] == unknownType) {
                    assertFalse(page.querySelectorAll("[data-batch-control-item-kind=\"org.example.NotInstalledFolder\"]").isEmpty(),
                            "premise (T-08-122: the scope carries data-batch-control-item-kind with the stored descriptor id): the page shows"
                                    + " the kind read from the edited file: " + excerpt(page.getWebResponse().getContentAsString()));
                }
                if ("notice".equals(edit[2])) {
                    assertTrue(appliesInside(page), "SPEC 8 line 173: with " + edit[0] + " the page still states that the folder's settings"
                            + " apply to the items inside it: " + excerpt(mainText(page)));
                } else {
                    assertTrue(mainText(page).contains("Folder"), "with " + edit[0] + " the page still shows the kind's name: "
                            + excerpt(mainText(page)));
                }
            }
        } finally {
            Files.writeString(file, original, StandardCharsets.UTF_8);
        }
    }

    // ------------------------------------------------------------------ L3-15

    /**
     * T-GAP-353 (L3-15; SPEC 8 line 175 "The grants ... pages list pending requests first, then active
     * and ended items", SPEC 6 usability "recorded history names who did what (for example who
     * cancelled a request ...)", D-40 "shown to the approver"): u1's Grants list shows, among the ended
     * rows, the request a2 (BatchControl/Manage) cancelled with the word "cancelled" and a2's id, and the
     * request whose pending timeout passed (periodic work run) with the word "expired"; among the active
     * rows the CREATE window on {@code team} restricted to {@code nightly-l3} with that restriction, and
     * the unrestricted CREATE window on {@code team2} without it.
     */
    @Test
    public void t_gap_353_grantsListNamesHowRequestsEndedAndCreateRestrictions() throws Exception {
        permit("a2", BatchControlPermissions.MANAGE);
        j.jenkins.createProject(Folder.class, "team2");
        Instant t0 = Instant.now();
        BatchClock.setForTest(Clock.fixed(t0, ZoneOffset.UTC));
        String cancelled = submitGrantOk(j, "u1", "job-k", List.of("CONFIGURE"), 30, KEPT_REASON, null, "a1");
        assertSuccess(ApproverFormFixtures.post(j, "a2", "batch-control/grants/" + cancelled + "/cancel", List.of()), "fixture: a2 cancels");
        String expired = submitGrantOk(j, "u1", "job-k", List.of("DELETE"), 30, KEPT_REASON, null, "a1");
        BatchClock.setForTest(Clock.fixed(t0.plus(Duration.ofHours(cfg.getPendingTimeoutHours() + 1)), ZoneOffset.UTC));
        ExtensionList.lookupSingleton(ExpiryPeriodicWork.class).doRun();
        assertEquals(RequestStatus.EXPIRED, GrantRequestService.get().load(expired).getStatus(), "premise: the overdue request EXPIRED");
        String restricted = submitGrantOk(j, "u1", "team", List.of("CREATE"), 30, KEPT_REASON, "nightly-l3", "a1");
        assertSuccess(decideGrant(j, "a1", restricted, "approve", "ok"), "fixture: a1 approves the restricted CREATE window");
        String unrestricted = submitGrantOk(j, "u1", "team2", List.of("CREATE"), 30, KEPT_REASON, null, "a1");
        assertSuccess(decideGrant(j, "a1", unrestricted, "approve", "ok"), "fixture: a1 approves the unrestricted CREATE window");

        HtmlPage list = UsabilityFixtures.htmlPage(j, "u1", "batch-control/grants/");
        DomElement cancelledRow = rowLinking(list, WindowStateFixtures.ENDED_LIST, cancelled);
        assertNotNull(cancelledRow, "the cancelled request is listed among the ended rows: " + excerpt(list.asNormalizedText()));
        String cancelledText = cancelledRow.asNormalizedText().toLowerCase(Locale.ROOT);
        assertTrue(cancelledText.contains("cancel") && cancelledText.contains("a2"),
                "SPEC 6: the row says it was cancelled and by whom (a2): " + cancelledRow.asNormalizedText());
        DomElement expiredRow = rowLinking(list, WindowStateFixtures.ENDED_LIST, expired);
        assertNotNull(expiredRow, "the expired request is listed among the ended rows");
        assertTrue(expiredRow.asNormalizedText().toLowerCase(Locale.ROOT).contains("expired"),
                "the row says the request expired: " + expiredRow.asNormalizedText());

        String restrictedWindow = WindowStateFixtures.windowId("u1", "team");
        String unrestrictedWindow = WindowStateFixtures.windowId("u1", "team2");
        DomElement restrictedRow = WindowStateFixtures.activeRow(j, list, restrictedWindow);
        DomElement unrestrictedRow = WindowStateFixtures.activeRow(j, list, unrestrictedWindow);
        assertNotNull(restrictedRow, "the restricted CREATE window is listed among the active rows");
        assertNotNull(unrestrictedRow, "the unrestricted CREATE window is listed among the active rows");
        assertTrue(restrictedRow.asNormalizedText().contains("nightly-l3"), "D-40: the active row shows the restriction: "
                + restrictedRow.asNormalizedText());
        assertFalse(unrestrictedRow.asNormalizedText().contains("nightly-l3"), "guard: the unrestricted window's row shows no restriction: "
                + unrestrictedRow.asNormalizedText());
    }

    /**
     * T-GAP-354 (L3-15; SPEC 8 line 175 lists, ARCHITECTURE 4 "The screens read grants through the same
     * service as the permission checks"): u1's active CONFIGURE window on {@code job-k}; its request file
     * {@code requests/grant/<id>.xml} is deleted while Jenkins runs. The window is still active; u1's and
     * the administrator's Grants lists still answer 200 and list it among the active rows (found by its
     * id), naming {@code job-k}, and no link in that row leads to a 404 or 403 page (SPEC 6 usability).
     */
    @Test
    public void t_gap_354_windowWhoseRequestFileVanishedIsStillListed() throws Exception {
        String id = openWindow("u1", "job-k");
        String window = WindowStateFixtures.windowId("u1", "job-k");
        WindowStateFixtures.assertActiveOn(j, "u1", window, "job-k", "premise");
        Path file = j.jenkins.getRootDir().toPath().resolve("batch-control/requests/grant/" + id + ".xml");
        assertTrue(Files.isRegularFile(file), "premise (ARCHITECTURE 5): the request is stored at " + file);
        Files.delete(file);
        assertNotNull(WindowStateFixtures.active(window), "the window is still active after its request file vanished");
        for (String viewer : new String[] {"u1", "admin"}) {
            HtmlPage list = UsabilityFixtures.htmlPage(j, viewer, "batch-control/grants/");
            assertEquals(200, list.getWebResponse().getStatusCode(), viewer + "'s Grants list renders after the request file vanished");
            DomElement row = activeRowById(list, window);
            assertNotNull(row, viewer + "'s Grants list must still list the window among the active rows: " + excerpt(mainText(list)));
            assertTrue(row.asNormalizedText().contains("job-k"), "the row names job-k: " + row.asNormalizedText());
            assertNoDeadLinks(viewer, list, row, viewer + "'s active row of the window");
        }
    }

    /**
     * T-GAP-355 (L3-15; SPEC 8 line 171 / D-75 (1) "the grant pages, the grants list ... show the item's
     * current full name only to a viewer with Item/Read on it ... Administrators see the current name"):
     * u1's CONFIGURE window on {@code team/x}; the administrator moves {@code team/x} into the folder
     * {@code vault-l3}, which u1 may not read (the window follows, premise); then the window's request
     * file is deleted. u1's Grants list (raw HTML) names {@code vault-l3} nowhere; the administrator's
     * list shows {@code vault-l3/x} in the window's active row (the row found by the window's id).
     */
    @Test
    public void t_gap_355_followedNameStaysHiddenWhenTheRequestFileVanished() throws Exception {
        Folder team = (Folder) j.jenkins.getItemByFullName("team");
        team.createProject(FreeStyleProject.class, "x");
        vault();
        String id = openWindow("u1", "team/x");
        String window = WindowStateFixtures.windowId("u1", "team/x");
        moveIntoVault("team/x");
        WindowStateFixtures.assertActiveOn(j, "admin", window, VAULT + "/x", "premise (D-74): the window follows into " + VAULT);
        Files.delete(j.jenkins.getRootDir().toPath().resolve("batch-control/requests/grant/" + id + ".xml"));

        WebResponse u1List = ApproverFormFixtures.get(j, "u1", "batch-control/grants/");
        assertEquals(200, u1List.getStatusCode(), "u1's Grants list renders");
        assertFalse(u1List.getContentAsString().contains(VAULT), "D-75 (1): u1's Grants list must not name " + VAULT + ": "
                + excerpt(around(u1List.getContentAsString(), VAULT)));
        HtmlPage adminList = UsabilityFixtures.htmlPage(j, "admin", "batch-control/grants/");
        DomElement row = activeRowById(adminList, window);
        assertNotNull(row, "the administrator's list shows the window");
        assertTrue(row.asNormalizedText().contains(VAULT + "/x"), "D-75 (1): the administrator sees the current name: " + row.asNormalizedText());
    }

    /**
     * T-GAP-356 (L3-15; SPEC 8 line 171 / D-75 (1), SPEC 8 line 153 "재요청 링크"): u1's window on
     * {@code team/x} is moved by the administrator into {@code vault-l3} while open and then expires
     * (plugin clock). The "Request again" form for it ({@code batch-control/grants/new?from=<id>})
     * starts with an empty item name, names {@code vault-l3} nowhere and shows the approved name
     * {@code team/x} with a note saying it was moved. Guard: the form for u1's window on {@code team/y},
     * renamed by the administrator to {@code team/y2} (which u1 may read), starts with {@code team/y2}.
     */
    @Test
    public void t_gap_356_requestAgainShowsTheApprovedNameWithTheMovedNote() throws Exception {
        Folder team = (Folder) j.jenkins.getItemByFullName("team");
        team.createProject(FreeStyleProject.class, "x");
        team.createProject(FreeStyleProject.class, "y");
        vault();
        Instant t0 = Instant.now();
        BatchClock.setForTest(Clock.fixed(t0, ZoneOffset.UTC));
        openWindow("u1", "team/y");
        String control = WindowStateFixtures.windowId("u1", "team/y");
        assertTrue(ApproverFormFixtures.post(j, "admin", "job/team/job/y/confirmRename", List.of(new NameValuePair("newName", "y2")))
                .getStatusCode() < 400, "fixture: the administrator renames team/y to team/y2");
        openWindow("u1", "team/x");
        String window = WindowStateFixtures.windowId("u1", "team/x");
        moveIntoVault("team/x");
        BatchClock.setForTest(Clock.fixed(t0.plus(Duration.ofMinutes(31)), ZoneOffset.UTC));
        assertNull(WindowStateFixtures.active(window), "premise: the window has expired");

        Page form = UsabilityFixtures.get(j, UsabilityFixtures.clientNoJs(j, "u1"), "batch-control/grants/new?from=" + window);
        assertEquals(200, form.getWebResponse().getStatusCode(), "u1's Request again form opens");
        String raw = form.getWebResponse().getContentAsString();
        assertFalse(raw.contains(VAULT), "D-75 (1): the form names " + VAULT + " nowhere: " + excerpt(around(raw, VAULT)));
        HtmlPage page = (HtmlPage) form;
        assertEquals("", scopeField(page), "D-75 (1): the item name starts empty");
        String text = mainText(page);
        assertTrue(text.contains("team/x") && text.toLowerCase(Locale.ROOT).contains("moved"),
                "D-75 (1): the form shows the approved name team/x with a note that it was moved: " + excerpt(text));

        HtmlPage controlForm = UsabilityFixtures.htmlPage(j, "u1", "batch-control/grants/new?from=" + control);
        assertEquals("team/y2", scopeField(controlForm), "guard: the Request again form of u1's window on the renamed team/y2, which u1"
                + " may read, starts with its current name");
    }

    // ------------------------------------------------------------------ helpers

    /** The grant form's parameters; {@code custom} and {@code pattern} are left out when null. */
    static List<NameValuePair> form(String scope, List<String> actions, String duration, String custom, String reason,
                                    String pattern, String... approvers) {
        List<NameValuePair> params = new ArrayList<>();
        params.add(new NameValuePair("scopeFullName", scope));
        for (String action : actions) {
            params.add(new NameValuePair("actions", action));
        }
        params.add(new NameValuePair("durationMinutes", duration));
        if (custom != null) {
            params.add(new NameValuePair("customDurationMinutes", custom));
        }
        params.add(new NameValuePair("reason", reason));
        if (pattern != null) {
            params.add(new NameValuePair("createNamePattern", pattern));
        }
        params.addAll(approverPairs(approvers));
        return params;
    }

    /** A crumbed POST as {@code userId} (null: anonymous); redirects are not followed. */
    Page postPage(String userId, String path, List<NameValuePair> params) throws Exception {
        JenkinsRule.WebClient wc = ApproverFormFixtures.client(j, userId);
        wc.getOptions().setJavaScriptEnabled(false);
        WebRequest request = new WebRequest(wc.createCrumbedUrl(path), HttpMethod.POST);
        request.setRequestParameters(new ArrayList<>(params));
        request.setCharset(StandardCharsets.UTF_8);
        return wc.getPage(request);
    }

    /**
     * The usability line's form refusal: HTTP 4xx, an HTML page without a crash page or stack trace,
     * carrying a form that posts to {@code formSuffix}, with an error message inside the form item of
     * one of {@code fields}, and (when {@code kept} is not null) the typed value {@code kept}.
     */
    static void assertFormRefusal(Page answer, String what, String formSuffix, String[] fields, String kept) throws Exception {
        int code = answer.getWebResponse().getStatusCode();
        assertTrue(code >= 400 && code < 500, what + " must be refused with HTTP 4xx, got " + code + ": "
                + excerpt(answer.getWebResponse().getContentAsString()));
        assertTrue(answer instanceof HtmlPage, what + ": the refusal must be an HTML page, got " + answer.getWebResponse().getContentType());
        HtmlPage page = (HtmlPage) answer;
        UsabilityFixtures.assertNotBareErrorPage(what, page);
        UsabilityFixtures.assertPlainRefusal(what, page.asNormalizedText(), null);
        assertFalse(UsabilityFixtures.formsEndingWith(page, formSuffix).isEmpty(), what + ": the page must carry the form again; forms: "
                + UsabilityFixtures.formActions(page));
        boolean marked = false;
        for (String field : fields) {
            marked |= !fieldMessages(page, field).isEmpty();
        }
        assertTrue(marked, what + ": SPEC 6 usability: a message must stand next to the field " + Arrays.toString(fields) + ": "
                + excerpt(WindowStateFixtures.mainPanel(page).asNormalizedText()));
        if (kept != null) {
            assertTrue(UsabilityFixtures.pageKeepsValue(page, kept) || page.getWebResponse().getContentAsString().contains(kept),
                    what + ": the typed input '" + excerpt(kept) + "' must be kept");
        }
    }

    /**
     * Error messages inside a form item ({@code .jenkins-form-item}) that holds an input, select or
     * textarea named {@code field}: elements whose class list contains {@code error} or that carry
     * {@code role=alert}, with text.
     */
    static List<String> fieldMessages(HtmlPage page, String field) {
        List<String> out = new ArrayList<>();
        for (String tag : new String[] {"input", "select", "textarea"}) {
            for (DomElement control : page.getElementsByTagName(tag)) {
                if (!field.equals(control.getAttribute("name"))) {
                    continue;
                }
                for (DomNode n = control.getParentNode(); n instanceof DomElement; n = n.getParentNode()) {
                    DomElement e = (DomElement) n;
                    if (!(" " + e.getAttribute("class") + " ").contains(" jenkins-form-item ")) {
                        continue;
                    }
                    for (DomNode d : e.querySelectorAll("*")) {
                        DomElement candidate = (DomElement) d;
                        boolean error = (" " + candidate.getAttribute("class") + " ").contains(" error ")
                                || "alert".equals(candidate.getAttribute("role"));
                        String text = candidate.asNormalizedText().trim();
                        if (error && !text.isEmpty() && !out.contains(text)) {
                            out.add(text);
                        }
                    }
                }
            }
        }
        return out;
    }

    private String openWindow(String userId, String fullName) throws Exception {
        String id = submitGrantOk(j, userId, fullName, List.of("CONFIGURE"), 30, KEPT_REASON, null, "a1");
        assertSuccess(decideGrant(j, "a1", id, "approve", "ok"), "fixture: approval by a1");
        return id;
    }

    private void permit(String userId, Permission... permissions) {
        BatchControlMatrixAuthorizationStrategy strategy = (BatchControlMatrixAuthorizationStrategy) j.jenkins.getAuthorizationStrategy();
        for (Permission permission : permissions) {
            strategy.add(permission, PermissionEntry.user(userId));
        }
    }

    /** {@code vault-l3}: a folder only the administrator may read (non-inheriting authorization property). */
    private void vault() throws Exception {
        Folder vault = j.jenkins.createProject(Folder.class, VAULT);
        com.cloudbees.hudson.plugins.folder.properties.AuthorizationMatrixProperty adminOnly =
                new com.cloudbees.hudson.plugins.folder.properties.AuthorizationMatrixProperty(new HashMap<Permission, Set<String>>());
        adminOnly.setInheritanceStrategy(new NonInheritingStrategy());
        adminOnly.add(Item.READ, PermissionEntry.user("admin"));
        vault.addProperty(adminOnly);
        assertFalse(vault.getACL().hasPermission2(User.getById("u1", true).impersonate2(), Item.READ), "fixture: u1 may not read " + VAULT);
    }

    private void moveIntoVault(String fullName) throws Exception {
        Item item = j.jenkins.getItemByFullName(fullName);
        WebResponse moved = ApproverFormFixtures.post(j, "admin", item.getUrl() + "move/move", List.of(new NameValuePair("destination", "/" + VAULT)));
        assertTrue(moved.getStatusCode() >= 300 && moved.getStatusCode() < 400, "fixture: the administrator moves " + fullName + ", got "
                + moved.getStatusCode());
        assertNotNull(j.jenkins.getItemByFullName(VAULT + "/" + item.getName()), "fixture: " + fullName + " is now in " + VAULT);
    }

    private WebResponse scopeCheck(String userId, String name) throws Exception {
        List<NameValuePair> params = new ArrayList<>();
        params.add(new NameValuePair("value", name));
        params.add(new NameValuePair("scopeFullName", name));
        return ApproverFormFixtures.post(j, userId, "batch-control/grants/checkScopeFullName", params);
    }

    private static String validationKind(WebResponse r) {
        Matcher m = Pattern.compile("class=[\"']?(ok|warning|error)\\b").matcher(r.getContentAsString());
        return m.find() ? m.group(1) : "";
    }

    private static String validationText(WebResponse r) {
        return r.getContentAsString().replaceAll("<[^>]*>", " ").replace("&#039;", "'").replace("&#39;", "'")
                .replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&").replaceAll("\\s+", " ").trim();
    }

    private static boolean mentionsChangeControlOff(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("change control") && (lower.contains(" off") || lower.contains("disabled") || lower.contains("not enabled")
                || lower.contains("turned off") || lower.contains("switched off"));
    }

    private static boolean appliesInside(HtmlPage page) {
        String lower = mainText(page).toLowerCase(Locale.ROOT);
        return lower.contains("apply to the items inside") || lower.contains("applies to the items inside")
                || (lower.contains("settings") && lower.contains("items inside"));
    }

    private static String mainText(HtmlPage page) {
        return WindowStateFixtures.mainPanel(page).asNormalizedText();
    }

    /** The row (tr) of {@code tableSelector} that links to {@code batch-control/grants/<id>/}. */
    private DomElement rowLinking(HtmlPage list, String tableSelector, String id) throws Exception {
        String path = new URL(j.getURL(), "batch-control/grants/" + id + "/").getPath();
        for (DomNode table : list.querySelectorAll(tableSelector)) {
            for (DomNode n : table.querySelectorAll("tr")) {
                for (DomElement a : ((DomElement) n).getElementsByTagName("a")) {
                    if (a.hasAttribute("href") && list.getFullyQualifiedUrl(a.getAttribute("href")).getPath().equals(path)) {
                        return (DomElement) n;
                    }
                }
            }
        }
        return null;
    }

    private static String scopeField(HtmlPage page) throws Exception {
        List<HtmlForm> forms = UsabilityFixtures.formsEndingWith(page, "batch-control/grants/create");
        assertFalse(forms.isEmpty(), "the page carries the grant request form: " + excerpt(page.asNormalizedText()));
        for (DomElement e : forms.get(0).getElementsByTagName("input")) {
            if (e instanceof HtmlInput input && "scopeFullName".equals(input.getAttribute("name"))) {
                return input.getValue();
            }
        }
        throw new AssertionError("the form offers scopeFullName: " + excerpt(forms.get(0).asXml()));
    }

    /** The row (tr) of the Active list whose text carries the window id {@code id}. */
    private static DomElement activeRowById(HtmlPage list, String id) {
        DomNode table = list.querySelector(WindowStateFixtures.ACTIVE_LIST);
        if (table == null) {
            return null;
        }
        for (DomNode n : table.querySelectorAll("tr")) {
            if (n.asNormalizedText().contains(id) || ((DomElement) n).asXml().contains(id)) {
                return (DomElement) n;
            }
        }
        return null;
    }

    /** SPEC 6 usability "no link leads to a 404 or 403 page": every anchor inside {@code scope}, opened by {@code viewer}. */
    private void assertNoDeadLinks(String viewer, HtmlPage page, DomElement scope, String what) throws Exception {
        JenkinsRule.WebClient wc = UsabilityFixtures.clientNoJs(j, viewer);
        for (DomElement a : scope.getElementsByTagName("a")) {
            String href = a.getAttribute("href");
            if (href == null || href.isEmpty() || href.startsWith("#") || href.startsWith("javascript:")) {
                continue;
            }
            int code = wc.getPage(new WebRequest(page.getFullyQualifiedUrl(href), HttpMethod.GET)).getWebResponse().getStatusCode();
            assertTrue(code < 400, what + ": the link " + href + " must not lead to a 404 or 403 page, got " + code);
        }
    }

    private static String around(String text, String needle) {
        int at = text.indexOf(needle);
        return at < 0 ? "" : text.substring(Math.max(0, at - 200), Math.min(text.length(), at + 200));
    }
}
