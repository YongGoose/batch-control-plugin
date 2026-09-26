# Jenkins under test for the batch-control e2e pass.
#
# The tag is pinned to the exact `jenkins.version` of pom.xml (2.568.3) so the
# plugin runs against the core it was compiled against; a floating `lts` tag
# would silently move.
FROM jenkins/jenkins:2.568.3-lts-jdk21

# Plugins are baked into the image instead of downloaded at boot: the run is
# then reproducible and the startup log stays free of update-centre noise.
COPY --chown=jenkins:jenkins plugins.txt /usr/share/jenkins/ref/plugins.txt
RUN jenkins-plugin-cli --plugin-file /usr/share/jenkins/ref/plugins.txt

# The setup wizard would block every scenario behind the unlock screen; the
# security realm and authorization strategy are configured by init.groovy.d.
ENV JAVA_OPTS="-Djenkins.install.runSetupWizard=false"
