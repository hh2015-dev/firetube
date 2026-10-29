// SPDX-License-Identifier: GPL-3.0-only
import { DrivePickerSession } from './session.mjs';
const element = id => document.getElementById(id);
const status = message => { element('status').textContent = message; };
const result = value => { element('result').textContent = typeof value === 'string' ? value : JSON.stringify(value, null, 2); };
const load = url => new Promise((resolve, reject) => {
  const script = document.createElement('script'); script.src = url;
  script.onload = resolve; script.onerror = reject; document.head.append(script);
});
let session;
try {
  await Promise.all([load('https://apis.google.com/js/api.js'), load('https://accounts.google.com/gsi/client')]);
  await new Promise(resolve => gapi.load('picker', resolve));
  session = new DrivePickerSession({
    oauth: google.accounts.oauth2, status,
    picker: ({ token, projectNumber, apiKey, origin, mimeTypes, picked }) => {
      const view = new google.picker.DocsView().setIncludeFolders(true).setSelectFolderEnabled(false).setMimeTypes(mimeTypes);
      const picker = new google.picker.PickerBuilder().addView(view)
        .enableFeature(google.picker.Feature.MULTISELECT_ENABLED)
        .setDeveloperKey(apiKey).setAppId(projectNumber).setOAuthToken(token).setOrigin(origin)
        .setCallback(data => {
          if (data.action === google.picker.Action.PICKED) {
            picked(data[google.picker.Response.DOCUMENTS].map(file => ({
              id: file[google.picker.Document.ID], name: file[google.picker.Document.NAME],
            })));
          }
        }).build();
      picker.setVisible(true);
      return () => { picker.setVisible(false); picker.dispose(); };
    },
    selected: files => {
      element('files').replaceChildren(); result('');
      for (const file of files) {
        const row = document.createElement('li');
        const name = document.createElement('bdi'); name.textContent = file.name; row.append(name, ' ');
        const action = (label, fn) => {
          const button = document.createElement('button'); button.textContent = label;
          button.onclick = async () => { try { result(await fn()); } catch (error) { result(error.message); } };
          row.append(button, ' ');
        };
        action('Read metadata', () => session.metadata(file.id));
        action('Probe bytes 0–4095', () => session.probe(file.id));
        action('Probe bytes 4096–8191', () => session.probe(file.id, 4096));
        element('files').append(row);
      }
    },
  });
  element('connect').disabled = false; status('Ready for public configuration and a dedicated test account.');
} catch { status('Google scripts did not load. Check the domain allowlist and browser. No authorization was tested.'); }
element('configuration').onsubmit = event => {
  event.preventDefault();
  try { session.authorize({ clientId: element('client').value.trim(), projectNumber: element('project').value.trim(), apiKey: element('key').value.trim(), origin: location.origin }); }
  catch (error) { status(error.message); }
};
element('clear').onclick = () => { session?.clear(); status('Session cleared locally; grant not revoked.'); };
element('negative').onclick = async () => {
  try { result(await session.metadata(element('unselected').value.trim(), true)); }
  catch (error) { result(error.message); }
};
window.addEventListener('pagehide', () => session?.clear());
