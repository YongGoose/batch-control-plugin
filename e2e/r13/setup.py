"""R13 arrangement: apply casc/profile-role.yaml (Batch Control: Role-Based Strategy over
role-strategy 927) and print the installed strategy and plugin versions."""
from lib import casc, strategy, groovy, log
out = {"before": strategy(), "casc": casc("/var/jenkins_casc/profile-role.yaml"), "after": strategy(),
       "plugins": groovy("return ['role-strategy','batch-control'].collect{ it + ' ' + jenkins.model.Jenkins.get().pluginManager.getPlugin(it).version }"),
       "changeControl": groovy("return io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration.get().changeControlEnabled")}
print(out); log("setup", out)
