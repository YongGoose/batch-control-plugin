// Scenario 2/3 setup: an administrator creates jobs through New Item, as a user would.
import { login, BASE, shot, text, api, log, close } from './lib.mjs';

const jobs = process.argv.slice(2).length ? process.argv.slice(2).map((a) => a.split(':')) : [['fresh-daily', 'freestyle']];
const typeCls = { freestyle: 'hudson.model.FreeStyleProject', pipeline: 'org.jenkinsci.plugins.workflow.job.WorkflowJob', folder: 'com.cloudbees.hudson.plugins.folder.Folder' };
const { page } = await login('admin');
for (const [name, type] of jobs) {
  await page.goto(BASE + '/view/all/newJob');
  await page.fill('#name', name);
  await page.locator(`input[name=mode][value="${typeCls[type]}"]`).locator('xpath=..').click();
  await page.waitForTimeout(300);
  await Promise.all([page.waitForNavigation(), page.click('#ok-button')]);
  log('created', name, '->', page.url());
  const sec = page.locator('section.jenkins-section, .jenkins-form-item', { hasText: 'Require approval to run' }).first();
  if (await sec.count()) {
    const boxes = await page.$$eval('input[type=checkbox]', (cs) => cs.filter((c) => /approval|block|Batch/i.test(c.name + c.closest('.jenkins-form-item, .optionalBlock-container, div')?.innerText)).map((c) => `${c.name}=${c.checked}`));
    log('  batch control checkboxes on new job', boxes.join(' '));
    await shot(page, page.locator('.jenkins-section', { hasText: 'Require approval to run' }).last(), `S2-00-${name.replace(/[^a-z0-9-]/gi, '_')}-new-config`);
  }
  await Promise.all([page.waitForNavigation(), page.click('button[name="Submit"]')]);
  log('  saved ->', page.url());
  const jp = '/job/' + encodeURIComponent(name) + '/';
  await page.goto(BASE + jp);
  log('  job page:', (await text(page)).replace(/\s+/g, ' ').slice(0, 500));
  await shot(page, '#main-panel', `S2-00-${name.replace(/[^a-z0-9-]/gi, '_')}-job-page`);
}
await close();
