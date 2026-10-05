package io.jenkins.plugins.batchcontrol;

import com.michelin.cio.hudson.plugins.rolestrategy.Role;
import com.michelin.cio.hudson.plugins.rolestrategy.RoleBasedAuthorizationStrategy;
import com.michelin.cio.hudson.plugins.rolestrategy.RoleStrategyConfig;
import com.synopsys.arc.jenkins.plugins.rolestrategy.RoleType;
import hudson.PluginWrapper;
import hudson.model.Descriptor;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.security.AuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.model.GrantAction;
import io.jenkins.plugins.batchcontrol.security.BatchControlRoleBasedAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.htmlunit.WebResponse;
import org.htmlunit.util.NameValuePair;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.StrategyFixtures.T0;
import static io.jenkins.plugins.batchcontrol.StrategyFixtures.has;
import static io.jenkins.plugins.batchcontrol.UsabilityFixtures.excerpt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * D-35f, D-35g (role-strategy 927 is the minimum supported version) and SPEC item 2 ("With the Batch
 * Control role-strategy strategy installed, Manage Roles ... work"). Matrix rows T-02-88..99
 * (note 190).
 *
 * <ul>
 *   <li>T-02-88..95: every role-strategy save path (the REST endpoints under
 *   {@code role-strategy/strategy/}, the Assign Roles save and the permission-template save of the
 *   RoleStrategyConfig page) keeps the installed {@code BatchControlRoleBasedAuthorizationStrategy},
 *   the change takes effect, and an open grant keeps conferring. Each row runs the same call under
 *   the plain {@code RoleBasedAuthorizationStrategy} as its guard: the plain class stays plain, so
 *   the assertion measures the installed class and not a constant answer.</li>
 *   <li>T-02-96..98: the descriptor surface guard. role-strategy's own views (Jelly in its jar,
 *   the frontend bundle in its exploded plugin directory) are scanned for every member they call on
 *   the installed strategy's descriptor and every descriptor URL they request; each must exist on
 *   the Batch Control descriptor and answer under a running Jenkins. A future role-strategy release
 *   that calls something new fails the build here, naming the member.</li>
 *   <li>T-02-99: the Manage Roles, Assign Roles and permission-template pages render under the
 *   Batch Control strategy and point their validation URLs at its descriptor.</li>
 * </ul>
 *
 * <p>Written from docs/SPEC.md, docs/DECISIONS.md D-35a/D-35f and role-strategy's published jar and
 * hpi only (no src/main knowledge).
 */
@WithJenkins
public class RoleStrategySurfaceTest {

    private static final String PLUGIN = "role-strategy";
    private static final String REST = "role-strategy/strategy/";
    private static final String CONFIG_PAGE = "manage/role-strategy/";
    private static final String VARIANT = BatchControlRoleBasedAuthorizationStrategy.class.getName();
    private static final String PLAIN = RoleBasedAuthorizationStrategy.class.getName();
    private static final String ITEM_READ = Item.READ.getId();
    private static final String ITEM_CONFIGURE = Item.CONFIGURE.getId();

