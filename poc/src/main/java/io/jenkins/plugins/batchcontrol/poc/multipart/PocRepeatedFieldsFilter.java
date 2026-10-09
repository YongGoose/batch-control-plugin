package io.jenkins.plugins.batchcontrol.poc.multipart;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.util.PluginServletFilter;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.SequenceInputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.apache.commons.fileupload2.core.DiskFileItem;
import org.apache.commons.fileupload2.core.DiskFileItemFactory;
import org.apache.commons.fileupload2.core.FileItemInput;
import org.apache.commons.fileupload2.core.FileItemInputIterator;
import org.apache.commons.fileupload2.core.ParameterParser;
import org.apache.commons.fileupload2.jakarta.servlet5.JakartaServletFileUpload;
import org.kohsuke.stapler.Stapler;

/**
 * PoC-6 (D-37, LIMITATIONS 56): a servlet filter in front of Stapler that collects every value of
 * a repeated plain multipart field, which Stapler's {@code RequestImpl} collapses to the last one.
 *
 * <p>Scope: POST, {@code Content-Type} starting with {@code multipart/} (the same predicate as
 * Stapler's {@code RequestImpl#isMultipart}), and a path ending in {@code /poc-multipart/submit}.
 * Anything else goes down the chain untouched.
 *
 * <p>In scope: a body with a declared length over {@link #maxBytes} passes through unread (the
 * action refuses it with 413). Otherwise at most {@code maxBytes + 1} bytes are copied into memory
 * up to {@link #memoryThreshold}, beyond that into an owner-only temp file. A streamed (chunked)
 * body that turns out longer than the cap is handed on as "copied prefix + rest of the socket",
 * unparsed. A body within the cap is parsed with the commons-fileupload2 streaming iterator (the
 * library and version Stapler uses), only the {@link #FIELDS} plain parts are kept, and they are
 * exposed as the request attribute {@link #ATTRIBUTE}. Stapler then reads a replay of the same
 * bytes. A parse error leaves the attribute unset and replays the bytes, so Stapler sees exactly
 * what it would have seen. The temp file is deleted when the chain returns.
 */
@SuppressFBWarnings(value = {"PA_PUBLIC_PRIMITIVE_ATTRIBUTE", "ST_WRITE_TO_STATIC_FROM_INSTANCE_METHOD"},
        justification = "PoC switches and test instrumentation")
public final class PocRepeatedFieldsFilter implements Filter {

    private static final Logger LOGGER = Logger.getLogger(PocRepeatedFieldsFilter.class.getName());

    /** Request attribute: {@code Map<String, List<String>>}, every value of each {@link #FIELDS} part, in order. */
    public static final String ATTRIBUTE = PocRepeatedFieldsFilter.class.getName() + ".fields";

    /** Only these plain fields are collected. */
    static final Set<String> FIELDS = Set.of("approvers", "approver");

    /** Same bound as Stapler's default {@code FILEUPLOAD_MAX_FILES}. */
    static final int MAX_PARTS = 1000;

    /** Bound on one collected value. */
    static final int MAX_VALUE_BYTES = 4096;

    public static final PocRepeatedFieldsFilter INSTANCE = new PocRepeatedFieldsFilter();

    // PoC switches, set by the tests.
    public static volatile boolean enabled = true;
    /** Same default as the real cap (D-72, 100 MiB). */
    public static volatile long maxBytes = 104_857_600L;
    public static volatile int memoryThreshold = 256 * 1024;
    /** Alternative exposure: also override {@code getParameterValues} for {@link #FIELDS} (shown to be wrong). */
    public static volatile boolean overrideParameterValues = false;

    public static volatile Probe LAST;

    /** Number of temp files the filter has created (PoC instrumentation). */
    public static final java.util.concurrent.atomic.AtomicInteger TEMP_FILES_CREATED = new java.util.concurrent.atomic.AtomicInteger();

