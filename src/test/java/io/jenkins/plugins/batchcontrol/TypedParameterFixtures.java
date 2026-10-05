package io.jenkins.plugins.batchcontrol;

import hudson.EnvVars;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.Job;
import hudson.util.Secret;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.commons.fileupload2.core.DiskFileItem;
import org.apache.commons.fileupload2.core.DiskFileItemFactory;
import org.htmlunit.Page;
import org.htmlunit.html.HtmlCheckBoxInput;
import org.htmlunit.html.HtmlElement;
import org.htmlunit.html.HtmlFileInput;
import org.htmlunit.html.HtmlForm;
import org.htmlunit.html.HtmlFormUtil;
import org.htmlunit.html.HtmlInput;
import org.htmlunit.html.HtmlPage;
import org.htmlunit.html.HtmlSelect;
import org.htmlunit.html.HtmlTextArea;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestBuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shared steps of the D-72 rows (run requests keep the submitted, typed parameter values; matrix
 * note 260). Everything here drives public surfaces only: the job's Request Run form as a browser
 * submits it (multipart, with core's structured {@code json} field built by Jenkins' own form
 * script), the documented temporary directories of the two file parameter implementations, and
 * the Batch Control store directory of ARCHITECTURE section 5.
 *
 * <p>Temporary directories (SPEC item 5, D-72): core's {@code file} parameter keeps its upload in
 * {@code $JENKINS_HOME/fileParameterValueFiles/}, the file-parameters plugin's
 * {@code stashedFile} in {@code $JENKINS_HOME/stashedFileParameterValueFiles/}, and
 * {@code base64File} inside the value itself.
 *
 * <p>Written from docs/SPEC.md item 5 and 11, docs/DECISIONS.md D-72, docs/ARCHITECTURE.md
 * section 5 and the frozen D-72 contract only (no src/main knowledge).
 */
final class TypedParameterFixtures {

    static final String CORE_TMP_DIR = "fileParameterValueFiles";
    static final String STASH_TMP_DIR = "stashedFileParameterValueFiles";

    /** The mask SPEC item 5 names for a sensitive value. */
    static final String MASK = "********";

    private TypedParameterFixtures() {
        // utility class
    }

    /** The masked display form SPEC item 5 gives a file value. */
    static String fileDisplay(String originalName) {
        return "[file] " + originalName;
    }

    // ------------------------------------------------------------------ file content

    /**
     * Exactly {@code size} deterministic bytes: an ASCII marker first (so a leak of the content into
     * a page or a file is found by a plain substring search), then bytes covering every value
     * 0..255 (so a text-mode copy that alters line ends or drops NUL bytes cannot pass a byte
     * comparison).
     */
    static byte[] payload(String marker, int size) {
        byte[] head = marker.getBytes(StandardCharsets.US_ASCII);
        assertTrue(size >= head.length, "fixture: the payload must hold its marker");
        byte[] out = new byte[size];
        System.arraycopy(head, 0, out, 0, head.length);
        for (int i = head.length; i < size; i++) {
            out[i] = (byte) ((i - head.length) % 256);
        }
        return out;
    }

    /** A file named exactly {@code fileName} (the name a browser sends) holding {@code bytes}. */
    static File uploadFile(String fileName, byte[] bytes) throws IOException {
        Path dir = Files.createTempDirectory("d72-upload");
        Path file = dir.resolve(fileName);
        Files.write(file, bytes);
        file.toFile().deleteOnExit();
        dir.toFile().deleteOnExit();
        return file.toFile();
    }

    /** A commons-fileupload2 item as Jenkins builds one from an upload, holding {@code bytes}. */
    static DiskFileItem fileItem(String fileName, byte[] bytes) throws IOException {
        DiskFileItem item = DiskFileItemFactory.builder().get().fileItemBuilder()
                .setFieldName("file")
                .setFileName(fileName)
                .setContentType("application/octet-stream")
                .setFormField(false)
                .get();
        try (OutputStream out = item.getOutputStream()) {
            out.write(bytes);
        }
        return item;
    }

    // ------------------------------------------------------------------ temporary directories

