# Throwaway variant of the e2e image with libfaketime, for checklist D-05 (month boundary).
# Not used by docker-compose.yml; built and run by hand, see e2e/README.md.
FROM batch-control-e2e-jenkins:2.568.3
USER root
RUN apt-get update -qq && apt-get install -y -qq --no-install-recommends faketime libfaketime >/dev/null \
 && rm -rf /var/lib/apt/lists/* \
 && ln -s "$(dpkg -L libfaketime | grep 'libfaketime.so.1$' | head -1)" /usr/local/lib/libfaketime.so.1
USER jenkins
