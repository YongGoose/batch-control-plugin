/*
 * ci.jenkins.io build, reported to GitHub as the "Jenkins" check. This is the standard form
 * other jenkinsci plugins use; the options are documented in
 * https://github.com/jenkins-infra/pipeline-library/blob/master/vars/buildPlugin.groovy
 *
 * - Spot agents only. buildPlugin chooses the agent labels and retries an agent that is
 *   reclaimed. Never name a `*-nonspot` label and do not replace this call with a custom
 *   pipeline: the ci.jenkins.io administrators asked for that in
 *   jenkinsci/batch-control-plugin#80.
 * - Linux on JDK 25 and Windows on JDK 21, the two JDKs the hosting checker accepts.
 * - Core tests only. A Jenkins build sets BUILD_URL, which activates the
 *   core-tests-on-jenkins profile in pom.xml: Surefire runs the tests tagged @Tag("core") and
 *   the generated InjectedTest, and fails when no test carries the tag. The whole suite took about 103 minutes on four forks here,
 *   too long for the default timeout and for spot agents. It runs on GitHub Actions instead:
 *   Linux on JDK 21 and 25 in the `build` workflow, Windows on JDK 21 in `windows-tests`.
 * - timeout: 90 minutes per platform. The core tests take about 30 minutes on four forks;
 *   the rest is compilation and the static checks, and Windows is slower. buildPlugin's
 *   default is 60 minutes and its cap 180. forkCount is not passed: pom.xml sets 1C.
 */
buildPlugin(useContainerAgent: true, timeout: 90, configurations: [
  [platform: 'linux', jdk: 25],
  [platform: 'windows', jdk: 21],
])
