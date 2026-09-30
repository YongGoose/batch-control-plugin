import { globalCfg, groovy, api } from '../lib.mjs';
console.log(JSON.stringify(await globalCfg()));
console.log(await groovy(`
def a = jenkins.model.Jenkins.get().getAuthorizationStrategy(); println a.getClass().name
jenkins.model.Jenkins.get().getAllItems(hudson.model.Job).each { j ->
  def p = j.getProperty(io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty)
  println j.fullName + ' next=' + j.nextBuildNumber + ' prop=' + (p==null?'-':('appr='+p.approvalRequired+' timer='+p.blockTimer+' up='+p.blockUpstream)) + ' disabled=' + (j.respondsTo('isDisabled')? j.disabled : '')
}
println 'queue=' + jenkins.model.Jenkins.get().queue.items.size()
`));
