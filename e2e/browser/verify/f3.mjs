// DEF-35 follow-up: saves the guard must not touch (standing Configure; change control off).
import { login, close, api, BASE, setGlobal, changeRows, sleep } from '../lib.mjs';
import { row, ev } from '../audit/rec.mjs';
const J = 'team/app-2'; const P = '/job/team/job/app-2/';
const entries = async () => ((await api('admin', `${P}config.xml`, { raw: true })).text.match(/<permission>[^<]*:approver-2<\/permission>/g) || []).length;
const viol = async () => (await changeRows(/,GRANT_VIOLATION,/)).length;
async function post(add) {
  const x = (await api('configurer', `${P}config.xml`, { raw: true })).text;
  let y = x.replace(/<permission>USER:hudson\.model\.Item\.Read:approver-2<\/permission>/g, '');
  if (add) y = /AuthorizationMatrixProperty>/.test(y)
    ? y.replace(/(<hudson\.security\.AuthorizationMatrixProperty>[\s\S]*?)(<\/hudson\.security\.AuthorizationMatrixProperty>)/, '$1<permission>USER:hudson.model.Item.Read:approver-2</permission>$2')
    : y.replace('<properties>', '<properties><hudson.security.AuthorizationMatrixProperty><inheritanceStrategy class="org.jenkinsci.plugins.matrixauth.inheritance.InheritParentStrategy"/><permission>USER:hudson.model.Item.Read:approver-2</permission></hudson.security.AuthorizationMatrixProperty>');
  const r = await api('configurer', `${P}config.xml`, { method: 'POST', body: y, headers: { 'Content-Type': 'application/xml' } }); await sleep(1200);
  return r.status;
}
const v0 = await viol();
const on1 = await post(true); const e1 = await entries(); await post(false);
const ad = await login('admin'); await setGlobal(ad.page, { changeControlEnabled: false });
const off1 = await post(true); const e2 = await entries(); await post(false);
await setGlobal(ad.page, { changeControlEnabled: true }); await ad.context.close();
const v1 = await viol();
ev(`F3 configurer standing: change on ${on1} kept ${e1}; change off ${off1} kept ${e2}; violations ${v0}->${v1}`);
row('B9-08', { roles: 'configurer (standing Configure), admin (switch)', V: 'n.a.', G: `${on1 === 200 && e1 === 1 && off1 === 200 && e2 === 1 ? '✓' : '✗'} a standing Configure holder's authorization edit on ${J} (adding approver-2 Job/Read) is kept: change control on -> HTTP ${on1}, kept; change control off -> HTTP ${off1}, kept; both removed again`, R: 'n.a. (nothing refused)', C: `${v1 === v0 ? '✓' : '✗'} no GRANT_VIOLATION (${v0} -> ${v1})`, E: '✓ text', note: 'DEF-35 follow-up (security-19): the guard does not touch saves it has no reason to touch' });
await close();
