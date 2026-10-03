"""R4-1 Java 25 smoke: plugin active (version, caffeine-api), JVM version, then the s_dialogs A/D flows and approval."""
from lib import api, groovy, log
res = {"plugins": groovy("""def pm = jenkins.model.Jenkins.get().pluginManager; return ['batch-control','caffeine-api'].collect { def p = pm.getPlugin(it); it + '=' + p?.version + ' active=' + p?.isActive() + ' failed=' + (pm.getFailedPlugins().find{ f -> f.name == it } != null) }.join(', ') + ' java=' + System.getProperty('java.version')"""),
       "overview": api("admin", "/batch-control/").status_code}
log("j25", res); print(res)
