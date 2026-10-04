"""e2e-14 arrangement: `fast` is created by the script console while run control is on, so it starts under the
new-job lock (approvalRequired=true, D-31/D-34), like every job in 20-sample-jobs.groovy. Remove the lock the way
the seed's uncontrol() does so seed_fast.py can build it 60 times for the dashboard bound (R4-13)."""
from lib import groovy
print(groovy(r'''
def p = jenkins.model.Jenkins.get().getItem('fast')
p.removeProperty(io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty)
p.save()
return "property=${p.getProperty(io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty)}"
'''))
