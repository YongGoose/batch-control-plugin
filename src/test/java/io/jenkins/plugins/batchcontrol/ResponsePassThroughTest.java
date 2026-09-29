package io.jenkins.plugins.batchcontrol;

import hudson.model.UnprotectedRootAction;
import hudson.security.csrf.CrumbExclusion;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.ServletResponseWrapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.net.URL;
import java.util.concurrent.atomic.AtomicReference;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;
import org.kohsuke.stapler.verb.POST;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-48 ("nothing changes for saves the guard does not touch") and CLAUDE.md ("a new feature does
 * not change existing Jenkins behaviour"), security-20 S-20-02: while change control is on, an
 * unflagged POST endpoint that writes through {@code rsp.getWriter()} gets a writer that behaves
 * like the container's. Matrix row T-02-50 (note 157).
 *
 * <p>The underlying writer is put in error by a test {@link CrumbExclusion} for the probe's path:
 * core runs the CSRF filter, and with it the exclusion, before the plugin filters, so the wrapper
 * installed there sits below any plugin response wrapper. The row asserts that premise from the
 * wrapper chain the endpoint sees. A client disconnect is not used: when writes start failing
 * after a closed socket depends on socket buffer sizes and timing, so it is not reliable in
 * JenkinsRule; the erroring writer is the deterministic form of the same condition.
 *
 * <p>Written from docs/SPEC.md, docs/DECISIONS.md D-48, docs/reports/security-20.md and
 * docs/TEST-MATRIX.md only (no src/main knowledge).
 */
@WithJenkins
public class ResponsePassThroughTest {

    static final AtomicReference<String> SEEN = new AtomicReference<>();
    private static final int LARGE = 3 * 1024 * 1024 + 17;

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) {
        this.j = rule;
        SEEN.set(null);
    }

    /**
     * T-02-50 (S-20-02): (1) with change control on, a large body written as one String arrives
     * complete and unchanged; (2) with change control off and then on, the probe's
     * {@code getWriter().checkError()} is true when the underlying writer is in error — on as off.
     */
    @Test
    public void t_02_50_postWriterKeepsCheckErrorAndBodyWhileChangeControlIsOn() throws Exception {
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setChangeControlEnabled(true);
        cfg.save();
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false);
        Page page = wc.getPage(new WebRequest(new URL(j.getURL(), "s20-probe/large"), HttpMethod.POST));
        assertEquals(200, page.getWebResponse().getStatusCode());
        String body = page.getWebResponse().getContentAsString();
        assertEquals(LARGE, body.length(), "the large body must arrive complete");
        assertEquals(largeBody(), body, "the large body must arrive unchanged");

        cfg.setChangeControlEnabled(false);
        cfg.save();
        String off = probeErrored();
        assertTrue(off.contains(ErroringResponse.class.getName()), "premise: the endpoint must see the erroring"
                + " response: " + off);
        assertTrue(off.startsWith("checkError=true"), "premise: with change control off checkError() reports the"
                + " underlying error: " + off);

        cfg.setChangeControlEnabled(true);
        cfg.save();
        String on = probeErrored();
        int plugin = on.indexOf("io.jenkins.plugins.batchcontrol.");
        int erroring = on.indexOf(ErroringResponse.class.getName());
        assertTrue(plugin >= 0 && erroring > plugin, "premise: with change control on the plugin's wrapper must sit"
                + " above the erroring response, or this row does not observe it: " + on);
        assertTrue(on.startsWith("checkError=true"), "with change control on checkError() must still report the"
                + " underlying writer's error, as it does with change control off: " + on);
    }

    private String probeErrored() throws Exception {
        SEEN.set(null);
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false);
        wc.getPage(new WebRequest(new URL(j.getURL(), "s20-probe/errored"), HttpMethod.POST));
        String seen = SEEN.get();
        assertTrue(seen != null, "fixture: the probe endpoint must have run");
        return seen;
    }

    static String largeBody() {
        StringBuilder sb = new StringBuilder(LARGE);
        for (int i = 0; i < LARGE; i++) {
            sb.append((char) ('a' + (i * 7 + i / 1024) % 26));
        }
        return sb.toString();
    }

    /** Test-only endpoints: {@code errored} records checkError() and the wrapper chain; {@code large} writes one big String. */
    @TestExtension("t_02_50_postWriterKeepsCheckErrorAndBodyWhileChangeControlIsOn")
    public static class Probe implements UnprotectedRootAction {
        @Override
        public String getIconFileName() {
            return null;
        }

        @Override
        public String getDisplayName() {
            return null;
        }

        @Override
        public String getUrlName() {
            return "s20-probe";
        }

        @POST
        public void doErrored(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException {
            PrintWriter w = rsp.getWriter();
            w.print("x");
            boolean error = w.checkError();
            StringBuilder chain = new StringBuilder();
            ServletResponse r = rsp;
            while (r != null) {
                chain.append(' ').append(r.getClass().getName());
                r = r instanceof ServletResponseWrapper ? ((ServletResponseWrapper) r).getResponse() : null;
            }
            SEEN.set("checkError=" + error + chain);
        }

        @POST
        public void doLarge(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException {
            rsp.setContentType("text/plain;charset=UTF-8");
            PrintWriter w = rsp.getWriter();
            w.write(largeBody());
            w.flush();
        }
    }

    /** Lets the probe's POSTs through without a crumb and, for {@code errored}, puts the writer in error. */
    @TestExtension("t_02_50_postWriterKeepsCheckErrorAndBodyWhileChangeControlIsOn")
    public static class ProbeCrumbExclusion extends CrumbExclusion {
        @Override
        public boolean process(HttpServletRequest req, HttpServletResponse rsp, FilterChain chain)
                throws IOException, ServletException {
            String path = req.getPathInfo();
            if (path == null || !path.startsWith("/s20-probe/")) {
                return false;
            }
            chain.doFilter(req, path.startsWith("/s20-probe/errored") ? new ErroringResponse(rsp) : rsp);
            return true;
        }
    }

    /** A response whose writer is already in error, as the container's is after the client went away. */
    public static class ErroringResponse extends HttpServletResponseWrapper {
        ErroringResponse(HttpServletResponse rsp) {
            super(rsp);
        }

        @Override
        public PrintWriter getWriter() {
            return new PrintWriter(Writer.nullWriter()) {
                {
                    setError();
                }
            };
        }
    }
}
