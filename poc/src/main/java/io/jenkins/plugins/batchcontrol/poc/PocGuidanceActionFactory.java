package io.jenkins.plugins.batchcontrol.poc;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Action;
import hudson.model.Job;
import java.util.Collection;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import jenkins.model.TransientActionFactory;

/**
 * PoC sidebar guidance action for design assumption D.
 * Adds a "request run" link to the sidebar of jobs listed in {@link #GUIDED_JOBS}.
 */
@Extension
public class PocGuidanceActionFactory extends TransientActionFactory<Job> {

    public static final Set<String> GUIDED_JOBS = ConcurrentHashMap.newKeySet();

    public static void reset() {
        GUIDED_JOBS.clear();
    }

    @Override
    public Class<Job> type() {
        return Job.class;
    }

    @NonNull
    @Override
    public Collection<? extends Action> createFor(@NonNull Job target) {
        if (!GUIDED_JOBS.contains(target.getFullName())) {
            return Collections.emptySet();
        }
        return Collections.singleton(new PocRequestRunAction());
    }

    /** Sidebar link shown on guided jobs. */
    public static final class PocRequestRunAction implements Action {

        public static final String DISPLAY_NAME = "Batch Control Guidance (PoC)";
        public static final String URL_NAME = "poc-request-run";

        @Override
        public String getIconFileName() {
            return "clipboard.png";
        }

        @Override
        public String getDisplayName() {
            return DISPLAY_NAME;
        }

        @Override
        public String getUrlName() {
            return URL_NAME;
        }
    }
}
