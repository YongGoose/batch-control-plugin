/*
 * ci.jenkins.io build, reported to GitHub as the "Jenkins" check. This is the standard form
 * other jenkinsci plugins use; the options are documented in
 * https://github.com/jenkins-infra/pipeline-library/blob/master/vars/buildPlugin.groovy
 *
 * - Spot agents only. buildPlugin chooses the agent labels and retries an agent that is
 *   reclaimed. Never name a `*-nonspot` label and do not replace this call with a custom
 *   pipeline: the ci.jenkins.io administrators asked for that in
 *   jenkinsci/batch-control-plugin#80.
 * - Linux on JDK 25 and Windows on JDK 21, the two JDKs the hosting checker accepts. Linux on
 *   JDK 21 is covered by the GitHub Actions `build` job (.github/workflows/build.yml).
 * - timeout: 120 minutes per platform. The full suite took 2 to 3 hours run serially on one
 *   ci.jenkins.io agent; pom.xml already runs Surefire with forkCount 1C (one fork per core),
 *   so forkCount is not passed here. buildPlugin's default of 60 minutes is too short, and it
 *   caps the value at 180.
 */
buildPlugin(useContainerAgent: true, timeout: 120, configurations: [
  [platform: 'linux', jdk: 25],
  [platform: 'windows', jdk: 21],
])
