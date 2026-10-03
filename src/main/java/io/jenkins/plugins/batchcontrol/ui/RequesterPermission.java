package io.jenkins.plugins.batchcontrol.ui;

import io.jenkins.plugins.batchcontrol.model.RunRequest;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * D-38a: whether the <em>requester</em> of a run request (not the viewer) lacks {@code Item/Build}
 * on the request's job, for the request detail page. Delegates to
 * {@link RunRequestService#requesterLacksBuild}, the predicate the approver notification uses
 * too, so the page and the mail cannot disagree. Read only.
 */
@Restricted(NoExternalUse.class)
public final class RequesterPermission {

    private RequesterPermission() {
    }

    /** See {@link RunRequestService#requesterLacksBuild}. */
    public static boolean lacksBuild(RunRequest request) {
        return RunRequestService.get().requesterLacksBuild(request);
    }

    /** The frozen sentence the detail page shows ({@link RunRequestService#REQUESTER_LACKS_BUILD_NOTICE}). */
    public static String notice() {
        return RunRequestService.REQUESTER_LACKS_BUILD_NOTICE;
    }
}
