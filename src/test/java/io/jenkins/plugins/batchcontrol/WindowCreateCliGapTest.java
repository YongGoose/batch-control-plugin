package io.jenkins.plugins.batchcontrol;

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.security.AuthorizationMatrixProperty;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.security.BatchControlMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.security.GrantService;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.WebResponse;
import org.htmlunit.util.NameValuePair;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.jenkinsci.plugins.matrixauth.inheritance.InheritParentStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.assertSuccess;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.decideGrant;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.records;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.submitGrantOk;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Coverage lane 2, scenario L2-06 (matrix rows T-GAP-215 .. T-GAP-221, note 277): the D-40 name
 * restriction on the CLI over HTTP and on non-web creation, and the D-71c rename refusal.
 *
 * <p>Basis: SPEC item 8, the D-40 line: "With a restriction, the grant lets its holder create only
 * items directly inside the window's folder whose name matches in full; an attempt with another name
 * is refused with HTTP 4xx, leaves no item, and is recorded as GRANT_VIOLATION ... a rename resting on
 * a window is refused whatever the new name (D-71c)"; DECISIONS D-40a "The new name of a creation or
 * rename is read only from the parameter of the endpoint that performs it ... the CLI command's own
 * argument); anything else is unknown and a restricted grant confers nothing"; SPEC item 8 line 170
 * "a refused rename, whatever URL form reaches core's rename endpoints, is recorded as
 * GRANT_VIOLATION (D-73 coalescing applies)"; SPEC item 9 "UI, REST, CLI ... 경로의 변경이 모두
 * ChangeRecord로 남는다"; SPEC 6 usability (no link leads to a 404 or 403 page).
 *
 * <p>The CLI is the real client ({@code hudson.cli.CLI}, the {@code cli} artifact of Jenkins core) run
 * in a child JVM with {@code -http -auth <user>:<API token>}, so the server sees a real CLI request
 * (the scenario file explains why {@code CLICommandInvoker} would not exercise it). Raw rename
 * variants are sent on a plain socket with basic authentication (API token, no crumb), so the path
 * reaches the server exactly as written.
 *
 * <p>Fixture: change control on, the Batch Control matrix strategy ({@link StrategyFixtures}); folders
 * {@code ff} and {@code gg}; bob holds a CREATE window on {@code ff} restricted to {@code /ok-.*}{@code /}
 * and native Item/Configure on the source job {@code src} (its own property), so a copy may read it.
 *
 * <p>Written from docs/SPEC.md, docs/DECISIONS.md D-40/D-40a/D-71c/D-73 and docs/TEST-MATRIX.md only
 * (no src/main knowledge).
 */
@WithJenkins
public class WindowCreateCliGapTest {

    static final String MINIMAL_JOB_XML = "<?xml version='1.1' encoding='UTF-8'?>"
            + "<project><builders/><publishers/><buildWrappers/></project>";

