"""Folder side/ with job-b where opsreq holds Item/Read but not BatchControl/Request (D-38b boundary)."""
from lib import groovy
print(groovy(r'''
import jenkins.model.Jenkins
import hudson.model.*
import org.jenkinsci.plugins.matrixauth.*
import com.cloudbees.hudson.plugins.folder.properties.AuthorizationMatrixProperty as FAMP
def j = Jenkins.get()
def side = j.getItemByFullName('side') ?: j.createProject(com.cloudbees.hudson.plugins.folder.Folder, 'side')
def fp = side.getProperties().get(FAMP)
if (fp == null) { fp = new FAMP([:]); side.addProperty(fp) }
fp.add(Item.READ, new PermissionEntry(AuthorizationType.USER, 'opsreq'))
side.save()
if (side.getItem('job-b') == null) side.createProject(FreeStyleProject, 'job-b')
return "side items=${side.items*.name}"
'''))