    /** Every regular file currently under the two documented temporary directories (absolute paths). */
    static Set<Path> tempFiles(JenkinsRule j) throws IOException {
        Set<Path> out = new TreeSet<>();
        for (String dir : new String[] {CORE_TMP_DIR, STASH_TMP_DIR}) {
            Path root = j.jenkins.getRootDir().toPath().resolve(dir);
            if (Files.isDirectory(root)) {
                try (Stream<Path> paths = Files.walk(root)) {
                    paths.filter(Files::isRegularFile).forEach(out::add);
                }
            }
        }
        return out;
    }

    /** The files of {@code now} that were not in {@code before}. */
    static Set<Path> added(Set<Path> before, Set<Path> now) {
        Set<Path> out = new TreeSet<>(now);
        out.removeAll(before);
        return out;
    }

    /** The members of {@code files} that lie under {@code $JENKINS_HOME/<dir>}. */
    static Set<Path> under(JenkinsRule j, Set<Path> files, String dir) {
        Path root = j.jenkins.getRootDir().toPath().resolve(dir);
        return files.stream().filter(p -> p.startsWith(root)).collect(Collectors.toCollection(TreeSet::new));
    }

    /** The members of {@code files} that still exist. */
    static Set<Path> stillThere(Set<Path> files) {
        return files.stream().filter(Files::exists).collect(Collectors.toCollection(TreeSet::new));
    }

    // ------------------------------------------------------------------ the Request Run form