    @Initializer(after = InitMilestone.PLUGINS_STARTED)
    public static void register() throws ServletException {
        PluginServletFilter.addFilter(INSTANCE);
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (!enabled || !(request instanceof HttpServletRequest req) || !inScope(req)) {
            chain.doFilter(request, response);
            return;
        }
        Probe probe = new Probe();
        probe.seq = PocMultipartAction.SEQ.incrementAndGet();
        probe.staplerRequestAlreadyCurrent = Stapler.getCurrentRequest2() != null;
        probe.authentication = Jenkins.getAuthentication2().getName();
        probe.contentLength = req.getContentLengthLong();
        LAST = probe;

        long cap = maxBytes;
        if (probe.contentLength > cap) {
            // Declared over the cap: not a byte read here. The endpoint refuses it (413).
            probe.outcome = Outcome.PASSED_OVER_CAP_DECLARED;
            chain.doFilter(request, response);
            return;
        }
        InputStream raw = req.getInputStream();
        Body copied;
        try {
            copied = Body.copy(raw, cap + 1, memoryThreshold);
        } catch (IOException e) {
            // Client went away mid-upload: Body#copy already deleted its temp file.
            probe.outcome = Outcome.COPY_FAILED;
            throw e;
        }
        try (Body body = copied) {
            probe.bytesBuffered = body.size;
            probe.inMemory = body.file == null;
            probe.tempFile = body.file;
            probe.tempFilePermissions = body.permissions();
            if (body.size > cap) {
                // Streamed body over the cap: hand on the copied prefix plus the unread rest, unparsed.
                probe.outcome = Outcome.PASSED_OVER_CAP_STREAMED;
                chain.doFilter(new Replay(req, new SequenceInputStream(body.open(), raw), null), response);
                return;
            }
            Map<String, List<String>> fields;
            try {
                fields = parse(new Replay(req, body.open(), null), cap);
                probe.outcome = Outcome.PARSED;
            } catch (IOException | RuntimeException e) {
                // Malformed or over a bound: no attribute, Stapler gets the same bytes and decides.
                LOGGER.log(Level.FINE, "PoC-6: multipart body not parsed", e);
                probe.outcome = Outcome.NOT_PARSED;
                probe.parseError = e.toString();
                fields = null;
            }
            if (fields != null) {
                req.setAttribute(ATTRIBUTE, fields);
            }
            chain.doFilter(new Replay(req, body.open(), overrideParameterValues ? fields : null), response);
        } finally {
            if (probe.tempFile != null) {
                probe.tempFileDeletedAfterChain = !Files.exists(probe.tempFile);
            }
        }
    }

    static boolean inScope(HttpServletRequest req) {
        if (!"POST".equals(req.getMethod())) {
            return false;
        }
        String type = req.getContentType();
        if (type == null || !type.startsWith("multipart/")) {
            return false;
        }
        String path = req.getRequestURI().substring(req.getContextPath().length());
        return path.contains("/job/")
                && (path.endsWith("/" + PocMultipartAction.URL_NAME + "/submit")
                        || path.endsWith("/" + PocMultipartAction.URL_NAME + "/submit/"));
    }

    static Map<String, List<String>> parse(HttpServletRequest replay, long cap) throws IOException {
        JakartaServletFileUpload<DiskFileItem, DiskFileItemFactory> upload = new JakartaServletFileUpload<>();
        upload.setMaxSize(cap);
        // As Stapler decodes the json part (RequestImpl#getSubmittedForm): the part's charset, else the
        // request's (UTF-8 in Jenkins), else fileupload's default. Stapler's plain getParameter uses
        // only the part's charset or ISO-8859-1, which garbles non-ASCII values from browsers.
        String requestEncoding = replay.getCharacterEncoding();
        Charset fallback = requestEncoding != null ? Charset.forName(requestEncoding)
                : DiskFileItemFactory.builder().get().getCharsetDefault();
        Map<String, List<String>> out = new LinkedHashMap<>();
        FileItemInputIterator items = upload.getItemIterator(replay);
        int parts = 0;
        while (items.hasNext()) {
            FileItemInput item = items.next();
            if (++parts > MAX_PARTS) {
                throw new IOException("more than " + MAX_PARTS + " parts");
            }
            if (!item.isFormField() || !FIELDS.contains(item.getFieldName())) {
                continue; // the iterator skips the unread content of this part
            }
            byte[] bytes;
            try (InputStream in = item.getInputStream()) {
                bytes = in.readNBytes(MAX_VALUE_BYTES + 1);
            }
            if (bytes.length > MAX_VALUE_BYTES) {
                throw new IOException("value of '" + item.getFieldName() + "' over " + MAX_VALUE_BYTES + " bytes");
            }
            out.computeIfAbsent(item.getFieldName(), k -> new ArrayList<>())
                    .add(new String(bytes, charset(item.getContentType(), fallback)));
        }
        out.replaceAll((k, v) -> Collections.unmodifiableList(v));
        return Collections.unmodifiableMap(out);
    }

    private static Charset charset(String contentType, Charset fallback) {
        if (contentType == null) {
            return fallback;
        }
        ParameterParser parser = new ParameterParser();
        parser.setLowerCaseNames(true);
        String name = parser.parse(contentType, ';').get("charset");
        return name == null ? fallback : Charset.forName(name);
    }

