#!/usr/bin/env groovy
/*
 * ci.jenkins.io build, reported to GitHub as the "Jenkins" check.
 *
 * buildPlugin runs the whole suite on one agent per platform, which took 2 to 3 hours and hit
 * its 180-minute cap. This pipeline runs each platform on several agents instead: one per test
 * group of .github/test-shards.txt (the groups GitHub Actions runs, 7 to 13 minutes each there)
 * and one "checks" agent for `verify -DskipTests` (enforcer, access-modifier checker, SpotBugs).
 * It is built from the pipeline-library steps buildPlugin uses (vars/infra.groovy): checkout,
 * Maven with the artifact caching proxy, Incrementals publishing.
 *
 * Every test class runs exactly once per platform: the Plan stage assigns each class to its
 * group and fails on a class in no group or in two (as .github/scripts/test-shards.py check
 * does), and each test agent fails unless Surefire reported exactly its group's classes.
 * Test failures fail their agent's branch and the build; the other branches still finish.
 */

properties([
  disableConcurrentBuilds(abortPrevious: true),
  buildDiscarder(logRotator(numToKeepStr: '5')),
])

// The JDKs the hosting checker accepts, on non-spot agents so that they are not reclaimed
// mid-run (jenkins-infra/helpdesk#4906, #5171). The first platform also records coverage,
// static analysis and the Incrementals artifacts.
def platforms = [
  [name: 'linux-21', label: 'maven-21', jdk: '21'],
  [name: 'linux-25', label: 'maven-25', jdk: '25'],
  [name: 'windows-21', label: 'maven-21-windows', jdk: '21'],
]

def groups
stage('Plan') {
  onAgent('maven-21', 10) {
    infra.checkoutSCM()
    discoverReferenceBuild()
    // Test sources, then "path:abstract class Name" for every abstract class.
    String sources = sh(returnStdout: true, script: '''
      cd src/test/java
      find . -name '*.java' | sed 's|^[.]/||' | LC_ALL=C sort
      echo '-- abstract --'
      grep -rEo --include='*.java' 'abstract[[:space:]]+class[[:space:]]+[A-Za-z0-9_]+' . | sed 's|^[.]/||' || true
    ''')
    Map plan = planGroups(readFile('.github/test-shards.txt'), sources)
    echo(plan.summary)
    if (!plan.errors.isEmpty()) {
      error('.github/test-shards.txt does not assign every test class to exactly one group:\n' + plan.errors.join('\n'))
    }
    groups = plan.groups
  }
}

def branches = [failFast: false]
def coverageStashes = []
for (def p in platforms) {
  def platform = p
  boolean primary = platform.name == platforms.get(0).name
  branches["${platform.name} (checks)"] = {
    onAgent(platform.label, 30) {
      runChecks(platform, primary)
    }
  }
  for (def g in groups) {
    def group = g
    branches["${platform.name} (${group.label})"] = {
      onAgent(platform.label, 60) {
        runGroup(platform, group, primary)
        if (primary) {
          String stashName = "coverage-${group.id}"
          coverageStashes.add(stashName)
        }
      }
    }
  }
}
parallel branches

// Reached only when every branch succeeded.
stage('Coverage') {
  onAgent('maven-21-nonspot', 15) {
    infra.checkoutSCM()
    for (def s in coverageStashes) {
      def name = s
      dir("coverage/${name}") {
        unstash(name)
      }
    }
    // The reports of the groups are merged per source line.
    recordCoverage(tools: [[parser: 'JACOCO', pattern: 'coverage/**/jacoco.xml']],
        sourceDirectories: [[path: 'src/main/java']], sourceCodeRetention: 'MODIFIED')
  }
}
infra.maybePublishIncrementals()

// A fresh agent, retried once on a new one if the agent is lost (as core's Jenkinsfile does).
void onAgent(String label, int minutes, Closure body) {
  retry(count: 2, conditions: [kubernetesAgent(handleNonKubernetes: true), nonresumable()]) {
    node(label) {
      timeout(time: minutes, unit: 'MINUTES') {
        body()
      }
    }
  }
}

// As buildPlugin: a local repository per agent (infra.runMaven seeds it from the agent's Maven
// cache) and no Incrementals dependencies.
List<String> mavenOptions(String tmp) {
  String repo = "-Dmaven.repo.local=${tmp}/m2repo"
  return ['--update-snapshots', repo, '-P-consume-incrementals']
}

