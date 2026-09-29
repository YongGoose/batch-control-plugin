package io.jenkins.plugins.batchcontrol.listener;

import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.model.Failure;
import hudson.model.Item;
import hudson.util.PluginServletFilter;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.ui.SelfGrantRevertedFailure;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.Stapler;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;

/**
 * Tells the saving user when the D-35b guard reverted part of their save (SPEC item 2, D-48,
 * e2e-03 DEF-35).
 *
 * <p>The guard runs in a {@link hudson.model.listeners.SaveableListener} or
 * {@link hudson.model.listeners.ItemListener}, which cannot fail the request: core catches and
 * logs whatever a listener throws. So the guard only {@linkplain #flag marks} the current HTTP
 * request, and this filter, wrapped around every POST, replaces the answer the request would
 * otherwise give (a 2xx or 3xx) with {@link SelfGrantRevertedFailure} (HTTP 403):
 * <ul>
 *   <li>while Stapler is still dispatching, the first redirect, status, body or flush the save's
 *       endpoint produces is turned into the failure page, rendered through Stapler (form
 *       {@code configSubmit} and Apply, {@code createItem}, a copy);</li>
 *   <li>if the endpoint wrote nothing at all (core's {@code config.xml} POST returns without a
 *       body), the failure's plain-text message is written after the chain returns.</li>
 * </ul>
 * An error answer (4xx/5xx) the endpoint chooses itself is left as it is. A request the guard did
 * not mark passes through unchanged: the wrapper only forwards.
 *
 * <p>Chosen over a Stapler-level hook because it covers every save path in one place, including
 * core's {@code config.xml} POST, which answers without going through any response object the
 * plugin could otherwise intercept; and over refusing the save up front because not every path
 * has a veto point before it persists (D-48).
 */
@Restricted(NoExternalUse.class)
public final class SelfGrantRevertFilter implements Filter {

    private static final Logger LOGGER = Logger.getLogger(SelfGrantRevertFilter.class.getName());

    /** Request attribute holding the {@link SelfGrantRevertedFailure} of the first reverted item. */
    static final String ATTRIBUTE = SelfGrantRevertFilter.class.getName() + ".failure";

    /** Registers the filter once the plugin has started. */
    @Initializer(after = InitMilestone.PLUGINS_STARTED)
    public static void register() throws ServletException {
        PluginServletFilter.addFilter(new SelfGrantRevertFilter());
    }

    /**
     * Marks the current HTTP request, if any, so its answer tells the user that the guard reverted
     * the authorization part of the save of {@code item}. Without a current request (scripts,
     * background work, the CLI over WebSocket or remoting) it does nothing. The CLI in {@code -http}
     * mode runs inside its {@code /cli} POST, so that request is marked, but its response is
     * already committed before the command runs, so the answer is left as it is (S-19-04). The
     * GRANT_VIOLATION record is written either way.
     */
    static void flag(Item item) {
        StaplerRequest2 req = Stapler.getCurrentRequest2();
        if (req != null && req.getAttribute(ATTRIBUTE) == null) {
            req.setAttribute(ATTRIBUTE, new SelfGrantRevertedFailure(item));
        }
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        // S-19-03: while change control is off the guard never acts, so nothing is wrapped at all.
        if (!(request instanceof HttpServletRequest) || !(response instanceof HttpServletResponse)
                || !"POST".equals(((HttpServletRequest) request).getMethod())
                || !changeControlOn()) {
            chain.doFilter(request, response);
            return;
        }
        HttpServletRequest req = (HttpServletRequest) request;
        GuardedResponse guarded = new GuardedResponse(req, (HttpServletResponse) response);
        chain.doFilter(request, guarded);
        guarded.finish();
    }

    /** Whether change control is on; {@code false} while the configuration is not available yet. */
    private static boolean changeControlOn() {
        try {
            return BatchControlGlobalConfiguration.get().isChangeControlEnabled();
        } catch (IllegalStateException e) {
            return false;
        }
    }

    /** Forwards everything until the request is marked; then answers the failure once. */
    private static final class GuardedResponse extends HttpServletResponseWrapper {

        private enum State { WATCHING, RENDERING, REPLACED, PASSED }

        private final HttpServletRequest req;
        private State state = State.WATCHING;

        GuardedResponse(HttpServletRequest req, HttpServletResponse rsp) {
            super(rsp);
            this.req = req;
        }

        /**
         * Whether the caller's own output must be swallowed: {@code true} once the failure has
         * replaced the answer (rendering it now if the request is marked and nothing was sent).
         */
        private boolean divert() throws IOException {
            switch (state) {
                case REPLACED:
                    return true;
                case WATCHING:
                    Object failure = req.getAttribute(ATTRIBUTE);
                    if (failure instanceof Failure && !super.isCommitted()) {
                        replace((Failure) failure, true);
                        return true;
                    }
                    return false;
                default:
                    return false;
            }
        }

