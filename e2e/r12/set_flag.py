"""Arrangement: sets the new job page experimental flag for the given accounts (true = new page, false = classic)."""
import sys
from lib import groovy
val, users = sys.argv[1], sys.argv[2:]
print(groovy(r'''
import jenkins.model.experimentalflags.*
def out = []
''' + repr(users).replace("'", '"') + r'''.each { id ->
  def u = hudson.model.User.getById(id, true)
  def m = new HashMap(); m.put('new-job-page.flag', "''' + val + r'''")
  u.addProperty(new UserExperimentalFlagsProperty(m)); u.save()
  out << id + '=' + new NewJobPageUserExperimentalFlag().getFlagValue(u)
}
return out
'''))