void runChecks(Map platform, boolean primary) {
  infra.checkoutSCM()
  String tmp = pwd(tmp: true)
  String changelistFile = "${tmp}/changelist"
  String output = "-Doutput=${changelistFile}"
  List<String> options = mavenOptions(tmp) + ['-DskipTests']
  if (primary) {
    // Incrementals (JEP-305), as buildPlugin: install with the changelist version.
    sh 'git clean -xffd'
    options += ['-Dset.changelist', 'help:evaluate', '-Dexpression=changelist', output, 'clean', 'install']
  } else {
    options += ['clean', 'verify']
  }
  try {
    infra.runMaven(options, platform.jdk, null, false)
  } finally {
    if (primary) {
      recordIssues(enabledForFailure: true, tool: mavenConsole(), skipBlames: true, trendChartType: 'TOOLS_ONLY')
      recordIssues(enabledForFailure: true, tools: [java(), javaDoc()], filters: [excludeFile('.*Assert.java')],
          sourceCodeEncoding: 'UTF-8', skipBlames: true, trendChartType: 'TOOLS_ONLY')
      recordIssues(enabledForFailure: true, tool: spotBugs(pattern: '**/target/spotbugsXml.xml'),
          sourceCodeEncoding: 'UTF-8', skipBlames: true, trendChartType: 'TOOLS_ONLY')
    }
  }
  if (primary) {
    // The changelist is <count>.v<hash>, the version ${revision}.<changelist> (1.0.<count>.v<hash>):
    // the leading '*' of each part of the pattern matches the "1.0." prefix, as in buildPlugin.
    String changelist = readFile(changelistFile)
    dir("${tmp}/m2repo") {
      archiveArtifacts(artifacts: "**/*${changelist}/*${changelist}*", excludes: '**/*.lastUpdated',
          allowEmptyArchive: true)
    }
  }
}

void runGroup(Map platform, Map group, boolean coverage) {
  infra.checkoutSCM()
  String tmp = pwd(tmp: true)
  String includesFile = "${tmp}/includes.txt"
  writeFile(file: includesFile, text: group.patterns.join('\n') + '\n')
  String includes = "-Dsurefire.includesFile=${includesFile}"
  // The access-modifier checker runs in the checks branch.
  List<String> options = mavenOptions(tmp) + [includes, '-Daccess-modifier-checker.skip=true']
  options += coverage ? ['-Penable-jacoco', 'clean', 'test', 'jacoco:report'] : ['clean', 'test']
  try {
    infra.runMaven(options, platform.jdk, null, false)
  } finally {
    junit(testResults: 'target/surefire-reports/TEST-*.xml', testDataPublishers: [attachments()],
        allowEmptyResults: true)
  }
  String reports = isUnix() ? sh(returnStdout: true, script: 'ls target/surefire-reports')
      : bat(returnStdout: true, script: '@dir /b target\\surefire-reports')
  List<String> problems = reportProblems(reports, group.classes)
  if (!problems.isEmpty()) {
    error("${group.label}: Surefire did not run exactly the classes of the group:\n" + problems.join('\n'))
  }
  echo("${group.label}: a report for each of the ${group.classes.size()} classes of the group")
  if (coverage) {
    stash(name: "coverage-${group.id}", includes: 'target/site/jacoco/jacoco.xml')
  }
}

void discoverReferenceBuild() {
  def folders = env.JOB_NAME.split('/')
  if (folders.length > 1) {
    discoverGitReferenceBuild(scm: folders[1])
  }
}

/*
 * The groups of .github/test-shards.txt (format in its header), each with the test classes it
 * runs, following .github/scripts/test-shards.py: a test class is a non-abstract class whose
 * simple name matches Surefire's default includes; a pattern matches the simple name, '*'
 * matching any run of characters.
 */