        private void replace(Failure failure, boolean viaStapler) throws IOException {
            state = State.RENDERING;
            try {
                super.reset();
                StaplerRequest2 sreq = viaStapler ? Stapler.getCurrentRequest2() : null;
                StaplerResponse2 srsp = viaStapler ? Stapler.getCurrentResponse2() : null;
                if (sreq != null && srsp != null) {
                    failure.generateResponse(sreq, srsp, null);
                } else {
                    writePlain(failure);
                }
                super.flushBuffer();
            } catch (ServletException | IOException | RuntimeException e) {
                LOGGER.log(Level.WARNING, "Could not render the self-grant notice; answering in plain text", e);
                // S-19-05 (b): the save and the revert are done; a failing fallback must not turn
                // the answer into a 500, so it is only logged.
                try {
                    if (!super.isCommitted()) {
                        super.reset();
                        writePlain(failure);
                        super.flushBuffer();
                    }
                } catch (IOException | RuntimeException fallback) {
                    LOGGER.log(Level.WARNING, "Could not write the plain-text self-grant notice", fallback);
                }
            } finally {
                state = State.REPLACED;
            }
        }

        private void writePlain(Failure failure) throws IOException {
            super.setStatus(HttpServletResponse.SC_FORBIDDEN);
            super.setContentType("text/plain;charset=UTF-8");
            super.setHeader("X-Content-Type-Options", "nosniff");
            PrintWriter writer = super.getWriter();
            writer.println(failure.getMessage());
            writer.flush();
        }

        /** After the chain: a marked request that produced no answer gets the plain-text failure. */
        void finish() throws IOException {
            if (req.isAsyncStarted()) {
                return; // S-19-05 (c): the answer is produced later, on another thread
            }
            if (state == State.WATCHING) {
                Object failure = req.getAttribute(ATTRIBUTE);
                if (failure instanceof Failure && !super.isCommitted()) {
                    replace((Failure) failure, false);
                }
            }
        }

        @Override
        public void sendRedirect(String location) throws IOException {
            if (!divert()) {
                super.sendRedirect(location);
            }
        }

        @Override
        public void setStatus(int sc) {
            if (state == State.WATCHING && sc >= 400) {
                state = State.PASSED; // the endpoint's own error stays
            }
            try {
                if (divert()) {
                    return;
                }
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Could not render the self-grant notice", e);
                return;
            }
            super.setStatus(sc);
        }

        @Override
        @SuppressWarnings("deprecation")
        public void setStatus(int sc, String message) {
            setStatus(sc);
        }

        @Override
        public void sendError(int sc) throws IOException {
            sendError(sc, null);
        }

        @Override
        public void sendError(int sc, String message) throws IOException {
            if (state == State.WATCHING) {
                state = State.PASSED;
            }
            if (state == State.REPLACED) {
                return;
            }
            if (message == null) {
                super.sendError(sc);
            } else {
                super.sendError(sc, message);
            }
        }

        @Override
        public PrintWriter getWriter() throws IOException {
            if (divert()) {
                return new PrintWriter(OutputStream.nullOutputStream(), false, StandardCharsets.UTF_8);
            }
            PrintWriter raw = super.getWriter();
            // While rendering the notice the raw writer is used; otherwise the endpoint gets a gated
            // one, so output it writes through a reference taken before the save never lands after
            // the notice (S-19-05 (a)).
            return state == State.RENDERING ? raw : new PrintWriter(new GatedWriter(raw), false);
        }

        @Override
        public ServletOutputStream getOutputStream() throws IOException {
            if (divert()) {
                return new DiscardingStream();
            }
            ServletOutputStream raw = super.getOutputStream();
            return state == State.RENDERING ? raw : new GatedStream(raw);
        }

        /** A writer that diverts to the notice on first use once the request is marked. */
        private final class GatedWriter extends Writer {
            private final Writer raw;

            GatedWriter(Writer raw) {
                this.raw = raw;
            }

            @Override
            public void write(char[] cbuf, int off, int len) throws IOException {
                if (!divert()) {
                    raw.write(cbuf, off, len);
                }
            }

            @Override
            public void flush() throws IOException {
                if (!divert()) {
                    raw.flush();
                }
            }

            @Override
            public void close() throws IOException {
                if (!divert()) {
                    raw.close();
                }
            }
        }

        /** A stream that diverts to the notice on first use once the request is marked. */
        private final class GatedStream extends ServletOutputStream {
            private final ServletOutputStream raw;

            GatedStream(ServletOutputStream raw) {
                this.raw = raw;
            }

            @Override
            public boolean isReady() {
                return raw.isReady();
            }

            @Override
            public void setWriteListener(WriteListener writeListener) {
                raw.setWriteListener(writeListener);
            }

            @Override
            public void write(int b) throws IOException {
                if (!divert()) {
                    raw.write(b);
                }
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                if (!divert()) {
                    raw.write(b, off, len);
                }
            }

            @Override
            public void flush() throws IOException {
                if (!divert()) {
                    raw.flush();
                }
            }

            @Override
            public void close() throws IOException {
                if (!divert()) {
                    raw.close();
                }
            }
        }

        @Override
        public void flushBuffer() throws IOException {
            if (!divert()) {
                super.flushBuffer();
            }
        }
    }

    /** Swallows what the endpoint writes after the failure replaced its answer. */
    private static final class DiscardingStream extends ServletOutputStream {
        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setWriteListener(WriteListener writeListener) {
            // blocking I/O only
        }

        @Override
        public void write(int b) {
            // discarded
        }

        @Override
        public void write(byte[] b, int off, int len) {
            // discarded
        }
    }
}
