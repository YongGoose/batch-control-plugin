package io.jenkins.plugins.batchcontrol.ui;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.model.Failure;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.sf.json.JSONObject;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.StaplerRequest2;

/**
 * Reads the designated approver set from a submitted form (SPEC item 3, D-37).
 *
 * <p>The field is the repeated {@code approvers} (one user id per value), which is what the
 * rendered checkbox picker posts. For scripts written against the single-approver contract the
 * old {@code approver} field is still read and merged in. When a form arrives only as a
 * {@code json} blob (as {@code getSubmittedForm} reads it), {@code approvers} may be a string or
 * an array of strings there.
 *
 * <p>A {@code multipart/form-data} body (the Request Run form, D-72) keeps only the last part of
 * a repeated field in Stapler, so there the {@code json} blob the form posts with it is read as
 * well ({@link RepeatedField}): the rendered checkboxes put the checked ids into it in order.
 *
 * <p>Validation here is shape only: entries are trimmed, blanks dropped, duplicates collapsed,
 * and the count and length are bounded so a request cannot carry unbounded input. Eligibility
 * (listed approver, not the requester, job restriction) stays in the policy layer.
 */
@Restricted(NoExternalUse.class)
public final class ApproverInput {

    /** Upper bound on the number of designated approvers in one submission. */
    public static final int MAX_APPROVERS = 50;

    /** Upper bound on one user id. */
    public static final int MAX_USER_ID_LENGTH = 256;

    /** Field name of the repeated approver field. */
    public static final String FIELD = "approvers";

    /** Field name of the single-approver field accepted for compatibility. */
    public static final String LEGACY_FIELD = "approver";

    private ApproverInput() {
    }

    /**
     * @param req the current request
     * @param formData the parsed {@code json} blob when the form carried one, otherwise {@code null}
     *        (for a multipart request the blob is then read from the request when it carries one)
     * @return the trimmed, de-duplicated approver ids in submission order, possibly empty (the
     *         service then refuses the request with its own message)
     * @throws Failure when the input exceeds the count or length bounds
     */
    public static List<String> read(StaplerRequest2 req, @CheckForNull JSONObject formData) {
        List<String> raw = new ArrayList<>();
        if (RepeatedField.isMultipart(req)) {
            // D-37: Stapler keeps only the last of the repeated multipart parts, so the blob's ids
            // come first (all of them, in order), then the raw part (one of them for the form).
            raw.addAll(RepeatedField.values(req, formData, FIELD));
            raw.addAll(RepeatedField.values(req, formData, LEGACY_FIELD));
            return normalize(raw);
        }
        addAll(raw, req.getParameterValues(FIELD));
        addAll(raw, req.getParameterValues(LEGACY_FIELD));
        if (raw.isEmpty() && formData != null) {
            RepeatedField.addJson(raw, formData.opt(FIELD));
            RepeatedField.addJson(raw, formData.opt(LEGACY_FIELD));
        }
        return normalize(raw);
    }

    static List<String> normalize(List<String> raw) {
        Set<String> out = new LinkedHashSet<>();
        for (String value : raw) {
            if (value == null) {
                continue;
            }
            String id = value.trim();
            if (id.isEmpty()) {
                continue;
            }
            if (id.length() > MAX_USER_ID_LENGTH) {
                throw new Failure("An approver user id must not exceed " + MAX_USER_ID_LENGTH
                        + " characters.");
            }
            out.add(id);
            if (out.size() > MAX_APPROVERS) {
                throw new Failure("At most " + MAX_APPROVERS + " approvers can be designated.");
            }
        }
        return new ArrayList<>(out);
    }

    private static void addAll(List<String> out, @CheckForNull String[] values) {
        if (values != null) {
            for (String value : values) {
                out.add(value);
            }
        }
    }
}
