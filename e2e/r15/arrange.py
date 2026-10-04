"""e2e-15 arrangement: index the seeded multibranch team-mb (local file:// repo, branches main and feature-1;
the seed's source has no discovery trait, which is added first) and
wait for its branch jobs; print the activation state of team-mb (created while run control is on: not activated)."""
import time
from lib import groovy, api
# The seed's GitSCMSource has no traits, so indexing discovers nothing; add "Discover branches" (the default an
# administrator gets from the configure page).
print(groovy("""def mb = jenkins.model.Jenkins.get().getItemByFullName('team-mb')
def src = mb.getSCMSources()[0]
if (!src.traits.any { it instanceof jenkins.plugins.git.traits.BranchDiscoveryTrait }) {
  src.setTraits([new jenkins.plugins.git.traits.BranchDiscoveryTrait()]); mb.save() }
return src.traits"""))
print(api("admin", "/job/team-mb/build?delay=0", "POST").status_code)
for _ in range(60):
    out = groovy("return jenkins.model.Jenkins.get().getItemByFullName('team-mb').getItems()*.name.sort()")
    if "main" in out and "feature-1" in out:
        break
    time.sleep(2)
print("branches:", out)
print(groovy("""def j = jenkins.model.Jenkins.get()
return ['team-mb','team-mb/main','batch-pipeline'].collect { n -> n + ' ' + j.getItemByFullName(n).getClass().simpleName }"""))