    /** The copied body: memory up to the threshold, then an owner-only temp file. */
    static final class Body implements Closeable {
        private final Mem mem = new Mem();
        Path file;
        long size;

        static Body copy(InputStream in, long limit, int threshold) throws IOException {
            Body body = new Body();
            OutputStream out = body.mem;
            byte[] buf = new byte[16 * 1024];
            try {
                while (body.size < limit) {
                    int n = in.read(buf, 0, (int) Math.min(buf.length, limit - body.size));
                    if (n < 0) {
                        break;
                    }
                    if (body.file == null && body.size + n > threshold) {
                        // Files.createTempFile: rw------- on POSIX, so other local users cannot read uploads.
                        body.file = Files.createTempFile("bc-multipart-", ".tmp");
                        TEMP_FILES_CREATED.incrementAndGet();
                        out = Files.newOutputStream(body.file);
                        body.mem.writeTo(out);
                        body.mem.reset();
                    }
                    out.write(buf, 0, n);
                    body.size += n;
                }
            } catch (IOException | RuntimeException e) {
                if (out != body.mem) {
                    out.close();
                }
                body.close();
                throw e;
            }
            if (out != body.mem) {
                out.close();
            }
            return body;
        }

        InputStream open() throws IOException {
            return file == null ? mem.in() : Files.newInputStream(file);
        }

        String permissions() {
            try {
                return file == null ? null : PosixFilePermissions.toString(Files.getPosixFilePermissions(file));
            } catch (IOException | UnsupportedOperationException e) {
                return "n/a";
            }
        }

        @Override
        public void close() throws IOException {
            if (file != null) {
                Files.deleteIfExists(file);
            }
        }
    }

    private static final class Mem extends ByteArrayOutputStream {
        InputStream in() {
            return new ByteArrayInputStream(buf, 0, count);
        }
    }

    /** Hands a replay of the copied body to whoever reads the request next. */
    static final class Replay extends HttpServletRequestWrapper {
        private final ServletInputStream in;
        private final Map<String, List<String>> exposed;
        private BufferedReader reader;

        Replay(HttpServletRequest request, InputStream body, Map<String, List<String>> exposed) {
            super(request);
            this.in = new ReplayStream(body);
            this.exposed = exposed;
        }

        @Override
        public ServletInputStream getInputStream() {
            return in;
        }

        @Override
        public BufferedReader getReader() {
            if (reader == null) {
                String enc = getCharacterEncoding();
                reader = new BufferedReader(new InputStreamReader(in,
                        enc == null ? StandardCharsets.ISO_8859_1 : Charset.forName(enc)));
            }
            return reader;
        }

        @Override
        public String[] getParameterValues(String name) {
            if (exposed != null && exposed.containsKey(name)) {
                return exposed.get(name).toArray(new String[0]);
            }
            return super.getParameterValues(name);
        }
    }

    private static final class ReplayStream extends ServletInputStream {
        private final InputStream in;
        private boolean finished;

        ReplayStream(InputStream in) {
            this.in = in;
        }

        @Override
        public int read() throws IOException {
            int b = in.read();
            finished = b < 0;
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = in.read(b, off, len);
            finished = n < 0;
            return n;
        }

        @Override
        public boolean isFinished() {
            return finished;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener listener) {
            throw new UnsupportedOperationException("PoC-6 replay is blocking only");
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }

    public enum Outcome { PARSED, NOT_PARSED, PASSED_OVER_CAP_DECLARED, PASSED_OVER_CAP_STREAMED, COPY_FAILED }

    /** What the filter did with the last in-scope request. */
    public static final class Probe {
        public volatile Outcome outcome;
        public long seq;
        public boolean staplerRequestAlreadyCurrent;
        public String authentication;
        public long contentLength;
        public long bytesBuffered = -1;
        public boolean inMemory;
        public Path tempFile;
        public String tempFilePermissions;
        public volatile Boolean tempFileDeletedAfterChain;
        public String parseError;

        @Override
        public String toString() {
            return "Probe{outcome=" + outcome + ", seq=" + seq + ", staplerRequestAlreadyCurrent="
                    + staplerRequestAlreadyCurrent + ", authentication=" + authentication + ", contentLength="
                    + contentLength + ", bytesBuffered=" + bytesBuffered + ", inMemory=" + inMemory + ", tempFile="
                    + tempFile + ", tempFilePermissions=" + tempFilePermissions + ", tempFileDeletedAfterChain="
                    + tempFileDeletedAfterChain + ", parseError=" + parseError + "}";
        }
    }
}
