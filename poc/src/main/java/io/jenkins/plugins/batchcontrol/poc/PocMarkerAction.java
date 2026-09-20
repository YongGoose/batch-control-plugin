package io.jenkins.plugins.batchcontrol.poc;

import hudson.model.InvisibleAction;

/**
 * Marker action attached to queue submissions that represent an approved run.
 * {@link PocQueueDecisionHandler} lets any scheduling attempt carrying this action pass.
 */
public class PocMarkerAction extends InvisibleAction {
}
