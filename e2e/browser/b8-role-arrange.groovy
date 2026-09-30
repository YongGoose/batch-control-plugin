import com.synopsys.arc.jenkins.plugins.rolestrategy.RoleType
import com.michelin.cio.hudson.plugins.rolestrategy.PermissionEntry
import com.michelin.cio.hudson.plugins.rolestrategy.AuthorizationType
def s = jenkins.model.Jenkins.get().authorizationStrategy
def g = s.getRoleMap(RoleType.Global)
def p = s.getRoleMap(RoleType.Project)
def req = g.getRoles().find { it.name == 'bc-requester' }
try { g.unAssignRole(req, new PermissionEntry(AuthorizationType.USER, 'approver-1')) } catch (e) { println "unassign: " + e }
g.assignRole(req, new PermissionEntry(AuthorizationType.USER, 'requester'))
p.assignRole(p.getRoles().find { it.name == 'team-all' }, new PermissionEntry(AuthorizationType.USER, 'requester'))
jenkins.model.Jenkins.get().save()
println g.getGrantedRolesEntries().collect { k, v -> k.name + '=' + v.collect { it.sid } }.toString() + ' ' + p.getGrantedRolesEntries().collect { k, v -> k.name + '=' + v.collect { it.sid } }.toString()
