import { groovy } from '../lib.mjs';
console.log(await groovy(`
def j = jenkins.model.Jenkins.get().getItemByFullName('b10-ui')
println j.triggers.values()*.spec
def svc = io.jenkins.plugins.batchcontrol.policy.ActivationService
println svc.methods*.name.unique().sort()
`));
