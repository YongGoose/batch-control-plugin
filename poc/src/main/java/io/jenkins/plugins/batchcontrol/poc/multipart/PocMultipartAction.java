package io.jenkins.plugins.batchcontrol.poc.multipart;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.Extension;
import hudson.model.Action;
import hudson.model.Item;
import hudson.model.Job;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import jenkins.model.TransientActionFactory;
import net.sf.json.JSONObject;
import org.apache.commons.fileupload2.core.DiskFileItem;
import org.apache.commons.fileupload2.core.FileItem;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;
import org.kohsuke.stapler.interceptor.RequirePOST;

/**
 * PoC-6: a stand-in for {@code JobRequestAction#doSubmit} at {@code /job/<name>/poc-multipart/submit}.
 * It reads its fields the way the real action does ({@code getParameterValues("approvers")},
 * {@code getFileItem2("file0")}, the {@code json} blob when present) and records what it saw in
 * {@link #LAST} so the tests can assert on it. No dependency on the plugin's main code.
 */
@SuppressFBWarnings(value = {"PA_PUBLIC_PRIMITIVE_ATTRIBUTE", "ST_WRITE_TO_STATIC_FROM_INSTANCE_METHOD"},
        justification = "PoC test instrumentation: the tests read what the action saw")
public final class PocMultipartAction implements Action {

    public static final String URL_NAME = "poc-multipart";

    /** Shared order counter: the filter and the action each take a number. */
    public static final AtomicLong SEQ = new AtomicLong();

    /** What the last {@code doSubmit} saw. */
    public static volatile Seen LAST;

    private final Job<?, ?> job;

    PocMultipartAction(Job<?, ?> job) {
        this.job = job;
    }

    @Override
    public String getIconFileName() {
        return null;
    }

    @Override
    public String getDisplayName() {
        return "PoC multipart";
    }

    @Override
    public String getUrlName() {
        return URL_NAME;
    }

    @RequirePOST
    public void doSubmit(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException, ServletException {
        job.checkPermission(Item.READ);
        Seen seen = new Seen();
        seen.seq = SEQ.incrementAndGet();
        // D-72 stage 1 as in the real action: the declared length, before any form value is read.
        if (req.getContentLengthLong() > PocRepeatedFieldsFilter.maxBytes) {
            seen.refusedTooLarge = true;
            LAST = seen;
            rsp.sendError(413, "body over the cap");
            return;
        }
        seen.rawApprovers = asList(req.getParameterValues("approvers"));
        @SuppressWarnings("unchecked")
        Map<String, List<String>> attr = (Map<String, List<String>>) req.getAttribute(PocRepeatedFieldsFilter.ATTRIBUTE);
        seen.filterFields = attr;
        seen.reason = req.getParameter("reason");
        if (req.getParameter("json") != null) {
            JSONObject json = req.getSubmittedForm();
            seen.jsonApprovers = String.valueOf(json.opt("approvers"));
        }
        FileItem<?> file = req.getFileItem2("file0");
        if (file != null) {
            seen.fileName = file.getName();
            seen.fileSize = file.getSize();
            seen.fileInMemory = file.isInMemory();
            if (!file.isInMemory() && file instanceof DiskFileItem disk) {
                seen.staplerFilePath = disk.getPath();
            }
            try (InputStream in = file.getInputStream()) {
                seen.fileSha256 = sha256(in);
            }
        }
        // PoC-6 question 5: is the raw body still readable here, after Stapler parsed it?
        try (InputStream in = req.getInputStream()) {
            seen.remainingBodyBytes = in.readAllBytes().length;
        } catch (IOException | RuntimeException e) {
            seen.remainingBodyError = e.toString();
        }
        // PoC-6 question 3: is the filter's own copy still on disk while the action runs?
        PocRepeatedFieldsFilter.Probe probe = PocRepeatedFieldsFilter.LAST;
        if (probe != null && probe.tempFile != null) {
            seen.filterTempFileExistedDuringAction = Files.exists(probe.tempFile);
        }
        if (seen.staplerFilePath != null) {
            seen.staplerFileExistedDuringAction = Files.exists(seen.staplerFilePath);
        }
        LAST = seen;
        rsp.setContentType("text/plain;charset=UTF-8");
        rsp.getWriter().print("ok");
    }

    private static List<String> asList(String[] values) {
        return values == null ? null : Arrays.asList(values);
    }

    static String sha256(InputStream in) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** What one {@code doSubmit} call observed. */
    @SuppressFBWarnings(value = "URF_UNREAD_PUBLIC_OR_PROTECTED_FIELD", justification = "read by the tests")
    public static final class Seen {
        public long seq;
        public boolean refusedTooLarge;
        public List<String> rawApprovers;
        public Map<String, List<String>> filterFields;
        public String reason;
        public String jsonApprovers;
        public String fileName;
        public long fileSize = -1;
        public boolean fileInMemory;
        public Path staplerFilePath;
        public String fileSha256;
        public long remainingBodyBytes = -1;
        public String remainingBodyError;
        public Boolean filterTempFileExistedDuringAction;
        public Boolean staplerFileExistedDuringAction;

        @Override
        public String toString() {
            return "Seen{seq=" + seq + ", refusedTooLarge=" + refusedTooLarge + ", rawApprovers=" + rawApprovers
                    + ", filterFields=" + filterFields + ", reason=" + reason + ", jsonApprovers=" + jsonApprovers
                    + ", fileName=" + fileName + ", fileSize=" + fileSize + ", fileInMemory=" + fileInMemory
                    + ", fileSha256=" + fileSha256 + ", remainingBodyBytes=" + remainingBodyBytes
                    + ", remainingBodyError=" + remainingBodyError + "}";
        }
    }

    /** Adds the action to every job (PoC only). */
    @Extension
    @SuppressWarnings("rawtypes")
    public static final class Factory extends TransientActionFactory<Job> {
        @Override
        public Class<Job> type() {
            return Job.class;
        }

        @NonNull
        @Override
        public Collection<? extends Action> createFor(@NonNull Job target) {
            return Set.of(new PocMultipartAction(target));
        }
    }
}