    static JenkinsRule.WebClient browser(JenkinsRule j, String userId) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false);
        wc.login(userId);
        return wc;
    }

    /** The Request Run form of {@code job} as {@code wc}'s user sees it on {@code job/<name>/batch-control/}. */
    static HtmlForm requestRunForm(JenkinsRule j, JenkinsRule.WebClient wc, Job<?, ?> job) throws Exception {
        Page page = wc.getPage(new URL(j.getURL(), job.getUrl() + "batch-control/"));
        assertEquals(200, page.getWebResponse().getStatusCode(), "fixture: the Request Run page of "
                + job.getFullName() + " must open");
        assertTrue(page instanceof HtmlPage, "fixture: the Request Run page must be HTML");
        HtmlPage html = (HtmlPage) page;
        HtmlForm form = UsabilityFixtures.formsEndingWith(html, job.getUrl() + "batch-control/submit").stream()
                .findFirst().orElse(null);
        assertNotNull(form, "fixture: the page must carry the Request Run form; forms: "
                + UsabilityFixtures.formActions(html));
        return form;
    }

    /** Core's {@code <div name="parameter">} block of the job parameter {@code name}. */
    static HtmlElement parameterBlock(HtmlForm form, String name) {
        List<HtmlElement> blocks = form.getByXPath(
                ".//*[@name='parameter'][.//input[@name='name' and @value='" + name + "']]");
        assertEquals(1, blocks.size(), "fixture: exactly one parameter block for " + name + ": "
                + UsabilityFixtures.excerpt(form.asXml()));
        return blocks.get(0);
    }

    /** Chooses {@code file} in the file control of the job parameter {@code name}. */
    static void setFile(HtmlForm form, String name, File file) {
        List<HtmlFileInput> inputs = parameterBlock(form, name).getByXPath(".//input[@type='file']");
        assertEquals(1, inputs.size(), "fixture: the file parameter " + name + " must offer one file control: "
                + UsabilityFixtures.excerpt(parameterBlock(form, name).asXml()));
        inputs.get(0).setFiles(file);
    }

    /** Sets the value control (text, textarea, checkbox, select or password) of the job parameter {@code name}. */
    static void setValue(HtmlForm form, String name, String value) {
        HtmlElement control = valueControl(form, name);
        if (control instanceof HtmlCheckBoxInput) {
            ((HtmlCheckBoxInput) control).setChecked(Boolean.parseBoolean(value));
        } else if (control instanceof HtmlSelect) {
            ((HtmlSelect) control).setSelectedAttribute(value, true);
        } else if (control instanceof HtmlTextArea) {
            ((HtmlTextArea) control).setText(value);
        } else {
            ((HtmlInput) control).setValue(value);
        }
    }

    /** The current value of the job parameter {@code name}'s control, as the form would submit it. */
    static String valueOf(HtmlForm form, String name) {
        HtmlElement control = valueControl(form, name);
        if (control instanceof HtmlCheckBoxInput) {
            return String.valueOf(((HtmlCheckBoxInput) control).isChecked());
        }
        if (control instanceof HtmlSelect) {
            List<org.htmlunit.html.HtmlOption> selected = ((HtmlSelect) control).getSelectedOptions();
            return selected.isEmpty() ? "" : selected.get(0).getValueAttribute();
        }
        if (control instanceof HtmlTextArea) {
            return ((HtmlTextArea) control).getText();
        }
        return ((HtmlInput) control).getValue();
    }

    private static HtmlElement valueControl(HtmlForm form, String name) {
        HtmlElement block = parameterBlock(form, name);
        List<HtmlElement> controls = new ArrayList<>();
        for (HtmlElement e : block.getHtmlElementDescendants()) {
            if ("value".equals(e.getAttribute("name")) && !"hidden".equalsIgnoreCase(e.getAttribute("type"))) {
                controls.add(e);
            }
        }
        if (controls.isEmpty()) {
            for (HtmlElement e : block.getHtmlElementDescendants()) {
                if ("value".equals(e.getAttribute("name"))) {
                    controls.add(e);
                }
            }
        }
        assertFalse(controls.isEmpty(), "fixture: a value control for " + name + ": "
                + UsabilityFixtures.excerpt(block.asXml()));
        return controls.get(0);
    }

    /** Fills in reason and approver and submits as a browser does; returns the answer (redirects followed). */
    static Page submit(JenkinsRule.WebClient wc, HtmlForm form, String reason, String approver) throws Exception {
        UsabilityFixtures.setField(form, "reason", reason);
        UsabilityFixtures.selectApprover(form, approver);
        Page answer = HtmlFormUtil.submit(form);
        wc.waitForBackgroundJavaScript(5000);
        return answer;
    }

    /**
     * Submits {@code job}'s Request Run form as {@code userId} with the given simple values and
     * files, and returns the id of the one request it created (asserted).
     */
    static String submitRequest(JenkinsRule j, String userId, Job<?, ?> job, Map<String, String> values,
                                Map<String, File> files) throws Exception {
        Set<String> before = ApproverFormFixtures.runRequestIds();
        JenkinsRule.WebClient wc = browser(j, userId);
        HtmlForm form = requestRunForm(j, wc, job);
        for (Map.Entry<String, String> e : values.entrySet()) {
            setValue(form, e.getKey(), e.getValue());
        }
        for (Map.Entry<String, File> e : files.entrySet()) {
            setFile(form, e.getKey(), e.getValue());
        }
        Page answer = submit(wc, form, "month-end batch with typed parameters", "a1");
        assertTrue(answer.getWebResponse().getStatusCode() < 400, "fixture: the Request Run submission by " + userId
                + " must succeed, got HTTP " + answer.getWebResponse().getStatusCode() + ": "
                + UsabilityFixtures.excerpt(answer.getWebResponse().getContentAsString()));
        Set<String> after = ApproverFormFixtures.runRequestIds();
        after.removeAll(before);
        assertEquals(1, after.size(), "fixture: the submission must have created exactly one run request, got " + after);
        return after.iterator().next();
    }

    // ------------------------------------------------------------------ the store and the pages

    /** {@code $JENKINS_HOME/batch-control} (ARCHITECTURE section 5). */
    static Path storeDir(JenkinsRule j) {
        return j.jenkins.getRootDir().toPath().resolve("batch-control");
    }

    /** Store files (relative to the store) whose bytes contain {@code needle}. */
    static List<String> storeFilesContaining(JenkinsRule j, String needle) throws IOException {
        Path root = storeDir(j);
        assertTrue(Files.isDirectory(root), "fixture: the store must live at " + root);
        List<String> out = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).collect(Collectors.toList())) {
                String content = new String(Files.readAllBytes(path), StandardCharsets.ISO_8859_1);
                if (content.contains(needle)) {
                    out.add(root.relativize(path).toString().replace('\\', '/'));
                }
            }
        }
        return out;
    }

    /** The stored file of run request {@code id}: {@code requests/run/<id>.xml} (ARCHITECTURE section 5). */
    static Path requestFile(JenkinsRule j, String id) {
        Path file = storeDir(j).resolve("requests").resolve("run").resolve(id + ".xml");
        assertTrue(Files.isRegularFile(file), "fixture: the run request must be stored at " + file);
        return file;
    }

    private static final Pattern ENCRYPTED = Pattern.compile("\\{[A-Za-z0-9+/=]{16,}\\}");

    /** True if {@code file} holds a Jenkins-encrypted {@link Secret} whose plaintext is {@code plaintext}. */
    static boolean holdsEncrypted(Path file, String plaintext) throws IOException {
        String xml = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        Matcher m = ENCRYPTED.matcher(xml);
        while (m.find()) {
            Secret secret = Secret.decrypt(m.group());
            if (secret != null && plaintext.equals(secret.getPlainText())) {
                return true;
            }
        }
        return false;
    }

    /** GET as {@code userId}; returns status and body whatever the status. */
    static Page get(JenkinsRule j, String userId, String relative) throws Exception {
        return UsabilityFixtures.get(j, UsabilityFixtures.clientNoJs(j, userId), relative);
    }

    /** GET as {@code userId}, asserting the surface opens (status below 400); returns the body. */
    static String readable(JenkinsRule j, String userId, String relative) throws Exception {
        Page page = get(j, userId, relative);
        int code = page.getWebResponse().getStatusCode();
        assertTrue(code < 400, userId + " must be able to read " + relative + ", got HTTP " + code);
        return page.getWebResponse().getContentAsString();
    }

    /** Asserts that none of {@code forbidden} occurs in {@code content}. */
    static void assertAbsent(String where, String content, List<String> forbidden) {
        for (String needle : forbidden) {
            int at = content.indexOf(needle);
            assertTrue(at < 0, where + " must not contain " + describe(needle) + ", found at offset " + at + ": "
                    + UsabilityFixtures.excerpt(content.substring(Math.max(0, at - 160))));
        }
    }

    private static String describe(String needle) {
        return needle.length() <= 60 ? "'" + needle + "'" : "'" + needle.substring(0, 60) + "...' (" + needle.length() + " chars)";
    }

    /**
     * Server paths that a file value must never be shown as: the two documented temporary
     * directories, {@code $JENKINS_HOME}, and the servlet upload area in the JVM's temporary
     * directory (where an upload lives before a parameter type copies it; a file item's
     * {@code toString()} names it as {@code StoreLocation=...}, which a pre-D-72 build displayed).
     */
    static List<String> serverPaths(JenkinsRule j) {
        List<String> out = new ArrayList<>();
        out.add(CORE_TMP_DIR);
        out.add(STASH_TMP_DIR);
        out.add(j.jenkins.getRootDir().getAbsolutePath());
        out.add("jenkins-stapler-uploads");
        out.add("StoreLocation=");
        String tmp = System.getProperty("java.io.tmpdir", "");
        if (tmp.endsWith(File.separator)) {
            tmp = tmp.substring(0, tmp.length() - 1);
        }
        if (tmp.length() >= 10) { // a short one such as /tmp could match unrelated text
            out.add(tmp);
        }
        return out;
    }

    /** The query of {@code url}, decoded. */
    static Map<String, String> query(URL url) {
        Map<String, String> out = new LinkedHashMap<>();
        if (url.getQuery() == null) {
            return out;
        }
        for (String pair : url.getQuery().split("&")) {
            int eq = pair.indexOf('=');
            String k = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            String v = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            out.put(k, v);
        }
        return out;
    }

    // ------------------------------------------------------------------ the request file on disk (D-72b, note 265)

    /** The names of every file in {@code requests/run/} (an absent directory is empty), temporary files included. */
    static Set<String> requestDirListing(JenkinsRule j) throws IOException {
        Path dir = storeDir(j).resolve("requests").resolve("run");
        Set<String> out = new TreeSet<>();
        if (Files.isDirectory(dir)) {
            try (Stream<Path> paths = Files.list(dir)) {
                paths.forEach(p -> out.add(p.getFileName().toString()));
            }
        }
        return out;
    }

    /** The current text of request {@code id}'s file. */
    static String requestXml(JenkinsRule j, String id) throws IOException {
        return new String(Files.readAllBytes(requestFile(j, id)), StandardCharsets.UTF_8);
    }

    /** Rewrites request {@code id}'s file with {@code edit} (an edit on disk, as a pre-release file or an administrator would leave it). */
    static String editRequestFile(JenkinsRule j, String id, java.util.function.UnaryOperator<String> edit) throws IOException {
        Path file = requestFile(j, id);
        String before = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        String after = edit.apply(before);
        assertFalse(before.equals(after), "fixture: the edit must change the request file");
        Files.write(file, after.getBytes(StandardCharsets.UTF_8));
        return after;
    }

    private static final Pattern TYPED_SECTION = Pattern.compile("(?s)<parameterValues(?:\\s[^>]*)?>(.*?)</parameterValues>");

    /** The element of the typed value named {@code name} inside the file's {@code parameterValues} (ARCHITECTURE 5). */
    static String typedElement(String xml, String name) {
        Matcher section = TYPED_SECTION.matcher(xml);
        assertTrue(section.find(), "fixture: the request file must hold its typed values in parameterValues (D-72): "
                + UsabilityFixtures.excerpt(xml));
        Matcher element = Pattern.compile("(?s)<([A-Za-z_][\\w.$-]*)(?:\\s[^>]*)?>\\s*<name>" + Pattern.quote(name)
                + "</name>.*?</\\1>").matcher(section.group(1));
        assertTrue(element.find(), "fixture: a typed value named " + name + " must be stored: " + UsabilityFixtures.excerpt(section.group(1)));
        String found = element.group();
        assertFalse(element.find(), "fixture: exactly one typed value named " + name + " must be stored before the edit");
        return found;
    }

    /** Adds a second typed value named {@code name} right after the first, holding {@code value} (the display map is untouched). */
    static String duplicateTypedValue(String xml, String name, String value) {
        String element = typedElement(xml, name);
        String copy = element.replaceFirst("(?s)<value>.*?</value>", "<value>" + Matcher.quoteReplacement(value) + "</value>");
        assertFalse(copy.equals(element), "fixture: the typed value " + name + " must hold a <value> to change");
        return xml.replace(element, element + "\n    " + copy);
    }

    /** Renames the typed value {@code from} to {@code to} (the display map still names {@code from}). */
    static String renameTypedValue(String xml, String from, String to) {
        String element = typedElement(xml, from);
        return xml.replace(element, element.replace("<name>" + from + "</name>", "<name>" + to + "</name>"));
    }

    /** Stores the typed value {@code name} under a class that cannot be loaded (its plugin removed, its class renamed). */
    static String retypeTypedValue(String xml, String name, String className) {
        String element = typedElement(xml, name);
        Matcher open = Pattern.compile("^<([A-Za-z_][\\w.$-]*)").matcher(element);
        assertTrue(open.find(), "fixture: the typed value must start with its element");
        String tag = open.group(1);
        String renamed = "<" + className + element.substring(open.end(), element.length() - ("</" + tag + ">").length())
                + "</" + className + ">";
        return xml.replace(element, renamed);
    }

    /** The file in the shape written before D-72: the display map but no typed values. */
    static String withoutTypedValues(String xml) {
        String out = xml.replaceAll("(?s)\\s*<parameterValues(?:\\s[^>]*)?>.*?</parameterValues>", "")
                .replaceAll("\\s*<parameterValues(?:\\s[^>]*)?/>", "");
        assertFalse(out.contains("<parameterValues"), "fixture: no typed values may remain");
        return out;
    }

    /** Appends {@code mark} to the stored reason, so a row can prove that the plugin reads the edited file. */
    static String markReason(String xml, String mark) {
        assertTrue(xml.contains("</reason>"), "fixture: the request file must hold its reason");
        return xml.replace("</reason>", mark + "</reason>");
    }

    /**
     * Asserts that request {@code id}'s file no longer holds its typed values (D-72b (5)): none of
     * {@code forbidden} (Base64 text, temporary file paths) and no encrypted token that decrypts to
     * {@code secret}.
     */
    static void assertTypedValuesGone(JenkinsRule j, String what, String id, List<String> forbidden, String secret) throws IOException {
        String xml = requestXml(j, id);
        assertAbsent(what + ": the request file", xml, forbidden);
        assertFalse(holdsEncrypted(requestFile(j, id), secret), what + ": the request file must no longer hold the secret,"
                + " not even encrypted (D-72b (5))");
    }

    /** Asserts that request {@code id}'s file still holds its typed values: each of {@code present} and the encrypted secret. */
    static void assertTypedValuesKept(JenkinsRule j, String what, String id, List<String> present, String secret) throws IOException {
        String xml = requestXml(j, id);
        for (String needle : present) {
            assertTrue(xml.contains(needle), what + ": the request file must still hold " + describe(needle));
        }
        assertTrue(holdsEncrypted(requestFile(j, id), secret), what + ": the request file must still hold the encrypted secret");
    }

    /** The bytes of {@code file}, which must exist. */
    static byte[] bytes(FilePath file) throws Exception {
        assertTrue(file.exists(), "the build must have produced " + file.getRemote());
        try (java.io.InputStream in = file.read()) {
            return in.readAllBytes();
        }
    }

    // ------------------------------------------------------------------ a build step that observes values

    /**
     * Records the named environment variables of each build into {@link #SEEN} (keyed
     * {@code <job>#<number>:<VAR>}) without printing them, and fails build #1 when asked, so an
     * incident opens on the first run and a rerun can succeed. A static nested class with no
     * captured state: the job's saved configuration (and so the Batch Control config snapshot and
     * diff) never holds any value this step observed.
     */
    public static final class CaptureEnv extends TestBuilder {
        static final Map<String, String> SEEN = new ConcurrentHashMap<>();

        // a plain array: the job configuration is saved with XStream, which handles it everywhere
        private final String[] names;
        private final boolean failFirst;

        public CaptureEnv(boolean failFirst, String... names) {
            this.names = names.clone();
            this.failFirst = failFirst;
        }

        @Override
        public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener)
                throws InterruptedException, IOException {
            EnvVars env = build.getEnvironment(listener);
            for (String name : names) {
                String value = env.get(name);
                SEEN.put(key(build.getParent().getFullName(), build.getNumber(), name), value == null ? "<unset>" : value);
            }
            return !(failFirst && build.getNumber() == 1);
        }

        static String key(String job, int number, String name) {
            return job + "#" + number + ":" + name;
        }

        static String seen(String job, int number, String name) {
            return SEEN.get(key(job, number, name));
        }
    }

    /**
     * Copies the approved run request's file (found through the build's {@code ApprovedCause}) into
     * {@link #SNAPSHOTS} while the build runs, keyed {@code <job>#<number>}, so a row can see what
     * the file holds once the approved run has started (D-72b (5)). Test memory only; nothing is
     * printed. A build without an {@code ApprovedCause} records nothing.
     */
    public static final class RequestFileSnapshot extends TestBuilder {
        static final Map<String, String> SNAPSHOTS = new ConcurrentHashMap<>();

        @Override
        public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) throws IOException {
            io.jenkins.plugins.batchcontrol.queue.ApprovedCause cause =
                    build.getCause(io.jenkins.plugins.batchcontrol.queue.ApprovedCause.class);
            if (cause != null) {
                Path file = jenkins.model.Jenkins.get().getRootDir().toPath().resolve("batch-control").resolve("requests")
                        .resolve("run").resolve(cause.getRequestId() + ".xml");
                SNAPSHOTS.put(build.getParent().getFullName() + "#" + build.getNumber(),
                        Files.exists(file) ? new String(Files.readAllBytes(file), StandardCharsets.UTF_8) : "<missing>");
            }
            return true;
        }
    }
}
