"""Re-arrangement between runs of assign_roles.py: remove nobc's e2e13 assignments (REST)."""
from lib import api, groovy
for t, role in [("globalRoles", "e2e13-global"), ("projectRoles", "e2e13-team")]:
    print(t, api("admin", "/role-strategy/strategy/unassignUserRole", "POST", data={"type": t, "roleName": role, "user": "nobc"}).status_code)
print(groovy("def s=jenkins.model.Jenkins.get().authorizationStrategy; def T=com.synopsys.arc.jenkins.plugins.rolestrategy.RoleType; "
             "return [s.class.simpleName, s.getRoleMap(T.Global).getSidEntries(true)*.sid, s.getRoleMap(T.Project).getSidEntries(true)*.sid]"))
