package io.jenkins.plugins.batchcontrol.config;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Descriptor.FormException;
import hudson.model.ManagementLink;
import hudson.security.Permission;
import hudson.util.FormApply;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import jakarta.servlet.ServletException;
import java.io.IOException;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;
import org.kohsuke.stapler.interceptor.RequirePOST;

/**
 * e2e-03 DEF-10 (SPEC section 6, usability): {@code BatchControl/Manage} is enough to open and
 * save the Batch Control configuration. Core's system configuration page needs
 * {@code Overall/Manage}, which a {@code BatchControl/Manage} holder may not have, so the same
 * configuration is also served at {@code /batch-control-configuration/} (Jenkins resolves a
 * management link at the root URL too). It is listed on "Manage Jenkins" for users who can open
 * that page, and linked from the Batch Control page for everyone else holding the permission.
 *
 * <p>The form is the configuration's own {@code config.jelly}; saving goes through
 * {@link BatchControlGlobalConfiguration#configure}, so validation (DEF-08), the transactional
 * write and the toggle records are exactly those of the system configuration page.
 */
@Extension
@Restricted(NoExternalUse.class)
public class BatchControlConfigurationLink extends ManagementLink {

    /** URL of the page, relative to the Jenkins root (and to {@code /manage/}). */
    public static final String URL_NAME = "batch-control-configuration";

    /**
     * The entry is listed only once its page ({@code index.jelly}) is shipped, so the link can
     * never lead to a 404 (SPEC section 6).
     */
    @Override
    @CheckForNull
    public String getIconFileName() {
        return isPageAvailable() ? "symbol-shield-checkmark-outline plugin-ionicons-api" : null;
    }

    /** Whether the page's view exists (Jelly: the Batch Control page links here only then). */
    public boolean isPageAvailable() {
        return BatchControlConfigurationLink.class.getResource("BatchControlConfigurationLink/index.jelly") != null;
    }

    @Override
    public String getDisplayName() {
        return "Batch Control";
    }

    @Override
    public String getDescription() {
        return "Run and change control switches, approvers, timeouts, grant durations, incidents and retention.";
    }

    @Override
    public String getUrlName() {
        return URL_NAME;
    }

    @NonNull
    @Override
    public Permission getRequiredPermission() {
        return BatchControlPermissions.MANAGE;
    }

    @NonNull
    @Override
    public Category getCategory() {
        return Category.CONFIGURATION;
    }

    /** Whether the current user may open and save this page. */
    public boolean isCanManage() {
        return Jenkins.get().hasPermission(BatchControlPermissions.MANAGE);
    }

    /** Request attribute carrying the refused submission's draft (D-53, e2e-04 FD-03). */
    static final String DRAFT = BatchControlConfigurationLink.class.getName() + ".draft";

    /** Request attribute carrying the refusal message of the submission. */
    static final String SAVE_ERROR = BatchControlConfigurationLink.class.getName() + ".error";

    /**
     * The configuration the page edits (Jelly binds its fields to it). While a refused submission
     * is being answered, the submitted values instead (a detached draft that is never saved), so
     * the page keeps what the user typed and the field checks show the message next to the field.
     */
    public BatchControlGlobalConfiguration getConfiguration() {
        org.kohsuke.stapler.StaplerRequest2 req = org.kohsuke.stapler.Stapler.getCurrentRequest2();
        Object draft = req == null ? null : req.getAttribute(DRAFT);
        return draft instanceof BatchControlGlobalConfiguration
                ? (BatchControlGlobalConfiguration) draft : BatchControlGlobalConfiguration.get();
    }

    /** The refusal message of the submission being answered, or {@code null} (for the view). */
    @CheckForNull
    public String getSaveError() {
        org.kohsuke.stapler.StaplerRequest2 req = org.kohsuke.stapler.Stapler.getCurrentRequest2();
        Object error = req == null ? null : req.getAttribute(SAVE_ERROR);
        return error instanceof String ? (String) error : null;
    }

    /**
     * POST {@code configSubmit}: saves the form. An invalid value is refused by
     * {@link BatchControlGlobalConfiguration#configure} with a {@link FormException} naming the
     * field, and nothing is saved.
     */
    @RequirePOST
    public void doConfigSubmit(StaplerRequest2 req, StaplerResponse2 rsp)
            throws IOException, ServletException, FormException {
        Jenkins.get().checkPermission(BatchControlPermissions.MANAGE);
        BatchControlGlobalConfiguration configuration = getConfiguration();
        JSONObject json = req.getSubmittedForm();
        String key = configuration.getJsonSafeClassName();
        JSONObject section = json.has(key) ? json.getJSONObject(key) : json;
        try {
            configuration.configure(req, section);
        } catch (FormException e) {
            // D-53 / SPEC 6 usability: the refused form is shown again with the submitted values
            // (HTTP 400); nothing was saved.
            BatchControlGlobalConfiguration draft = BatchControlGlobalConfiguration.draftOf(req, section);
            if (draft == null) {
                throw e;
            }
            req.setAttribute(DRAFT, draft);
            req.setAttribute(SAVE_ERROR, e.getMessage());
            rsp.setStatus(jakarta.servlet.http.HttpServletResponse.SC_BAD_REQUEST);
            req.getView(this, "index.jelly").forward(req, rsp);
            return;
        }
        FormApply.success(".").generateResponse(req, rsp, null);
    }
}
