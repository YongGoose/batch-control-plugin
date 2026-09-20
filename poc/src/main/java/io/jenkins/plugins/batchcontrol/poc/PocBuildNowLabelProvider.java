package io.jenkins.plugins.batchcontrol.poc;

import hudson.Extension;
import hudson.model.AbstractProject;
import hudson.model.Job;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import jenkins.model.ParameterizedJobMixIn;
import hudson.util.AlternativeUiTextProvider;

/**
 * PoC "Build Now" relabeling for design assumption D.
 * Replaces the sidebar "Build Now" label for jobs listed in {@link #RELABELED_JOBS}.
 */
@Extension
public class PocBuildNowLabelProvider extends AlternativeUiTextProvider {

    public static final String LABEL = "Request Approval to Run";

    public static final Set<String> RELABELED_JOBS = ConcurrentHashMap.newKeySet();

    public static void reset() {
        RELABELED_JOBS.clear();
    }

    @Override
    public <T> String getText(Message<T> text, T context) {
        boolean buildNowMessage = text == AbstractProject.BUILD_NOW_TEXT
                || text == ParameterizedJobMixIn.BUILD_NOW_TEXT;
        if (buildNowMessage && context instanceof Job
                && RELABELED_JOBS.contains(((Job<?, ?>) context).getFullName())) {
            return LABEL;
        }
        return null;
    }
}