    private JenkinsRule j;
    private BatchControlMatrixAuthorizationStrategy strategy;
    private Folder ff;
    private Folder gg;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        strategy = StrategyFixtures.matrix(new BatchControlMatrixAuthorizationStrategy());
        strategy.add(Jenkins.READ, PermissionEntry.user("erin"));
        strategy.add(Item.READ, PermissionEntry.user("erin"));
        strategy.add(BatchControlPermissions.REQUEST_GRANT, PermissionEntry.user("erin"));
        j.jenkins.setAuthorizationStrategy(strategy);
        StrategyFixtures.changeControlOn();
        ff = j.jenkins.createProject(Folder.class, "ff");
        gg = j.jenkins.createProject(Folder.class, "gg");
        FreeStyleProject src = j.createFreeStyleProject("src");
        AuthorizationMatrixProperty amp = new AuthorizationMatrixProperty(new HashMap<>(), new InheritParentStrategy());
        amp.add(Item.CONFIGURE, PermissionEntry.user("bob"));
        src.addProperty(amp);
    }

    // ------------------------------------------------------------------ CLI over HTTP

    /**
     * T-GAP-215 (L2-06, SPEC 8 D-40, D-40a): over the real CLI ({@code -http}), bob's
     * {@code create-job ff/ok-1} (config.xml on stdin) exits 0 and creates the job;
     * {@code create-job ff/bad} exits non-zero, leaves no item and adds one GRANT_VIOLATION naming bob.
     */
    @Test
    public void t_gap_215_cliCreateJobFollowsTheRestriction() throws Exception {
        restrictedWindow("bob", "ff", "/ok-.*/");
        CliResult ok = cli(j, "bob", MINIMAL_JOB_XML, "create-job", "ff/ok-1");
        assertEquals(0, ok.code, "create-job of a matching name must succeed: " + ok);
        assertNotNull(ff.getItem("ok-1"), "the matching job must exist");
        assertTrue(records(ChangeType.GRANT_VIOLATION).isEmpty(), "the permitted creation records no violation");

        CliResult bad = cli(j, "bob", MINIMAL_JOB_XML, "create-job", "ff/bad");
        assertNotEquals(0, bad.code, "create-job of a non-matching name must exit non-zero: " + bad);
        assertNull(ff.getItem("bad"), "no item may be left behind");
        List<ChangeRecord> violations = records(ChangeType.GRANT_VIOLATION);
        assertEquals(1, violations.size(), "the refused CLI creation is recorded once: " + violations);
        assertEquals("bob", violations.get(0).getUser(), "the GRANT_VIOLATION names bob");
    }

    /**
     * T-GAP-216 (L2-06, SPEC 8 D-40, D-71 "directly inside the window's folder"): over the real CLI,
     * bob's {@code copy-job src ff/ok-2} exits 0 and creates the copy; {@code copy-job src ff/bad2}
     * and {@code copy-job src gg/ok-3} (a folder no window names) exit non-zero and leave no item.
     */
    @Test
    public void t_gap_216_cliCopyJobFollowsTheRestrictionAndTheFolder() throws Exception {
        restrictedWindow("bob", "ff", "/ok-.*/");
        CliResult ok = cli(j, "bob", null, "copy-job", "src", "ff/ok-2");
        assertEquals(0, ok.code, "copy-job to a matching name must succeed: " + ok);
        assertNotNull(ff.getItem("ok-2"), "the matching copy must exist");

        // (the permitted copy may itself record the removal of src's authorization property, D-35c)
        int before = records(ChangeType.GRANT_VIOLATION).size();
        CliResult bad = cli(j, "bob", null, "copy-job", "src", "ff/bad2");
        assertNotEquals(0, bad.code, "copy-job to a non-matching name must exit non-zero: " + bad);
        assertNull(ff.getItem("bad2"), "no copy may be left behind");
        List<ChangeRecord> violations = records(ChangeType.GRANT_VIOLATION);
        assertEquals(before + 1, violations.size(), "the refused copy is recorded once: " + violations);
        assertEquals("bob", violations.get(violations.size() - 1).getUser(), "the GRANT_VIOLATION names bob");

        CliResult other = cli(j, "bob", null, "copy-job", "src", "gg/ok-3");
        assertNotEquals(0, other.code, "copy-job into another folder must exit non-zero: " + other);
        assertNull(gg.getItem("ok-3"), "nothing may be created in gg");
    }

    // ------------------------------------------------------------------ non-web creation

    /**
     * T-GAP-217 (L2-06, D-40a "anything else is unknown and a restricted grant confers nothing"): bob
     * creates {@code ff/ok-prog} from code ({@code ACL.as2(bob)}, {@code Folder#createProject}, no HTTP
     * request): refused, no item. Guard: the same name through {@code createItem} succeeds.
     */
    @Test
    public void t_gap_217_programmaticCreationUnderARestrictedWindowIsRefused() throws Exception {
        restrictedWindow("bob", "ff", "/ok-.*/");
        boolean refused = false;
        try (ACLContext ignored = ACL.as2(User.getById("bob", true).impersonate2())) {
            ff.createProject(FreeStyleProject.class, "ok-prog");
        } catch (RuntimeException expected) {
            refused = true;
        }
        assertTrue(refused, "a creation outside any endpoint must be refused under a restricted window");
        assertNull(ff.getItem("ok-prog"), "no item may be left behind");

        assertTrue(createItem("bob", ff, "ok-http") < 400, "guard: the same name through createItem succeeds");
        assertNotNull(ff.getItem("ok-http"), "guard: the createItem creation exists");
    }

    /**
     * T-GAP-218 (L2-06, D-40a): bob POSTs {@code job/ff/createItem} with a config.xml but no
     * {@code name} parameter: refused (4xx) and nothing is created in {@code ff}.
     */
    @Test
    public void t_gap_218_createItemWithoutANameIsRefused() throws Exception {
        restrictedWindow("bob", "ff", "/ok-.*/");
        int before = ff.getItems().size();
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login("bob");
        WebRequest req = new WebRequest(wc.createCrumbedUrl(ff.getUrl() + "createItem"), HttpMethod.POST);
        req.setAdditionalHeader("Content-Type", "application/xml; charset=UTF-8");
        req.setRequestBody(MINIMAL_JOB_XML);
        int code = wc.getPage(req).getWebResponse().getStatusCode();
        assertTrue(code >= 400 && code < 500, "a creation without a name must be refused with 4xx, got " + code);
        assertEquals(before, ff.getItems().size(), "nothing may be created");
    }

    /**
     * T-GAP-219 (L2-06, SPEC 6 usability "no link leads to a 404 or 403 page"): bob, whose only Create
     * on {@code ff} is the restricted window, opens {@code job/ff/newJob}: the New Item page renders
     * (200). Guard: carol, who holds no Create on {@code ff}, is refused.
     */
    @Test
    public void t_gap_219_newItemPageRendersForARestrictedWindowHolder() throws Exception {
        restrictedWindow("bob", "ff", "/ok-.*/");
        WebResponse bob = ApproverFormFixtures.get(j, "bob", ff.getUrl() + "newJob");
        assertEquals(200, bob.getStatusCode(), "the New Item page must render for the window holder");
        WebResponse carol = ApproverFormFixtures.get(j, "carol", ff.getUrl() + "newJob");
        assertTrue(carol.getStatusCode() >= 400, "guard: a user without Create on ff is refused, got " + carol.getStatusCode());
    }

    // ------------------------------------------------------------------ rename through Delete + Create

    /**
     * T-GAP-220 (L2-06, SPEC 8 D-71c, LIMITATIONS 33): erin holds native Item/Delete on job
     * {@code ff/x} (its own property; no Configure) and an unrestricted CREATE window on {@code ff}.
     * erin's {@code confirmRename} of {@code ff/x} answers 400 with the D-71c refusal and "Nothing was
     * renamed.", {@code ff/x} keeps its name, and one GRANT_VIOLATION names erin. Guard: c1, who holds
     * Item/Configure natively, renames {@code ff/x2} under core's rule.
     */
    @Test
    public void t_gap_220_renameThroughDeletePlusCreateWindowIsRefused() throws Exception {
        FreeStyleProject x = deletableBy("erin", "x");
        ff.createProject(FreeStyleProject.class, "x2");
        unrestrictedWindow("erin", "ff");
        assertTrue(StrategyFixtures.has(x, "erin", Item.DELETE) && StrategyFixtures.has(ff, "erin", Item.CREATE),
                "premise: erin holds Delete on ff/x and Create in ff");
        assertFalse(StrategyFixtures.has(x, "erin", Item.CONFIGURE), "premise: erin holds no Configure on ff/x");

        WebResponse answer = ApproverFormFixtures.post(j, "erin", x.getUrl() + "confirmRename",
                Collections.singletonList(new NameValuePair("newName", "renamed-x")));
        RenameRefusalFixtures.assertWindowRenameRefused(answer, "ff/x", "Freestyle project", "erin's rename");
        assertNotNull(ff.getItem("x"), "ff/x keeps its name");
        assertNull(ff.getItem("renamed-x"), "nothing carries the new name");
        List<ChangeRecord> violations = records(ChangeType.GRANT_VIOLATION);
        assertEquals(1, violations.size(), "the refused rename is recorded once: " + violations);
        assertEquals("erin", violations.get(0).getUser(), "the GRANT_VIOLATION names erin");

        ApproverFormFixtures.post(j, "c1", ff.getUrl() + "job/x2/confirmRename",
                Collections.singletonList(new NameValuePair("newName", "x2-renamed")));
        assertNotNull(ff.getItem("x2-renamed"), "guard: a standing Configure holder renames under core's rule");
    }

    /**
     * T-GAP-221 (L2-06 (u), SPEC 8 line 170 "whatever URL form reaches core's rename endpoints"): the
     * T-GAP-220 rename posted by erin (basic authentication with an API token) as
     * {@code .../confirmRename;x=1}, {@code ..././confirmRename} and {@code .../confirm%ZZRename} (a
     * malformed escape). Each variant that reaches core's rename endpoint is refused with the D-71c
     * refusal, renames nothing and adds one GRANT_VIOLATION; a variant answered with 400 before it reaches
     * that endpoint (Jetty's bad-escape answer, core's "Semicolons are not allowed" request filter) is
     * skipped (scenario rule) after asserting that nothing was renamed, and the row is skipped when every
     * variant is.
     */
    @Test
    public void t_gap_221_renameUrlVariantsAreRefusedToo() throws Exception {
        FreeStyleProject x = deletableBy("erin", "x");
        unrestrictedWindow("erin", "ff");
        String token = RawHttpFixtures.apiToken("erin");
        String base = x.getUrl();
        String[][] variants = {
            {base + "confirmRename;x=1?newName=v-one", "v-one"},
            {base + "./confirmRename?newName=v-two", "v-two"},
            {base + "confirm%ZZRename?newName=v-three", "v-three"},
        };
        int reached = 0;
        List<String> skipped = new ArrayList<>();
        for (String[] variant : variants) {
            int before = records(ChangeType.GRANT_VIOLATION).size();
            String[] answer = rawPost(j.getURL(), variant[0], RawHttpFixtures.basic("erin", token));
            int code = Integer.parseInt(answer[0]);
            String text = RenameRefusalFixtures.visible(answer[1]);
            assertNotNull(ff.getItem("x"), variant[0] + " must leave ff/x under its name");
            assertNull(ff.getItem(variant[1]), "nothing may carry " + variant[1] + " after " + variant[0]);
            boolean byJenkins = text.contains("Jenkins") || text.contains("Renaming") || records(ChangeType.GRANT_VIOLATION).size() > before;
            if (code == 400 && !byJenkins) {
                skipped.add(variant[0] + " (answered 400 before it reached core's rename endpoint: "
                        + ApproverFormFixtures.excerpt(text) + ")");
                continue;
            }
            reached++;
            assertTrue(code >= 400 && code < 500, variant[0] + " must be refused with 4xx, got " + code + ": "
                    + ApproverFormFixtures.excerpt(text));
            if (code != 404) {
                assertTrue(text.contains(RenameRefusalFixtures.refusal("ff/x", "Freestyle project")),
                        variant[0] + " must carry the D-71c refusal: " + ApproverFormFixtures.excerpt(text));
                assertEquals(before + 1, records(ChangeType.GRANT_VIOLATION).size(), variant[0] + " must be recorded once");
            }
        }
        System.out.println("T-GAP-221 skipped variants: " + skipped);
        assumeTrue(reached > 0, "every variant was answered before it reached core's rename endpoint: " + skipped);
    }

    // ---------------------------------------------------------------- helpers

    /** A job in {@code ff} whose own property gives {@code user} Item/Delete (no Configure). */
    private FreeStyleProject deletableBy(String user, String name) throws Exception {
        FreeStyleProject x = ff.createProject(FreeStyleProject.class, name);
        AuthorizationMatrixProperty amp = new AuthorizationMatrixProperty(new HashMap<>(), new InheritParentStrategy());
        amp.add(Item.DELETE, PermissionEntry.user(user));
        x.addProperty(amp);
        return x;
    }

    /** A CREATE window on {@code folder} restricted to {@code pattern}, requested by {@code user}, approved by a1. */
    private void restrictedWindow(String user, String folder, String pattern) throws Exception {
        String id = submitGrantOk(j, user, folder, Arrays.asList("CREATE"), 30, "create the ok jobs", pattern, "a1");
        assertSuccess(decideGrant(j, "a1", id, "approve", "ok"), "fixture: approval by a1");
        assertTrue(GrantService.get().listActive().stream().anyMatch(g -> user.equals(g.getUser())),
                "fixture: " + user + " must hold an active window");
    }

    private void unrestrictedWindow(String user, String folder) throws Exception {
        String id = submitGrantOk(j, user, folder, Arrays.asList("CREATE"), 30, "create jobs", null, "a1");
        assertSuccess(decideGrant(j, "a1", id, "approve", "ok"), "fixture: approval by a1");
    }

    private int createItem(String user, Folder container, String name) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login(user);
        URL url = new URL(wc.createCrumbedUrl(container.getUrl() + "createItem").toExternalForm()
                + "&name=" + URLEncoder.encode(name, StandardCharsets.UTF_8));
        WebRequest req = new WebRequest(url, HttpMethod.POST);
        req.setAdditionalHeader("Content-Type", "application/xml; charset=UTF-8");
        req.setRequestBody(MINIMAL_JOB_XML);
        return wc.getPage(req).getWebResponse().getStatusCode();
    }

    /** Exit code and output of one CLI run. */
    static final class CliResult {
        final int code;
        final String out;
        final String err;

        CliResult(int code, String out, String err) {
            this.code = code;
            this.out = out;
            this.err = err;
        }

        @Override
        public String toString() {
            return "exit=" + code + " stdout=" + ApproverFormFixtures.excerpt(out) + " stderr=" + ApproverFormFixtures.excerpt(err);
        }
    }

    /**
     * Runs the real Jenkins CLI client ({@code hudson.cli.CLI}) in a child JVM against {@code j} over
     * HTTP ({@code -http}), authenticated as {@code user} with a fresh API token, feeding {@code stdin}
     * (or nothing).
     */
    static CliResult cli(JenkinsRule j, String user, String stdin, String... args) throws Exception {
        String token = RawHttpFixtures.apiToken(user);
        List<String> command = new ArrayList<>(Arrays.asList(
                System.getProperty("java.home") + File.separator + "bin" + File.separator + "java",
                "-cp", System.getProperty("java.class.path"), "hudson.cli.CLI",
                "-s", j.getURL().toExternalForm(), "-http", "-auth", user + ":" + token));
        command.addAll(Arrays.asList(args));
        File out = File.createTempFile("cli-out", ".txt");
        File err = File.createTempFile("cli-err", ".txt");
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectOutput(out);
            pb.redirectError(err);
            Process process = pb.start();
            try (OutputStream in = process.getOutputStream()) {
                if (stdin != null) {
                    in.write(stdin.getBytes(StandardCharsets.UTF_8));
                }
            }
            if (!process.waitFor(180, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new AssertionError("the CLI did not finish within 180 s: " + String.join(" ", args));
            }
            return new CliResult(process.exitValue(), Files.readString(out.toPath(), StandardCharsets.UTF_8),
                    Files.readString(err.toPath(), StandardCharsets.UTF_8));
        } finally {
            out.delete();
            err.delete();
        }
    }

    /** POSTs {@code relative} as written on a plain socket with the given Authorization; returns {status, body}. */
    static String[] rawPost(URL jenkins, String relative, String authorization) throws Exception {
        URL target = new URL(jenkins, "x");
        String path = jenkins.getPath() + relative;
        try (Socket socket = new Socket(target.getHost(), target.getPort())) {
            socket.setSoTimeout(60_000);
            OutputStream out = socket.getOutputStream();
            String head = "POST " + path + " HTTP/1.1\r\nHost: " + target.getHost() + ":" + target.getPort()
                    + "\r\nConnection: close\r\nAuthorization: " + authorization
                    + "\r\nContent-Length: 0\r\nAccept: text/html\r\n\r\n";
            out.write(head.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            InputStream in = socket.getInputStream();
            ByteArrayOutputStream all = new ByteArrayOutputStream();
            in.transferTo(all);
            String response = all.toString(StandardCharsets.UTF_8);
            String status = response.split(" ", 3)[1];
            int bodyAt = response.indexOf("\r\n\r\n");
            return new String[] {status, bodyAt < 0 ? "" : response.substring(bodyAt + 4)};
        }
    }
}
