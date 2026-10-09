// timeout: the full test suite needs more than buildPlugin's default 60 minutes
// on a single agent (180 is the maximum buildPlugin allows).
// failFast: off, so that one platform's failures or timeout do not abort the
// other platforms and every failure gets reported.
buildPlugin(
    useContainerAgent: true,
    timeout: 180,
    failFast: false,
    configurations: [
        [platform: 'linux', jdk: 21],
        [platform: 'linux', jdk: 25],
        [platform: 'windows', jdk: 21],
    ]
)