@NonCPS
Map planGroups(String shardFile, String sources) {
  List<String> errors = []
  List<Map> groups = []
  int lineno = 0
  for (String raw in shardFile.readLines()) {
    lineno++
    String line = raw.trim()
    if (line.isEmpty() || line.startsWith('#')) {
      continue
    }
    String where = "test-shards.txt:${lineno}"
    if (line.startsWith('[') && line.endsWith(']')) {
      String label = line.substring(1, line.length() - 1)
      String id = label.replaceAll('[^a-z0-9]+', '-')
      if (label.length() > 25 || !label.matches('[a-z0-9]+([ -][a-z0-9]+)*')) {
        errors.add("${where}: invalid label '${label}'")
      }
      for (Map other in groups) {
        if (other.id == id) {
          errors.add("${where}: label '${label}' repeats the group id '${id}'")
        }
      }
      groups.add([label: label, id: id, patterns: [], classes: []])
      continue
    }
    if (groups.isEmpty()) {
      errors.add("${where}: patterns before the first '[<label>]' line")
      continue
    }
    Map current = groups.get(groups.size() - 1)
    for (String pattern in line.tokenize(', \t')) {
      if (!pattern.matches('[A-Za-z0-9_*]+')) {
        errors.add("${where}: unsupported pattern '${pattern}'")
      } else if (current.patterns.contains(pattern)) {
        errors.add("${where}: pattern '${pattern}' is listed twice in group '${current.label}'")
      } else {
        current.patterns.add(pattern)
      }
    }
  }

  List<String> files = []
  List<String> abstractClasses = []
  boolean abstractPart = false
  for (String raw in sources.readLines()) {
    String line = raw.trim()
    if (line.isEmpty()) {
      continue
    }
    if (line == '-- abstract --') {
      abstractPart = true
    } else if (abstractPart) {
      abstractClasses.add(line.substring(0, line.indexOf(':')) + ' ' + line.replaceAll('^.*\\s', ''))
    } else {
      files.add(line)
    }
  }
  List<String> classes = []
  for (String path in files) {
    String simple = path.substring(path.lastIndexOf('/') + 1).replaceAll('[.]java$', '')
    if (simple.matches('Test.*|.*Test|.*Tests|.*TestCase') && !abstractClasses.contains(path + ' ' + simple)) {
      classes.add(path.replaceAll('[.]java$', '').replace('/', '.'))
    }
  }
  // Generated at build time by maven-hpi-plugin (insert-test goal), not present in src/test.
  classes.add('io.jenkins.plugins.batch_control.InjectedTest')

  List<String> used = []
  for (String fqcn in classes) {
    String simple = fqcn.substring(fqcn.lastIndexOf('.') + 1)
    List<Map> hits = []
    for (Map g in groups) {
      boolean hit = false
      for (String pattern in g.patterns) {
        if (simple.matches(pattern.replace('*', '.*'))) {
          used.add(g.id + ' ' + pattern)
          hit = true
        }
      }
      if (hit) {
        hits.add(g)
      }
    }
    if (hits.isEmpty()) {
      errors.add("${fqcn} matches no group")
    } else if (hits.size() > 1) {
      String labels = ''
      for (Map g in hits) {
        labels += " '${g.label}'"
      }
      errors.add("${fqcn} matches more than one group:${labels}")
    } else {
      hits.get(0).classes.add(fqcn)
    }
  }
  String summary = 'Test groups (.github/test-shards.txt):\n'
  for (Map g in groups) {
    if (g.classes.isEmpty()) {
      errors.add("group '${g.label}' matches no test class")
    }
    for (String pattern in g.patterns) {
      if (!used.contains(g.id + ' ' + pattern)) {
        errors.add("pattern '${pattern}' of group '${g.label}' matches no test class (remove it)")
      }
    }
    summary += "  ${g.label}: ${g.classes.size()} classes\n"
  }
  summary += "  total: ${classes.size()} classes in ${groups.size()} groups"
  return [groups: groups, errors: errors, summary: summary]
}

// The differences between the classes Surefire reported (a listing of target/surefire-reports)
// and the classes of the group, as .github/scripts/test-shards.py verify reports them.
@NonCPS
List<String> reportProblems(String listing, List<String> expected) {
  List<String> reported = []
  for (String raw in listing.readLines()) {
    String name = raw.trim()
    if (name.startsWith('TEST-') && name.endsWith('.xml')) {
      reported.add(name.substring(5, name.length() - 4))
    }
  }
  List<String> problems = []
  for (String c in expected) {
    if (!reported.contains(c)) {
      problems.add("no Surefire report for ${c}")
    }
  }
  for (String c in reported) {
    if (!expected.contains(c)) {
      problems.add("ran ${c}, which belongs to another group")
    }
  }
  return problems
}