    /** A member called on the installed strategy's descriptor: {@code strategy.descriptor.<m>}. */
    private static final Pattern DESCRIPTOR_MEMBER = Pattern.compile("\\bstrategy\\.descriptor\\.([A-Za-z_$][\\w$]*)");
    /** A descriptor URL written directly: {@code /descriptor/${...strategy.descriptor...}/<m>}. */
    private static final Pattern DESCRIPTOR_URL = Pattern.compile("/descriptor(?:ByName)?/\\$\\{[^}]*strategy\\.descriptor[^}]*\\}/([A-Za-z]\\w*)");
    /** A Jelly variable holding the descriptor URL: {@code <j:set var="x" value=".../descriptor/${...}"/>}. */
    private static final Pattern DESCRIPTOR_URL_VAR = Pattern.compile(
            "<j:set\\s+var=\"(\\w+)\"\\s+value=\"[^\"]*/descriptor(?:ByName)?/\\$\\{[^}]*strategy\\.descriptor[^}]*\\}\"");
    /** The frontend appending a method to the descriptor URL it read from the page. */
    private static final Pattern JS_DESCRIPTOR_URL = Pattern.compile(
            "(?i)descriptor[-_]?(?:url|path)[\"'`\\])]*\\s*\\+\\s*[\"'`]/([A-Za-z]\\w*)"
            + "|(?i)\\$\\{[^}]*descriptor[-_]?(?:url|path)[^}]*\\}/([A-Za-z]\\w*)");

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        BatchClock.setForTest(Clock.fixed(T0, ZoneOffset.UTC));
        PluginWrapper rs = j.jenkins.getPluginManager().getPlugin(PLUGIN);
        assertNotNull(rs, "premise: role-strategy must be installed");
    }

    @AfterEach
    public void resetClock() {
        BatchClock.reset();
    }

    // ---------------------------------------------------------------- save paths (T-02-88..95)

    /** T-02-88: {@code addRole} creating a new item role keeps the Batch Control class; the role exists. */
    @Test
    public void t_02_88_addRoleNewKeepsBatchControlClass() throws Exception {
        savePath("addRole (new)",
                () -> rest("addRole", "type", RoleBasedAuthorizationStrategy.PROJECT, "roleName", "fresh",
                        "permissionIds", ITEM_READ + "," + ITEM_CONFIGURE, "overwrite", "false",
                        "pattern", "fresh-.*", "template", ""),
                () -> {
                    Role role = installed().getRoleMap(RoleType.Project).getRole("fresh");
                    assertNotNull(role, "addRole must have created the item role 'fresh'");
                    assertEquals("fresh-.*", role.getPattern().pattern(), "the new role must carry the posted pattern");
                });
    }

    /** T-02-89: {@code addRole} overwriting {@code team} keeps the class; bob loses Configure on team-a. */
    @Test
    public void t_02_89_addRoleOverwriteKeepsBatchControlClass() throws Exception {
        savePath("addRole (overwrite)",
                () -> {
                    assertTrue(has(team(), "bob", Item.CONFIGURE), "premise: the team role gives bob Configure on team-a");
                    rest("addRole", "type", RoleBasedAuthorizationStrategy.PROJECT, "roleName", "team",
                            "permissionIds", ITEM_READ, "overwrite", "true", "pattern", "team-.*", "template", "");
                },
                () -> assertFalse(has(team(), "bob", Item.CONFIGURE),
                        "the overwritten team role (Item/Read only) must no longer give bob Configure"));
    }

    /** T-02-90: {@code removeRoles} keeps the class; bob loses Configure on team-a. */
    @Test
    public void t_02_90_removeRolesKeepsBatchControlClass() throws Exception {
        savePath("removeRoles",
                () -> {
                    assertTrue(has(team(), "bob", Item.CONFIGURE), "premise: the team role gives bob Configure on team-a");
                    rest("removeRoles", "type", RoleBasedAuthorizationStrategy.PROJECT, "roleNames", "team");
                },
                () -> {
                    assertNull(installed().getRoleMap(RoleType.Project).getRole("team"), "removeRoles must remove 'team'");
                    assertFalse(has(team(), "bob", Item.CONFIGURE), "without the team role bob has no Configure on team-a");
                });
    }

    /** T-02-91: {@code addTemplate} keeps the class; the template exists. */
    @Test
    public void t_02_91_addTemplateKeepsBatchControlClass() throws Exception {
        savePath("addTemplate",
                () -> rest("addTemplate", "name", "tmpl", "permissionIds", ITEM_READ, "overwrite", "false"),
                () -> assertTrue(installed().hasPermissionTemplate("tmpl"), "addTemplate must have created 'tmpl'"));
    }

    /** T-02-92: {@code removeTemplates} keeps the class; the template is gone. */
    @Test
    public void t_02_92_removeTemplatesKeepsBatchControlClass() throws Exception {
        savePath("removeTemplates",
                () -> {
                    rest("addTemplate", "name", "tmpl", "permissionIds", ITEM_READ, "overwrite", "false");
                    assertTrue(installed().hasPermissionTemplate("tmpl"), "premise: the template exists");
                    rest("removeTemplates", "names", "tmpl", "force", "true");
                },
                () -> assertFalse(installed().hasPermissionTemplate("tmpl"), "removeTemplates must remove 'tmpl'"));
    }

    /** T-02-93: {@code assignUserRole} keeps the class; carol gains Configure on team-a. */
    @Test
    public void t_02_93_assignUserRoleKeepsBatchControlClass() throws Exception {
        savePath("assignUserRole",
                () -> {
                    assertFalse(has(team(), "carol", Item.CONFIGURE), "premise: carol holds no team role");
                    rest("assignUserRole", "type", RoleBasedAuthorizationStrategy.PROJECT, "roleName", "team", "user", "carol");
                },
                () -> assertTrue(has(team(), "carol", Item.CONFIGURE), "assignUserRole must give carol the team role"));
    }

    /**
     * T-02-94 (rewritten by D-35g, note 236): role-strategy 927's Assign Roles page saves through the
     * strategy endpoints {@code assignRole}, {@code unassignUserRole}, {@code deleteUser} and
     * {@code deleteSid} (918's {@code manage/role-strategy/assignSubmit} is gone). The sequence keeps
     * the class; carol gains the team role and bob loses it.
     */
    @Test
    public void t_02_94_assignRolesSaveKeepsBatchControlClass() throws Exception {
        savePath("Assign Roles endpoints (assignRole, unassignUserRole, deleteUser, deleteSid)",
                () -> {
                    assertTrue(has(team(), "bob", Item.CONFIGURE), "premise: bob holds the team role");
                    assertFalse(has(team(), "carol", Item.CONFIGURE), "premise: carol holds no team role");
                    String type = RoleBasedAuthorizationStrategy.PROJECT;
                    rest("assignRole", "type", type, "roleName", "team", "sid", "carol");
                    rest("unassignUserRole", "type", type, "roleName", "team", "user", "bob");
                    rest("deleteUser", "type", type, "user", "bob");
                    rest("deleteSid", "type", type, "sid", "bob");
                },
                () -> {
                    assertTrue(has(team(), "carol", Item.CONFIGURE), "the Assign Roles endpoints must give carol the team role");
                    assertFalse(has(team(), "bob", Item.CONFIGURE), "the Assign Roles endpoints must take the team role from bob");
                });
    }

    /** T-02-95: the permission-template save keeps the class; the posted template exists. */
    @Test
    public void t_02_95_permissionTemplateSaveKeepsBatchControlClass() throws Exception {
        savePath("permission-template save (templatesSubmit)",
                () -> {
                    JSONObject data = new JSONObject().element("tmpl2", new JSONObject().element(ITEM_READ, true));
                    submit("templatesSubmit", new JSONObject().element(RoleBasedAuthorizationStrategy.PERMISSION_TEMPLATES,
                            new JSONObject().element("data", data)));
                },
                () -> assertTrue(installed().hasPermissionTemplate("tmpl2"), "the template save must have created 'tmpl2'"));
    }

    // ---------------------------------------------------------------- descriptor surface (T-02-96..98)

    /**
     * T-02-96: every member role-strategy's views call on {@code it.strategy.descriptor} resolves on
     * {@code BatchControlRoleBasedAuthorizationStrategy.DescriptorImpl} (public method of that name,
     * public getter/is-getter, or public field, inherited ones included), and every descriptor URL
     * method they request has a public {@code do<Method>} web method.
     *
     * <p>Expected to fail on role-strategy releases that call a descriptor member this plugin does not
     * provide: for example the unreleased role-strategy PR #766 adds {@code checkSidName}, and this
     * row stays red on that release until a delegate is added (D-35f).
     */
    @Test
    public void t_02_96_descriptorSurfaceCoversRoleStrategyViews() throws Exception {
        installVariant();
        Scan scan = scanRoleStrategyViews();
        assertFalse(scan.members.isEmpty() || scan.urlMethods.isEmpty(),
                "the scan of role-strategy's views found nothing (members " + scan.members + ", URLs " + scan.urlMethods
                        + "): the guard would pass vacuously (D-35f)");

        Descriptor<AuthorizationStrategy> descriptor = j.jenkins.getAuthorizationStrategy().getDescriptor();
        assertTrue(descriptor instanceof BatchControlRoleBasedAuthorizationStrategy.DescriptorImpl,
                "the installed strategy's descriptor must be BatchControlRoleBasedAuthorizationStrategy.DescriptorImpl, got "
                        + descriptor.getClass().getName());
        Class<?> type = descriptor.getClass();

        List<String> missing = new ArrayList<>();
        scan.members.forEach((member, where) -> {
            if (!resolvesAsMember(type, member)) {
                missing.add("member '" + member + "' (called as it.strategy.descriptor." + member + " in " + where
                        + "): no public " + member + "(...), get/is" + cap(member) + "() or field " + member);
            }
        });
        scan.urlMethods.forEach((method, where) -> {
            if (!hasWebMethod(type, method)) {
                missing.add("web method 'do" + cap(method) + "' (requested as descriptor/<class>/" + method + " in " + where + ")");
            }
        });
        if (!missing.isEmpty()) {
            fail("role-strategy " + version() + " calls descriptor members that " + type.getName()
                    + " does not provide (D-35f: every member role-strategy's pages call on the installed strategy's"
                    + " descriptor must be available on the Batch Control descriptor; add a delegate):\n  - "
                    + String.join("\n  - ", missing));
        }
    }

    /**
     * T-02-97: every descriptor URL found in role-strategy's views answers under a running Jenkins
     * with the Batch Control strategy installed (admin POST with crumb): never 404.
     */
    @Test
    public void t_02_97_discoveredDescriptorUrlsAnswerUnderBatchControl() throws Exception {
        installVariant();
        Scan scan = scanRoleStrategyViews();
        assertFalse(scan.urlMethods.isEmpty(), "the scan found no descriptor URL: the row would pass vacuously (D-35f)");
        List<String> notFound = new ArrayList<>();
        for (Map.Entry<String, String> e : scan.urlMethods.entrySet()) {
            WebResponse r = ApproverFormFixtures.post(j, "admin", "descriptor/" + VARIANT + "/" + e.getKey(),
                    List.of(new NameValuePair("value", "team-.*")));
            if (r.getStatusCode() == 404) {
                notFound.add(e.getKey() + " (from " + e.getValue() + "): " + excerpt(r.getContentAsString()));
            }
        }
        assertTrue(notFound.isEmpty(), "role-strategy " + version() + " requests descriptor URLs that answer 404 under "
                + VARIANT + " (D-35f):\n  - " + String.join("\n  - ", notFound));
    }

    /**
     * T-02-98: the scanner is not blind. On role-strategy 927 it must find the descriptor member
     * {@code clazz} and the descriptor URLs {@code checkPattern} and {@code checkSidName}
     * ({@code data-check-pattern-url}, {@code data-check-sid-name-url}), which the Manage/Assign Roles
     * pages are known to use (918's {@code entryFor}/{@code hasAmbiguousEntries} are gone, D-35g,
     * note 236), so an empty or broken scan cannot make
     * T-02-96/97 pass.
     */
    @Test
    public void t_02_98_scannerFindsKnownDescriptorMembers() throws Exception {
        Scan scan = scanRoleStrategyViews();
        for (String known : new String[] {"clazz"}) {
            assertTrue(scan.members.containsKey(known), "the scan of role-strategy " + version()
                    + " must find the descriptor member '" + known + "', found " + scan.members.keySet() + " (D-35f)");
        }
        for (String known : new String[] {"checkPattern", "checkSidName"}) {
            assertTrue(scan.urlMethods.containsKey(known), "the scan of role-strategy " + version()
                    + " must find the descriptor URL '" + known + "', found " + scan.urlMethods.keySet() + " (D-35g)");
        }
        assertTrue(scan.files > 0, "the scan must have read role-strategy's Jelly files");
    }

    // ---------------------------------------------------------------- pages (T-02-99)

    /**
     * T-02-99: with the Batch Control strategy, the Manage Roles, Assign Roles and permission-template
     * pages answer 200 to the administrator, and the Manage Roles check-pattern URL and the Assign Roles
     * descriptor URL point at the Batch Control descriptor. Guard: under the plain strategy the same
     * pages point at role-strategy's own descriptor.
     */
    @Test
    public void t_02_99_roleStrategyPagesRenderWithBatchControlStrategy() throws Exception {
        j.jenkins.setAuthorizationStrategy(new RoleBasedAuthorizationStrategy(StrategyFixtures.roles(), Collections.emptySet()));
        String plainManage = page("manage-roles");
        assertTrue(plainManage.contains("/descriptor/" + PLAIN + "/checkPattern"),
                "guard: under the plain strategy the check-pattern URL names role-strategy's descriptor: " + excerpt(plainManage));

        installVariant();
        String manage = page("manage-roles");
        String contextPath = j.contextPath;
        assertTrue(manage.contains("data-check-pattern-url=\"" + contextPath + "/descriptor/" + VARIANT + "/checkPattern\""),
                "the Manage Roles page must point its check-pattern URL at the Batch Control descriptor: " + excerpt(manage));
        assertFalse(manage.contains("/descriptor/" + PLAIN + "/"), "the Manage Roles page must not name role-strategy's descriptor");
        String assign = page(""); // Assign Roles is the RoleStrategyConfig index page (918 and 927)
        assertTrue(assign.contains("/descriptor/" + VARIANT),
                "the Assign Roles page must point its descriptor URL at the Batch Control descriptor: " + excerpt(assign));
        page("permission-templates");
    }

    // ---------------------------------------------------------------- helpers

    private interface Step {
        void run() throws Exception;
    }

    /**
     * Runs {@code call} under the Batch Control strategy (change control on, carol's open grant on
     * {@code other}) and asserts that the class is kept, {@code effect} holds and the grant still
     * confers; then the guard: the same call under the plain strategy leaves the plain class.
     */
    private void savePath(String what, Step call, Step effect) throws Exception {
        j.createFreeStyleProject("team-a");
        j.createFreeStyleProject("fresh-a");
        FreeStyleProject other = j.createFreeStyleProject("other");

        installVariant();
        StrategyFixtures.changeControlOn();
        StrategyFixtures.grant("carol", "other", Arrays.asList(GrantAction.CONFIGURE));
        assertTrue(has(other, "carol", Item.CONFIGURE), "premise: carol's grant confers under the Batch Control strategy");
        call.run();
        assertSame(BatchControlRoleBasedAuthorizationStrategy.class, j.jenkins.getAuthorizationStrategy().getClass(),
                what + " must keep the installed BatchControlRoleBasedAuthorizationStrategy (D-35f), got "
                        + j.jenkins.getAuthorizationStrategy().getClass().getName());
        effect.run();
        assertTrue(has(other, "carol", Item.CONFIGURE), "after " + what + " carol's open grant must still confer (D-35f)");

        // guard: the call itself does not install a fixed class
        j.jenkins.setAuthorizationStrategy(new RoleBasedAuthorizationStrategy(StrategyFixtures.roles(), Collections.emptySet()));
        call.run();
        assertSame(RoleBasedAuthorizationStrategy.class, j.jenkins.getAuthorizationStrategy().getClass(),
                "guard: " + what + " under the plain strategy must leave the plain class");
        effect.run();
    }

    private void installVariant() {
        j.jenkins.setAuthorizationStrategy(
                new BatchControlRoleBasedAuthorizationStrategy(StrategyFixtures.roles(), Collections.emptySet()));
        assertSame(BatchControlRoleBasedAuthorizationStrategy.class, j.jenkins.getAuthorizationStrategy().getClass(),
                "fixture: the Batch Control role strategy must be installed");
    }

    private RoleBasedAuthorizationStrategy installed() {
        return (RoleBasedAuthorizationStrategy) j.jenkins.getAuthorizationStrategy();
    }

    private Item team() {
        return j.jenkins.getItemByFullName("team-a");
    }

    private void rest(String method, String... kv) throws Exception {
        List<NameValuePair> params = new ArrayList<>();
        for (int i = 0; i < kv.length; i += 2) {
            params.add(new NameValuePair(kv[i], kv[i + 1]));
        }
        WebResponse r = ApproverFormFixtures.post(j, "admin", REST + method, params);
        assertTrue(r.getStatusCode() < 400, "fixture: " + method + " must succeed for the administrator, got HTTP "
                + r.getStatusCode() + ": " + excerpt(r.getContentAsString()));
    }

    private void submit(String method, JSONObject json) throws Exception {
        WebResponse r = ApproverFormFixtures.post(j, "admin", CONFIG_PAGE + method,
                List.of(new NameValuePair("json", json.toString())));
        assertTrue(r.getStatusCode() < 400, "fixture: " + method + " must succeed for the administrator, got HTTP "
                + r.getStatusCode() + ": " + excerpt(r.getContentAsString()));
    }

    private String page(String name) throws Exception {
        JenkinsRule.WebClient wc = ApproverFormFixtures.client(j, "admin");
        wc.getOptions().setJavaScriptEnabled(false); // role-strategy's bundles do not run in HtmlUnit
        WebResponse r = wc.getPage(new URL(j.getURL(), CONFIG_PAGE + name)).getWebResponse();
        assertEquals(200, r.getStatusCode(), "the '" + name + "' page must answer 200 to the administrator: "
                + excerpt(r.getContentAsString()));
        return r.getContentAsString();
    }

    private String version() {
        return j.jenkins.getPluginManager().getPlugin(PLUGIN).getVersion();
    }

    private static String cap(String s) {
        return s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
    }

    private static boolean resolvesAsMember(Class<?> type, String member) {
        for (Method m : type.getMethods()) {
            String n = m.getName();
            if (n.equals(member) || (m.getParameterCount() == 0 && (n.equals("get" + cap(member)) || n.equals("is" + cap(member))))) {
                return true;
            }
        }
        for (Field f : type.getFields()) {
            if (f.getName().equals(member) && Modifier.isPublic(f.getModifiers())) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasWebMethod(Class<?> type, String method) {
        for (Method m : type.getMethods()) {
            if (m.getName().equals("do" + cap(method))) {
                return true;
            }
        }
        return false;
    }

    /** What role-strategy's views call on the strategy descriptor: name → first file that does it. */
    private static final class Scan {
        final Map<String, String> members = new TreeMap<>();
        final Map<String, String> urlMethods = new TreeMap<>();
        int files;
    }

    private Scan scanRoleStrategyViews() throws Exception {
        Scan scan = new Scan();
        // Jelly/Groovy views shipped in role-strategy's jar (the one the test classpath loads).
        URL location = RoleStrategyConfig.class.getProtectionDomain().getCodeSource().getLocation();
        File jarOrDir = Paths.get(location.toURI()).toFile();
        if (jarOrDir.isDirectory()) {
            try (Stream<Path> s = Files.walk(jarOrDir.toPath())) {
                for (Path p : (Iterable<Path>) s.filter(RoleStrategySurfaceTest::isView)::iterator) {
                    scanView(scan, jarOrDir.toPath().relativize(p).toString(), Files.readString(p, StandardCharsets.UTF_8));
                }
            }
        } else {
            try (JarFile jar = new JarFile(jarOrDir)) {
                Enumeration<JarEntry> entries = jar.entries();
                while (entries.hasMoreElements()) {
                    JarEntry e = entries.nextElement();
                    if (!e.isDirectory() && isView(Paths.get(e.getName()))) {
                        try (InputStream in = jar.getInputStream(e)) {
                            scanView(scan, e.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
                        }
                    }
                }
            }
        }
        // The frontend bundle served from the exploded plugin directory.
        PluginWrapper rs = Jenkins.get().getPluginManager().getPlugin(PLUGIN);
        URL base = rs.baseResourceURL;
        if (base != null && "file".equals(base.getProtocol())) {
            Path root = Paths.get(base.toURI());
            if (Files.isDirectory(root)) {
                try (Stream<Path> s = Files.walk(root)) {
                    for (Path p : (Iterable<Path>) s.filter(p -> p.toString().endsWith(".js"))::iterator) {
                        scanScript(scan, root.relativize(p).toString(), Files.readString(p, StandardCharsets.UTF_8));
                    }
                }
            }
        }
        return scan;
    }

    private static boolean isView(Path p) {
        String n = p.toString();
        return n.endsWith(".jelly") || n.endsWith(".groovy");
    }

    private static void scanView(Scan scan, String file, String text) {
        scan.files++;
        Matcher m = DESCRIPTOR_MEMBER.matcher(text);
        while (m.find()) {
            scan.members.putIfAbsent(m.group(1), file);
        }
        m = DESCRIPTOR_URL.matcher(text);
        while (m.find()) {
            scan.urlMethods.putIfAbsent(m.group(1), file);
        }
        TreeSet<String> vars = new TreeSet<>();
        m = DESCRIPTOR_URL_VAR.matcher(text);
        while (m.find()) {
            vars.add(m.group(1));
        }
        for (String var : vars) {
            Matcher u = Pattern.compile("\\$\\{" + Pattern.quote(var) + "\\}/([A-Za-z]\\w*)").matcher(text);
            while (u.find()) {
                scan.urlMethods.putIfAbsent(u.group(1), file);
            }
        }
    }

    private static void scanScript(Scan scan, String file, String text) throws IOException {
        Matcher m = JS_DESCRIPTOR_URL.matcher(text);
        while (m.find()) {
            String method = m.group(1) != null ? m.group(1) : m.group(2);
            scan.urlMethods.putIfAbsent(method, file);
        }
    }
}
