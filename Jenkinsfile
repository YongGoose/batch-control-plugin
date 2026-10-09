// timeout: the full test suite needs more than buildPlugin's default 60 minutes
// on a single agent (180 is the maximum buildPlugin allows).
// failFast: off, so that one platform's failures or timeout do not abort the
// other platforms and every failure gets reported.
//
// Linux runs on ci.jenkins.io's non-spot container agents. The default
// `maven-<jdk>` pods run on spot nodes, which were reclaimed during most runs
// of this ~2-hour suite ("Agent was removed"), and buildPlugin retries them on
// spot again. buildPlugin has no label option for container agents; with
// useContainerAgent an unrecognised `platform` is used as the label as is
// (pipeline-library vars/infra.groovy, containerAgentLabel), and the stage is
// named after it. Same pods (4 CPUs, 12 GB), on-demand nodes; Jenkins core
// uses these labels for the same reason (jenkins-infra/helpdesk#4906, #5171).
buildPlugin(
    useContainerAgent: true,
    timeout: 180,
    failFast: false,
    configurations: [
        [platform: 'maven-21-nonspot', jdk: 21],
        [platform: 'maven-25-nonspot', jdk: 25],
        [platform: 'windows', jdk: 21],
    ]
)
